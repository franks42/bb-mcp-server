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
         '[streamable-http.core :as shttp])

(def port
  "HTTP server port from CLI args or default 3000."
  (or (some-> (first *command-line-args*) parse-long) 3000))

(println "=== BB MCP Server - Streamable HTTP Transport ===")
(println "    MCP Spec: 2025-03-26")

;; Initialize module system from config file
(println "\n[1/4] Loading modules from system.edn...")
(let [create-result (sys/create-system-from-config)]
  (if (:error create-result)
    (do
      (println "ERROR: Failed to create system:" (:error create-result))
      (System/exit 1))
    (println "  Modules configured:" (get-in create-result [:success :modules]))))

;; Start module system
(println "\n[2/4] Starting module system...")
(let [start-result (sys/start-system!)]
  (if (:error start-result)
    (do
      (println "ERROR: Failed to start system:" (:error start-result))
      (System/exit 1))
    (println "  Started:" (get-in start-result [:success :started]))))

;; Set up MCP handlers (initialize, tools/list, tools/call)
(println "\n[3/4] Setting up MCP handlers...")
(harness/setup-handlers-only!)
(println "  Handlers registered")

;; Create the JSON-RPC handler for streamable-http
;; This bridges the router to the transport
(defn json-rpc-handler
  "Handle JSON-RPC requests by routing to registered MCP handlers.

   Takes a JSON-RPC request map, routes it, returns response map.
   This is the bridge between streamable-http transport and bb-mcp-server."
  [request]
  (router/route-request request))

;; Start Streamable HTTP server
(println (str "\n[4/4] Starting Streamable HTTP server on port " port "..."))
(def server
  (shttp/start-server! json-rpc-handler
                       {:port port
                        :host "0.0.0.0"
                        :path "/mcp"
                        :health-path "/health"}))

(println (str "\n\u2713 Server ready! http://localhost:" port))
(println "  Endpoints:")
(println "    POST /mcp    - JSON-RPC requests (initialize, tools/list, tools/call)")
(println "    GET  /mcp    - SSE stream (with Mcp-Session-Id header)")
(println "    DELETE /mcp  - Terminate session")
(println "    GET /health  - Health check")
(println "\n  Features:")
(println "    - Session management (Mcp-Session-Id header)")
(println "    - Server-Sent Events for server notifications")
(println "    - CORS enabled")
(println "\n  Press Ctrl+C to stop")

;; Handle shutdown
(.addShutdownHook
 (Runtime/getRuntime)
 (Thread.
  (fn []
    (println "\n\nShutting down...")
    (shttp/stop-server! server)
    (println "Goodbye!"))))

;; Keep running
(deref (promise))
