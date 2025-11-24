(ns streamable-http.handlers.get
    "DEPRECATED: Use mcp-http.handlers.get directly.

   This namespace re-exports mcp-http.handlers.get for backwards compatibility.
   New code should require mcp-http.handlers.get instead."
    (:require [mcp-http.handlers.get :as core-get]))

;; Re-export public API
(def handle-get core-get/handle-get)
