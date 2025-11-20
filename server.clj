#!/usr/bin/env bb

;; bb-mcp-server - stdio server entry point
;; This script starts the MCP server and listens on stdin/stdout

(require '[bb-mcp-server.transport.stdio :as stdio])

(stdio/run-stdio-server!)
