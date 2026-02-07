(ns bb-mcp-server.nrepl-direct.client
    "Standalone nREPL client for direct protocol communication.

   This is a lightweight, stateless nREPL client that:
   - Opens a TCP socket to nREPL server
   - Clones a session for each operation
   - Sends eval/load-file messages
   - Returns results
   - Closes connection

   No dependency on mcp-nrepl module or its state management."
    (:require [bencode.core :as bencode]
              [clojure.edn :as edn]
              [com.github.franks42.uuidv7.core :as uuidv7])
    (:import [java.net Socket]
             [java.io PushbackInputStream]
             [java.util Base64]))

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
;; Base64 helpers
;; =============================================================================

(defn- encode-base64
  "Encode string to base64."
  [s]
  (when s
    (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8"))))

(defn- decode-base64
  "Decode base64 to string."
  [s]
  (when s
    (String. (.decode (Base64/getDecoder) ^String s) "UTF-8")))

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
  (str (uuidv7/uuidv7)))

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

(defn- try-parse-edn
  "Try to parse value as EDN. Returns {:ok value} on success, nil on failure.
   This wrapper distinguishes between 'parsed to nil' and 'failed to parse'."
  [s]
  (when s
    (try
     {:ok (edn/read-string s)}
     (catch Exception _
            nil))))

(defn- merge-responses
  "Merge multiple nREPL responses into single result.
   Includes :value-parsed with EDN-parsed result when possible."
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
          final-status (:status (last responses))
          parse-result (try-parse-edn final-value)]
      (cond-> {}
              (seq all-out) (assoc :out all-out)
              (seq all-err) (assoc :err all-err)
              final-value (assoc :value final-value)
              parse-result (assoc :value-parsed (:ok parse-result))
              final-ex (assoc :ex final-ex)
              final-root-ex (assoc :root-ex final-root-ex)
              final-ns (assoc :ns final-ns)
              final-session (assoc :session final-session)
              final-status (assoc :status final-status)))))

(defn send-message
  "Send nREPL message and wait for response.

   Returns flat response map with :status as string for consistency with mcp-nrepl:
     {:status \"success\" :value \"...\" :value-parsed ... :out \"...\" :err \"...\" ...}
     {:status \"timeout\" :responses [...]}
     {:status \"error\" :error message}"
  [{:keys [out in]} message & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [msg-with-id (assoc message :id (generate-id))]
    (bencode/write-bencode out msg-with-id)
    (.flush out)
    (let [result (read-responses in timeout-ms)]
      (if (= :success (:status result))
        ;; Flatten: merge response fields directly, use string status
        (assoc (merge-responses (:responses result)) :status "success")
        ;; Error/timeout: convert status to string
        (update result :status name)))))

;; =============================================================================
;; High-level operations
;; =============================================================================

(defn clone-session
  "Clone a new nREPL session. Returns session ID in flat response."
  [conn & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [result (send-message conn {:op "clone"} :timeout-ms timeout-ms)]
    (if (= "success" (:status result))
      {:status "success" :session (:new-session result)}
      result)))

(defn describe
  "Get nREPL server capabilities."
  [conn & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (send-message conn {:op "describe"} :timeout-ms timeout-ms))

(defn- add-base64-fields
  "Add base64-encoded versions of output fields if output-base64 is true."
  [result]
  (cond-> result
          (:value result) (assoc :value-base64 (encode-base64 (:value result)))
          (:out result) (assoc :out-base64 (encode-base64 (:out result)))
          (:err result) (assoc :err-base64 (encode-base64 (:err result)))))

(defn eval-code
  "Evaluate code in nREPL session.

   Options:
     :session - session ID (clones new if not provided)
     :ns - namespace to eval in
     :timeout-ms - timeout (default: 30000)
     :input-base64 - if true, decode code from base64 before eval
     :output-base64 - if true, add base64-encoded output fields
     :connection - browser connection nickname/id for nrepl-proxy routing"
  [conn code & {:keys [session ns timeout-ms input-base64 output-base64 connection]
                :or {timeout-ms 30000}}]
  (let [;; Decode input if base64
        actual-code (if input-base64
                      (decode-base64 code)
                      code)
        ;; Clone session if not provided
        session (or session
                    (let [clone-result (clone-session conn)]
                      (when (= "success" (:status clone-result))
                        (:session clone-result))))
        message (cond-> {:op "eval" :code actual-code}
                        session (assoc :session session)
                        ns (assoc :ns ns)
                        connection (assoc :connection connection))
        result (send-message conn message :timeout-ms timeout-ms)]
    ;; Add base64 output fields if requested
    (if (and output-base64 (= "success" (:status result)))
      (add-base64-fields result)
      result)))

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
   Use this for browser contexts or when server can't access the file.

   Options:
     :session - session ID
     :ns - namespace to eval in
     :timeout-ms - timeout (default: 30000)
     :output-base64 - if true, add base64-encoded output fields
     :connection - browser connection nickname/id for nrepl-proxy routing"
  [conn file-path & {:keys [session ns timeout-ms output-base64 connection]
                     :or {timeout-ms 30000}}]
  (let [content (slurp file-path)]
    (eval-code conn content
               :session session
               :ns ns
               :timeout-ms timeout-ms
               :output-base64 output-base64
               :connection connection)))

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
     :timeout-ms - timeout (default: 30000)
     :input-base64 - if true, decode code from base64 before eval
     :output-base64 - if true, add base64-encoded output fields
     :connection - browser connection nickname/id for nrepl-proxy routing"
  [code & {:keys [host port ns timeout-ms input-base64 output-base64 connection]
           :or {host "localhost" timeout-ms 30000}}]
  (with-connection {:host host :port port}
                   (fn [conn]
                     (eval-code conn code
                                :ns ns
                                :timeout-ms timeout-ms
                                :input-base64 input-base64
                                :output-base64 output-base64
                                :connection connection))))

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
     :timeout-ms - timeout (default: 30000)
     :output-base64 - if true, add base64-encoded output fields
     :connection - browser connection nickname/id for nrepl-proxy routing"
  [file-path & {:keys [host port ns timeout-ms output-base64 connection]
                :or {host "localhost" timeout-ms 30000}}]
  (with-connection {:host host :port port}
                   (fn [conn]
                     (load-local-file conn file-path
                                      :ns ns
                                      :timeout-ms timeout-ms
                                      :output-base64 output-base64
                                      :connection connection))))
