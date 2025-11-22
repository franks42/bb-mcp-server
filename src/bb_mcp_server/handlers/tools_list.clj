(ns bb-mcp-server.handlers.tools-list
    "Handler for tools/list JSON-RPC method.

  Returns the list of available tools from the unified registry.
  Tool registration is handled by bb-mcp-server.registry."
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

;; DEPRECATED: Use bb-mcp-server.registry/register! instead
;; Keeping for backwards compatibility during migration
(defn register-tool!
  "DEPRECATED: Use bb-mcp-server.registry/register! instead.

  This function is kept for backwards compatibility but will be removed
  in a future version. It does NOT register handlers - use the unified
  registry API instead."
  [tool-def]
  (log/log! {:level :warn
             :id ::deprecated-register-tool
             :msg "register-tool! is deprecated, use registry/register!"
             :data {:tool-name (:name tool-def)}})
  ;; Delegate to new registry (without handler - this is the old API)
  ;; Note: This won't work fully since old API doesn't include handlers
  (throw (ex-info "register-tool! is deprecated. Use bb-mcp-server.registry/register! with :handler"
                  {:type :deprecated-api
                   :tool-name (:name tool-def)})))

(defn handle-tools-list
  "Handle tools/list JSON-RPC request.

  Args:
  - request: Parsed JSON-RPC request map with :jsonrpc, :method, :id

  Returns: JSON-RPC response map with :jsonrpc, :result, :id

  The result contains {:tools [...]} with all registered tools."
  [request]
  (let [request-id (:id request)
        tools (registry/list-tools)
        tool-count (count tools)]
    (log/log! {:level :info
               :id ::handle-tools-list
               :msg "Processing tools/list request"
               :data {:request-id request-id
                      :tool-count tool-count}})

    (let [response (msg/create-response request-id {:tools tools})]
      (log/log! {:level :info
                 :id ::tools-list-complete
                 :msg "tools/list request completed"
                 :data {:request-id request-id
                        :tool-count tool-count}})
      response)))

(defn reset-registry!
  "DEPRECATED: Use bb-mcp-server.registry/clear! instead.

  Clears all tools from the unified registry."
  []
  (log/log! {:level :warn
             :id ::deprecated-reset-registry
             :msg "reset-registry! is deprecated, use registry/clear!"})
  (registry/clear!))
