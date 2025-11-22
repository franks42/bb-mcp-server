(ns bb-mcp-server.handlers.tools-call
    "Handler for tools/call JSON-RPC method.

  Executes tool handlers with argument validation and error handling.
  Tools are registered via bb-mcp-server.registry and executed via handle-tools-call."
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.protocol.errors :as errors]
              [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

;; DEPRECATED: Use bb-mcp-server.registry/register! instead
(defn register-handler!
  "DEPRECATED: Use bb-mcp-server.registry/register! instead.

  The unified registry requires both tool definition and handler together."
  [tool-name _handler-fn]
  (log/log! {:level :warn
             :id ::deprecated-register-handler
             :msg "register-handler! is deprecated, use registry/register!"
             :data {:tool-name tool-name}})
  (throw (ex-info "register-handler! is deprecated. Use bb-mcp-server.registry/register! with full tool record"
                  {:type :deprecated-api
                   :tool-name tool-name})))

(defn- validate-arguments
  "Validate arguments against tool's input schema using Malli.

  Args:
  - arguments: Map of argument name -> value
  - input-schema: JSON Schema map
  - tool-name: Name of the tool being called

  Returns: {:valid true/false :errors humanized-errors}"
  [arguments input-schema tool-name]
  (errors/validate-arguments arguments input-schema tool-name))

(defn- execute-handler
  "Execute a tool handler with error handling.

  Args:
  - tool-name: String identifier for the tool
  - handler-fn: Handler function to execute
  - arguments: Arguments to pass to handler

  Returns: {:success result} or {:error error-info}

  Catches all exceptions during handler execution."
  [tool-name handler-fn arguments]
  (log/log! {:level :info
             :id ::execute-handler
             :msg "Executing tool handler"
             :data {:tool tool-name
                    :arguments (keys arguments)}})
  (let [start-time (System/currentTimeMillis)]
    (try
      (let [result (handler-fn arguments)
            duration (- (System/currentTimeMillis) start-time)]
        (log/log! {:level :info
                   :id ::handler-success
                   :msg "Tool handler executed successfully"
                   :data {:tool tool-name
                          :duration-ms duration}})
        {:success result})
      (catch Exception e
        (let [duration (- (System/currentTimeMillis) start-time)]
          (log/log! {:level :error
                     :id ::handler-failed
                     :msg "Tool handler execution failed"
                     :error e
                     :data {:tool tool-name
                            :duration-ms duration
                            :error (ex-message e)
                            :error-data (ex-data e)}})
          {:error {:message (ex-message e)
                   :data (ex-data e)}})))))

(defn handle-tools-call
  "Handle tools/call JSON-RPC request.

  Args:
  - request: Parsed JSON-RPC request map with :jsonrpc, :method, :params, :id

  Returns: JSON-RPC response map (success or error)

  Process:
  1. Extract tool name and arguments from params
  2. Look up tool from unified registry
  3. Validate arguments against input schema
  4. Execute handler with error handling
  5. Return result in content format

  Errors:
  - -32000: Tool not found
  - -32002: Invalid tool params (validation failed)
  - -32001: Tool execution failed (handler threw exception)"
  [request]
  (let [request-id (:id request)
        params (:params request)
        tool-name (:name params)
        arguments (or (:arguments params) {})]

    (log/log! {:level :info
               :id ::handle-tools-call
               :msg "Processing tools/call request"
               :data {:request-id request-id
                      :tool tool-name}})

    ;; Look up tool from unified registry
    (if-let [tool (registry/get-tool tool-name)]
      ;; Tool found - validate and execute
      (let [handler-fn (:handler tool)
            input-schema (:inputSchema tool)
            validation-result (validate-arguments arguments input-schema tool-name)]
        (if (:valid validation-result)
          ;; Arguments valid - execute handler
          (let [execution-result (execute-handler tool-name handler-fn arguments)]
            (if (:success execution-result)
              ;; Success - return result in content format
              (let [result (:success execution-result)
                    result-str (if (string? result)
                                 result
                                 (pr-str result))
                    response (msg/create-response
                              request-id
                              {:content [{:type "text"
                                          :text result-str}]})]
                (log/log! {:level :info
                           :id ::tools-call-success
                           :msg "tools/call request completed successfully"
                           :data {:request-id request-id
                                  :tool tool-name}})
                response)
              ;; Error - handler threw exception
              (let [error-data (errors/exception->error-data
                                 (ex-info "Tool execution failed"
                                          (:error execution-result))
                                 {:tool tool-name})]
                (errors/log-error! :runtime-error "Tool execution failed" error-data)
                (msg/create-error-response
                 request-id
                 (:tool-execution-failed errors/error-codes)
                 "Tool execution failed"
                 error-data))))
          ;; Arguments invalid - use detailed validation errors
          (let [error-data {:type :validation-error
                            :tool tool-name
                            :validation-errors (:errors validation-result)
                            :arguments (vec (keys arguments))}]
            (errors/log-error! :validation-error "Invalid tool arguments" error-data)
            (msg/create-error-response
             request-id
             (:invalid-tool-params errors/error-codes)
             "Invalid tool params"
             error-data))))

      ;; Tool not found
      (let [error-data {:type :not-found
                        :tool tool-name
                        :available (vec (registry/tool-names))}]
        (errors/log-error! :not-found "Tool not found in registry" error-data)
        (msg/create-error-response
         request-id
         (:tool-not-found errors/error-codes)
         "Tool not found"
         error-data)))))

(defn reset-handlers!
  "DEPRECATED: Use bb-mcp-server.registry/clear! instead.

  Clears all tools from the unified registry."
  []
  (log/log! {:level :warn
             :id ::deprecated-reset-handlers
             :msg "reset-handlers! is deprecated, use registry/clear!"})
  (registry/clear!))
