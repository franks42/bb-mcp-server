(ns streamable-http.handlers.post
    "DEPRECATED: Use mcp-http.handlers.post directly.

   This namespace re-exports mcp-http.handlers.post for backwards compatibility.
   New code should require mcp-http.handlers.post instead."
    (:require [mcp-http.handlers.post :as core-post]))

;; Re-export public API
(def handle-post "See mcp-http.handlers.post/handle-post" core-post/handle-post)
