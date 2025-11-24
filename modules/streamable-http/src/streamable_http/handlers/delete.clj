(ns streamable-http.handlers.delete
    "DEPRECATED: Use mcp-http.handlers.delete directly.

   This namespace re-exports mcp-http.handlers.delete for backwards compatibility.
   New code should require mcp-http.handlers.delete instead."
    (:require [mcp-http.handlers.delete :as core-delete]))

;; Re-export public API
(def handle-delete "See mcp-http.handlers.delete/handle-delete" core-delete/handle-delete)
