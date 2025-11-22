#!/usr/bin/env bb
;; Stdio MCP Server startup script
;; Usage: bb scripts/stdio_server.clj

(require '[bb-mcp-server.transport.stdio :as stdio])

(stdio/run-stdio-server!)
