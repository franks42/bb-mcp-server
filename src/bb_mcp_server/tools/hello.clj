(ns bb-mcp-server.tools.hello
    "Test tool for verifying MCP toolchain end-to-end.

  Implements a simple 'hello' tool that returns a greeting message.
  This tool serves as a reference implementation and integration test."
    (:require [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

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
    (log/log! {:level :info
               :id ::hello-request
               :msg "Processing hello request"
               :data {:name name}})

    ;; Validate name is present (should be caught by schema validation, but defensive)
    (when (or (nil? name) (empty? name))
      (log/log! {:level :error
                 :id ::hello-missing-name
                 :msg "Hello handler called with missing or empty name"
                 :data {:arguments arguments}})
      (throw (ex-info "Missing or empty name argument"
                      {:type :invalid-arguments
                       :arguments arguments})))

    (let [greeting (str "Hello, " name "!")]
      (log/log! {:level :info
                 :id ::hello-complete
                 :msg "Hello request completed"
                 :data {:name name :greeting greeting}})
      greeting)))

;; Complete tool record for unified registry
(def hello-tool
     "Complete tool record for the 'hello' tool.

  Includes definition and handler for unified registry."
     {:name "hello"
      :description "Returns a greeting message"
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Name to greet"}}
                    :required ["name"]}
      :handler hello-handler})

(defn init!
  "Initialize the hello tool.

  Registers the complete tool (definition + handler) with the unified registry.

  Side effects:
  - Registers hello-tool with registry/register!

  Telemetry: Logs initialization start and completion"
  []
  (log/log! {:level :info
             :id ::hello-init-start
             :msg "Initializing hello tool"})

  (try
   (registry/register! hello-tool)
   (log/log! {:level :info
              :id ::hello-init-complete
              :msg "Hello tool initialization complete"})
   true

   (catch Exception e
          (log/log! {:level :error
                     :id ::hello-init-failed
                     :msg "Hello tool initialization failed"
                     :error e
                     :data {:error (ex-message e)
                            :error-data (ex-data e)}})
          (throw e))))
