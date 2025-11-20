(ns bb-mcp-server.handlers.tools-call-test
    "Tests for tools/call handler."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [bb-mcp-server.handlers.tools-call :as tools-call]
              [bb-mcp-server.handlers.tools-list :as tools-list]))

;; Test fixtures
(defn reset-registries
  "Reset both tool and handler registries before each test."
  [test-fn]
  (tools-call/reset-handlers!)
  (tools-list/reset-registry!)
  (test-fn))

(use-fixtures :each reset-registries)

;; Test data
(def hello-tool
     "Simple hello tool definition."
     {:name "hello"
      :description "Say hello to someone"
      :inputSchema {:type "object"
                    :properties {:name {:type "string"}}
                    :required ["name"]}})

(def calculator-tool
     "Calculator tool with multiple parameters."
     {:name "calculator"
      :description "Perform basic arithmetic"
      :inputSchema {:type "object"
                    :properties {:operation {:type "string"}
                                 :a {:type "number"}
                                 :b {:type "number"}}
                    :required ["operation" "a" "b"]}})

(def optional-param-tool
     "Tool with optional parameters."
     {:name "greet"
      :description "Greet someone with optional title"
      :inputSchema {:type "object"
                    :properties {:name {:type "string"}
                                 :title {:type "string"}}
                    :required ["name"]}})

;; Test handler functions
(defn hello-handler
  "Simple hello handler that returns greeting."
  [args]
  (str "Hello, " (:name args) "!"))

(defn calculator-handler
  "Calculator handler that performs operations."
  [args]
  (let [a (:a args)
        b (:b args)
        op (:operation args)]
    (case op
      "add" (+ a b)
      "subtract" (- a b)
      "multiply" (* a b)
      "divide" (/ a b)
      (throw (ex-info "Unknown operation" {:operation op})))))

(defn failing-test-handler
  "Handler that always throws an exception."
  [_args]
  (throw (ex-info "Handler error" {:type :test-error})))

(defn greet-handler
  "Handler with optional parameters."
  [args]
  (let [title (:title args)
        name (:name args)]
    (if title
      (str "Hello, " title " " name "!")
      (str "Hello, " name "!"))))

;; Tests

(deftest register-handler-test
         (testing "Register a handler function"
                  (tools-call/reset-handlers!)
                  (let [result (tools-call/register-handler! "hello" hello-handler)]
                    (is (fn? result) "Returns the handler function")
                    (is (= hello-handler result) "Returns the same function")
                    (is (= 1 (count @tools-call/tool-handlers)) "Handler added to registry")
                    (is (= hello-handler (get @tools-call/tool-handlers "hello"))
                        "Handler stored with correct key")))

         (testing "Register multiple handlers"
                  (tools-call/reset-handlers!)
                  (tools-call/register-handler! "hello" hello-handler)
                  (tools-call/register-handler! "calculator" calculator-handler)
                  (is (= 2 (count @tools-call/tool-handlers)) "Both handlers registered")
                  (is (contains? @tools-call/tool-handlers "hello") "First handler present")
                  (is (contains? @tools-call/tool-handlers "calculator") "Second handler present"))

         (testing "Overwrite existing handler"
                  (tools-call/reset-handlers!)
                  (tools-call/register-handler! "hello" hello-handler)
                  (let [new-handler (fn [_] "New handler")]
                    (tools-call/register-handler! "hello" new-handler)
                    (is (= 1 (count @tools-call/tool-handlers)) "Only one handler")
                    (is (= new-handler (get @tools-call/tool-handlers "hello"))
                        "Handler overwritten"))))

(deftest reset-handlers-test
         (testing "Reset clears all handlers"
                  (tools-call/register-handler! "hello" hello-handler)
                  (tools-call/register-handler! "calculator" calculator-handler)
                  (is (= 2 (count @tools-call/tool-handlers)) "Handlers registered")
                  (tools-call/reset-handlers!)
                  (is (= 0 (count @tools-call/tool-handlers)) "All handlers cleared")
                  (is (empty? @tools-call/tool-handlers) "Registry is empty map")))

(deftest successful-tool-call-test
         (testing "Call tool with valid arguments"
    ;; Register tool and handler
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" hello-handler)

    ;; Make request
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {:name "World"}}
                                 :id 1}
                        response (tools-call/handle-tools-call request)]

      ;; Verify response structure
                    (is (= "2.0" (:jsonrpc response)) "JSON-RPC version correct")
                    (is (= 1 (:id response)) "Request ID matches")
                    (is (contains? response :result) "Has result field")
                    (is (not (contains? response :error)) "No error field")

      ;; Verify result content
                    (let [result (:result response)
                          content (:content result)]
                      (is (vector? content) "Content is a vector")
                      (is (= 1 (count content)) "One content item")
                      (let [item (first content)]
                        (is (= "text" (:type item)) "Content type is text")
                        (is (= "Hello, World!" (:text item)) "Text content correct"))))))

(deftest tool-not-found-test
         (testing "Call unknown tool"
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "nonexistent"
                                          :arguments {}}
                                 :id 2}
                        response (tools-call/handle-tools-call request)]

      ;; Verify error response
                    (is (= "2.0" (:jsonrpc response)) "JSON-RPC version correct")
                    (is (= 2 (:id response)) "Request ID matches")
                    (is (contains? response :error) "Has error field")
                    (is (not (contains? response :result)) "No result field")

      ;; Verify error details
                    (let [error (:error response)]
                      (is (= -32000 (:code error)) "Error code is tool-not-found")
                      (is (= "Tool not found" (:message error)) "Error message correct")
                      (is (= "nonexistent" (get-in error [:data :tool]))
                          "Error data contains tool name")))))

(deftest handler-not-registered-test
         (testing "Call tool without registered handler"
    ;; Register tool but not handler
                  (tools-list/register-tool! hello-tool)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {:name "World"}}
                                 :id 3}
                        response (tools-call/handle-tools-call request)]

      ;; Verify error response
                    (is (contains? response :error) "Has error field")
                    (let [error (:error response)]
                      (is (= -32000 (:code error)) "Error code is tool-not-found")
                      (is (= "Tool not found" (:message error)) "Error message correct")))))

(deftest missing-required-param-test
         (testing "Call tool with missing required parameter"
    ;; Register tool and handler
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" hello-handler)

    ;; Call without required 'name' parameter
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {}}
                                 :id 4}
                        response (tools-call/handle-tools-call request)]

      ;; Verify error response
                    (is (contains? response :error) "Has error field")
                    (let [error (:error response)]
                      (is (= -32002 (:code error)) "Error code is invalid-tool-params")
                      (is (= "Invalid tool params" (:message error)) "Error message correct")
                      (is (= "hello" (get-in error [:data :tool]))
                          "Error data contains tool name")
                      (is (contains? (set (get-in error [:data :missing])) "name")
                          "Error data contains missing field")))))

(deftest multiple-missing-params-test
         (testing "Call tool with multiple missing required parameters"
    ;; Register tool and handler
                  (tools-list/register-tool! calculator-tool)
                  (tools-call/register-handler! "calculator" calculator-handler)

    ;; Call with only one parameter
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "calculator"
                                          :arguments {:a 5}}
                                 :id 5}
                        response (tools-call/handle-tools-call request)]

      ;; Verify error response
                    (is (contains? response :error) "Has error field")
                    (let [error (:error response)
                          missing (set (get-in error [:data :missing]))]
                      (is (= -32002 (:code error)) "Error code is invalid-tool-params")
                      (is (contains? missing "operation") "Missing operation")
                      (is (contains? missing "b") "Missing b")))))

(deftest tool-execution-failed-test
         (testing "Handler throws exception"
    ;; Register tool and failing handler
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" failing-test-handler)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {:name "World"}}
                                 :id 6}
                        response (tools-call/handle-tools-call request)]

      ;; Verify error response
                    (is (contains? response :error) "Has error field")
                    (let [error (:error response)]
                      (is (= -32001 (:code error)) "Error code is tool-execution-failed")
                      (is (= "Tool execution failed" (:message error)) "Error message correct")
                      (is (= "hello" (get-in error [:data :tool]))
                          "Error data contains tool name")
                      (is (= "Handler error" (get-in error [:data :message]))
                          "Error data contains exception message")))))

(deftest content-format-test
         (testing "Result is formatted as content array"
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" hello-handler)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {:name "Test"}}
                                 :id 7}
                        response (tools-call/handle-tools-call request)
                        content (get-in response [:result :content])]

                    (is (vector? content) "Content is a vector")
                    (is (= 1 (count content)) "Content has one item")
                    (is (= "text" (:type (first content))) "Content type is text")
                    (is (string? (:text (first content))) "Text is a string"))))

(deftest result-stringification-test
         (testing "Non-string results are converted to strings"
                  (tools-list/register-tool! calculator-tool)
                  (tools-call/register-handler! "calculator" calculator-handler)

    ;; Call calculator which returns a number
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "calculator"
                                          :arguments {:operation "add"
                                                      :a 5
                                                      :b 3}}
                                 :id 8}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]

                    (is (string? text) "Result is stringified")
                    (is (= "8" text) "Numeric result converted correctly")))

         (testing "String results are returned as-is"
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" hello-handler)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "hello"
                                          :arguments {:name "String"}}
                                 :id 9}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]

                    (is (= "Hello, String!" text) "String result unchanged"))))

(deftest multiple-arguments-test
         (testing "Call tool with multiple arguments"
                  (tools-list/register-tool! calculator-tool)
                  (tools-call/register-handler! "calculator" calculator-handler)

    ;; Test addition
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "calculator"
                                          :arguments {:operation "add"
                                                      :a 10
                                                      :b 20}}
                                 :id 10}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]
                    (is (= "30" text) "Addition works"))

    ;; Test multiplication
                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "calculator"
                                          :arguments {:operation "multiply"
                                                      :a 7
                                                      :b 6}}
                                 :id 11}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]
                    (is (= "42" text) "Multiplication works"))))

(deftest optional-parameters-test
         (testing "Call tool with optional parameters provided"
                  (tools-list/register-tool! optional-param-tool)
                  (tools-call/register-handler! "greet" greet-handler)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "greet"
                                          :arguments {:name "Smith"
                                                      :title "Dr."}}
                                 :id 12}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]
                    (is (= "Hello, Dr. Smith!" text) "Optional parameter used")))

         (testing "Call tool without optional parameters"
                  (tools-list/register-tool! optional-param-tool)
                  (tools-call/register-handler! "greet" greet-handler)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/call"
                                 :params {:name "greet"
                                          :arguments {:name "Smith"}}
                                 :id 13}
                        response (tools-call/handle-tools-call request)
                        text (get-in response [:result :content 0 :text])]
                    (is (= "Hello, Smith!" text) "Works without optional parameter"))))

(deftest empty-arguments-test
         (testing "Call tool with no required parameters"
                  (let [no-params-tool {:name "ping"
                                        :description "Simple ping"
                                        :inputSchema {:type "object"
                                                      :properties {}
                                                      :required []}}
                        ping-handler (fn [_] "pong")]
                    (tools-list/register-tool! no-params-tool)
                    (tools-call/register-handler! "ping" ping-handler)

                    (let [request {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "ping"
                                            :arguments {}}
                                   :id 14}
                          response (tools-call/handle-tools-call request)
                          text (get-in response [:result :content 0 :text])]
                      (is (= "pong" text) "Works with empty arguments")))))

(deftest handler-exception-data-test
         (testing "Exception with ex-data is included in error response"
                  (let [failing-handler (fn [_]
                                          (throw (ex-info "Custom error"
                                                          {:error-type :custom
                                                           :details "Something went wrong"})))]
                    (tools-list/register-tool! hello-tool)
                    (tools-call/register-handler! "hello" failing-handler)

                    (let [request {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "hello"
                                            :arguments {:name "World"}}
                                   :id 15}
                          response (tools-call/handle-tools-call request)
                          error-data (get-in response [:error :data])]

                      (is (= "Custom error" (get-in error-data [:message]))
                          "Exception message in error data")
                      (is (= :custom (get-in error-data [:data :error-type]))
                          "Exception ex-data included")))))

(deftest concurrent-handlers-test
         (testing "Multiple handlers can be registered and called"
                  (tools-list/register-tool! hello-tool)
                  (tools-list/register-tool! calculator-tool)
                  (tools-call/register-handler! "hello" hello-handler)
                  (tools-call/register-handler! "calculator" calculator-handler)

    ;; Call hello
                  (let [request1 {:jsonrpc "2.0"
                                  :method "tools/call"
                                  :params {:name "hello"
                                           :arguments {:name "First"}}
                                  :id 16}
                        response1 (tools-call/handle-tools-call request1)]
                    (is (= "Hello, First!" (get-in response1 [:result :content 0 :text]))
                        "First tool works"))

    ;; Call calculator
                  (let [request2 {:jsonrpc "2.0"
                                  :method "tools/call"
                                  :params {:name "calculator"
                                           :arguments {:operation "subtract"
                                                       :a 10
                                                       :b 3}}
                                  :id 17}
                        response2 (tools-call/handle-tools-call request2)]
                    (is (= "7" (get-in response2 [:result :content 0 :text]))
                        "Second tool works"))))

(deftest request-id-preservation-test
         (testing "Response ID matches request ID in all cases"
                  (tools-list/register-tool! hello-tool)
                  (tools-call/register-handler! "hello" hello-handler)

    ;; Success case
                  (let [response (tools-call/handle-tools-call
                                  {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "hello"
                                            :arguments {:name "Test"}}
                                   :id 100})]
                    (is (= 100 (:id response)) "ID preserved in success response"))

    ;; Tool not found error
                  (let [response (tools-call/handle-tools-call
                                  {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "unknown"
                                            :arguments {}}
                                   :id 200})]
                    (is (= 200 (:id response)) "ID preserved in error response"))

    ;; Validation error
                  (let [response (tools-call/handle-tools-call
                                  {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "hello"
                                            :arguments {}}
                                   :id 300})]
                    (is (= 300 (:id response)) "ID preserved in validation error"))))
