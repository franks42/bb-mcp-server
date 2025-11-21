#!/usr/bin/env bb

;; bb-mcp-server - stdio server entry point
;; This script starts the MCP server and listens on stdin/stdout

(require '[bb-mcp-server.transport.stdio :as stdio]
         '[bb-mcp-server.telemetry :as telemetry]
         '[taoensso.trove :as log])

;; Initialize telemetry (Trove + Timbre)
;; This ensures all logs go to stderr (critical for MCP stdio protocol)
(telemetry/ensure-initialized!)

;; Log server startup using Trove API
(log/log! {:level :info
           :id :bb-mcp-server.server/startup
           :data {:transport :stdio
                  :protocol "MCP 2025-03-26"
                  :mode "stdio"}})

(stdio/run-stdio-server!)
