(ns bb-mcp-server.tools.hello
    "Test tool for verifying MCP toolchain end-to-end.

  Implements a simple 'hello' tool that returns a greeting message.
  This tool serves as a reference implementation and integration test."
    (:require [bb-mcp-server.handlers.tools-list :as tools-list]
              [bb-mcp-server.handlers.tools-call :as tools-call]
              [taoensso.trove :as log]))

;; Tool definition for tools/list
(def hello-tool
     "Tool definition for the 'hello' tool.

  Returns a greeting message for the provided name."
     {:name "hello"
      :description "Returns a greeting message"
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Name to greet"}}
                    :required ["name"]}})

(defn hello-handler
  "Handler function for the 'hello' tool.

  Args:
  - arguments: Map containing:
    - :name (string): Name to greet

  Returns: String greeting in format 'Hello, {name}!'

  Throws: ex-info if :name is missing or empty

  Telemetry: Logs hello request with provided name"
  [arguments]
  (let [name (:name arguments)]
    (log/log! {:level :info :msg "Processing hello request" :data {:name name}})

    ;; Validate name is present (should be caught by schema validation, but defensive)
    (when (or (nil? name) (empty? name))
      (log/log! {:level :error :msg "Hello handler called with missing or empty name" :data {:arguments arguments}})
      (throw (ex-info "Missing or empty name argument"
                      {:type :invalid-arguments
                       :arguments arguments})))

    (let [greeting (str "Hello, " name "!")]
      (log/log! {:level :info :msg "Hello request completed" :data {:name name :greeting greeting}})
      greeting)))

(defn init!
  "Initialize the hello tool.

  Registers the tool definition and handler function with the MCP server.

  Side effects:
  - Registers hello-tool with tools-list/register-tool!
  - Registers hello-handler with tools-call/register-handler!

  Telemetry: Logs initialization start and completion"
  []
  (log/log! {:level :info :msg "Initializing hello tool"})

  (try
    ;; Register tool definition
   (tools-list/register-tool! hello-tool)
   (log/log! {:level :info :msg "Hello tool definition registered"})

    ;; Register handler function
   (tools-call/register-handler! "hello" hello-handler)
   (log/log! {:level :info :msg "Hello tool handler registered"})

   (log/log! {:level :info :msg "Hello tool initialization complete"})
   true

   (catch Exception e
          (log/log! {:level :error :msg "Hello tool initialization failed"
                     :error e
                     :data {:error (ex-message e)
                            :error-data (ex-data e)}})
          (throw e))))
