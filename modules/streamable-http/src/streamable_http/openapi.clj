(ns streamable-http.openapi
    "DEPRECATED: Use rest-api.openapi directly.

   This namespace re-exports rest-api.openapi for backwards compatibility.
   New code should require rest-api.openapi instead."
    (:require [rest-api.openapi :as core-openapi]))

;; Re-export public API
(def openapi-version core-openapi/openapi-version)
(def generate-spec core-openapi/generate-spec)
(def generate-json core-openapi/generate-json)
