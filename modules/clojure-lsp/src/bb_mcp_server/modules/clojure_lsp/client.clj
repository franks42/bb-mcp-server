(ns bb-mcp-server.modules.clojure-lsp.client
    "Async LSP client for clojure-lsp communication.
   Handles request/response matching, notifications, and reader loop."
    (:require [babashka.process :as p]
              [bb-mcp-server.modules.clojure-lsp.jsonrpc :as rpc]
              [clojure.java.io :as io]
              [clojure.string :as str]
              [taoensso.trove :as trove])
    (:import [java.io BufferedReader BufferedWriter
              InputStreamReader OutputStreamWriter]))

(defonce ^:private state
         (atom {:process nil
                :in nil
                :out nil
                :err nil
                :project-root nil
                :executable-path "clojure-lsp"
                :request-id 0
                :pending {}  ;; {id {:promise prom :method m :params p :retries n}}
                :initialized? false
                :diagnostics {}}))

;; Registry for notification callbacks
;; {key callback-fn} where callback-fn receives {:method string :params map}
(defonce ^:private !notification-callbacks (atom {}))

(defn- handle-response!
  "Handle a JSON-RPC response by delivering to the pending promise."
  [{:keys [id result error]}]
  (when-let [{:keys [promise]} (get-in @state [:pending id])]
            (deliver promise (if error {:error error} result))
            (swap! state update :pending dissoc id)))

(defn- extract-id-from-partial
  "Try to extract the 'id' field from partial/corrupted JSON.
   Returns the id as a number, or nil if not found."
  [^String s]
  (when s
    (when-let [match (re-find #"\"id\"\s*:\s*(\d+)" s)]
              (parse-long (second match)))))

(def ^:private max-nul-retries
     "Maximum number of retries for NUL byte corruption."
     2)

(def ^:private nul-retry-timeout-ms
     "Timeout to wait for retry response before triggering LSP restart."
     2000)

(declare force-restart-and-retry!)

(defn- retry-request!
  "Retry a request that failed due to NUL byte corruption.
   Returns true if retry was issued, false if max retries exceeded.
   Starts a watchdog timer - if no response, triggers LSP restart."
  [id {:keys [method params retries] :as pending-info}]
  (if (>= retries max-nul-retries)
    (do
     (trove/log! {:level :error
                  :id :clojure-lsp/max-retries-exceeded
                  :msg "Max NUL retries exceeded, giving up"
                  :data {:request-id id :method method :retries retries}})
     false)
    (let [new-id (-> (swap! state update :request-id inc) :request-id)]
      (trove/log! {:level :warn
                   :id :clojure-lsp/nul-retry
                   :msg "Retrying request after NUL corruption"
                   :data {:old-id id :new-id new-id :method method :retry (inc retries)}})
      ;; Move pending entry to new ID with incremented retry count
      (swap! state (fn [s]
                     (-> s
                         (update :pending dissoc id)
                         (assoc-in [:pending new-id]
                                   (assoc pending-info :retries (inc retries))))))
      ;; Re-send the request
      (rpc/write-message! (:out @state)
                          {:jsonrpc "2.0" :id new-id :method method :params params})
      ;; Start watchdog timer - if request still pending after timeout, restart LSP
      (future
       (Thread/sleep nul-retry-timeout-ms)
       (when (get-in @state [:pending new-id])
         (trove/log! {:level :warn
                      :id :clojure-lsp/retry-timeout
                      :msg "Retry timed out, triggering LSP restart"
                      :data {:request-id new-id :method method}})
         (force-restart-and-retry! new-id)))
      true)))

(defn- handle-parse-error!
  "Handle a parse error by retrying the request (NUL corruption is transient).
   Falls back to delivering error if max retries exceeded or ID not found."
  [{:keys [message preview] :as parse-error}]
  (let [id (extract-id-from-partial preview)]
    (if id
      (if-let [pending-info (get-in @state [:pending id])]
        ;; Try to retry the request
              (when-not (retry-request! id pending-info)
          ;; Max retries exceeded - deliver error
                (deliver (:promise pending-info)
                         {:error {:code -32700
                                  :message "Parse error (NUL byte corruption) - max retries exceeded"
                                  :data parse-error}})
                (swap! state update :pending dissoc id))
        ;; No pending request found (maybe already timed out)
              (trove/log! {:level :warn
                           :id :clojure-lsp/parse-error-no-pending
                           :msg "Parse error but no pending request found"
                           :data {:request-id id}}))
      ;; Can't extract ID - log warning
      (trove/log! {:level :error
                   :id :clojure-lsp/parse-error-orphaned
                   :msg "Parse error without extractable ID"
                   :data {:message message
                          :pending-count (count (:pending @state))}}))))

(defn- run-notification-callbacks!
  "Run all registered notification callbacks with the notification data.
   Callbacks are called in a try/catch to prevent one from breaking others."
  [notification]
  (doseq [[key callback-fn] @!notification-callbacks]
         (try
          (callback-fn notification)
          (catch Exception e
                 (trove/log! {:level :warn
                              :id :clojure-lsp/notification-callback-error
                              :msg "Notification callback threw exception"
                              :data {:key key :error (ex-message e)}})))))

(defn- handle-notification!
  "Handle a JSON-RPC notification from the server.
   Stores diagnostics and notifies all registered callbacks."
  [{:keys [method params] :as notification}]
  (trove/log! {:level :debug
               :id :clojure-lsp/notification
               :msg (str "Received notification: " method)
               :data {:method method}})
  (case method
    "textDocument/publishDiagnostics"
    (do
     (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
     ;; Notify all registered callbacks
     (run-notification-callbacks! notification))
    ;; Default: ignore unknown notifications, but still notify callbacks
    (run-notification-callbacks! notification)))

(defn- stderr-loop!
  "Background reader loop for logging clojure-lsp stderr output."
  [^BufferedReader err]
  (trove/log! {:level :debug
               :id :clojure-lsp/stderr-loop-started
               :msg "Starting LSP stderr reader"})
  (try
   (loop []
         (when-let [line (.readLine err)]
                   (trove/log! {:level :warn
                                :id :clojure-lsp/stderr
                                :msg "clojure-lsp stderr"
                                :data {:line line}})
                   (recur)))
   (catch Exception e
          (trove/log! {:level :debug
                       :id :clojure-lsp/stderr-loop-ended
                       :msg "LSP stderr loop ended"
                       :data {:error (.getMessage e)}}))))

(defn- read-loop!
  "Background reader loop for processing LSP responses and notifications."
  [^BufferedReader in]
  (trove/log! {:level :debug
               :id :clojure-lsp/read-loop-started
               :msg "Starting LSP read loop"})
  (try
   (loop []
         (when-let [msg (rpc/read-message! in)]
                   (cond
                    ;; JSON parse error - deliver error to pending request
                     (:parse-error msg)
                     (do
                      (handle-parse-error! (:parse-error msg))
                      (recur))

                    ;; Response (has :id)
                     (:id msg)
                     (do (handle-response! msg) (recur))

                    ;; Notification (no :id)
                     :else
                     (do (handle-notification! msg) (recur)))))
   (catch Exception e
          (trove/log! {:level :warn
                       :id :clojure-lsp/read-loop-ended
                       :msg "LSP read loop ended"
                       :data {:error (.getMessage e)}}))))

;; --- Public API ---

(defn running?
  "Check if the clojure-lsp process is running."
  []
  (boolean (:process @state)))

(defn initialized?
  "Check if the LSP client has completed initialization."
  []
  (:initialized? @state))

(defn request!
  "Send a JSON-RPC request and wait for response.
   Returns result map or {:error ...} on failure/timeout.
   Automatically retries on NUL byte corruption (up to max-nul-retries)."
  [method params & {:keys [timeout] :or {timeout 30000}}]
  (when-not (running?)
    (throw (ex-info "clojure-lsp not running" {:method method})))
  (let [id (-> (swap! state update :request-id inc) :request-id)
        prom (promise)]
    ;; Store promise with method/params for retry capability
    (swap! state assoc-in [:pending id]
           {:promise prom :method method :params params :retries 0})
    (trove/log! {:level :debug
                 :id :clojure-lsp/request
                 :msg (str "Sending request: " method)
                 :data {:id id :method method}})
    (rpc/write-message! (:out @state)
                        {:jsonrpc "2.0" :id id :method method :params params})
    (let [result (deref prom timeout ::timeout)]
      (if (= result ::timeout)
        (do
         (swap! state update :pending dissoc id)
         {:error {:message "Request timeout" :code -32000}})
        result))))

(defn notify!
  "Send a JSON-RPC notification (no response expected)."
  [method params]
  (when-not (running?)
    (throw (ex-info "clojure-lsp not running" {:method method})))
  (trove/log! {:level :debug
               :id :clojure-lsp/notify
               :msg (str "Sending notification: " method)
               :data {:method method}})
  (rpc/write-message! (:out @state)
                      {:jsonrpc "2.0" :method method :params params}))

(defn did-open!
  "Notify clojure-lsp that a document was opened."
  [path]
  (let [uri (str "file://" path)
        text (slurp path)]
    (notify! "textDocument/didOpen"
             {:textDocument {:uri uri
                             :languageId "clojure"
                             :version 1
                             :text text}})))

(defn did-close!
  "Notify clojure-lsp that a document was closed."
  [path]
  (let [uri (str "file://" path)]
    (notify! "textDocument/didClose" {:textDocument {:uri uri}})))

(defn stop!
  "Shutdown clojure-lsp subprocess gracefully."
  []
  (when-let [proc (:process @state)]
            (trove/log! {:level :info
                         :id :clojure-lsp/stopping
                         :msg "Stopping clojure-lsp subprocess"})
            (try
      ;; Send LSP shutdown sequence
             (request! "shutdown" nil :timeout 5000)
             (notify! "exit" nil)
             (catch Exception _
        ;; Process may have already exited
                    nil))
            (p/destroy proc)
            (reset! state {:process nil
                           :in nil
                           :out nil
                           :project-root nil
                           :executable-path "clojure-lsp"
                           :request-id 0
                           :pending {}
                           :initialized? false
                           :diagnostics {}})
            {:status "stopped"}))

(defn- force-restart-and-retry!
  "Force restart LSP after stuck retry and re-send the pending request.
   Called by watchdog timer when retry doesn't get a response."
  [stuck-id]
  (let [{:keys [project-root executable-path pending]} @state
        pending-info (get pending stuck-id)]
    (when (and pending-info project-root)
      (let [{:keys [promise method params]} pending-info]
        (trove/log! {:level :warn
                     :id :clojure-lsp/force-restart
                     :msg "Force restarting LSP to recover from stuck state"
                     :data {:stuck-id stuck-id :method method :project-root project-root}})

        ;; Force kill the process (no graceful shutdown - it's stuck)
        (when-let [proc (:process @state)]
                  (p/destroy proc))

        ;; Reset state but preserve what we need for restart
        (reset! state {:process nil
                       :in nil
                       :out nil
                       :err nil
                       :project-root project-root
                       :executable-path (or executable-path "clojure-lsp")
                       :request-id 0
                       :pending {}
                       :initialized? false
                       :diagnostics {}})

        ;; Start fresh LSP process
        (let [proc (p/process [(or executable-path "clojure-lsp")]
                              {:dir project-root
                               :in :pipe
                               :out :pipe
                               :err :pipe})
              in (BufferedReader. (InputStreamReader. (:out proc)))
              out (BufferedWriter. (OutputStreamWriter. (:in proc)))
              err (BufferedReader. (InputStreamReader. (:err proc)))]

          (swap! state assoc
                 :process proc
                 :in in
                 :out out
                 :err err)

          ;; Start reader threads
          (future (read-loop! in))
          (future (stderr-loop! err))

          (trove/log! {:level :info
                       :id :clojure-lsp/restarted
                       :msg "LSP restarted, performing initialization"})

          ;; LSP Initialize handshake
          (let [init-result (request! "initialize"
                                      {:processId (.pid (java.lang.ProcessHandle/current))
                                       :rootUri (str "file://" project-root)
                                       :capabilities
                                       {:textDocument
                                        {:hover {:contentFormat ["markdown" "plaintext"]}
                                         :completion {:completionItem {:snippetSupport false}}
                                         :definition {:linkSupport false}
                                         :references {}
                                         :rename {:prepareSupport true}
                                         :codeAction {:codeActionLiteralSupport
                                                      {:codeActionKind {:valueSet []}}}}}
                                       :workspaceFolders [{:uri (str "file://" project-root)
                                                           :name "root"}]}
                                      :timeout 30000)]
            (if (:error init-result)
              (do
               (trove/log! {:level :error
                            :id :clojure-lsp/restart-init-failed
                            :msg "LSP restart initialization failed"
                            :data init-result})
               (deliver promise {:error {:code -32000
                                         :message "LSP restart failed"
                                         :data init-result}}))
              (do
               (notify! "initialized" {})
               (swap! state assoc :initialized? true)
               (trove/log! {:level :info
                            :id :clojure-lsp/restart-complete
                            :msg "LSP restart complete, re-sending stuck request"
                            :data {:method method}})

               ;; Re-send the stuck request with a new ID
               (let [new-id (-> (swap! state update :request-id inc) :request-id)]
                 (swap! state assoc-in [:pending new-id]
                        {:promise promise :method method :params params :retries 0})
                 (rpc/write-message! (:out @state)
                                     {:jsonrpc "2.0" :id new-id :method method :params params})
                 (trove/log! {:level :info
                              :id :clojure-lsp/request-resent
                              :msg "Stuck request re-sent after LSP restart"
                              :data {:new-id new-id :method method}}))))))))))

(defn start!
  "Start clojure-lsp subprocess and perform LSP initialization."
  [{:keys [project-root executable-path]
    :or {executable-path "clojure-lsp"}}]
  (when (running?)
    (stop!))

  (when-not (and project-root (.exists (io/file project-root)))
    (let [msg (str "Project root does not exist: " project-root)]
      (trove/log! {:level :error
                   :id :clojure-lsp/init-failed
                   :msg msg})
      (throw (ex-info msg {:project-root project-root}))))

  (trove/log! {:level :info
               :id :clojure-lsp/starting
               :msg "Starting clojure-lsp subprocess"
               :data {:project-root project-root :executable-path executable-path}})

  (let [proc (p/process [executable-path]
                        {:dir project-root
                         :in :pipe
                         :out :pipe
                         :err :pipe})  ; Capture stderr instead of inheriting
        in (BufferedReader. (InputStreamReader. (:out proc)))
        out (BufferedWriter. (OutputStreamWriter. (:in proc)))
        err (BufferedReader. (InputStreamReader. (:err proc)))]

    (swap! state assoc
           :process proc
           :in in
           :out out
           :err err
           :project-root project-root
           :executable-path executable-path)

    ;; Start reader threads
    (future (read-loop! in))
    (future (stderr-loop! err))

    (trove/log! {:level :info
                 :id :clojure-lsp/started
                 :msg "clojure-lsp subprocess started, sending initialize request"
                 :data {:pid (.pid (:proc proc))}})

    ;; LSP Initialize handshake
    (let [result (request! "initialize"
                           {:processId (.pid (java.lang.ProcessHandle/current))
                            :rootUri (str "file://" project-root)
                            :capabilities
                            {:textDocument
                             {:hover {:contentFormat ["markdown" "plaintext"]}
                              :completion {:completionItem {:snippetSupport false}}
                              :definition {:linkSupport false}
                              :references {}
                              :rename {:prepareSupport true}
                              :codeAction {:codeActionLiteralSupport
                                           {:codeActionKind {:valueSet []}}}}}
                            :workspaceFolders [{:uri (str "file://" project-root)
                                                :name "root"}]})]
      (if (:error result)
        (do
         (trove/log! {:level :error
                      :id :clojure-lsp/init-failed
                      :msg "LSP initialize failed"
                      :data result})
         (stop!)
         result)
        (do
         (notify! "initialized" {})
         (swap! state assoc :initialized? true)
         (trove/log! {:level :info
                      :id :clojure-lsp/initialized
                      :msg "clojure-lsp initialized successfully"})
         {:status "initialized"
          :capabilities (:capabilities result)})))))

(defn get-diagnostics
  "Get cached diagnostics for a file or all files."
  ([] (:diagnostics @state))
  ([path] (get-in @state [:diagnostics (str "file://" path)])))

(defn did-change-watched-files!
  "Notify clojure-lsp that files have changed on disk.

   Args:
     changes - Sequence of {:path \"...\" :type :created|:changed|:deleted}

   LSP FileChangeType: 1=created, 2=changed, 3=deleted"
  [changes]
  (let [type-map {:created 1 :changed 2 :deleted 3}
        lsp-changes (mapv (fn [{:keys [path type]}]
                            {:uri (str "file://" path)
                             :type (get type-map type 2)})
                          changes)]
    (trove/log! {:level :info
                 :id :clojure-lsp/file-changes
                 :msg (str "Notifying clojure-lsp of " (count changes) " file change(s)")
                 :data {:changes (mapv #(select-keys % [:path :type]) changes)}})
    (notify! "workspace/didChangeWatchedFiles" {:changes lsp-changes})))

(defn get-project-root
  "Get the current project root path."
  []
  (:project-root @state))

;; =============================================================================
;; Notification Callback API (Phase 1.5-Watch)
;; =============================================================================

(defn on-notification!
  "Register a callback for LSP notifications.
   callback-fn receives {:method string :params map}.
   Returns the key for later removal.

   Example:
     (on-notification! :my-handler
       (fn [{:keys [method params]}]
         (when (= method \"textDocument/publishDiagnostics\")
           (println \"File changed:\" (:uri params)))))"
  [key callback-fn]
  (swap! !notification-callbacks assoc key callback-fn)
  (trove/log! {:level :debug
               :id :clojure-lsp/notification-callback-registered
               :msg "Registered notification callback"
               :data {:key key}})
  key)

(defn remove-notification-callback!
  "Remove a previously registered notification callback by key."
  [key]
  (swap! !notification-callbacks dissoc key)
  (trove/log! {:level :debug
               :id :clojure-lsp/notification-callback-removed
               :msg "Removed notification callback"
               :data {:key key}})
  nil)

(defn uri->path
  "Convert file:// URI to filesystem path."
  [uri]
  (when uri
    (if (str/starts-with? uri "file://")
      (subs uri 7)
      uri)))
