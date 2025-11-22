(ns streamable-http.server
    "HTTP server lifecycle management.

   Wraps http-kit server with start/stop lifecycle and configuration."
    (:require [org.httpkit.server :as http]
              [streamable-http.router :as router]
              [streamable-http.session :as session]
              [taoensso.trove :as log]))

;; =============================================================================
;; Server State
;; =============================================================================

;; Holds reference to running server for cleanup
(defonce ^:private server-instance (atom nil))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn- create-handler
  "Create the Ring handler stack.

   Arguments:
     json-rpc-handler - Function (fn [json-rpc-msg] -> json-rpc-response)
     config           - Server configuration map

   Returns:
     Ring handler function."
  [json-rpc-handler config]
  (let [{:keys [path health-path]} config
        base-router (router/create-router json-rpc-handler
                                          {:path path
                                           :health-path health-path})]
    ;; Apply CORS wrapper
    (router/wrap-cors base-router)))

(defn start-server!
  "Start the Streamable HTTP server.

   Arguments:
     json-rpc-handler - Function (fn [json-rpc-msg] -> json-rpc-response)
     config           - Optional configuration map:
                        :port         - HTTP port (default: 3000)
                        :host         - Bind address (default: \"0.0.0.0\")
                        :path         - MCP endpoint path (default: \"/mcp\")
                        :health-path  - Health check path (default: \"/health\")
                        :session-timeout-ms - Session timeout (default: 3600000)

   Returns:
     Server instance map with :stop! function.

   Example:
     (def server (start-server! my-handler {:port 8080}))
     ;; ... use server ...
     ((:stop! server))"
  [json-rpc-handler & [{:keys [port host path health-path session-timeout-ms]
                        :or {port 3000
                             host "0.0.0.0"
                             path "/mcp"
                             health-path "/health"
                             session-timeout-ms 3600000}
                        :as config}]]

  ;; Check if already running
  (when @server-instance
    (log/log! {:level :warn
               :id    ::server-already-running
               :msg   "Server already running, stopping first"})
    ((:stop! @server-instance)))

  (let [config (merge {:port port
                       :host host
                       :path path
                       :health-path health-path
                       :session-timeout-ms session-timeout-ms}
                      config)
        handler (create-handler json-rpc-handler config)
        stop-fn (http/run-server handler {:port port
                                          :ip host
                                          :legacy-return-value? false})]

    (log/log! {:level :info
               :id    ::server-started
               :msg   "Streamable HTTP server started"
               :data  {:port port
                       :host host
                       :path path
                       :health-path health-path}})

    ;; Start session cleanup task
    (session/start-cleanup-task! session-timeout-ms 60000)

    (let [instance {:stop! (fn []
                             (log/log! {:level :info
                                        :id    ::server-stopping
                                        :msg   "Stopping Streamable HTTP server"})
                             ;; Stop cleanup task
                             (session/stop-cleanup-task!)
                             ;; Destroy all sessions
                             (session/destroy-all-sessions!)
                             ;; Stop http-kit server
                             @(http/server-stop! stop-fn {:timeout 5000})
                             (reset! server-instance nil)
                             (log/log! {:level :info
                                        :id    ::server-stopped
                                        :msg   "Server stopped"}))
                    :port port
                    :host host
                    :config config
                    :http-kit-server stop-fn}]
      (reset! server-instance instance)
      instance)))

(defn stop-server!
  "Stop the running server.

   Arguments:
     server - Server instance returned by start-server!
              If nil, stops the default server instance."
  [& [server]]
  (let [srv (or server @server-instance)]
    (when srv
      ((:stop! srv)))))

(defn server-status
  "Get current server status.

   Returns:
     Map with :running, :port, :host or nil if not running."
  []
  (when-let [srv @server-instance]
            {:running true
             :port (:port srv)
             :host (:host srv)
             :config (:config srv)}))
