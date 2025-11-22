#!/usr/bin/env bb
;; HTTP MCP Server startup script
;; Usage: bb scripts/http_server.clj [port]

(require '[bb-mcp-server.transport.http :as http])

(def port
     "HTTP server port from CLI args or default 3000."
     (or (some-> (first *command-line-args*) parse-long) 3000))

(println (str "Starting HTTP MCP server on port " port "..."))
(http/start! {:port port})
(println (str "Server started! http://localhost:" port))
(println "Endpoints: POST /mcp, GET /health")
(println "Press Ctrl+C to stop")

;; Keep running
(deref (promise))
