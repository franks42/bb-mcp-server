(ns bb-mcp-server.protocol.router-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [bb-mcp-server.protocol.router :as router]
              [bb-mcp-server.protocol.message :as msg]))

;; Test fixtures
(defn reset-router-fixture
  "Reset router state before each test."
  [f]
  (router/reset-state!)
  (f))

(use-fixtures :each reset-router-fixture)

;; Helper functions
(defn mock-handler-success
  "Mock handler that returns success response."
  [request]
  (msg/create-response (:id request) {:message "success"}))

(defn mock-handler-error
  "Mock handler that returns error response."
  [request]
  (msg/create-error-response
   (:id request)
   (:invalid-params msg/error-codes)
   "Test error"
   {:test true}))

(defn mock-handler-exception
  "Mock handler that throws an exception."
  [_request]
  (throw (ex-info "Test exception" {:test true})))

;; Tests
(deftest test-register-handler-single
         (testing "Register a handler"
                  (router/register-handler! "test-method" mock-handler-success)
                  (let [handlers (router/get-handlers)]
                    (is (contains? handlers "test-method")
                        "Handler should be registered")
                    (is (fn? (get handlers "test-method"))
                        "Registered value should be a function"))))

(deftest test-register-handler-multiple
         (testing "Register multiple handlers"
                  (router/register-handler! "method1" mock-handler-success)
                  (router/register-handler! "method2" mock-handler-error)
                  (let [handlers (router/get-handlers)]
                    (is (= 2 (count handlers))
                        "Should have two handlers registered")
                    (is (contains? handlers "method1")
                        "First handler should be registered")
                    (is (contains? handlers "method2")
                        "Second handler should be registered"))))

(deftest test-register-handler-overwrite
         (testing "Overwrite existing handler"
                  (router/register-handler! "test-method" mock-handler-success)
                  (router/register-handler! "test-method" mock-handler-error)
                  (let [handlers (router/get-handlers)]
                    (is (= 1 (count handlers))
                        "Should only have one handler")
                    (is (= mock-handler-error (get handlers "test-method"))
                        "Handler should be overwritten with new value"))))

(deftest test-route-request-unknown-method
         (testing "Route to unknown method returns method-not-found error"
                  (let [request {:jsonrpc "2.0"
                                 :method "unknown-method"
                                 :id 1}
                        response (router/route-request request)]

                    (is (contains? response :error)
                        "Response should contain error")
                    (is (= (:method-not-found msg/error-codes)
                           (get-in response [:error :code]))
                        "Error code should be method-not-found")
                    (is (= "Method not found"
                           (get-in response [:error :message]))
                        "Error message should be 'Method not found'")
                    (is (= "unknown-method"
                           (get-in response [:error :data :method]))
                        "Error data should include method name")
                    (is (= 1 (:id response))
                        "Response should include request ID"))))

(deftest test-route-request-not-initialized
         (testing "Route to method when not initialized returns error"
                  (router/register-handler! "tools/list" mock-handler-success)
                  (is (not (router/initialized?))
                      "Server should not be initialized")

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/list"
                                 :id 2}
                        response (router/route-request request)]

                    (is (contains? response :error)
                        "Response should contain error")
                    (is (= (:server-not-initialized msg/error-codes)
                           (get-in response [:error :code]))
                        "Error code should be server-not-initialized")
                    (is (= "Server not initialized"
                           (get-in response [:error :message]))
                        "Error message should be 'Server not initialized'")
                    (is (= "tools/list"
                           (get-in response [:error :data :method]))
                        "Error data should include method name")
                    (is (= 2 (:id response))
                        "Response should include request ID"))))

(deftest test-route-request-initialize-allowed-when-not-initialized
         (testing "Initialize method is allowed when not initialized"
                  (router/register-handler! "initialize" mock-handler-success)
                  (is (not (router/initialized?))
                      "Server should not be initialized")

                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name "test-client"}}
                                 :id 1}
                        response (router/route-request request)]

                    (is (not (contains? response :error))
                        "Response should not contain error")
                    (is (contains? response :result)
                        "Response should contain result")
                    (is (= {:message "success"} (:result response))
                        "Result should match mock handler output")
                    (is (= 1 (:id response))
                        "Response should include request ID"))))

(deftest test-route-request-success
         (testing "Route to existing handler when initialized returns success"
                  (router/register-handler! "tools/list" mock-handler-success)
                  (router/set-initialized! true)

                  (let [request {:jsonrpc "2.0"
                                 :method "tools/list"
                                 :id 3}
                        response (router/route-request request)]

                    (is (not (contains? response :error))
                        "Response should not contain error")
                    (is (contains? response :result)
                        "Response should contain result")
                    (is (= {:message "success"} (:result response))
                        "Result should match mock handler output")
                    (is (= 3 (:id response))
                        "Response should include request ID"))))

(deftest test-route-request-handler-error
         (testing "Route to handler that returns error response"
                  (router/register-handler! "test-error" mock-handler-error)
                  (router/set-initialized! true)

                  (let [request {:jsonrpc "2.0"
                                 :method "test-error"
                                 :id 4}
                        response (router/route-request request)]

                    (is (contains? response :error)
                        "Response should contain error")
                    (is (= (:invalid-params msg/error-codes)
                           (get-in response [:error :code]))
                        "Error code should match handler error code")
                    (is (= "Test error"
                           (get-in response [:error :message]))
                        "Error message should match handler error message")
                    (is (= true
                           (get-in response [:error :data :test]))
                        "Error data should match handler error data")
                    (is (= 4 (:id response))
                        "Response should include request ID"))))

(deftest test-route-request-handler-exception
         (testing "Route to handler that throws exception returns internal error"
                  (router/register-handler! "test-exception" mock-handler-exception)
                  (router/set-initialized! true)

                  (let [request {:jsonrpc "2.0"
                                 :method "test-exception"
                                 :id 5}
                        response (router/route-request request)]

                    (is (contains? response :error)
                        "Response should contain error")
                    (is (= (:internal-error msg/error-codes)
                           (get-in response [:error :code]))
                        "Error code should be internal-error")
                    (is (= "Internal error"
                           (get-in response [:error :message]))
                        "Error message should be 'Internal error'")
                    (is (contains? (:data (:error response)) :error)
                        "Error data should include error message")
                    (is (= "Test exception"
                           (get-in response [:error :data :error]))
                        "Error data should include exception message")
                    (is (= 5 (:id response))
                        "Response should include request ID"))))

(deftest test-set-initialized-true
         (testing "Set initialized to true"
                  (is (not (router/initialized?))
                      "Server should start not initialized")
                  (router/set-initialized! true)
                  (is (router/initialized?)
                      "Server should be initialized after set-initialized! true")))

(deftest test-set-initialized-false
         (testing "Set initialized to false"
                  (router/set-initialized! true)
                  (is (router/initialized?)
                      "Server should be initialized")
                  (router/set-initialized! false)
                  (is (not (router/initialized?))
                      "Server should not be initialized after set-initialized! false")))

(deftest test-initialized-check
         (testing "Initialized check returns correct state"
                  (is (not (router/initialized?))
                      "Should return false initially")
                  (router/set-initialized! true)
                  (is (router/initialized?)
                      "Should return true after initialization")
                  (router/set-initialized! false)
                  (is (not (router/initialized?))
                      "Should return false after deinitialization")))

(deftest test-reset-state
         (testing "Reset state clears handlers and initialization"
                  (router/register-handler! "test1" mock-handler-success)
                  (router/register-handler! "test2" mock-handler-error)
                  (router/set-initialized! true)

                  (is (= 2 (count (router/get-handlers)))
                      "Should have 2 handlers before reset")
                  (is (router/initialized?)
                      "Should be initialized before reset")

                  (router/reset-state!)

                  (is (= 0 (count (router/get-handlers)))
                      "Should have 0 handlers after reset")
                  (is (not (router/initialized?))
                      "Should not be initialized after reset")))

(deftest test-get-handlers
         (testing "Get handlers returns current handler map"
                  (is (= {} (router/get-handlers))
                      "Should return empty map initially")

                  (router/register-handler! "test" mock-handler-success)
                  (let [handlers (router/get-handlers)]
                    (is (= 1 (count handlers))
                        "Should return map with one handler")
                    (is (contains? handlers "test")
                        "Should contain registered handler"))))

(deftest test-request-with-params
         (testing "Route request with parameters to handler"
                  (let [params-handler (fn [request]
                                         (msg/create-response
                                          (:id request)
                                          {:params-received (:params request)}))]
                    (router/register-handler! "test-params" params-handler)
                    (router/set-initialized! true)

                    (let [request {:jsonrpc "2.0"
                                   :method "test-params"
                                   :params {:foo "bar" :num 42}
                                   :id 6}
                          response (router/route-request request)]

                      (is (not (contains? response :error))
                          "Response should not contain error")
                      (is (= {:foo "bar" :num 42}
                             (get-in response [:result :params-received]))
                          "Handler should receive params correctly")))))

(deftest test-telemetry-emitted-success
         (testing "Telemetry is emitted on successful routing"
    ;; We can't directly test log output in unit tests,
    ;; but we can verify the function executes without errors
                  (router/register-handler! "telemetry-test" mock-handler-success)
                  (router/set-initialized! true)

                  (let [request {:jsonrpc "2.0"
                                 :method "telemetry-test"
                                 :id 7}
                        response (router/route-request request)]

                    (is (not (contains? response :error))
                        "Response should not contain error")
      ;; If telemetry logging failed, we'd get an exception
                    (is (= "2.0" (:jsonrpc response))
                        "Response should be valid JSON-RPC"))))

(deftest test-telemetry-emitted-error
         (testing "Telemetry is emitted on error routing"
                  (router/register-handler! "telemetry-error" mock-handler-error)
                  (router/set-initialized! true)

                  (let [request {:jsonrpc "2.0"
                                 :method "telemetry-error"
                                 :id 8}
                        response (router/route-request request)]

                    (is (contains? response :error)
                        "Response should contain error")
      ;; If telemetry logging failed, we'd get an exception
                    (is (= "2.0" (:jsonrpc response))
                        "Response should be valid JSON-RPC"))))

(deftest test-multiple-sequential-requests
         (testing "Handle multiple sequential requests correctly"
                  (router/register-handler! "test" mock-handler-success)
                  (router/set-initialized! true)

                  (let [request1 {:jsonrpc "2.0" :method "test" :id 1}
                        request2 {:jsonrpc "2.0" :method "test" :id 2}
                        request3 {:jsonrpc "2.0" :method "test" :id 3}
                        response1 (router/route-request request1)
                        response2 (router/route-request request2)
                        response3 (router/route-request request3)]

                    (is (= 1 (:id response1))
                        "First response should have ID 1")
                    (is (= 2 (:id response2))
                        "Second response should have ID 2")
                    (is (= 3 (:id response3))
                        "Third response should have ID 3")
                    (is (every? #(not (contains? % :error)) [response1 response2 response3])
                        "All responses should be successful"))))
