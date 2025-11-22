#!/usr/bin/env bb
;; Stdio MCP Server startup script with module system
;; Usage: bb scripts/stdio_server.clj

(require '[bb-mcp-server.transport.stdio :as stdio]
         '[bb-mcp-server.module.system :as sys]
         '[bb-mcp-server.test-harness :as harness]
         '[taoensso.trove :as log])

;; Redirect logs to stderr so stdout stays clean for JSON-RPC
(log/log! {:level :info :msg "Starting stdio MCP server with module system"})

;; Initialize module system from config file
(let [create-result (sys/create-system-from-config)]
  (if (:error create-result)
    (do
      (binding [*out* *err*]
        (println "ERROR: Failed to create system:" (:error create-result)))
      (System/exit 1))
    (log/log! {:level :info
               :msg "Modules configured"
               :data {:modules (get-in create-result [:success :modules])}})))

;; Start module system (registers all tools)
(let [start-result (sys/start-system!)]
  (if (:error start-result)
    (do
      (binding [*out* *err*]
        (println "ERROR: Failed to start system:" (:error start-result)))
      (System/exit 1))
    (log/log! {:level :info
               :msg "Modules started"
               :data {:started (get-in start-result [:success :started])}})))

;; Setup MCP handlers only (tools already registered by modules)
(harness/setup-handlers-only!)

;; Run the stdio server (reads stdin, writes stdout)
(stdio/run-stdio-loop!)
