#!/usr/bin/env bb
;; bb-mcp-server - stdio server entry point
;; This script starts the MCP server and listens on stdin/stdout

(ns server
    "Main entry point for bb-mcp-server stdio transport."
    (:require [mcp-stdio.core :as stdio]
              [bb-mcp-server.protocol.processor :as processor]
              [bb-mcp-server.telemetry :as telemetry]
              [taoensso.trove :as log]))

;; Initialize telemetry (Trove + Timbre)
;; This ensures all logs go to stderr (critical for MCP stdio protocol)
(telemetry/ensure-initialized!)

;; Log server startup using Trove API
(log/log! {:level :info
           :id :bb-mcp-server.server/startup
           :data {:transport :stdio
                  :protocol "MCP 2025-03-26"
                  :mode "stdio"}})

;; Create stdio context and handler
;; The handler processes JSON-RPC requests using the unified processor
(let [ctx (processor/make-stdio-ctx)
      handler (fn [line] (processor/process-request-str ctx line))]
  (stdio/run-stdio-loop! handler))
