(ns streamable-http.core
    "MCP Streamable HTTP Transport - Public API

   This module implements MCP spec 2025-03-26 Streamable HTTP transport.
   It is designed to be handler-agnostic: you provide a function that
   processes JSON-RPC messages, and this module handles all HTTP/SSE concerns.

   Quick Start:
     (require '[streamable-http.core :as shttp])

     (defn my-handler [msg]
       {:jsonrpc \"2.0\"
        :result {:echo msg}
        :id (:id msg)})

     (def server (shttp/start-server! my-handler {:port 3000}))

     ;; Later...
     (shttp/stop-server! server)"
    (:require [streamable-http.server :as server]
              [streamable-http.session :as session]
              [streamable-http.sse :as sse]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-config
     "Default server configuration."
     {:port 3000
      :host "0.0.0.0"
      :path "/mcp"
      :health-path "/health"
      :session-timeout-ms 3600000
      :session-cleanup-interval-ms 60000})

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start-server!
  "Start a Streamable HTTP server.

   Arguments:
     json-rpc-handler - Function (fn [json-rpc-msg] -> json-rpc-response)
                        Where json-rpc-msg is a map with :jsonrpc, :method, :params, :id
                        And response is a map with :jsonrpc, :result/:error, :id
     config           - Optional configuration map (merged with default-config)

   Returns:
     Server instance with :stop! function

   Example:
     (def server (start-server! my-handler {:port 8080}))
     ((:stop! server))  ; or (stop-server! server)"
  [json-rpc-handler & [config]]
  (server/start-server! json-rpc-handler (merge default-config config)))

(defn stop-server!
  "Stop a running server.

   Arguments:
     server - Server instance returned by start-server!
              If nil, stops the default server instance."
  [& [server]]
  (server/stop-server! server))

(defn server-status
  "Get current server status.

   Returns:
     Map with :running, :port, :host, :config or nil if not running."
  []
  (server/server-status))

;; =============================================================================
;; Session API (Advanced)
;; =============================================================================

(defn get-session
  "Get session data by ID.

   Arguments:
     session-id - Session UUID string

   Returns:
     Session map or nil if not found."
  [session-id]
  (session/get-session session-id))

(defn list-sessions
  "List all active session IDs.

   Returns:
     Sequence of session ID strings."
  []
  (session/list-sessions))

(defn send-notification!
  "Send a server-to-client notification via SSE.

   Arguments:
     session-id - Target session
     method     - Notification method (e.g., \"notifications/message\")
     params     - Notification params map

   Returns:
     Number of channels notified (0 if session has no SSE channels)."
  [session-id method params]
  (if-let [sess (session/get-session session-id)]
          (let [notification {:jsonrpc "2.0"
                              :method method
                              :params params}
                channels (:sse-channels sess)]
            (doseq [ch channels]
                   (sse/send-event! ch {:event "message" :data notification}))
            (count channels))
          0))

(defn broadcast-notification!
  "Send a notification to ALL active sessions.

   Use sparingly - for server-wide announcements only.

   Arguments:
     method - Notification method
     params - Notification params map

   Returns:
     Total number of channels notified."
  [method params]
  (let [notification {:jsonrpc "2.0"
                      :method method
                      :params params}]
    (reduce + (for [session-id (session/list-sessions)]
                   (if-let [sess (session/get-session session-id)]
                           (let [channels (:sse-channels sess)]
                             (doseq [ch channels]
                                    (sse/send-event! ch {:event "message" :data notification}))
                             (count channels))
                           0)))))

;; =============================================================================
;; Module Entry Point (for bb-mcp-server integration)
;; =============================================================================

(defn module
  "Module entry point for bb-mcp-server module system.

   Arguments:
     json-rpc-handler - Handler function from bb-mcp-server
     config           - Module configuration

   Returns:
     Map with :start!, :stop!, :status functions."
  [json-rpc-handler config]
  (let [server-atom (atom nil)]
    {:start! (fn []
               (reset! server-atom (start-server! json-rpc-handler config)))
     :stop!  (fn []
               (when @server-atom
                 (stop-server! @server-atom)
                 (reset! server-atom nil)))
     :status (fn []
               (if @server-atom
                 (server-status)
                 {:running false}))}))
