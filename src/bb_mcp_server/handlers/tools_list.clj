(ns bb-mcp-server.handlers.tools-list
    "Handler for tools/list JSON-RPC method.

  Manages a registry of available tools and returns them to clients.
  Tools are registered with register-tool! and retrieved via handle-tools-list."
    (:require [bb-mcp-server.protocol.message :as msg]
              [taoensso.timbre :as log]))

;; Atom containing vector of registered tool definitions.
;; Each tool is a map with :name, :description, and :inputSchema keys.
(defonce ^{:doc "Global registry of available tool definitions."}
 tool-registry
         (atom []))

(defn register-tool!
  "Register a tool definition in the global registry.

  Args:
  - tool-def: Map with required keys:
    - :name (string): Tool identifier
    - :description (string): Human-readable description
    - :inputSchema (map): JSON Schema defining tool parameters

  Returns: The registered tool definition

  Throws: ex-info if required fields are missing

  Side effects: Adds tool to tool-registry atom"
  [tool-def]
  (log/info "Registering tool" {:name (:name tool-def)})

  ;; Validate required fields
  (when-not (:name tool-def)
    (log/error "Tool registration failed: missing :name" {:tool tool-def})
    (throw (ex-info "Tool definition missing :name field"
                    {:type :invalid-tool-definition
                     :tool tool-def})))

  (when-not (:description tool-def)
    (log/error "Tool registration failed: missing :description" {:tool tool-def})
    (throw (ex-info "Tool definition missing :description field"
                    {:type :invalid-tool-definition
                     :tool tool-def})))

  (when-not (:inputSchema tool-def)
    (log/error "Tool registration failed: missing :inputSchema" {:tool tool-def})
    (throw (ex-info "Tool definition missing :inputSchema field"
                    {:type :invalid-tool-definition
                     :tool tool-def})))

  ;; Add to registry
  (swap! tool-registry conj tool-def)
  (log/info "Tool registered successfully"
            {:name (:name tool-def)
             :total-tools (count @tool-registry)})
  tool-def)

(defn handle-tools-list
  "Handle tools/list JSON-RPC request.

  Args:
  - request: Parsed JSON-RPC request map with :jsonrpc, :method, :id

  Returns: JSON-RPC response map with :jsonrpc, :result, :id

  The result contains {:tools [...]} with all registered tools."
  [request]
  (let [request-id (:id request)
        tools @tool-registry
        tool-count (count tools)]
    (log/info "Processing tools/list request"
              {:request-id request-id
               :tool-count tool-count})

    (let [response (msg/create-response request-id {:tools tools})]
      (log/info "tools/list request completed"
                {:request-id request-id
                 :tool-count tool-count})
      response)))

(defn reset-registry!
  "Clear all tools from the registry.

  Primarily used for testing to ensure clean state between tests.

  Side effects: Resets tool-registry atom to empty vector"
  []
  (log/info "Resetting tool registry" {:current-count (count @tool-registry)})
  (reset! tool-registry [])
  (log/info "Tool registry reset complete" {:new-count (count @tool-registry)}))
