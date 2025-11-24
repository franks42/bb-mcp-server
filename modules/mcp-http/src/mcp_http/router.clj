(ns mcp-http.router
    "Request routing for MCP Streamable HTTP transport.

   Routes requests to appropriate handlers based on method and path.
   This is the MCP-only router; REST API routing is separate."
    (:require [mcp-http.handlers.post :as post]
              [mcp-http.handlers.get :as get-handler]
              [mcp-http.handlers.delete :as delete]
              [http-core.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; CORS Headers
;; =============================================================================

(def ^:private cors-headers
     "Default CORS headers for preflight responses."
     {"Access-Control-Allow-Origin" "*"
      "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
      "Access-Control-Allow-Headers" "Content-Type, Accept, Mcp-Session-Id, Last-Event-ID"
      "Access-Control-Expose-Headers" "Mcp-Session-Id"
      "Access-Control-Max-Age" "86400"})

;; =============================================================================
;; Standard Responses
;; =============================================================================

(defn- options-response
  "Handle OPTIONS preflight request."
  []
  {:status 204
   :headers cors-headers
   :body ""})

(defn- not-found-response
  "Handle unknown path."
  [request]
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (util/generate-json {:error "Not found"
                              :path (:uri request)})})

(defn- method-not-allowed-response
  "Handle unsupported method."
  [request]
  {:status 405
   :headers {"Content-Type" "application/json"
             "Allow" "GET, POST, DELETE, OPTIONS"}
   :body (util/generate-json {:error "Method not allowed"
                              :method (name (:request-method request))})})

(defn- health-response
  "Handle health check request."
  []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (util/generate-json {:status "ok"})})

;; =============================================================================
;; Router
;; =============================================================================

(defn create-router
  "Create a router function for MCP endpoint.

   Arguments:
     json-rpc-handler - Function (fn [ctx msg] -> json-rpc-response)
     config           - Configuration map with:
                        :path        - MCP endpoint path (default: /mcp)
                        :health-path - Health check path (default: /health)

   Returns:
     Ring handler function."
  [json-rpc-handler {:keys [path health-path]
                     :or {path "/mcp"
                          health-path "/health"}}]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]

      (log/log! {:level :trace
                 :id    ::route-request
                 :msg   "Routing request"
                 :data  {:uri uri
                         :method method}})

      (cond
        ;; Health check
        (and (= uri health-path) (= method :get))
        (health-response)

        ;; MCP endpoint
        (= uri path)
        (case method
          :options (options-response)
          :post    (post/handle-post request json-rpc-handler)
          :get     (get-handler/handle-get request)
          :delete  (delete/handle-delete request)
          (method-not-allowed-response request))

        ;; Unknown path
        :else
        (not-found-response request)))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn wrap-cors
  "Add CORS headers to all responses.

   Arguments:
     handler - Ring handler function

   Returns:
     Wrapped handler with CORS headers."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if response
        (update response :headers merge
                {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Expose-Headers" "Mcp-Session-Id"})
        response))))
