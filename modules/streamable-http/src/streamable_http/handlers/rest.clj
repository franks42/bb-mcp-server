(ns streamable-http.handlers.rest
    "DEPRECATED: Use rest-api.handlers directly.

   This namespace re-exports rest-api.handlers for backwards compatibility.
   New code should require rest-api.handlers instead."
    (:require [rest-api.handlers :as core-handlers]))

;; Re-export public API
(def handle-openapi "See rest-api.handlers/handle-openapi" core-handlers/handle-openapi)
(def handle-docs "See rest-api.handlers/handle-docs" core-handlers/handle-docs)
(def handle-server-info "See rest-api.handlers/handle-server-info" core-handlers/handle-server-info)
(def handle-list-modules "See rest-api.handlers/handle-list-modules" core-handlers/handle-list-modules)
(def handle-list-module-tools "See rest-api.handlers/handle-list-module-tools" core-handlers/handle-list-module-tools)
(def handle-get-module-tool "See rest-api.handlers/handle-get-module-tool" core-handlers/handle-get-module-tool)
(def handle-call-module-tool "See rest-api.handlers/handle-call-module-tool" core-handlers/handle-call-module-tool)
(def create-rest-router "See rest-api.handlers/create-rest-router" core-handlers/create-rest-router)
