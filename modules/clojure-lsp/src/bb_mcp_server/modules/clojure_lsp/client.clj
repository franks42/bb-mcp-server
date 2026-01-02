(ns bb-mcp-server.modules.clojure-lsp.client
    "Async LSP client for clojure-lsp communication.
   Handles request/response matching, notifications, and reader loop."
    (:require [babashka.process :as p]
              [bb-mcp-server.modules.clojure-lsp.jsonrpc :as rpc]
              [clojure.java.io :as io]
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
                :pending {}
                :initialized? false
                :diagnostics {}}))

(defn- handle-response!
  "Handle a JSON-RPC response by delivering to the pending promise."
  [{:keys [id result error]}]
  (when-let [prom (get-in @state [:pending id])]
            (deliver prom (if error {:error error} result))
            (swap! state update :pending dissoc id)))

(defn- handle-notification!
  "Handle a JSON-RPC notification from the server."
  [{:keys [method params]}]
  (trove/log! {:level :debug
               :id :clojure-lsp/notification
               :msg (str "Received notification: " method)
               :data {:method method}})
  (case method
    "textDocument/publishDiagnostics"
    (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
    ;; Default: ignore unknown notifications
    nil))

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

(defn- find-nul-positions
  "Find positions of NUL bytes in a string for debugging."
  [^String s]
  (let [max-positions 10]
    (->> (range (count s))
         (filter #(= \u0000 (.charAt s %)))
         (take max-positions)
         vec)))

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
                    ;; JSON parse error - log with NUL byte detection
                     (:parse-error msg)
                     (let [{:keys [message length preview]} (:parse-error msg)
                           nul-positions (find-nul-positions preview)
                           has-nul? (seq nul-positions)]
                       (trove/log! {:level :error
                                    :id :clojure-lsp/parse-error
                                    :msg (if has-nul?
                                           "LSP response contains NUL bytes (stdout corruption?)"
                                           "Failed to parse LSP message")
                                    :data {:message message
                                           :length length
                                           :has-nul-bytes has-nul?
                                           :nul-positions nul-positions
                                           :preview-hex (when has-nul?
                                                          (apply str (map #(format "%02x " (int %))
                                                                          (take 50 preview))))
                                           :preview-escaped (pr-str (subs preview 0 (min 100 (count preview))))}})
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
   Returns result map or {:error ...} on failure/timeout."
  [method params & {:keys [timeout] :or {timeout 30000}}]
  (when-not (running?)
    (throw (ex-info "clojure-lsp not running" {:method method})))
  (let [id (-> (swap! state update :request-id inc) :request-id)
        prom (promise)]
    (swap! state assoc-in [:pending id] prom)
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
