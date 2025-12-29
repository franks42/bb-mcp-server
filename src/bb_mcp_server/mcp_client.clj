(ns bb-mcp-server.mcp-client
    "Shared MCP client functionality for testing and evaluation"
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [clojure.edn :as edn]
              [cheshire.core :as json]
              [babashka.http-client :as http]))

;; Session management
(def ^:private sessions (atom {}))

(defn ports-dir
  "Get the ports directory path. Public for testing override."
  []
  ".ports")

(defn find-port-by-nickname
  "Find the port number for a server by nickname"
  [nickname]
  (let [port-file (io/file (ports-dir) (str nickname ".json"))]
    (when (.exists port-file)
      (try
       (let [content (slurp port-file)
             data (json/parse-string content true)]
         (:port data))
       (catch Exception _
              nil)))))

(defn find-any-ports
  "Find all running servers and their ports"
  []
  (let [dir (io/file (ports-dir))]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".json"))
           (mapcat (fn [f]
                     (try
                      (let [content (slurp f)
                            data (json/parse-string content true)
                            nickname (str/replace (.getName f) ".json" "")]
                        [{:nickname nickname :port (:port data) :data data}])
                      (catch Exception _
                             []))))))))

(defn encode-base64
  "Encode text to base64 string using Java"
  [text]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes text "UTF-8")))

(defn needs-base64?
  "Check if text needs base64 encoding"
  [text]
  (or (str/includes? text "\"")
      (str/includes? text "\n")
      (str/includes? text "\\")
      (> (count text) 100)))

(defn build-eval-request
  "Build a JSON-RPC request for local-eval tool"
  [code _session-id]
  (let [needs-b64 (needs-base64? code)
        actual-code (if needs-b64 (encode-base64 code) code)]
    {:jsonrpc "2.0"
     :id (random-uuid)
     :method "tools/call"
     :params {:name "local-eval.local-eval"
              :arguments {:code actual-code
                          :input-base64 needs-b64}}}))

(defn create-session!
  "Create a new MCP session on the given port"
  [port]
  (let [url (str "http://localhost:" port "/mcp")
        init-request {:jsonrpc "2.0"
                      :id 0
                      :method "initialize"
                      :params {:protocolVersion "2024-11-05"
                               :capabilities {:tools {:listChanged true}}
                               :clientInfo {:name "bb-mcp-client" :version "1.0.0"}}}
        response (http/post url {:body (json/generate-string init-request)
                                 :headers {"Content-Type" "application/json"}})]
    (when (= 200 (:status response))
      (let [session-id (get-in response [:headers "mcp-session-id"])]
        session-id))))

(defn get-or-create-session!
  "Get existing session or create new one"
  [port]
  (if-let [session-id (get @sessions port)]
          session-id
          (if-let [new-session (create-session! port)]
                  (do
                   (swap! sessions assoc port new-session)
                   new-session)
                  (throw (ex-info "Failed to create session" {:port port})))))

(defn build-load-file-request
  "Build a JSON-RPC request for local-load-file tool"
  [path _session-id]
  {:jsonrpc "2.0"
   :id (random-uuid)
   :method "tools/call"
   :params {:name "local-eval.local-load-file"
            :arguments {:path path}}})

(defn send-request!
  "Send a JSON-RPC request to the server"
  [port request]
  (let [url (str "http://localhost:" port "/mcp")
        session-id (get-or-create-session! port)
        request-with-session (assoc request :params
                                    (assoc (:params request) :sessionId session-id))
        response (http/post url {:body (json/generate-string request-with-session)
                                 :headers {"Content-Type" "application/json"
                                           "Mcp-Session-Id" session-id}})]
    (if (= 200 (:status response))
      (json/parse-string (:body response) true)
      (throw (ex-info "Request failed" {:status (:status response)
                                        :body (:body response)})))))

(defn- try-parse-edn
  "Try to parse a value as EDN if it's a string. Useful for pr-str'd values."
  [value]
  (if (string? value)
    (try
     (edn/read-string value)
     (catch Exception _ value))
    value))

(defn- parse-result-text
  "Parse result text (always JSON from local-eval, then optionally EDN for result).
   :edn   - Parse as JSON, then parse :result as EDN (preserves Clojure types)
   :json  - Parse as JSON only (default)
   :raw   - Return text as-is without parsing"
  [text output-format]
  (case output-format
    :raw text
    ;; Both :edn and :json start by parsing JSON
    (try
     (let [parsed (json/parse-string text true)]
       (if (= output-format :edn)
          ;; For EDN mode, try to parse the :result field as EDN
         (if (map? parsed)
           (update parsed :result try-parse-edn)
           parsed)
         parsed))
     (catch Exception _ text))))

(defn extract-result
  "Extract the result from an MCP response.

   Options:
     :output-format - :edn (preserves Clojure types), :json (default), or :raw

   The server returns results as JSON with :result potentially being pr-str'd.
   Using :edn parses that pr-str'd value back to Clojure types."
  ([response]
   (extract-result response {}))
  ([response opts]
   (let [output-format (get opts :output-format :json)]
     (if-let [error (:error response)]
             (throw (ex-info "MCP Error" error))
             (let [content (get-in response [:result :content])]
               (if (vector? content)
                 (let [text (:text (first content))
                       parsed (parse-result-text text output-format)]
                   (if (= output-format :raw)
                     parsed
               ;; For JSON and EDN, handle wrapped results
                     (if (map? parsed)
                       (if (= "error" (:status parsed))
                         (throw (ex-info "Evaluation Error" parsed))
                         (:result parsed))
                       parsed)))
                 content))))))

(defn extract-full-result
  "Extract the full evaluation response including stdout, stderr, and result.

   Returns a map with:
     :status  - :ok or :error
     :result  - the evaluation result (on success)
     :error   - error message (on failure)
     :stdout  - captured stdout
     :stderr  - captured stderr

   Options:
     :output-format - :edn (default), :json, or :raw for the result field"
  ([response]
   (extract-full-result response {}))
  ([response opts]
   (let [output-format (get opts :output-format :edn)]
     (if-let [error (:error response)]
             (throw (ex-info "MCP Error" error))
             (let [content (get-in response [:result :content])
                   text (:text (first content))
                   parsed (json/parse-string text true)]
               (if (= "error" (:status parsed))
                 {:status :error
                  :error (:error parsed)
                  :stdout (:stdout parsed "")
                  :stderr (:stderr parsed "")
                  :stacktrace (:stacktrace parsed)}
                 {:status :ok
                  :result (let [r (:result parsed)]
                            (case output-format
                              :edn (if (string? r)
                                     (try (edn/read-string r)
                                          (catch Exception _ r))
                                     r)
                              :raw r
                              r))
                  :stdout (:stdout parsed "")
                  :stderr (:stderr parsed "")}))))))

(defn eval-code!
  "Evaluate Clojure code on the server.

   Options:
     :output-format - :edn (preserves Clojure types), :json (default), or :raw

   Examples:
     (eval-code! 3000 \"(+ 1 2)\")
     (eval-code! \"my-server\" \"#{:a :b :c}\" {:output-format :edn})
     (eval-code! 3000 \"(range 5)\" {:output-format :edn})"
  ([port-or-nickname code]
   (eval-code! port-or-nickname code {}))
  ([port-or-nickname code opts]
   (let [port (if (number? port-or-nickname)
                port-or-nickname
                (or (find-port-by-nickname port-or-nickname)
                    (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
         request (build-eval-request code nil)
         response (send-request! port request)]
     (extract-result response opts))))

(defn eval-code-full!
  "Evaluate Clojure code and return full response including stdout/stderr.

   Returns a map with :status, :result/:error, :stdout, :stderr.
   Options:
     :output-format - :edn (default), :json, or :raw"
  ([port-or-nickname code]
   (eval-code-full! port-or-nickname code {}))
  ([port-or-nickname code opts]
   (let [port (if (number? port-or-nickname)
                port-or-nickname
                (or (find-port-by-nickname port-or-nickname)
                    (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
         request (build-eval-request code nil)
         response (send-request! port request)]
     (extract-full-result response opts))))

(defn load-file!
  "Load a Clojure file on the server.

   Options:
     :output-format - :edn (preserves Clojure types), :json (default), or :raw"
  ([port-or-nickname path]
   (load-file! port-or-nickname path {}))
  ([port-or-nickname path opts]
   (let [port (if (number? port-or-nickname)
                port-or-nickname
                (or (find-port-by-nickname port-or-nickname)
                    (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
         request (build-load-file-request path nil)
         response (send-request! port request)]
     (extract-result response opts))))

(defn clear-sessions!
  "Clear all cached sessions"
  []
  (reset! sessions {}))

;; Helper functions for CLI tools
(defn find-server
  "Find a server port by nickname, port number, or auto-detect"
  [nickname-or-port]
  (cond
    (number? nickname-or-port) nickname-or-port
    (string? nickname-or-port) (or (find-port-by-nickname nickname-or-port)
                                   (throw (ex-info "Server not found" {:nickname nickname-or-port})))
    :else (let [servers (find-any-ports)]
            (if (= 1 (count servers))
              (:port (first servers))
              (if (empty? servers)
                (throw (ex-info "No servers running" {}))
                (throw (ex-info "Multiple servers running, please specify --nickname"
                                {:servers (map :nickname servers)})))))))

;; =============================================================================
;; Generic Tool Calling
;; =============================================================================

(defn build-tool-request
  "Build a JSON-RPC request for any MCP tool.

   Arguments:
     tool-name - Fully qualified tool name (e.g., \"nrepl.nrepl-connection\")
     arguments - Map of arguments to pass to the tool

   Returns a JSON-RPC request map."
  [tool-name arguments]
  {:jsonrpc "2.0"
   :id (random-uuid)
   :method "tools/call"
   :params {:name tool-name
            :arguments arguments}})

(defn call-tool!
  "Call any MCP tool on a server.

   Arguments:
     port-or-nickname - Server port number or nickname
     tool-name        - Fully qualified tool name (e.g., \"nrepl.nrepl-connection\")
     arguments        - Map of arguments to pass to the tool

   Returns the raw JSON-RPC response from the server."
  [port-or-nickname tool-name arguments]
  (let [port (if (number? port-or-nickname)
               port-or-nickname
               (or (find-port-by-nickname port-or-nickname)
                   (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
        request (build-tool-request tool-name arguments)]
    (send-request! port request)))

(defn extract-text-content
  "Extract plain text content from a tool call response.

   Returns the text from the first content item.
   Use this for tools that return plain text (echo, hello, etc)."
  [response]
  (if-let [error (:error response)]
          (throw (ex-info "MCP Error" error))
          (get-in response [:result :content 0 :text])))

(defn extract-json-result
  "Extract and parse JSON from a tool call response.

   For tools that return JSON (nrepl, calculate, local-eval with maps).
   Returns the parsed map from the first content item's text."
  [response]
  (let [text (extract-text-content response)]
    (when text
      (json/parse-string text true))))

(defn extract-tool-result
  "Extract the result from a tool call response.

   Tries to parse as JSON, falls back to plain text if parsing fails.
   Returns either parsed JSON (map/vector) or plain text string."
  [response]
  (let [text (extract-text-content response)]
    (when text
      (try
       (json/parse-string text true)
       (catch Exception _
              text)))))

;; =============================================================================
;; MCP Protocol Introspection
;; =============================================================================

(defn- build-list-tools-request
  "Build a JSON-RPC request for tools/list."
  []
  {:jsonrpc "2.0"
   :id (random-uuid)
   :method "tools/list"
   :params {}})

(defn list-tools!
  "List all available tools on an MCP server.

   Returns a vector of tool definitions, each containing:
     :name        - Fully qualified tool name (module.tool)
     :description - Tool description
     :inputSchema - JSON Schema for arguments

   Example:
     (list-tools! \"my-server\")
     (list-tools! 3000)"
  [port-or-nickname]
  (let [port (if (number? port-or-nickname)
               port-or-nickname
               (or (find-port-by-nickname port-or-nickname)
                   (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
        request (build-list-tools-request)
        response (send-request! port request)]
    (if-let [error (:error response)]
            (throw (ex-info "MCP Error" error))
            (get-in response [:result :tools] []))))

(defn get-tool
  "Get a specific tool by name from the server.

   Returns the tool definition or nil if not found."
  [port-or-nickname tool-name]
  (let [tools (list-tools! port-or-nickname)]
    (first (filter #(= tool-name (:name %)) tools))))

(defn get-server-info!
  "Get server information via initialize handshake.

   Returns a map with:
     :protocolVersion - MCP protocol version
     :capabilities    - Server capabilities
     :serverInfo      - Server name and version
     :session-id      - The session ID (from response header)

   Example:
     (get-server-info! \"my-server\")
     (get-server-info! 3000)"
  [port-or-nickname]
  (let [port (if (number? port-or-nickname)
               port-or-nickname
               (or (find-port-by-nickname port-or-nickname)
                   (throw (ex-info "Server not found" {:nickname port-or-nickname}))))
        url (str "http://localhost:" port "/mcp")
        init-request {:jsonrpc "2.0"
                      :id 0
                      :method "initialize"
                      :params {:protocolVersion "2024-11-05"
                               :capabilities {:tools {:listChanged true}}
                               :clientInfo {:name "bb-mcp-client" :version "1.0.0"}}}
        response (http/post url {:body (json/generate-string init-request)
                                 :headers {"Content-Type" "application/json"}})]
    (if (= 200 (:status response))
      (let [session-id (get-in response [:headers "mcp-session-id"])
            body (json/parse-string (:body response) true)
            result (:result body)]
        (assoc result :session-id session-id))
      (throw (ex-info "Failed to get server info"
                      {:status (:status response)
                       :body (:body response)})))))
