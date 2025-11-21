(ns bb-mcp-server.protocol.message-test
    (:require [clojure.test :refer [deftest is testing]]
              [bb-mcp-server.protocol.message :as msg]
              [cheshire.core :as json]))

(deftest test-parse-valid-request
         (testing "Parse valid JSON-RPC 2.0 request"
                  (let [json-str (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "tools/list"
                                   :id 1})
                        result (msg/parse-request json-str)]
                    (is (contains? result :request))
                    (is (= "2.0" (get-in result [:request :jsonrpc])))
                    (is (= "tools/list" (get-in result [:request :method])))
                    (is (= 1 (get-in result [:request :id]))))))

(deftest test-parse-request-with-params
         (testing "Parse request with params"
                  (let [json-str (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "hello" :arguments {:name "World"}}
                                   :id 2})
                        result (msg/parse-request json-str)]
                    (is (contains? result :request))
                    (is (= {:name "hello" :arguments {:name "World"}}
                           (get-in result [:request :params]))))))

(deftest test-parse-invalid-json
         (testing "Parse error on invalid JSON"
                  (let [result (msg/parse-request "{invalid json")]
                    (is (contains? result :error))
                    (is (= -32700 (get-in result [:error :code])))
                    (is (= "Parse error" (get-in result [:error :message]))))))

(deftest test-parse-missing-jsonrpc
         (testing "Invalid request when jsonrpc field is missing"
                  (let [json-str (json/generate-string
                                  {:method "tools/list"
                                   :id 1})
                        result (msg/parse-request json-str)]
                    (is (contains? result :error))
                    (is (= -32600 (get-in result [:error :code])))
                    (is (= "Invalid Request" (get-in result [:error :message])))
                    (is (= "Missing 'jsonrpc' field" (get-in result [:error :data]))))))

(deftest test-parse-wrong-jsonrpc-version
         (testing "Invalid request when jsonrpc version is not 2.0"
                  (let [json-str (json/generate-string
                                  {:jsonrpc "1.0"
                                   :method "tools/list"
                                   :id 1})
                        result (msg/parse-request json-str)]
                    (is (contains? result :error))
                    (is (= -32600 (get-in result [:error :code])))
                    (is (= "Invalid Request" (get-in result [:error :message])))
                    (is (= "JSON-RPC version must be '2.0'" (get-in result [:error :data]))))))

(deftest test-parse-missing-method
         (testing "Invalid request when method field is missing"
                  (let [json-str (json/generate-string
                                  {:jsonrpc "2.0"
                                   :id 1})
                        result (msg/parse-request json-str)]
                    (is (contains? result :error))
                    (is (= -32600 (get-in result [:error :code])))
                    (is (= "Invalid Request" (get-in result [:error :message])))
                    (is (= "Missing 'method' field" (get-in result [:error :data]))))))

(deftest test-parse-missing-id
         (testing "Missing id field is treated as notification (JSON-RPC 2.0 spec)"
                  (let [json-str (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "tools/list"})
                        result (msg/parse-request json-str)]
                    ;; JSON-RPC 2.0 spec: missing id = notification
                    (is (contains? result :notification)
                        "Result should have :notification field")
                    (is (not (contains? result :error))
                        "Result should not have :error field")
                    (is (not (contains? result :request))
                        "Result should not have :request field")
                    ;; Check notification structure
                    (is (= "2.0" (get-in result [:notification :jsonrpc]))
                        "Notification should have jsonrpc field")
                    (is (= "tools/list" (get-in result [:notification :method]))
                        "Notification should have method field"))))

(deftest test-create-response
         (testing "Create valid success response"
                  (let [result (msg/create-response 42 {:tools []})]
                    (is (= "2.0" (:jsonrpc result)))
                    (is (= {:tools []} (:result result)))
                    (is (= 42 (:id result))))))

(deftest test-create-error-response-without-data
         (testing "Create error response without data"
                  (let [result (msg/create-error-response 10 -32601 "Method not found")]
                    (is (= "2.0" (:jsonrpc result)))
                    (is (= -32601 (get-in result [:error :code])))
                    (is (= "Method not found" (get-in result [:error :message])))
                    (is (= 10 (:id result)))
                    (is (not (contains? (:error result) :data))))))

(deftest test-create-error-response-with-data
         (testing "Create error response with data"
                  (let [result (msg/create-error-response
                                20
                                -32602
                                "Invalid params"
                                {:expected "string" :got "number"})]
                    (is (= "2.0" (:jsonrpc result)))
                    (is (= -32602 (get-in result [:error :code])))
                    (is (= "Invalid params" (get-in result [:error :message])))
                    (is (= {:expected "string" :got "number"} (get-in result [:error :data])))
                    (is (= 20 (:id result))))))

(deftest test-create-error-response-nil-id
         (testing "Create error response with nil id (for parse errors)"
                  (let [result (msg/create-error-response nil -32700 "Parse error")]
                    (is (= "2.0" (:jsonrpc result)))
                    (is (= -32700 (get-in result [:error :code])))
                    (is (nil? (:id result))))))

(deftest test-error-codes-map
         (testing "Error codes map contains expected values"
                  (is (= -32700 (:parse-error msg/error-codes)))
                  (is (= -32600 (:invalid-request msg/error-codes)))
                  (is (= -32601 (:method-not-found msg/error-codes)))
                  (is (= -32602 (:invalid-params msg/error-codes)))
                  (is (= -32603 (:internal-error msg/error-codes)))
                  (is (= -32000 (:tool-not-found msg/error-codes)))
                  (is (= -32001 (:tool-execution-failed msg/error-codes)))
                  (is (= -32002 (:invalid-tool-params msg/error-codes)))
                  (is (= -32003 (:server-not-initialized msg/error-codes)))))
