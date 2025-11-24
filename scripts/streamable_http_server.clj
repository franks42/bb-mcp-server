#!/usr/bin/env bb
;; Streamable HTTP MCP Server startup script
;;
;; Usage: bb scripts/streamable_http_server.clj [port]
;;
;; This runs the MCP server using the Streamable HTTP transport
;; (MCP spec 2025-03-26) with session management and SSE support.

(ns streamable-http-server
    "Streamable HTTP MCP Server startup script."
    (:require [bb-mcp-server.module.system :as sys]
              [bb-mcp-server.test-harness :as harness]
              [bb-mcp-server.protocol.router :as router]
              [bb-mcp-server.registry :as registry]
              [streamable-http.core :as shttp]
              [pid-util :as pid-util]))

(def ^:private http-port
     "HTTP server port from CLI args or default 3000."
     (or (some-> (first *command-line-args*) parse-long) 3000))

(println "=== BB MCP Server - Streamable HTTP Transport ===")
(println "    MCP Spec: 2025-03-26")

;; Initialize module system from config file
(println "\n[1/6] Loading modules from system.edn...")
(let [create-result (sys/create-system-from-config)]
  (if (:error create-result)
    (do
     (println "ERROR: Failed to create system:" (:error create-result))
     (System/exit 1))
    (println "  Modules configured:" (get-in create-result [:success :modules]))))

;; Start module system
(println "\n[2/6] Starting module system...")
(let [start-result (sys/start-system!)]
  (if (:error start-result)
    (do
     (println "ERROR: Failed to start system:" (:error start-result))
     (System/exit 1))
    (println "  Started:" (get-in start-result [:success :started]))))

;; Set up MCP handlers (initialize, tools/list, tools/call)
(println "\n[3/6] Setting up MCP handlers...")
(harness/setup-handlers-only!)
(println "  Handlers registered")

;; Write PID file for server management
(println "\n[4/6] Registering process...")
(pid-util/write-pid-file! http-port)

;; Create the JSON-RPC handler for streamable-http
;; This bridges the router to the transport
;; Note: The handler now accepts [ctx request] to support the unified processor
(defn json-rpc-handler
  "Handle JSON-RPC requests by routing to registered MCP handlers.

   Takes a context and JSON-RPC request map, routes it, returns response map.
   This is the bridge between streamable-http transport and bb-mcp-server.

   The ctx contains:
     :transport       - :http
     :session-id      - Session identifier
     :send-notification! - Function to send SSE notifications"
  [ctx request]
  (router/route-request ctx request))

;; Start Streamable HTTP server with REST API support
(println (str "\n[5/6] Starting Streamable HTTP server on port " http-port "..."))
(def server
     "Running Streamable HTTP server instance."
     (shttp/start-server! json-rpc-handler
                          {:port http-port
                           :host "0.0.0.0"
                           :path "/mcp"
                           :health-path "/health"
                           ;; REST API configuration
                           :rest-config {:list-tools-fn            registry/list-tools-for-transport
                                         :get-tool-fn              registry/get-tool
                                         :get-handler-fn           registry/get-handler
                                         :supports-rest-fn         registry/tool-supports-transport?
                                         ;; Module-based routing
                                         :list-modules-fn          registry/list-modules
                                         :list-tools-for-module-fn registry/list-tools-for-module
                                         :get-tool-in-module-fn    registry/get-tool-in-module
                                         ;; Server info for introspection
                                         :server-info-fn           (fn []
                                                                     {:name "bb-mcp-server"
                                                                      :version "0.1.0"
                                                                      :moduleToolSeparator registry/module-tool-separator
                                                                      :mcpProtocolVersion "2025-03-26"})}}))

;; Set up tool list changed notification callback
;; When tools are registered/unregistered, broadcast to all SSE clients
(registry/set-list-changed-callback!
 #(shttp/broadcast-notification! "notifications/tools/list_changed" {}))
(println "  Tool list change notifications enabled")

;; Validate transport availability
(println "\n[6/6] Validating transport compatibility...")
(let [available #{:mcp-http :rest}  ; transports this server provides
      validation (registry/validate-transports available)]
  (println (str "  " (:summary validation)))
  (when-not (:valid? validation)
    (println "  ⚠ Some tools may be unreachable via this server")))

(println (str "\n✓ Server ready! http://localhost:" http-port))
(println "  MCP Endpoints:")
(println "    POST /mcp    - JSON-RPC requests (initialize, tools/list, tools/call)")
(println "    GET  /mcp    - SSE stream (with Mcp-Session-Id header)")
(println "    DELETE /mcp  - Terminate session")
(println "  REST API:")
(println "    GET  /api/server                       - Server info (introspection)")
(println "    GET  /api/modules                      - List all modules")
(println "    GET  /api/modules/:module/tools        - List tools in module")
(println "    GET  /api/modules/:module/tools/:name  - Get tool metadata")
(println "    POST /api/modules/:module/tools/:name  - Call a tool")
(println "  Documentation:")
(println "    GET /api/docs          - HTML API docs")
(println "    GET /api/openapi.json  - OpenAPI spec")
(println "  Utility:")
(println "    GET /health  - Health check")
(println "\n  Features:")
(println "    - Session management (MCP: Mcp-Session-Id header)")
(println "    - Server-Sent Events for server notifications")
(println "    - REST API for direct HTTP tool calls")
(println "    - CORS enabled")
(println (str "\n  Stop with: bb server:stop " http-port))
(println "  Or press Ctrl+C")

;; Handle shutdown - clean up both server and PID file
(.addShutdownHook
 (Runtime/getRuntime)
 (Thread.
  (fn []
    (println "\n\nShutting down...")
    (shttp/stop-server! server)
    (pid-util/delete-pid-file! http-port)
    (println "Goodbye!"))))

;; Keep running
(deref (promise))
