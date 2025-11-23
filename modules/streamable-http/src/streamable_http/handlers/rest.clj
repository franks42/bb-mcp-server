(ns streamable-http.handlers.rest
  "REST API handlers for tool operations.

   Provides HTTP REST endpoints for tools registered with :rest transport support.

   Endpoints:
     GET  /api/tools           - List available REST tools
     POST /api/tools/:name     - Call a tool by name
     GET  /api/tools/:name     - Get tool metadata

   Unlike MCP JSON-RPC, REST uses standard HTTP semantics:
   - Status codes for errors (400, 404, 500)
   - JSON request/response bodies
   - Tool name in URL path"
  (:require [streamable-http.util :as util]
            [taoensso.trove :as log]))

;; =============================================================================
;; Response Helpers
;; =============================================================================

(defn- json-response
  "Create JSON response with given status and body."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (util/generate-json body)})

(defn- success-response
  "Create success response (200)."
  [body]
  (json-response 200 body))

(defn- created-response
  "Create created response (201) - for tool call results."
  [body]
  (json-response 200 body))  ; Using 200 for tool calls, not 201

(defn- bad-request-response
  "Create bad request response (400)."
  [message & [details]]
  (json-response 400 (cond-> {:error "Bad Request"
                              :message message}
                       details (assoc :details details))))

(defn- not-found-response
  "Create not found response (404)."
  [message]
  (json-response 404 {:error "Not Found"
                      :message message}))

(defn- method-not-allowed-response
  "Create method not allowed response (405)."
  [allowed-methods]
  {:status 405
   :headers {"Content-Type" "application/json"
             "Allow" allowed-methods}
   :body (util/generate-json {:error "Method Not Allowed"
                              :allowed allowed-methods})})

(defn- internal-error-response
  "Create internal error response (500)."
  [message & [details]]
  (json-response 500 (cond-> {:error "Internal Server Error"
                              :message message}
                       details (assoc :details details))))

;; =============================================================================
;; Tool Transformation
;; =============================================================================

(defn- tool-to-rest-format
  "Transform tool definition to REST API format.

   Converts MCP tool format to REST-friendly format with:
   - Camelcase keys for JSON convention
   - URL path for invoking"
  [tool]
  {:name (:name tool)
   :description (:description tool)
   :inputSchema (:inputSchema tool)
   :href (str "/api/tools/" (:name tool))})

;; =============================================================================
;; Request Parsing
;; =============================================================================

(defn- parse-json-body
  "Parse JSON body from request. Returns [parsed-data nil] or [nil error-msg]."
  [request]
  (let [body (:body request)]
    (cond
      (nil? body)
      [{} nil]

      (string? body)
      (if (empty? body)
        [{} nil]
        (if-let [parsed (util/parse-json body)]
          [parsed nil]
          [nil "Invalid JSON"]))

      (instance? java.io.InputStream body)
      (let [content (slurp body)]
        (if (empty? content)
          [{} nil]
          (if-let [parsed (util/parse-json content)]
            [parsed nil]
            [nil "Invalid JSON"])))

      :else
      [nil "Unsupported body type"])))

(defn- extract-tool-name
  "Extract tool name from URI path.

   /api/tools/calculate -> 'calculate'
   /api/tools/my-tool   -> 'my-tool'"
  [uri]
  (when-let [[_ name] (re-matches #"/api/tools/([^/]+)" uri)]
    name))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn handle-list-tools
  "Handle GET /api/tools - list all REST-enabled tools.

   Arguments:
     request        - Ring request
     list-tools-fn  - Function to list tools for :rest transport

   Returns:
     Ring response with tool list"
  [_request list-tools-fn]
  (log/log! {:level :debug
             :id    ::list-tools
             :msg   "Listing REST tools"})
  (let [tools (list-tools-fn :rest)]
    (success-response {:tools (map tool-to-rest-format tools)
                       :count (count tools)})))

(defn handle-get-tool
  "Handle GET /api/tools/:name - get tool metadata.

   Arguments:
     request          - Ring request
     tool-name        - Name of tool to get
     get-tool-fn      - Function to get tool by name
     supports-rest-fn - Function to check if tool supports :rest

   Returns:
     Ring response with tool info or 404"
  [_request tool-name get-tool-fn supports-rest-fn]
  (log/log! {:level :debug
             :id    ::get-tool
             :msg   "Getting tool metadata"
             :data  {:tool-name tool-name}})

  (if-let [tool (get-tool-fn tool-name)]
    (if (supports-rest-fn tool-name :rest)
      (success-response (tool-to-rest-format
                          (select-keys tool [:name :description :inputSchema])))
      (not-found-response (str "Tool '" tool-name "' not available via REST")))
    (not-found-response (str "Tool '" tool-name "' not found"))))

(defn handle-call-tool
  "Handle POST /api/tools/:name - call a tool.

   Arguments:
     request          - Ring request
     tool-name        - Name of tool to call
     get-handler-fn   - Function to get tool handler
     supports-rest-fn - Function to check if tool supports :rest

   Returns:
     Ring response with tool result or error"
  [request tool-name get-handler-fn supports-rest-fn]
  (log/log! {:level :info
             :id    ::call-tool
             :msg   "Calling tool via REST"
             :data  {:tool-name tool-name}})

  ;; Check tool exists and supports REST
  (if-not (supports-rest-fn tool-name :rest)
    (not-found-response (str "Tool '" tool-name "' not found or not available via REST"))

    ;; Parse body
    (let [[params error] (parse-json-body request)]
      (if error
        (bad-request-response error)

        ;; Call handler
        (if-let [handler (get-handler-fn tool-name)]
          (try
            (let [result (handler params)]
              (log/log! {:level :info
                         :id    ::tool-success
                         :msg   "Tool call succeeded"
                         :data  {:tool-name tool-name}})
              (created-response {:result result
                                 :tool tool-name}))
            (catch Exception e
              (log/log! {:level :error
                         :id    ::tool-error
                         :msg   "Tool call failed"
                         :data  {:tool-name tool-name
                                 :error (.getMessage e)}})
              (internal-error-response
                "Tool execution failed"
                {:tool tool-name
                 :message (.getMessage e)})))
          (internal-error-response "Tool handler not found"))))))

;; =============================================================================
;; Router
;; =============================================================================

(defn create-rest-router
  "Create a router for REST API endpoints.

   Arguments:
     config - Map with:
       :list-tools-fn     - (fn [transport]) -> seq of tool definitions
       :get-tool-fn       - (fn [name]) -> tool or nil
       :get-handler-fn    - (fn [name]) -> handler fn or nil
       :supports-rest-fn  - (fn [name transport]) -> boolean

   Returns:
     Function (fn [request]) -> response or nil
     Returns nil if request doesn't match /api/* path"
  [{:keys [list-tools-fn get-tool-fn get-handler-fn supports-rest-fn]}]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]

      (cond
        ;; GET /api/tools - list all tools
        (and (= uri "/api/tools") (= method :get))
        (handle-list-tools request list-tools-fn)

        ;; POST /api/tools/:name - call a tool
        (and (re-matches #"/api/tools/[^/]+" uri) (= method :post))
        (when-let [tool-name (extract-tool-name uri)]
          (handle-call-tool request tool-name get-handler-fn supports-rest-fn))

        ;; GET /api/tools/:name - get tool metadata
        (and (re-matches #"/api/tools/[^/]+" uri) (= method :get))
        (when-let [tool-name (extract-tool-name uri)]
          (handle-get-tool request tool-name get-tool-fn supports-rest-fn))

        ;; OPTIONS for CORS preflight
        (and (re-matches #"/api/.*" uri) (= method :options))
        {:status 204
         :headers {"Access-Control-Allow-Origin" "*"
                   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                   "Access-Control-Allow-Headers" "Content-Type, Accept"
                   "Access-Control-Max-Age" "86400"}
         :body ""}

        ;; Method not allowed for known paths
        (= uri "/api/tools")
        (method-not-allowed-response "GET, OPTIONS")

        (re-matches #"/api/tools/[^/]+" uri)
        (method-not-allowed-response "GET, POST, OPTIONS")

        ;; Not an API path - return nil to let other handlers try
        :else
        nil))))
