(ns streamable-http.server
    "DEPRECATED: Use mcp-http.server directly.

   This namespace re-exports mcp-http.server for backwards compatibility.
   New code should require mcp-http.server instead."
    (:require [mcp-http.server :as core-server]))

;; Re-export all public vars from mcp-http.server
(def start-server! "See mcp-http.server/start-server!" core-server/start-server!)
(def stop-server! "See mcp-http.server/stop-server!" core-server/stop-server!)
(def server-status "See mcp-http.server/server-status" core-server/server-status)
(def draining? "See mcp-http.server/draining?" core-server/draining?)
