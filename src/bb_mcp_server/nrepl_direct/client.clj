(ns bb-mcp-server.nrepl-direct.client
    "Standalone nREPL client for direct protocol communication.

   This is a lightweight, stateless nREPL client that:
   - Opens a TCP socket to nREPL server
   - Clones a session for each operation
   - Sends eval/load-file messages
   - Returns results
   - Closes connection

   No dependency on mcp-nrepl module or its state management."
    (:require [bencode.core :as bencode])
    (:import [java.net Socket]
             [java.io PushbackInputStream]))

;; =============================================================================
;; Low-level bencode helpers
;; =============================================================================

(defn- bytes->string
  "Convert byte array to UTF-8 string."
  [obj]
  (cond
    (instance? (Class/forName "[B") obj) (String. ^bytes obj "UTF-8")
    (string? obj) obj
    :else (str obj)))

(defn- convert-response
  "Convert bencode byte arrays to strings recursively, using keyword keys."
  [obj]
  (cond
    (map? obj)
    (into {} (map (fn [[k v]]
                    [(keyword (bytes->string k))
                     (convert-response v)])
                  obj))
    (vector? obj) (mapv convert-response obj)
    (seq? obj) (map convert-response obj)
    :else (bytes->string obj)))

;; =============================================================================
;; Connection management
;; =============================================================================

(defn connect
  "Connect to nREPL server. Returns connection map.

   Options:
     :host - hostname (default: localhost)
     :port - port number (required)"
  [{:keys [host port] :or {host "localhost"}}]
  (let [socket (Socket. ^String host ^int port)
        out (.getOutputStream socket)
        in (PushbackInputStream. (.getInputStream socket))]
    {:socket socket
     :out out
     :in in
     :host host
     :port port}))

(defn close
  "Close nREPL connection."
  [{:keys [socket]}]
  (when socket
    (.close ^Socket socket)))

;; =============================================================================
;; Message handling
;; =============================================================================

(defn- generate-id
  "Generate unique message ID."
  []
  (str (java.util.UUID/randomUUID)))

(defn- read-responses
  "Read all responses until 'done' status. Returns vector of responses."
  [in timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [responses []]
          (if (> (System/currentTimeMillis) deadline)
            {:status :timeout :responses responses}
            (let [response (try
                            (convert-response (bencode/read-bencode in))
                            (catch Exception e
                                   {:error (ex-message e)}))]
              (if (:error response)
                {:status :error :responses responses :error (:error response)}
                (let [new-responses (conj responses response)
                      status (:status response)]
                  (if (and status (some #(= "done" %) status))
                    {:status :success :responses new-responses}
                    (recur new-responses)))))))))

(defn- merge-responses
  "Merge multiple nREPL responses into single result."
  [responses]
  (if (empty? responses)
    {}
    (let [all-out (apply str (keep :out responses))
          all-err (apply str (keep :err responses))
          final-value (last (keep :value responses))
          final-ex (last (keep :ex responses))
          final-root-ex (last (keep :root-ex responses))
          final-ns (last (keep :ns responses))
          final-session (last (keep :session responses))
          final-status (:status (last responses))]
      (cond-> {}
              (seq all-out) (assoc :out all-out)
              (seq all-err) (assoc :err all-err)
              final-value (assoc :value final-value)
              final-ex (assoc :ex final-ex)
              final-root-ex (assoc :root-ex final-root-ex)
              final-ns (assoc :ns final-ns)
              final-session (assoc :session final-session)
              final-status (assoc :status final-status)))))

(defn send-message
  "Send nREPL message and wait for response.

   Returns:
     {:status :success :response merged-response}
     {:status :timeout :responses [...]}
     {:status :error :error message}"
  [{:keys [out in]} message & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [msg-with-id (assoc message :id (generate-id))]
    (bencode/write-bencode out msg-with-id)
    (.flush out)
    (let [result (read-responses in timeout-ms)]
      (if (= :success (:status result))
        {:status :success :response (merge-responses (:responses result))}
        result))))

;; =============================================================================
;; High-level operations
;; =============================================================================

(defn clone-session
  "Clone a new nREPL session. Returns session ID."
  [conn & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [result (send-message conn {:op "clone"} :timeout-ms timeout-ms)]
    (if (= :success (:status result))
      {:status :success :session (get-in result [:response :new-session])}
      result)))

(defn describe
  "Get nREPL server capabilities."
  [conn & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (send-message conn {:op "describe"} :timeout-ms timeout-ms))

(defn eval-code
  "Evaluate code in nREPL session.

   Options:
     :session - session ID (clones new if not provided)
     :ns - namespace to eval in
     :timeout-ms - timeout (default: 30000)"
  [conn code & {:keys [session ns timeout-ms] :or {timeout-ms 30000}}]
  (let [;; Clone session if not provided
        session (or session
                    (let [clone-result (clone-session conn)]
                      (when (= :success (:status clone-result))
                        (:session clone-result))))
        message (cond-> {:op "eval" :code code}
                        session (assoc :session session)
                        ns (assoc :ns ns))]
    (send-message conn message :timeout-ms timeout-ms)))

(defn load-file-remote
  "Load file from server's filesystem using nREPL load-file op.

   This sends the file path to the server, which reads and evaluates it.
   Use this when the file exists on the server's filesystem."
  [conn file-path & {:keys [session timeout-ms] :or {timeout-ms 30000}}]
  (let [session (or session
                    (:session (clone-session conn)))
        ;; nREPL load-file op expects file content, not path
        ;; We need to use eval with load-file function instead
        code (format "(load-file \"%s\")" file-path)
        message (cond-> {:op "eval" :code code}
                        session (assoc :session session))]
    (send-message conn message :timeout-ms timeout-ms)))

(defn load-local-file
  "Read file locally and send as code to eval.

   This reads the file on the client side and sends its content as code.
   Use this for browser contexts or when server can't access the file."
  [conn file-path & {:keys [session ns timeout-ms] :or {timeout-ms 30000}}]
  (let [content (slurp file-path)]
    (eval-code conn content
               :session session
               :ns ns
               :timeout-ms timeout-ms)))

(defn interrupt
  "Interrupt evaluation in a session."
  [conn session & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (send-message conn {:op "interrupt" :session session} :timeout-ms timeout-ms))

(defn close-session
  "Close an nREPL session."
  [conn session & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (send-message conn {:op "close" :session session} :timeout-ms timeout-ms))

;; =============================================================================
;; Convenience wrapper for one-shot operations
;; =============================================================================

(defn with-connection
  "Execute function with nREPL connection, ensuring cleanup.

   Usage:
     (with-connection {:port 7888}
       (fn [conn] (eval-code conn \"(+ 1 2)\")))"
  [opts f]
  (let [conn (connect opts)]
    (try
     (f conn)
     (finally
      (close conn)))))

(defn eval!
  "One-shot eval: connect, eval, return result, close.

   Options:
     :host - hostname (default: localhost)
     :port - port number (required)
     :ns - namespace
     :timeout-ms - timeout (default: 30000)"
  [code & {:keys [host port ns timeout-ms] :or {host "localhost" timeout-ms 30000}}]
  (with-connection {:host host :port port}
                   (fn [conn]
                     (eval-code conn code :ns ns :timeout-ms timeout-ms))))

(defn load-file!
  "One-shot load-file from server filesystem.

   Options:
     :host - hostname (default: localhost)
     :port - port number (required)
     :timeout-ms - timeout (default: 30000)"
  [file-path & {:keys [host port timeout-ms] :or {host "localhost" timeout-ms 30000}}]
  (with-connection {:host host :port port}
                   (fn [conn]
                     (load-file-remote conn file-path :timeout-ms timeout-ms))))

(defn load-local-file!
  "One-shot load-local-file: read locally, send as code.

   Options:
     :host - hostname (default: localhost)
     :port - port number (required)
     :ns - namespace
     :timeout-ms - timeout (default: 30000)"
  [file-path & {:keys [host port ns timeout-ms] :or {host "localhost" timeout-ms 30000}}]
  (with-connection {:host host :port port}
                   (fn [conn]
                     (load-local-file conn file-path :ns ns :timeout-ms timeout-ms))))
