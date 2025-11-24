(ns streamable-http.docs
    "DEPRECATED: Use rest-api.docs directly.

   This namespace re-exports rest-api.docs for backwards compatibility.
   New code should require rest-api.docs instead."
    (:require [rest-api.docs :as core-docs]))

;; Re-export public API
(def generate-html "See rest-api.docs/generate-html" core-docs/generate-html)
