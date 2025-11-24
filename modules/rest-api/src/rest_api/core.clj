(ns rest-api.core
    "REST API module entry point.

   Provides HTTP REST endpoints for tool operations with OpenAPI support.

   Public API:
     - create-rest-router  - Create router for REST endpoints
     - generate-spec       - Generate OpenAPI 3.0 specification
     - generate-html       - Generate HTML documentation"
    (:require [rest-api.handlers :as handlers]
              [rest-api.openapi :as openapi]
              [rest-api.docs :as docs]))

;; =============================================================================
;; Module Manifest
;; =============================================================================

(def module
     "Module manifest for bb-mcp-server module system."
     {:name "rest-api"
      :version "0.1.0"
      :description "REST API endpoints with OpenAPI support"})

;; =============================================================================
;; Public API - Re-exports for convenience
;; =============================================================================

;; Router
(def create-rest-router
  "Create a router for REST API endpoints.
   See rest-api.handlers/create-rest-router for details."
  handlers/create-rest-router)

;; Individual handlers (for custom routing)
(def handle-openapi handlers/handle-openapi)
(def handle-docs handlers/handle-docs)
(def handle-server-info handlers/handle-server-info)
(def handle-list-modules handlers/handle-list-modules)
(def handle-list-module-tools handlers/handle-list-module-tools)
(def handle-get-module-tool handlers/handle-get-module-tool)
(def handle-call-module-tool handlers/handle-call-module-tool)

;; OpenAPI generation
(def generate-spec
  "Generate OpenAPI 3.0 specification from tools.
   See rest-api.openapi/generate-spec for details."
  openapi/generate-spec)

(def generate-openapi-json
  "Generate OpenAPI spec as JSON string.
   See rest-api.openapi/generate-json for details."
  openapi/generate-json)

;; HTML documentation
(def generate-html
  "Generate HTML documentation page.
   See rest-api.docs/generate-html for details."
  docs/generate-html)
