(ns bb-mcp-server.handlers.initialize-test
    "Tests for initialize handler."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [bb-mcp-server.handlers.initialize :as init]
              [bb-mcp-server.protocol.router :as router]
              [bb-mcp-server.protocol.message :as msg]))

;; Reset server state before each test
(use-fixtures :each
              (fn [f]
                (router/reset-state!)
                (f)))

(deftest valid-initialization-test
         (testing "Valid initialization request succeeds"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name "claude-code"
                                                       :version "1.0.0"}}
                                 :id 1}
                        response (init/handle-initialize request)]

      ;; Should be success response
                    (is (= "2.0" (:jsonrpc response))
                        "Response should be JSON-RPC 2.0")
                    (is (= 1 (:id response))
                        "Response ID should match request ID")
                    (is (contains? response :result)
                        "Response should have :result field")
                    (is (not (contains? response :error))
                        "Response should not have :error field")

      ;; Check result structure
                    (let [result (:result response)]
                      (is (= "1.0" (:protocolVersion result))
                          "Protocol version should be 1.0")

        ;; Check serverInfo
                      (is (contains? result :serverInfo)
                          "Result should have :serverInfo")
                      (is (= "bb-mcp-server" (get-in result [:serverInfo :name]))
                          "Server name should be bb-mcp-server")
                      (is (= "0.1.0" (get-in result [:serverInfo :version]))
                          "Server version should be 0.1.0")

        ;; Check capabilities
                      (is (contains? result :capabilities)
                          "Result should have :capabilities")
                      (is (true? (get-in result [:capabilities :tools]))
                          "Tools capability should be true")
                      (is (false? (get-in result [:capabilities :dynamicToolRegistration]))
                          "Dynamic tool registration should be false"))

      ;; Server should be marked as initialized
                    (is (router/initialized?)
                        "Server should be marked as initialized after success"))))

(deftest valid-initialization-without-client-version-test
         (testing "Valid initialization without optional client version succeeds"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name "test-client"}}
                                 :id 2}
                        response (init/handle-initialize request)]

      ;; Should succeed even without version
                    (is (contains? response :result)
                        "Response should succeed with missing optional client version")
                    (is (not (contains? response :error))
                        "Response should not have error")

      ;; Server should still be initialized
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest missing-protocol-version-test
         (testing "Missing protocolVersion returns error -32602"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:clientInfo {:name "test-client"
                                                       :version "1.0.0"}}
                                 :id 3}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (= "2.0" (:jsonrpc response))
                        "Response should be JSON-RPC 2.0")
                    (is (= 3 (:id response))
                        "Response ID should match request ID")
                    (is (contains? response :error)
                        "Response should have :error field")
                    (is (not (contains? response :result))
                        "Response should not have :result field")

      ;; Check error details
                    (let [error (:error response)]
                      (is (= (:invalid-params msg/error-codes) (:code error))
                          "Error code should be -32602 (invalid-params)")
                      (is (= "Invalid params" (:message error))
                          "Error message should be 'Invalid params'")
                      (is (string? (:data error))
                          "Error data should be a string")
                      (is (re-find #"protocolVersion" (:data error))
                          "Error data should mention protocolVersion"))

      ;; Server should NOT be initialized
                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest unsupported-protocol-version-test
         (testing "Unsupported protocolVersion returns error -32602"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "2.0"
                                          :clientInfo {:name "test-client"}}
                                 :id 4}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (contains? response :error)
                        "Response should have :error field")
                    (is (not (contains? response :result))
                        "Response should not have :result field")

      ;; Check error details
                    (let [error (:error response)]
                      (is (= (:invalid-params msg/error-codes) (:code error))
                          "Error code should be -32602 (invalid-params)")
                      (is (= "Invalid params" (:message error))
                          "Error message should be 'Invalid params'")
                      (is (string? (:data error))
                          "Error data should be a string")
                      (is (re-find #"Unsupported protocolVersion" (:data error))
                          "Error data should mention unsupported version")
                      (is (re-find #"2\.0" (:data error))
                          "Error data should include requested version")
                      (is (re-find #"1\.0" (:data error))
                          "Error data should include supported version"))

      ;; Server should NOT be initialized
                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest missing-client-info-test
         (testing "Missing clientInfo returns error -32602"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"}
                                 :id 5}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (contains? response :error)
                        "Response should have :error field")
                    (is (not (contains? response :result))
                        "Response should not have :result field")

      ;; Check error details
                    (let [error (:error response)]
                      (is (= (:invalid-params msg/error-codes) (:code error))
                          "Error code should be -32602 (invalid-params)")
                      (is (= "Invalid params" (:message error))
                          "Error message should be 'Invalid params'")
                      (is (string? (:data error))
                          "Error data should be a string")
                      (is (re-find #"clientInfo" (:data error))
                          "Error data should mention clientInfo"))

      ;; Server should NOT be initialized
                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest missing-client-info-name-test
         (testing "Missing clientInfo.name returns error -32602"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:version "1.0.0"}}
                                 :id 6}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (contains? response :error)
                        "Response should have :error field")
                    (is (not (contains? response :result))
                        "Response should not have :result field")

      ;; Check error details
                    (let [error (:error response)]
                      (is (= (:invalid-params msg/error-codes) (:code error))
                          "Error code should be -32602 (invalid-params)")
                      (is (= "Invalid params" (:message error))
                          "Error message should be 'Invalid params'")
                      (is (string? (:data error))
                          "Error data should be a string")
                      (is (re-find #"clientInfo\.name" (:data error))
                          "Error data should mention clientInfo.name"))

      ;; Server should NOT be initialized
                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest empty-client-info-name-test
         (testing "Empty clientInfo.name is technically valid (contains? returns true)"
    ;; Note: contains? only checks key existence, not value
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name ""}}
                                 :id 7}
                        response (init/handle-initialize request)]

      ;; Should succeed because key exists
                    (is (contains? response :result)
                        "Response should succeed when name key exists, even if empty")

      ;; Server should be initialized
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest response-format-matches-spec-test
         (testing "Response format exactly matches MCP protocol spec"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name "spec-validator"
                                                       :version "1.0.0"}}
                                 :id 100}
                        response (init/handle-initialize request)
                        result (:result response)]

      ;; Exact structure check
                    (is (= #{:jsonrpc :result :id} (set (keys response)))
                        "Response should have exactly jsonrpc, result, and id keys")

                    (is (= #{:protocolVersion :serverInfo :capabilities}
                           (set (keys result)))
                        "Result should have exactly protocolVersion, serverInfo, and capabilities keys")

                    (is (= #{:name :version}
                           (set (keys (:serverInfo result))))
                        "ServerInfo should have exactly name and version keys")

                    (is (= #{:tools :dynamicToolRegistration}
                           (set (keys (:capabilities result))))
                        "Capabilities should have exactly tools and dynamicToolRegistration keys")

      ;; Type checks
                    (is (string? (:protocolVersion result))
                        "protocolVersion should be string")
                    (is (string? (get-in result [:serverInfo :name]))
                        "serverInfo.name should be string")
                    (is (string? (get-in result [:serverInfo :version]))
                        "serverInfo.version should be string")
                    (is (boolean? (get-in result [:capabilities :tools]))
                        "capabilities.tools should be boolean")
                    (is (boolean? (get-in result [:capabilities :dynamicToolRegistration]))
                        "capabilities.dynamicToolRegistration should be boolean"))))

(deftest multiple-initialization-attempts-test
         (testing "Multiple initialization attempts all succeed"
    ;; First initialization
                  (let [request1 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "client-1"}}
                                  :id 1}
                        response1 (init/handle-initialize request1)]

                    (is (contains? response1 :result)
                        "First initialization should succeed")
                    (is (router/initialized?)
                        "Server should be initialized"))

    ;; Second initialization (re-initialization)
                  (let [request2 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "client-2"}}
                                  :id 2}
                        response2 (init/handle-initialize request2)]

                    (is (contains? response2 :result)
                        "Second initialization should also succeed")
                    (is (router/initialized?)
                        "Server should still be initialized"))))

(deftest initialization-state-persistence-test
         (testing "Initialization state persists across handler calls"
    ;; Initialize once
                  (init/handle-initialize
                   {:jsonrpc "2.0"
                    :method "initialize"
                    :params {:protocolVersion "1.0"
                             :clientInfo {:name "persistent-client"}}
                    :id 1})

    ;; Check state persists
                  (is (router/initialized?)
                      "Initialization state should persist after handler completes")))

(deftest nil-params-test
         (testing "Nil params returns appropriate error"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params nil
                                 :id 8}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (contains? response :error)
                        "Response should have :error field when params is nil")
                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest empty-params-test
         (testing "Empty params returns appropriate error"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {}
                                 :id 9}
                        response (init/handle-initialize request)]

      ;; Should be error response
                    (is (contains? response :error)
                        "Response should have :error field when params is empty")

                    (let [error (:error response)]
                      (is (= (:invalid-params msg/error-codes) (:code error))
                          "Error code should be -32602 (invalid-params)"))

                    (is (not (router/initialized?))
                        "Server should not be initialized on error"))))

(deftest error-response-structure-test
         (testing "Error responses have proper JSON-RPC 2.0 structure"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"}
                                 :id 10}
                        response (init/handle-initialize request)]

      ;; Check error response structure
                    (is (= #{:jsonrpc :error :id} (set (keys response)))
                        "Error response should have exactly jsonrpc, error, and id keys")

                    (is (= "2.0" (:jsonrpc response))
                        "Error response should be JSON-RPC 2.0")

                    (let [error (:error response)]
                      (is (contains? error :code)
                          "Error should have :code")
                      (is (contains? error :message)
                          "Error should have :message")
                      (is (number? (:code error))
                          "Error code should be a number")
                      (is (string? (:message error))
                          "Error message should be a string")))))
