(ns rest-api.openapi
    "OpenAPI 3.0 specification generator for REST API tools.

   Dynamically generates OpenAPI spec from registered tools that
   support the :rest transport."
    (:require [http-core.util :as util]))

(def openapi-version "3.0.3")

(defn- json-schema-to-openapi
  "Convert MCP JSON Schema to OpenAPI schema.
   MCP uses standard JSON Schema which is compatible with OpenAPI 3.0."
  [schema]
  (when schema
    (cond-> schema
      ;; Remove any MCP-specific extensions if present
            true identity)))

(defn- tool-to-path-item
  "Convert a tool definition to an OpenAPI path item.

   Each tool gets:
   - GET  /api/tools/:name - get metadata
   - POST /api/tools/:name - call tool"
  [tool]
  (let [{:keys [name description inputSchema]} tool]
    {:get {:summary (str "Get metadata for " name)
           :description description
           :operationId (str "get-" name)
           :tags ["tools"]
           :responses {"200" {:description "Tool metadata"
                              :content {"application/json"
                                        {:schema {:type "object"
                                                  :properties {:name {:type "string"}
                                                               :description {:type "string"}
                                                               :inputSchema {:type "object"}
                                                               :href {:type "string"}}}}}}
                       "404" {:description "Tool not found"}}}
     :post {:summary (str "Call " name)
            :description description
            :operationId (str "call-" name)
            :tags ["tools"]
            :requestBody {:required false
                          :content {"application/json"
                                    {:schema (json-schema-to-openapi inputSchema)}}}
            :responses {"200" {:description "Tool result"
                               :content {"application/json"
                                         {:schema {:type "object"
                                                   :properties {:result {}
                                                                :tool {:type "string"}}}}}}
                        "400" {:description "Invalid request"}
                        "404" {:description "Tool not found"}
                        "500" {:description "Tool execution error"}}}}))

(defn generate-spec
  "Generate OpenAPI 3.0 specification from REST-enabled tools.

   Arguments:
     tools - Sequence of tool definitions (from list-tools-for-transport :rest)
     opts  - Optional configuration map:
             :title       - API title (default: \"bb-mcp-server REST API\")
             :version     - API version (default: \"1.0.0\")
             :description - API description
             :server-url  - Server URL (default: \"http://localhost:3000\")

   Returns:
     OpenAPI 3.0 specification as a Clojure map"
  [tools & [{:keys [title version description server-url]
             :or {title "bb-mcp-server REST API"
                  version "1.0.0"
                  description "REST API for bb-mcp-server tools"
                  server-url "http://localhost:3000"}}]]
  {:openapi openapi-version
   :info {:title title
          :version version
          :description description}
   :servers [{:url server-url}]
   :paths (into
           {"/api/tools" {:get {:summary "List all REST-enabled tools"
                                :operationId "list-tools"
                                :tags ["tools"]
                                :responses {"200" {:description "List of tools"
                                                   :content {"application/json"
                                                             {:schema {:type "object"
                                                                       :properties {:tools {:type "array"
                                                                                            :items {:type "object"}}
                                                                                    :count {:type "integer"}}}}}}}}}}
           (for [tool tools]
                [(str "/api/tools/" (:name tool))
                 (tool-to-path-item tool)]))
   :components {:schemas {}}
   :tags [{:name "tools"
           :description "Tool operations"}]})

(defn generate-json
  "Generate OpenAPI spec as JSON string.

   Arguments:
     tools - Sequence of tool definitions
     opts  - Optional configuration (see generate-spec)

   Returns:
     JSON string of OpenAPI specification"
  [tools & [opts]]
  (util/generate-json (generate-spec tools opts)))
