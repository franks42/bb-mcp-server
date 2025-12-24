(ns nrepl-proxy-server.server
    "TCP nREPL server with bencode protocol.

   Accepts standard nREPL clients (Calva, terminal) and routes requests
   to either bb (local eval) or browser (via sente WebSocket)."
    (:require [bencode.core :as bencode]
              [clojure.java.io :as io]
              [nrepl.state.connection :as conn-state]
              [nrepl.state.messages :as msg-state]
              [nrepl.state.results :as results]
              [nrepl-proxy-server.session :as session]
              [nrepl-proxy-server.api :as api]
              [taoensso.trove :as log])
    (:import [java.io PushbackInputStream BufferedOutputStream EOFException]
             [java.net ServerSocket InetAddress]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !server-state (atom nil))  ; {:socket ServerSocket :config map}

;; =============================================================================
;; Port File Management
;; =============================================================================

(defn- write-port-file!
  "Write port to file for cross-process discovery."
  [port port-file]
  (spit port-file (str port))
  (log/log! {:level :debug
             :id ::port-file-written
             :msg "Port file written"
             :data {:port port :port-file port-file}}))

(defn- delete-port-file!
  "Delete port file."
  [port-file]
  (when (.exists (io/file port-file))
    (.delete (io/file port-file))
    (log/log! {:level :debug
               :id ::port-file-deleted
               :msg "Port file deleted"
               :data {:port-file port-file}})))

;; =============================================================================
;; Bencode Helpers
;; =============================================================================

(defn- coerce-bencode
  "Convert bencode bytes to strings."
  [x]
  (if (bytes? x)
    (String. ^bytes x "UTF-8")
    x))

(defn- read-bencode-msg
  "Read and decode bencode message from input stream."
  [in]
  (let [msg (bencode/read-bencode in)]
    (zipmap (map keyword (keys msg))
            (map coerce-bencode (vals msg)))))

(defn- write-bencode-msg
  "Write bencode message to output stream."
  [^BufferedOutputStream out msg]
  (bencode/write-bencode out msg)
  (.flush out))

(defn- edn-status->bencode
  "Convert EDN status keywords to bencode strings."
  [status]
  (if (sequential? status)
    (mapv name status)
    [(name status)]))

;; =============================================================================
;; Response Conversion
;; =============================================================================

(defn- edn-response->bencode
  "Convert EDN nREPL response to bencode format."
  [{:keys [value err out ns status id session ex] :as _response}]
  (cond-> {}
          value (assoc "value" value)
          err (assoc "err" err)
          out (assoc "out" out)
          ns (assoc "ns" ns)
          ex (assoc "ex" ex)
          status (assoc "status" (edn-status->bencode status))
          id (assoc "id" id)
          session (assoc "session" session)))

;; =============================================================================
;; Local BB Eval
;; =============================================================================

(defn- eval-in-bb
  "Evaluate code locally in babashka.
   Returns nREPL-style response map."
  [code ns-str]
  (try
   (let [result (load-string code)]
     {:value (pr-str result)
      :ns (or ns-str "user")
      :status [:done]})
   (catch Exception e
          {:err (.getMessage e)
           :ex (str (class e))
           :status [:done :error]})))

;; =============================================================================
;; Browser Eval
;; =============================================================================

(defn- eval-in-browser
  "Evaluate code in browser via sente WebSocket.
   Returns nREPL-style response map."
  [browser-conn-id code timeout-ms]
  (let [conn-data (conn-state/get-connection-by-id browser-conn-id)]
    (if (and conn-data (= :connected (:status conn-data)))
      ;; Enqueue message and wait for result
      (let [message {:op :eval :code code}
            msg-id (msg-state/enqueue-message! browser-conn-id message)]
        (if msg-id
          ;; Wait for response
          (let [result (results/get-result msg-id (or timeout-ms 30000))]
            (case (:status result)
              :success
              (let [response (get-in result [:result :response])]
                {:value (:value response)
                 :ns (:ns response "cljs.user")
                 :out (:out response)
                 :err (:err response)
                 :status (or (:status response) [:done])})

              :timeout
              {:err "Timeout waiting for browser response"
               :status [:done :error]}

              :error
              {:err (str "Browser eval error: " (:error result))
               :status [:done :error]}))
          ;; Enqueue failed
          {:err "Failed to send message to browser"
           :status [:done :error]}))
      ;; Browser not connected
      {:err (str "Browser not connected: " browser-conn-id)
       :status [:done :error]})))

;; =============================================================================
;; Operation Handlers
;; =============================================================================

(defn- handle-clone
  "Handle :clone - create new session."
  [out {:keys [id]}]
  (let [new-session (session/create-session!)]
    (write-bencode-msg out {"id" id
                            "new-session" new-session
                            "status" ["done"]})))

(defn- handle-describe
  "Handle :describe - report capabilities."
  [out {:keys [id session]}]
  (let [response {"versions" {"nrepl-proxy-server" {"major" "0"
                                                    "minor" "1"
                                                    "incremental" "0"}}
                  "ops" {"eval" {}
                         "load-file" {}
                         "clone" {}
                         "describe" {}
                         "close" {}}
                  "status" ["done"]}
        response (cond-> response
                         id (assoc "id" id)
                         session (assoc "session" session))]
    (write-bencode-msg out response)))

(defn- handle-close
  "Handle :close - close session."
  [out {:keys [id session]}]
  (when session
    (session/remove-session! session))
  (write-bencode-msg out {"id" id
                          "session" session
                          "status" ["done"]}))

(defn- handle-eval
  "Handle :eval - route to bb or browser based on session target."
  [out {:keys [id session code ns]}]
  (let [target (session/get-target session)]
    (log/log! {:level :debug
               :id ::eval-request
               :msg "Eval request"
               :data {:session session :target target :code-length (count code)}})

    (cond
      ;; Check for browser/list call
      (api/browser-list-call? code)
      (let [browsers (api/list-browsers)
            value (pr-str browsers)]
        (write-bencode-msg out {"id" id "session" session "value" value})
        (write-bencode-msg out {"id" id "session" session "status" ["done"]}))

      ;; Check for browser/repl call
      (api/browser-repl-call? code)
      (let [browser-id (api/browser-repl-call? code)
            result (api/switch-to-browser! session browser-id)]
        (if (:success result)
          (do
           (write-bencode-msg out {"id" id "session" session
                                   "value" (pr-str (:message result))})
           (write-bencode-msg out {"id" id "session" session "status" ["done"]}))
          (do
           (write-bencode-msg out {"id" id "session" session
                                   "err" (:error result)})
           (write-bencode-msg out {"id" id "session" session
                                   "status" ["done" "error"]}))))

      ;; Check for :cljs/quit
      (api/cljs-quit? code)
      (do
       (api/switch-to-bb! session)
       (write-bencode-msg out {"id" id "session" session "value" "nil"})
       (write-bencode-msg out {"id" id "session" session "status" ["done"]}))

      ;; Target is bb - eval locally
      (= target :bb)
      (let [response (eval-in-bb code ns)]
        (write-bencode-msg out (edn-response->bencode
                                (assoc response :id id :session session))))

      ;; Target is browser - forward via sente
      :else
      (let [response (eval-in-browser target code 30000)]
        (write-bencode-msg out (edn-response->bencode
                                (assoc response :id id :session session)))))))

(defn- handle-load-file
  "Handle :load-file - load file content as code."
  [out {:keys [id session file]}]
  (let [target (session/get-target session)]
    (if (= target :bb)
      ;; Load in bb
      (let [response (eval-in-bb file nil)]
        (write-bencode-msg out (edn-response->bencode
                                (assoc response :id id :session session))))
      ;; Load in browser
      (let [response (eval-in-browser target file 60000)]
        (write-bencode-msg out (edn-response->bencode
                                (assoc response :id id :session session)))))))

;; =============================================================================
;; Session Loop
;; =============================================================================

(defn- session-loop
  "Main session loop - reads bencode, dispatches operations."
  [in out]
  (loop []
        (when-let [msg (try
                        (read-bencode-msg in)
                        (catch EOFException _
                               nil)
                        (catch Exception e
                               (log/log! {:level :warn
                                          :id ::bencode-read-error
                                          :msg "Bencode read error"
                                          :data {:error (.getMessage e)}})
                               nil))]
                  (let [op (keyword (:op msg))
                        id (:id msg)
                        session (:session msg)]
                    (log/log! {:level :trace
                               :id ::op-received
                               :msg "Received operation"
                               :data {:op op :id id :session session}})
                    (try
                     (case op
                       :clone (handle-clone out msg)
                       :describe (handle-describe out msg)
                       :close (handle-close out msg)
                       :eval (handle-eval out msg)
                       :load-file (handle-load-file out msg)
            ;; Unknown op
                       (do
                        (log/log! {:level :warn
                                   :id ::unknown-op
                                   :msg "Unknown operation"
                                   :data {:op op}})
                        (write-bencode-msg out {"id" id "session" session
                                                "err" (str "Unknown operation: " (name op))
                                                "status" ["done" "error"]})))
                     (catch Exception e
                            (log/log! {:level :error
                                       :id ::op-handler-error
                                       :msg "Operation handler error"
                                       :error e
                                       :data {:op op :id id}})
                            (write-bencode-msg out {"id" id "session" session
                                                    "err" (str "Handler error: " (.getMessage e))
                                                    "status" ["done" "error"]})))
                    (recur)))))

(defn- accept-connections
  "Accept loop for incoming connections."
  [^ServerSocket socket]
  (log/log! {:level :info
             :id ::listening
             :msg "nREPL proxy listening"
             :data {:port (.getLocalPort socket)}})
  (loop []
        (when-not (.isClosed socket)
          (try
           (let [client (.accept socket)
                 in (PushbackInputStream. (.getInputStream client))
                 out (BufferedOutputStream. (.getOutputStream client))]
             (log/log! {:level :info
                        :id ::client-connected
                        :msg "nREPL client connected"
                        :data {:remote (.getRemoteSocketAddress client)}})
             (future
              (try
               (session-loop in out)
               (catch Exception e
                      (log/log! {:level :error
                                 :id ::session-error
                                 :msg "Session error"
                                 :error e}))
               (finally
                (.close client)
                (log/log! {:level :info
                           :id ::client-disconnected
                           :msg "nREPL client disconnected"})))))
           (catch Exception e
                  (when-not (.isClosed socket)
                    (log/log! {:level :error
                               :id ::accept-error
                               :msg "Accept error"
                               :error e}))))
          (recur))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Start the nREPL proxy server.

   Options:
   - :port - TCP port (default: 0 = ephemeral)
   - :host - bind address (default: 127.0.0.1)
   - :port-file - path to port file (default: .nrepl-proxy-port)
   - :write-port-file? - write port file for discovery (default: true)

   Returns: {:port actual-port :port-file path-or-nil}"
  [{:keys [port host port-file write-port-file?]
    :or {port 0 host "127.0.0.1" port-file ".nrepl-proxy-port" write-port-file? true}}]
  (when @!server-state
    (throw (ex-info "Server already running" {:port (:port (:config @!server-state))})))

  (let [inet-addr (InetAddress/getByName host)
        socket (ServerSocket. port 0 inet-addr)
        actual-port (.getLocalPort socket)]

    (reset! !server-state {:socket socket
                           :config {:port actual-port :host host :port-file port-file}})

    ;; Write port file for discovery
    (when write-port-file?
      (write-port-file! actual-port port-file))

    ;; Start accept loop in background
    (future (accept-connections socket))

    (log/log! {:level :info
               :id ::server-started
               :msg "nREPL proxy server started"
               :data {:port actual-port :host host :port-file (when write-port-file? port-file)}})

    {:port actual-port
     :port-file (when write-port-file? port-file)}))

(defn stop!
  "Stop the nREPL proxy server."
  []
  (when-let [{:keys [socket config]} @!server-state]
            (.close ^ServerSocket socket)

    ;; Delete port file
            (when-let [port-file (:port-file config)]
                      (delete-port-file! port-file))

    ;; Clear sessions
            (session/clear-all-sessions!)

            (reset! !server-state nil)

            (log/log! {:level :info
                       :id ::server-stopped
                       :msg "nREPL proxy server stopped"})
            :ok))

(defn status
  "Get server status."
  []
  (if-let [{:keys [config]} @!server-state]
          {:running? true
           :port (:port config)
           :host (:host config)
           :session-count (session/active-session-count)
           :browser-count (count (api/list-browsers))}
          {:running? false}))

(defn get-url
  "Get the nREPL URL for connecting.
   Returns nil if server not running."
  []
  (when-let [{:keys [config]} @!server-state]
            (str "nrepl://" (:host config) ":" (:port config))))
