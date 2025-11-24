(ns bb-mcp-server.transport.stdio
    "DEPRECATED: Use mcp-stdio.core directly.

  This namespace re-exports from mcp-stdio.core for backwards compatibility.

  Implements the stdio transport protocol:
  - Read JSON-RPC requests from stdin (one per line)
  - Write JSON-RPC responses to stdout (one per line)
  - Handle errors gracefully without crashing

  This is the main entry point for running the MCP server with Claude Code."
    (:require [mcp-stdio.core :as stdio-core]))

;; Re-export main function for backwards compatibility
(def run-stdio-loop!
     "Run the stdio request/response loop.
  DEPRECATED: Use mcp-stdio.core/run-stdio-loop! directly."
     stdio-core/run-stdio-loop!)


