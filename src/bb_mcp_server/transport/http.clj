(ns bb-mcp-server.transport.http
    "DEPRECATED: Use mcp-http.core directly.

  This namespace re-exports from mcp-http.core for backwards compatibility.

  Provides HTTP endpoint for JSON-RPC requests:
  - POST /mcp - JSON-RPC request/response
  - GET /mcp - SSE stream for notifications
  - DELETE /mcp - Session termination
  - GET /health - Health check endpoint"
    (:require [mcp-http.core :as mcp-http]))

;; =============================================================================
;; Re-exports for backwards compatibility
;; =============================================================================

;; Server lifecycle
(def start-server!
     "Start MCP HTTP server.
  DEPRECATED: Use mcp-http.core/start-server! directly."
     mcp-http/start-server!)

(def stop-server!
     "Stop MCP HTTP server.
  DEPRECATED: Use mcp-http.core/stop-server! directly."
     mcp-http/stop-server!)

(def server-status
     "Get server status.
  DEPRECATED: Use mcp-http.core/server-status directly."
     mcp-http/server-status)

;; Session management
(def list-sessions
     "List active sessions.
  DEPRECATED: Use mcp-http.core/list-sessions directly."
     mcp-http/list-sessions)

(def session-count
     "Get active session count.
  DEPRECATED: Use mcp-http.core/session-count directly."
     mcp-http/session-count)

;; Legacy API compatibility (maps old names to new)
(defn start!
  "Start HTTP transport (legacy API).
  DEPRECATED: Use mcp-http.core/start-server! directly.

  Args:
    config - Map with optional keys:
      :port       - HTTP port (default 3000)
      :host       - Bind address (default \"0.0.0.0\")
      :setup-mode - Ignored (use module system instead)"
  [{:keys [port host] :or {port 3000 host "0.0.0.0"}}]
  (start-server! nil {:port port :host host}))

(defn stop!
  "Stop HTTP transport (legacy API).
  DEPRECATED: Use mcp-http.core/stop-server! directly."
  []
  ;; Legacy API doesn't track server reference, so we can't stop properly
  ;; This is a no-op for backwards compatibility
  nil)

(defn running?
  "Check if HTTP transport is running (legacy API).
  DEPRECATED: Use mcp-http.core/server-status directly."
  []
  (boolean (:running (server-status))))
