(ns bb-mcp-server.transport.http
    "HTTP transport for MCP server.

  Provides HTTP endpoint for JSON-RPC requests:
  - POST /mcp - JSON-RPC request/response
  - GET /health - Health check endpoint

  Uses http-kit for async HTTP server."
    (:require [org.httpkit.server :as http]
              [bb-mcp-server.test-harness :as harness]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; CORS Support
;; -----------------------------------------------------------------------------

(def cors-headers
     "CORS headers for browser clients."
     {"Access-Control-Allow-Origin" "*"
      "Access-Control-Allow-Methods" "POST, GET, OPTIONS"
      "Access-Control-Allow-Headers" "Content-Type, Authorization, X-Request-ID"
      "Access-Control-Max-Age" "86400"})

(defn- cors-preflight-response
  "Handle CORS preflight OPTIONS request."
  []
  {:status 204
   :headers cors-headers
   :body ""})

;; -----------------------------------------------------------------------------
;; Error Code Mapping
;; -----------------------------------------------------------------------------

(defn- error-code->http-status
  "Map JSON-RPC error code to HTTP status code."
  [code]
  (cond
    (= code -32700) 400  ; Parse error
    (= code -32600) 400  ; Invalid request
    (= code -32601) 404  ; Method not found
    (= code -32602) 400  ; Invalid params
    (= code -32603) 500  ; Internal error
    (= code -32000) 404  ; Tool not found
    (= code -32001) 500  ; Tool execution failed
    (= code -32002) 400  ; Invalid tool params
    (= code -32003) 400  ; Server not initialized
    :else 500))

;; -----------------------------------------------------------------------------
;; Request Handlers
;; -----------------------------------------------------------------------------

(defn- mcp-handler
  "Handle MCP JSON-RPC request."
  [request]
  (let [body (slurp (:body request))
        _ (log/log! {:level :info
                     :id ::mcp-request
                     :msg "HTTP MCP request received"
                     :data {:content-length (count body)
                            :remote-addr (:remote-addr request)}})
        response-str (harness/process-json-rpc body)]
    (if response-str
      (let [response (json/parse-string response-str true)
            status (if (:error response)
                     (error-code->http-status (get-in response [:error :code]))
                     200)]
        (log/log! {:level :info
                   :id ::mcp-response
                   :msg "HTTP MCP response"
                   :data {:status status
                          :has-error (boolean (:error response))}})
        {:status status
         :headers (merge cors-headers {"Content-Type" "application/json"})
         :body response-str})
      ;; Notification - no response needed
      {:status 204
       :headers cors-headers
       :body ""})))

(defn- health-handler
  "Handle health check request."
  [_request]
  (let [tool-count (try
                     (require '[bb-mcp-server.registry :as registry])
                     ((resolve 'bb-mcp-server.registry/tool-count))
                     (catch Exception _ 0))]
    {:status 200
     :headers (merge cors-headers {"Content-Type" "application/json"})
     :body (json/generate-string {:status "ok"
                                  :transport "http"
                                  :tools tool-count})}))

(defn- not-found-handler
  "Handle unknown routes."
  [request]
  (log/log! {:level :warn
             :id ::not-found
             :msg "HTTP route not found"
             :data {:method (:request-method request)
                    :uri (:uri request)}})
  {:status 404
   :headers (merge cors-headers {"Content-Type" "application/json"})
   :body (json/generate-string {:error "Not found"
                                :path (:uri request)})})

;; -----------------------------------------------------------------------------
;; Router
;; -----------------------------------------------------------------------------

(defn- router
  "Route HTTP requests to handlers."
  [request]
  (let [method (:request-method request)
        uri (:uri request)]
    (cond
      ;; CORS preflight
      (= method :options)
      (cors-preflight-response)

      ;; MCP endpoint
      (and (= method :post) (= uri "/mcp"))
      (mcp-handler request)

      ;; Health check
      (and (= method :get) (= uri "/health"))
      (health-handler request)

      ;; Not found
      :else
      (not-found-handler request))))

;; -----------------------------------------------------------------------------
;; Transport State
;; -----------------------------------------------------------------------------

(defonce ^{:private true :doc "Server state atom"}
 server-state
         (atom {:server nil :config nil}))

;; -----------------------------------------------------------------------------
;; Transport Implementation
;; -----------------------------------------------------------------------------

(defn start!
  "Start HTTP transport.

  Args:
    config - Map with optional :port (default 3000), :host (default \"0.0.0.0\")

  Returns: Transport state map

  Side effects:
    - Initializes MCP handlers and tools
    - Starts HTTP server"
  [{:keys [port host] :or {port 3000 host "0.0.0.0"} :as config}]
  (log/log! {:level :info
             :id ::http-start
             :msg "Starting HTTP transport"
             :data {:port port :host host}})

  ;; Setup MCP handlers and tools
  (harness/setup!)

  ;; Start HTTP server
  (let [server (http/run-server router {:port port :ip host})]
    (reset! server-state {:server server :config config})
    (log/log! {:level :info
               :id ::http-started
               :msg "HTTP transport started"
               :data {:port port :host host}})
    @server-state))

(defn stop!
  "Stop HTTP transport.

  Returns: nil

  Side effects:
    - Stops HTTP server"
  []
  (log/log! {:level :info
             :id ::http-stop
             :msg "Stopping HTTP transport"})
  (when-let [server (:server @server-state)]
    (server) ; http-kit stop function
    (reset! server-state {:server nil :config nil})
    (log/log! {:level :info
               :id ::http-stopped
               :msg "HTTP transport stopped"}))
  nil)

(defn running?
  "Check if HTTP transport is running.

  Returns: boolean"
  []
  (some? (:server @server-state)))

(defn get-config
  "Get current HTTP transport configuration.

  Returns: Config map or nil if not running"
  []
  (:config @server-state))

;; -----------------------------------------------------------------------------
;; Transport Map (for protocol)
;; -----------------------------------------------------------------------------

(def http-transport
     "HTTP transport implementation map."
     {:type :http
      :start! start!
      :stop! (fn [_] (stop!))
      :running? (fn [_] (running?))})
