(ns bb-mcp-server.handlers.tools-call
    "Handler for tools/call JSON-RPC method.

  Executes tool handlers with argument validation and error handling.
  Tools are registered with register-handler! and executed via handle-tools-call."
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.handlers.tools-list :as tools-list]
              [taoensso.timbre :as log]))

;; Atom containing map of tool name -> handler function.
;; Handler functions take [arguments] and return a result value.
(defonce ^{:doc "Global registry of tool handler functions."}
 tool-handlers
         (atom {}))

(defn register-handler!
  "Register a tool handler function in the global registry.

  Args:
  - tool-name: String identifier for the tool
  - handler-fn: Function that takes [arguments] map and returns result

  Returns: The registered handler function

  Side effects: Adds handler to tool-handlers atom"
  [tool-name handler-fn]
  (log/info "Registering tool handler" {:tool tool-name})
  (swap! tool-handlers assoc tool-name handler-fn)
  (log/info "Tool handler registered successfully"
            {:tool tool-name
             :total-handlers (count @tool-handlers)})
  handler-fn)

(defn- validate-arguments
  "Validate arguments against tool's input schema.

  Args:
  - arguments: Map of argument name -> value
  - input-schema: JSON Schema map with :required field

  Returns: true if valid, false otherwise

  Currently implements simple validation checking that all required fields exist.
  Future enhancement: Full JSON Schema validation with Malli."
  [arguments input-schema]
  (let [required-fields (get input-schema :required [])
        argument-keys (set (map name (keys arguments)))
        missing-fields (remove #(contains? argument-keys %) required-fields)]
    (log/debug "Validating arguments"
               {:required required-fields
                :provided argument-keys
                :missing missing-fields})
    (empty? missing-fields)))

(defn- execute-handler
  "Execute a tool handler with error handling.

  Args:
  - tool-name: String identifier for the tool
  - handler-fn: Handler function to execute
  - arguments: Arguments to pass to handler

  Returns: {:success result} or {:error error-info}

  Catches all exceptions during handler execution."
  [tool-name handler-fn arguments]
  (log/info "Executing tool handler"
            {:tool tool-name
             :arguments (keys arguments)})
  (let [start-time (System/currentTimeMillis)]
    (try
     (let [result (handler-fn arguments)
           duration (- (System/currentTimeMillis) start-time)]
       (log/info "Tool handler executed successfully"
                 {:tool tool-name
                  :duration-ms duration})
       {:success result})
     (catch Exception e
            (let [duration (- (System/currentTimeMillis) start-time)]
              (log/error e "Tool handler execution failed"
                         {:tool tool-name
                          :duration-ms duration
                          :error (ex-message e)
                          :error-data (ex-data e)})
              {:error {:message (ex-message e)
                       :data (ex-data e)}})))))

(defn handle-tools-call
  "Handle tools/call JSON-RPC request.

  Args:
  - request: Parsed JSON-RPC request map with :jsonrpc, :method, :params, :id

  Returns: JSON-RPC response map (success or error)

  Process:
  1. Extract tool name and arguments from params
  2. Look up tool definition and handler
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

    (log/info "Processing tools/call request"
              {:request-id request-id
               :tool tool-name})

    ;; Look up tool definition
    (let [tools @tools-list/tool-registry
          tool-def (first (filter #(= (:name %) tool-name) tools))]

      (cond
        ;; Tool not found
        (nil? tool-def)
        (do
         (log/warn "Tool not found in registry"
                   {:tool tool-name
                    :available-tools (map :name tools)})
         (msg/create-error-response
          request-id
          (:tool-not-found msg/error-codes)
          "Tool not found"
          {:tool tool-name}))

        ;; Look up handler
        :else
        (let [handler-fn (get @tool-handlers tool-name)]
          (cond
            ;; Handler not registered
            (nil? handler-fn)
            (do
             (log/warn "Tool handler not registered"
                       {:tool tool-name})
             (msg/create-error-response
              request-id
              (:tool-not-found msg/error-codes)
              "Tool not found"
              {:tool tool-name}))

            ;; Validate arguments
            (not (validate-arguments arguments (:inputSchema tool-def)))
            (let [required-fields (get-in tool-def [:inputSchema :required] [])
                  provided-keys (set (map name (keys arguments)))
                  missing-fields (remove #(contains? provided-keys %) required-fields)]
              (log/warn "Invalid tool arguments"
                        {:tool tool-name
                         :required required-fields
                         :provided provided-keys
                         :missing missing-fields})
              (msg/create-error-response
               request-id
               (:invalid-tool-params msg/error-codes)
               "Invalid tool params"
               {:tool tool-name
                :required required-fields
                :provided provided-keys
                :missing missing-fields}))

            ;; Execute handler
            :else
            (let [execution-result (execute-handler tool-name handler-fn arguments)]
              (if (:success execution-result)
                ;; Success - return result in content format
                (let [result (:success execution-result)
                      ;; Convert result to string if not already
                      result-str (if (string? result)
                                   result
                                   (pr-str result))
                      response (msg/create-response
                                request-id
                                {:content [{:type "text"
                                            :text result-str}]})]
                  (log/info "tools/call request completed successfully"
                            {:request-id request-id
                             :tool tool-name})
                  response)
                ;; Error - handler threw exception
                (do
                 (log/error "Tool execution failed"
                            {:request-id request-id
                             :tool tool-name
                             :error (:error execution-result)})
                 (msg/create-error-response
                  request-id
                  (:tool-execution-failed msg/error-codes)
                  "Tool execution failed"
                  (merge {:tool tool-name}
                         (:error execution-result))))))))))))

(defn reset-handlers!
  "Clear all handlers from the registry.

  Primarily used for testing to ensure clean state between tests.

  Side effects: Resets tool-handlers atom to empty map"
  []
  (log/info "Resetting tool handlers" {:current-count (count @tool-handlers)})
  (reset! tool-handlers {})
  (log/info "Tool handlers reset complete" {:new-count (count @tool-handlers)}))
