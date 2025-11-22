#!/usr/bin/env bb
;; Streamable HTTP MCP Server startup script
;;
;; Usage: bb scripts/streamable_http_server.clj [port]
;;
;; This runs the MCP server using the Streamable HTTP transport
;; (MCP spec 2025-03-26) with session management and SSE support.

(require '[bb-mcp-server.module.system :as sys]
         '[bb-mcp-server.test-harness :as harness]
         '[bb-mcp-server.protocol.router :as router]
         '[bb-mcp-server.registry :as registry]
         '[streamable-http.core :as shttp]
         '[pid-util :as pid-util])

(def port
     "HTTP server port from CLI args or default 3000."
     (or (some-> (first *command-line-args*) parse-long) 3000))

(println "=== BB MCP Server - Streamable HTTP Transport ===")
(println "    MCP Spec: 2025-03-26")

;; Initialize module system from config file
(println "\n[1/5] Loading modules from system.edn...")
(let [create-result (sys/create-system-from-config)]
  (if (:error create-result)
    (do
     (println "ERROR: Failed to create system:" (:error create-result))
     (System/exit 1))
    (println "  Modules configured:" (get-in create-result [:success :modules]))))

;; Start module system
(println "\n[2/5] Starting module system...")
(let [start-result (sys/start-system!)]
  (if (:error start-result)
    (do
     (println "ERROR: Failed to start system:" (:error start-result))
     (System/exit 1))
    (println "  Started:" (get-in start-result [:success :started]))))

;; Set up MCP handlers (initialize, tools/list, tools/call)
(println "\n[3/5] Setting up MCP handlers...")
(harness/setup-handlers-only!)
(println "  Handlers registered")

;; Write PID file for server management
(println "\n[4/5] Registering process...")
(pid-util/write-pid-file! port)

;; Create the JSON-RPC handler for streamable-http
;; This bridges the router to the transport
(defn json-rpc-handler
  "Handle JSON-RPC requests by routing to registered MCP handlers.

   Takes a JSON-RPC request map, routes it, returns response map.
   This is the bridge between streamable-http transport and bb-mcp-server."
  [request]
  (router/route-request request))

;; Start Streamable HTTP server
(println (str "\n[5/5] Starting Streamable HTTP server on port " port "..."))
(def server
     "Running Streamable HTTP server instance."
     (shttp/start-server! json-rpc-handler
                          {:port port
                           :host "0.0.0.0"
                           :path "/mcp"
                           :health-path "/health"}))

;; Set up tool list changed notification callback
;; When tools are registered/unregistered, broadcast to all SSE clients
(registry/set-list-changed-callback!
 #(shttp/broadcast-notification! "notifications/tools/list_changed" {}))
(println "  Tool list change notifications enabled")

(println (str "\n✓ Server ready! http://localhost:" port))
(println "  Endpoints:")
(println "    POST /mcp    - JSON-RPC requests (initialize, tools/list, tools/call)")
(println "    GET  /mcp    - SSE stream (with Mcp-Session-Id header)")
(println "    DELETE /mcp  - Terminate session")
(println "    GET /health  - Health check")
(println "\n  Features:")
(println "    - Session management (Mcp-Session-Id header)")
(println "    - Server-Sent Events for server notifications")
(println "    - CORS enabled")
(println (str "\n  Stop with: bb server:stop " port))
(println "  Or press Ctrl+C")

;; Handle shutdown - clean up both server and PID file
(.addShutdownHook
 (Runtime/getRuntime)
 (Thread.
  (fn []
    (println "\n\nShutting down...")
    (shttp/stop-server! server)
    (pid-util/delete-pid-file! port)
    (println "Goodbye!"))))

;; Keep running
(deref (promise))
