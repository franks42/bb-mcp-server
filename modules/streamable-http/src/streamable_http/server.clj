(ns streamable-http.server
    "DEPRECATED: Use mcp-http.server directly.

   This namespace re-exports mcp-http.server for backwards compatibility.
   New code should require mcp-http.server instead."
    (:require [mcp-http.server :as core-server]))

;; Re-export all public vars from mcp-http.server
(def start-server! core-server/start-server!)
(def stop-server! core-server/stop-server!)
(def server-status core-server/server-status)
(def draining? core-server/draining?)
