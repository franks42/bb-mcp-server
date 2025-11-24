(ns streamable-http.router
    "Request routing for MCP Streamable HTTP transport.

   Routes requests to appropriate handlers based on method and path.
   Supports both MCP JSON-RPC (/mcp) and REST API (/api/*) endpoints.

   Note: MCP routing is delegated to mcp-http.router. This namespace
   adds REST API support on top."
    (:require [mcp-http.router :as mcp-router]
              [streamable-http.handlers.rest :as rest-handler]
              [http-core.util :as util]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; Standard Responses
;; =============================================================================

(defn- not-found-response
  "Handle unknown path."
  [request]
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (util/generate-json {:error "Not found"
                              :path (:uri request)})})

;; =============================================================================
;; Combined Router (MCP + REST)
;; =============================================================================

(defn create-router
  "Create a router function for MCP and REST endpoints.

   Arguments:
     json-rpc-handler - Function (fn [ctx msg] -> json-rpc-response)
     config           - Configuration map with:
                        :path        - MCP endpoint path (default: /mcp)
                        :health-path - Health check path (default: /health)
                        :rest-config - Optional REST API config map:
                          :list-tools-fn    - (fn [transport]) -> tools
                          :get-tool-fn      - (fn [name]) -> tool
                          :get-handler-fn   - (fn [name]) -> handler
                          :supports-rest-fn - (fn [name transport]) -> bool

   Returns:
     Ring handler function."
  [json-rpc-handler {:keys [path health-path rest-config]
                     :or {path "/mcp"
                          health-path "/health"}}]
  (let [;; Create MCP router (handles /mcp and /health)
        mcp-handler (mcp-router/create-router json-rpc-handler
                                              {:path path
                                               :health-path health-path})
        ;; Create REST router if configured
        rest-router (when rest-config
                      (rest-handler/create-rest-router rest-config))]
    (fn [request]
      (let [uri (:uri request)]
        (log/log! {:level :trace
                   :id    ::route-request
                   :msg   "Routing request"
                   :data  {:uri uri
                           :method (:request-method request)}})

        (cond
          ;; REST API endpoints (if configured)
          (and rest-router (str/starts-with? uri "/api/"))
          (or (rest-router request)
              (not-found-response request))

          ;; MCP and health endpoints
          :else
          (mcp-handler request))))))

;; Re-export wrap-cors from mcp-http.router
(def wrap-cors "CORS middleware. See mcp-http.router/wrap-cors." mcp-router/wrap-cors)
