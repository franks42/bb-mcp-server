(ns bb-mcp-server.test-harness
    "Test harness for MCP server - allows testing handlers without stdio transport.

  This namespace wires up all the components (router, handlers, tools) and provides
  a simple interface for testing the full MCP protocol flow programmatically."
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.protocol.router :as router]
              [bb-mcp-server.handlers.initialize :as init-handler]
              [bb-mcp-server.handlers.tools-list :as tools-list]
              [bb-mcp-server.handlers.tools-call :as tools-call]
              [bb-mcp-server.tools.hello :as hello]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

(defn setup!
  "Set up the test environment by registering all handlers and tools.

  This should be called once before testing to initialize the server state.

  Side effects:
  - Registers MCP method handlers with the router
  - Initializes the hello tool
  - Resets server state"
  []
  (log/log! {:level :info :msg "Setting up test harness"})

  ;; Reset server state
  (router/reset-state!)
  (tools-list/reset-registry!)
  (tools-call/reset-handlers!)

  ;; Register MCP method handlers
  (router/register-handler! "initialize" init-handler/handle-initialize)
  (router/register-handler! "tools/list" tools-list/handle-tools-list)
  (router/register-handler! "tools/call" tools-call/handle-tools-call)

  ;; Initialize hello tool
  (hello/init!)

  (log/log! {:level :info :msg "Test harness setup complete"}))

(defn process-json-rpc
  "Process a JSON-RPC request string and return a JSON-RPC response string.

  This simulates the full MCP request/response cycle:
  1. Parse JSON-RPC request
  2. Route to appropriate handler
  3. Format response as JSON

  Args:
  - json-str: JSON-RPC request as string

  Returns: JSON-RPC response as JSON string

  This is the main entry point for testing - send JSON-RPC, get JSON-RPC back."
  [json-str]
  (log/log! {:level :info :msg "Processing JSON-RPC request" :data {:input-length (count json-str)}})

  ;; Parse request
  (let [parse-result (msg/parse-request json-str)]
    (cond
      ;; Parse error - return error response
      (:error parse-result)
      (let [error-response (msg/create-error-response
                            nil
                            (get-in parse-result [:error :code])
                            (get-in parse-result [:error :message])
                            (get-in parse-result [:error :data]))]
        (json/generate-string error-response))

      ;; Notification - process but don't respond
      ;; JSON-RPC 2.0: "The Server MUST NOT reply to a Notification"
      (:notification parse-result)
      (do
       (log/log! {:level :info :msg "Processing notification (no response will be sent)"
                  :data {:method (get-in parse-result [:notification :method])
                         :params (get-in parse-result [:notification :params])}})
        ;; TODO: Route and process notification when we need to handle them
        ;; For now, just log full notification details and return nil
       (log/log! {:level :debug :msg "Notification details" :data {:notification (:notification parse-result)}})
       nil)

      ;; Regular request - route and respond
      :else
      (let [request (:request parse-result)
            response (router/route-request request)]
        (json/generate-string response)))))

(defn test-initialize
  "Test the initialize method.

  Returns: Response map"
  []
  (log/log! {:level :info :msg "Testing initialize method"})
  (let [request (json/generate-string
                 {:jsonrpc "2.0"
                  :method "initialize"
                  :params {:protocolVersion "1.0"
                           :clientInfo {:name "test-harness"
                                        :version "0.1.0"}}
                  :id 1})
        response-str (process-json-rpc request)
        response (json/parse-string response-str true)]
    (log/log! {:level :info :msg "Initialize response" :data {:response response}})
    response))

(defn test-tools-list
  "Test the tools/list method.

  Returns: Response map"
  []
  (log/log! {:level :info :msg "Testing tools/list method"})
  (let [request (json/generate-string
                 {:jsonrpc "2.0"
                  :method "tools/list"
                  :params {}
                  :id 2})
        response-str (process-json-rpc request)
        response (json/parse-string response-str true)]
    (log/log! {:level :info :msg "tools/list response" :data {:response response}})
    response))

(defn test-tools-call
  "Test the tools/call method with the hello tool.

  Args:
  - name: Name to greet

  Returns: Response map"
  [name]
  (log/log! {:level :info :msg "Testing tools/call method" :data {:tool "hello" :name name}})
  (let [request (json/generate-string
                 {:jsonrpc "2.0"
                  :method "tools/call"
                  :params {:name "hello"
                           :arguments {:name name}}
                  :id 3})
        response-str (process-json-rpc request)
        response (json/parse-string response-str true)]
    (log/log! {:level :info :msg "tools/call response" :data {:response response}})
    response))

(defn run-full-test!
  "Run a full test session demonstrating all MCP methods.

  This runs:
  1. initialize
  2. tools/list
  3. tools/call (hello with different names)

  Returns: Map with test results and success flag"
  []
  (log/log! {:level :info :msg "Running full MCP test session"})

  ;; Setup
  (setup!)

  ;; Run tests
  (let [init-response (test-initialize)
        list-response (test-tools-list)
        call-response-1 (test-tools-call "World")
        call-response-2 (test-tools-call "Alice")

        ;; Check all succeeded
        all-success? (and (contains? init-response :result)
                          (contains? list-response :result)
                          (contains? call-response-1 :result)
                          (contains? call-response-2 :result))]

    (log/log! {:level :info :msg "Full test session complete" :data {:success all-success?}})

    {:success all-success?
     :initialize init-response
     :tools-list list-response
     :tools-call-1 call-response-1
     :tools-call-2 call-response-2}))

(comment
  ;; Interactive testing from REPL

  ;; Setup
 (setup!)

  ;; Test each method
 (test-initialize)
 (test-tools-list)
 (test-tools-call "World")

  ;; Run full test
 (run-full-test!)

  ;; Test error cases
 (process-json-rpc "invalid json")
 (process-json-rpc "{\"jsonrpc\":\"2.0\",\"method\":\"unknown\",\"id\":1}")
 (process-json-rpc "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"nonexistent\"},\"id\":1}"))

