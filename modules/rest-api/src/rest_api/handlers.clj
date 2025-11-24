(ns rest-api.handlers
    "REST API handlers for tool operations.

   Provides HTTP REST endpoints for tools registered with :rest transport support.

   Server Introspection:
     GET  /api/server                         - Server info (name, version, moduleToolSeparator)

   Module-based Endpoints:
     GET  /api/modules                        - List all modules
     GET  /api/modules/:module/tools          - List tools for a module
     GET  /api/modules/:module/tools/:name    - Get tool metadata
     POST /api/modules/:module/tools/:name    - Call a tool

   Documentation Endpoints:
     GET  /api/openapi.json    - OpenAPI 3.0 specification
     GET  /api/docs            - HTML documentation

   Unlike MCP JSON-RPC, REST uses standard HTTP semantics:
   - Status codes for errors (400, 404, 500)
   - JSON request/response bodies
   - Module and tool name in URL path (prevents name collisions)"
    (:require [http-core.util :as util]
              [rest-api.openapi :as openapi]
              [rest-api.docs :as docs]
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
   - URL path for invoking (module-based when module is present)"
  [tool]
  (let [module (:module tool)
        href (if module
               (str "/api/modules/" module "/tools/" (:name tool))
               (str "/api/tools/" (:name tool)))]
    (cond-> {:name (:name tool)
             :description (:description tool)
             :inputSchema (:inputSchema tool)
             :href href}
            module (assoc :module module))))

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

(defn- extract-module-name
  "Extract module name from URI path.

   /api/modules/nrepl/tools/... -> 'nrepl'"
  [uri]
  (when-let [[_ module] (re-matches #"/api/modules/([^/]+)(?:/.*|$)" uri)]
            module))

(defn- extract-module-tool
  "Extract module and tool name from URI path.

   /api/modules/nrepl/tools/nrepl-eval -> {:module 'nrepl', :tool 'nrepl-eval'}"
  [uri]
  (when-let [[_ module tool] (re-matches #"/api/modules/([^/]+)/tools/([^/]+)" uri)]
            {:module module :tool tool}))

;; =============================================================================
;; OpenAPI Handler
;; =============================================================================

(defn handle-openapi
  "Handle GET /api/openapi.json - return OpenAPI specification.

   Arguments:
     request       - Ring request
     list-tools-fn - Function to list tools for :rest transport
     opts          - OpenAPI generation options (server-url, etc.)

   Returns:
     Ring response with OpenAPI JSON"
  [_request list-tools-fn opts]
  (log/log! {:level :debug
             :id    ::openapi-spec
             :msg   "Generating OpenAPI specification"})
  (let [tools (list-tools-fn :rest)
        spec (openapi/generate-spec tools opts)]
    (success-response spec)))

;; =============================================================================
;; Documentation Handler
;; =============================================================================

(defn handle-docs
  "Handle GET /api/docs - return HTML documentation page.

   Arguments:
     request       - Ring request
     list-tools-fn - Function to list tools for :rest transport
     opts          - Options (title, server-url, etc.)

   Returns:
     Ring response with HTML documentation"
  [_request list-tools-fn opts]
  (log/log! {:level :debug
             :id    ::docs-html
             :msg   "Generating HTML documentation"})
  (let [tools (list-tools-fn :rest)
        html (docs/generate-html tools opts)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body html}))

;; =============================================================================
;; Server Info Handler
;; =============================================================================

(defn handle-server-info
  "Handle GET /api/server - return server info for introspection.

   Returns information equivalent to MCP initialize response serverInfo,
   enabling clients to introspect server configuration without MCP handshake.

   Arguments:
     request         - Ring request
     server-info-fn  - Function returning server info map

   Returns:
     Ring response with server info"
  [_request server-info-fn]
  (log/log! {:level :debug
             :id    ::server-info
             :msg   "Returning server info"})
  (let [info (server-info-fn)]
    (success-response info)))

;; =============================================================================
;; Module-based Handlers
;; =============================================================================

(defn handle-list-modules
  "Handle GET /api/modules - list all modules.

   Arguments:
     request          - Ring request
     list-modules-fn  - Function to list all module names

   Returns:
     Ring response with module list"
  [_request list-modules-fn]
  (log/log! {:level :debug
             :id    ::list-modules
             :msg   "Listing modules"})
  (let [modules (list-modules-fn)]
    (success-response {:modules (map (fn [m] {:name m :href (str "/api/modules/" m "/tools")})
                                     modules)
                       :count (count modules)})))

(defn handle-list-module-tools
  "Handle GET /api/modules/:module/tools - list tools for a module.

   Arguments:
     request                      - Ring request
     module-name                  - Name of module
     list-tools-for-module-fn    - Function to list tools for a module

   Returns:
     Ring response with tool list or 404"
  [_request module-name list-tools-for-module-fn]
  (log/log! {:level :debug
             :id    ::list-module-tools
             :msg   "Listing module tools"
             :data  {:module module-name}})
  (let [tools (list-tools-for-module-fn module-name)]
    (if (empty? tools)
      (not-found-response (str "Module '" module-name "' not found or has no tools"))
      (success-response {:module module-name
                         :tools (map tool-to-rest-format tools)
                         :count (count tools)}))))

(defn handle-get-module-tool
  "Handle GET /api/modules/:module/tools/:name - get tool metadata.

   Arguments:
     request                  - Ring request
     module-name              - Name of module
     tool-name                - Name of tool
     get-tool-in-module-fn   - Function to get tool by module and name

   Returns:
     Ring response with tool info or 404"
  [_request module-name tool-name get-tool-in-module-fn]
  (log/log! {:level :debug
             :id    ::get-module-tool
             :msg   "Getting module tool metadata"
             :data  {:module module-name :tool-name tool-name}})
  (if-let [tool (get-tool-in-module-fn module-name tool-name)]
          (success-response (tool-to-rest-format
                             (select-keys tool [:name :description :inputSchema :module])))
          (not-found-response (str "Tool '" tool-name "' not found in module '" module-name "'"))))

(defn handle-call-module-tool
  "Handle POST /api/modules/:module/tools/:name - call a tool.

   Arguments:
     request                  - Ring request
     module-name              - Name of module
     tool-name                - Short name of tool (from URL)
     get-tool-in-module-fn   - Function to get tool by module and short name

   Returns:
     Ring response with tool result or error"
  [request module-name tool-name get-tool-in-module-fn]
  (log/log! {:level :info
             :id    ::call-module-tool
             :msg   "Calling tool via module REST"
             :data  {:module module-name :tool-name tool-name}})

  ;; Get the tool (includes handler) by module + short name
  (if-let [tool (get-tool-in-module-fn module-name tool-name)]
    ;; Parse body
          (let [[params error] (parse-json-body request)]
            (if error
              (bad-request-response error)

        ;; Call handler from tool record
              (if-let [handler (:handler tool)]
                      (try
                       (let [result (handler params)]
                         (log/log! {:level :info
                                    :id    ::module-tool-success
                                    :msg   "Module tool call succeeded"
                                    :data  {:module module-name :tool-name tool-name}})
                         (created-response {:result result
                                            :module module-name
                                            :tool tool-name}))
                       (catch Exception e
                              (log/log! {:level :error
                                         :id    ::module-tool-error
                                         :msg   "Module tool call failed"
                                         :data  {:module module-name
                                                 :tool-name tool-name
                                                 :error (.getMessage e)}})
                              (internal-error-response
                               "Tool execution failed"
                               {:module module-name
                                :tool tool-name
                                :message (.getMessage e)})))
                      (internal-error-response "Tool handler not found"))))

    ;; Tool not found
          (not-found-response (str "Tool '" tool-name "' not found in module '" module-name "'"))))

;; =============================================================================
;; Router
;; =============================================================================

(defn create-rest-router
  "Create a router for REST API endpoints.

   Arguments:
     config - Map with:
       :list-tools-fn            - (fn [transport]) -> seq of tool definitions (for docs/OpenAPI)
       :get-handler-fn           - (fn [name]) -> handler fn or nil
       :list-modules-fn          - (fn []) -> seq of module names
       :list-tools-for-module-fn - (fn [module-name]) -> seq of tool definitions
       :get-tool-in-module-fn    - (fn [module-name tool-name]) -> tool or nil
       :server-info-fn           - (fn []) -> server info map (optional)
       :openapi-opts             - Optional OpenAPI config (server-url, title, etc.)

   Returns:
     Function (fn [request]) -> response or nil
     Returns nil if request doesn't match /api/* path"
  [{:keys [list-tools-fn get-handler-fn
           list-modules-fn list-tools-for-module-fn get-tool-in-module-fn
           server-info-fn openapi-opts]}]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]

      (cond
        ;; GET /api/docs - HTML documentation
        (and (= uri "/api/docs") (= method :get))
        (handle-docs request list-tools-fn openapi-opts)

        ;; GET /api/openapi.json - OpenAPI specification
        (and (= uri "/api/openapi.json") (= method :get))
        (handle-openapi request list-tools-fn openapi-opts)

        ;; GET /api/server - server info for introspection
        (and (= uri "/api/server") (= method :get) server-info-fn)
        (handle-server-info request server-info-fn)

        ;; =================================================================
        ;; Module-based routes
        ;; =================================================================

        ;; GET /api/modules - list all modules
        (and (= uri "/api/modules") (= method :get))
        (handle-list-modules request list-modules-fn)

        ;; GET /api/modules/:module/tools - list module tools
        (and (re-matches #"/api/modules/[^/]+/tools" uri) (= method :get))
        (when-let [module (extract-module-name uri)]
                  (handle-list-module-tools request module list-tools-for-module-fn))

        ;; POST /api/modules/:module/tools/:name - call a tool in module
        (and (re-matches #"/api/modules/[^/]+/tools/[^/]+" uri) (= method :post))
        (when-let [{:keys [module tool]} (extract-module-tool uri)]
                  (handle-call-module-tool request module tool get-tool-in-module-fn))

        ;; GET /api/modules/:module/tools/:name - get tool metadata in module
        (and (re-matches #"/api/modules/[^/]+/tools/[^/]+" uri) (= method :get))
        (when-let [{:keys [module tool]} (extract-module-tool uri)]
                  (handle-get-module-tool request module tool get-tool-in-module-fn))

        ;; =================================================================
        ;; Common routes
        ;; =================================================================

        ;; OPTIONS for CORS preflight
        (and (re-matches #"/api/.*" uri) (= method :options))
        {:status 204
         :headers {"Access-Control-Allow-Origin" "*"
                   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                   "Access-Control-Allow-Headers" "Content-Type, Accept"
                   "Access-Control-Max-Age" "86400"}
         :body ""}

        ;; Method not allowed for known paths
        (= uri "/api/modules")
        (method-not-allowed-response "GET, OPTIONS")

        (re-matches #"/api/modules/[^/]+/tools" uri)
        (method-not-allowed-response "GET, OPTIONS")

        (re-matches #"/api/modules/[^/]+/tools/[^/]+" uri)
        (method-not-allowed-response "GET, POST, OPTIONS")

        ;; Not an API path - return nil to let other handlers try
        :else
        nil))))
