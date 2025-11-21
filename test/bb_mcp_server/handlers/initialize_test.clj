(ns bb-mcp-server.handlers.initialize-test
    "Tests for initialize handler."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [bb-mcp-server.handlers.initialize :as init]
              [bb-mcp-server.protocol.router :as router]))

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
                      (is (= "2025-03-26" (:protocolVersion result))
                          "Protocol version should be 2025-03-26")

        ;; Check serverInfo
                      (is (contains? result :serverInfo)
                          "Result should have :serverInfo")
                      (is (= "bb-mcp-server" (get-in result [:serverInfo :name]))
                          "Server name should be bb-mcp-server")
                      (is (= "0.1.0" (get-in result [:serverInfo :version]))
                          "Server version should be 0.1.0")

        ;; Check capabilities - MCP protocol version 2025-03-26
        ;; Capabilities are now objects, not booleans
        ;; Empty object = feature supported, missing = not supported
                      (is (contains? result :capabilities)
                          "Result should have :capabilities")
                      (is (map? (get-in result [:capabilities :tools]))
                          "Tools capability should be a map (object)")
                      (is (= {} (get-in result [:capabilities :tools]))
                          "Tools capability should be empty map (basic support)")
                      (is (nil? (get-in result [:capabilities :authorization]))
                          "Authorization capability not declared (OAuth not supported)"))

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

(deftest any-protocol-version-accepted-test
         (testing "Handler accepts any protocol version (nrepl-mcp-server pattern)"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "2.0"
                                          :clientInfo {:name "test-client"}}
                                 :id 3}
                        response (init/handle-initialize request)]

      ;; Should succeed with ANY protocol version
                    (is (contains? response :result)
                        "Response should succeed regardless of client protocol version")
                    (is (not (contains? response :error))
                        "Response should not have error")

      ;; Server returns its own protocol version
                    (is (= "2025-03-26" (get-in response [:result :protocolVersion]))
                        "Server always returns its own protocol version")

      ;; Server should be initialized
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest missing-params-accepted-test
         (testing "Handler accepts requests with missing params (nrepl-mcp-server pattern)"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {}
                                 :id 4}
                        response (init/handle-initialize request)]

      ;; Should succeed even with empty params
                    (is (contains? response :result)
                        "Response should succeed with empty params")
                    (is (not (contains? response :error))
                        "Response should not have error")
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest nil-params-accepted-test
         (testing "Handler accepts requests with nil params (nrepl-mcp-server pattern)"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params nil
                                 :id 5}
                        response (init/handle-initialize request)]

      ;; Should succeed even with nil params
                    (is (contains? response :result)
                        "Response should succeed with nil params")
                    (is (not (contains? response :error))
                        "Response should not have error")
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest empty-client-info-name-test
         (testing "Empty clientInfo.name is accepted"
                  (let [request {:jsonrpc "2.0"
                                 :method "initialize"
                                 :params {:protocolVersion "1.0"
                                          :clientInfo {:name ""}}
                                 :id 6}
                        response (init/handle-initialize request)]

      ;; Should succeed with empty name
                    (is (contains? response :result)
                        "Response should succeed when name is empty")
                    (is (router/initialized?)
                        "Server should be initialized"))))

(deftest response-format-matches-spec-test
         (testing "Response format exactly matches MCP protocol spec version 2025-03-26"
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

                    (is (= #{:tools}
                           (set (keys (:capabilities result))))
                        "Capabilities should have exactly tools key (protocol version 2025-03-26)")

      ;; Type checks
                    (is (string? (:protocolVersion result))
                        "protocolVersion should be string")
                    (is (string? (get-in result [:serverInfo :name]))
                        "serverInfo.name should be string")
                    (is (string? (get-in result [:serverInfo :version]))
                        "serverInfo.version should be string")
                    (is (map? (get-in result [:capabilities :tools]))
                        "capabilities.tools should be map (object in protocol 2025-03-26)")
                    (is (= {} (get-in result [:capabilities :tools]))
                        "capabilities.tools should be empty map (basic support)"))))

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

(deftest fixed-response-pattern-test
         (testing "Handler always returns same fixed response (nrepl-mcp-server pattern)"
                  (let [request1 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "client-1" :version "1.0"}}
                                  :id 1}
                        request2 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "2025-06-18"
                                           :clientInfo {:name "client-2" :version "2.0"}}
                                  :id 2}
                        request3 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {}
                                  :id 3}
                        response1 (init/handle-initialize request1)
                        response2 (init/handle-initialize request2)
                        response3 (init/handle-initialize request3)]

      ;; All responses should have same result (except id)
                    (is (= (:result response1) (:result response2) (:result response3))
                        "All requests should get same fixed response")

      ;; Protocol version is always the same
                    (is (= "2025-03-26" (get-in response1 [:result :protocolVersion]))
                        "Response 1 should have protocol version 2025-03-26")
                    (is (= "2025-03-26" (get-in response2 [:result :protocolVersion]))
                        "Response 2 should have protocol version 2025-03-26")
                    (is (= "2025-03-26" (get-in response3 [:result :protocolVersion]))
                        "Response 3 should have protocol version 2025-03-26"))))
