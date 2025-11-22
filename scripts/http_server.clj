#!/usr/bin/env bb
;; HTTP MCP Server startup script with module system
;; Usage: bb scripts/http_server.clj [port]

(require '[bb-mcp-server.transport.http :as http]
         '[bb-mcp-server.module.system :as sys])

(def port
     "HTTP server port from CLI args or default 3000."
     (or (some-> (first *command-line-args*) parse-long) 3000))

(println "=== BB MCP Server with Module System ===")

;; Initialize module system from config file
(println "\n[1/3] Loading modules from system.edn...")
(let [create-result (sys/create-system-from-config)]
  (if (:error create-result)
    (do
     (println "ERROR: Failed to create system:" (:error create-result))
     (System/exit 1))
    (println "  Modules configured:" (get-in create-result [:success :modules]))))

;; Start module system
(println "\n[2/3] Starting module system...")
(let [start-result (sys/start-system!)]
  (if (:error start-result)
    (do
     (println "ERROR: Failed to start system:" (:error start-result))
     (System/exit 1))
    (println "  Started:" (get-in start-result [:success :started]))))

;; Start HTTP server
(println (str "\n[3/3] Starting HTTP server on port " port "..."))
(http/start! {:port port :setup-mode :handlers-only})
(println (str "\n✓ Server ready! http://localhost:" port))
(println "  Endpoints: POST /mcp, GET /health")
(println "  Press Ctrl+C to stop")

;; Keep running
(deref (promise))
