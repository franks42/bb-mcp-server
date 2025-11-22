(ns bb-mcp-server.transport.stdio-test
    "Tests for stdio transport.

  Tests the complete stdio request/response cycle including:
  - Normal request processing
  - Error handling
  - I/O protocol compliance
  - Telemetry verification"
    (:require [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]
              [bb-mcp-server.telemetry :as telemetry]
              [bb-mcp-server.test-harness :as test-harness]
              [cheshire.core :as json]))

(defn with-stdio-capture
  "Execute function with captured stdin/stdout.

  Args:
  - input-lines: Vector of strings to provide as stdin (one per line)
  - f: Function to execute (takes no args)

  Returns: Map with :output (vector of output lines) and :result (function return value)"
  [input-lines f]
  ;; Initialize telemetry with :fatal level to suppress logging during tests
  (telemetry/init! {:level :fatal})
  (let [input-str (clojure.core/str (str/join "\n" input-lines) "\n")
        input-stream (java.io.ByteArrayInputStream. (.getBytes input-str))
        output-stream (java.io.ByteArrayOutputStream.)
        output-writer (java.io.OutputStreamWriter. output-stream)]

    (binding [*in* (java.io.BufferedReader. (java.io.InputStreamReader. input-stream))
              *out* output-writer]
             (let [result (f)]
               (.flush output-writer)
               {:output (vec (str/split-lines (.toString output-stream)))
                :result result}))))

(deftest test-successful-request-response
         (testing "Process valid initialize request"
                  (let [request (json/generate-string
                                 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "test-client"
                                                        :version "1.0.0"}}
                                  :id 1})
                        {:keys [output]} (with-stdio-capture [request ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have exactly one response line
                    (is (= 1 (count output))
                        "Should output one response")

      ;; Parse response
                    (let [response (json/parse-string (first output) true)]
                      (is (= "2.0" (:jsonrpc response))
                          "Should have JSON-RPC version")
                      (is (= 1 (:id response))
                          "Should match request ID")
                      (is (contains? response :result)
                          "Should have result field")
                      (is (not (contains? response :error))
                          "Should not have error field")))))

(deftest test-multiple-requests
         (testing "Process multiple requests in sequence"
                  (let [init-req (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "initialize"
                                   :params {:protocolVersion "1.0"
                                            :clientInfo {:name "test" :version "1.0"}}
                                   :id 1})
                        list-req (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "tools/list"
                                   :params {}
                                   :id 2})
                        {:keys [output]} (with-stdio-capture [init-req list-req ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have two responses
                    (is (= 2 (count output))
                        "Should output two responses")

      ;; Verify first response
                    (let [response1 (json/parse-string (first output) true)]
                      (is (= 1 (:id response1))
                          "First response should have ID 1")
                      (is (contains? response1 :result)
                          "First response should succeed"))

      ;; Verify second response
                    (let [response2 (json/parse-string (second output) true)]
                      (is (= 2 (:id response2))
                          "Second response should have ID 2")
                      (is (contains? response2 :result)
                          "Second response should succeed")))))

(deftest test-invalid-json-handling
         (testing "Handle invalid JSON input"
                  (let [invalid-json "this is not json"
                        {:keys [output]} (with-stdio-capture [invalid-json ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have one error response
                    (is (= 1 (count output))
                        "Should output one error response")

      ;; Parse error response
                    (let [response (json/parse-string (first output) true)]
                      (is (= "2.0" (:jsonrpc response))
                          "Should have JSON-RPC version")
                      (is (contains? response :error)
                          "Should have error field")
                      (is (= -32700 (get-in response [:error :code]))
                          "Should be parse error code")))))

(deftest test-invalid-request-handling
         (testing "Handle invalid JSON-RPC request"
                  (let [invalid-req (json/generate-string
                                     {:jsonrpc "2.0"
                        ;; Missing method field
                                      :id 1})
                        {:keys [output]} (with-stdio-capture [invalid-req ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have one error response
                    (is (= 1 (count output))
                        "Should output one error response")

      ;; Parse error response
                    (let [response (json/parse-string (first output) true)]
                      (is (contains? response :error)
                          "Should have error field")
                      (is (= -32600 (get-in response [:error :code]))
                          "Should be invalid request error code")))))

(deftest test-unknown-method-handling
         (testing "Handle unknown method"
                  (let [unknown-method (json/generate-string
                                        {:jsonrpc "2.0"
                                         :method "unknown/method"
                                         :params {}
                                         :id 1})
                        {:keys [output]} (with-stdio-capture [unknown-method ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have one error response
                    (is (= 1 (count output))
                        "Should output one error response")

      ;; Parse error response
                    (let [response (json/parse-string (first output) true)]
                      (is (contains? response :error)
                          "Should have error field")
                      (is (= -32601 (get-in response [:error :code]))
                          "Should be method not found error code")))))

(deftest test-empty-line-handling
         (testing "Handle empty lines gracefully"
                  (let [request (json/generate-string
                                 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "test" :version "1.0"}}
                                  :id 1})
          ;; Include empty lines before and after
                        {:keys [output]} (with-stdio-capture ["" request "" ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have exactly one response (empty lines ignored)
                    (is (= 1 (count output))
                        "Should output one response, ignoring empty lines")

      ;; Verify response is valid
                    (let [response (json/parse-string (first output) true)]
                      (is (= 1 (:id response))
                          "Should process non-empty request")
                      (is (contains? response :result)
                          "Should succeed")))))

(deftest test-tools-call-request
         (testing "Process tools/call request"
                  (let [init-req (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "initialize"
                                   :params {:protocolVersion "1.0"
                                            :clientInfo {:name "test" :version "1.0"}}
                                   :id 1})
                        call-req (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "tools/call"
                                   :params {:name "hello"
                                            :arguments {:name "World"}}
                                   :id 2})
                        {:keys [output]} (with-stdio-capture [init-req call-req ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have two responses
                    (is (= 2 (count output))
                        "Should output two responses")

      ;; Verify tools/call response
                    (let [response2 (json/parse-string (second output) true)]
                      (is (= 2 (:id response2))
                          "Second response should have ID 2")
                      (is (contains? response2 :result)
                          "Should have result")
                      (is (= "text" (get-in response2 [:result :content 0 :type]))
                          "Result should be text content")
                      (is (str/includes?
                           (get-in response2 [:result :content 0 :text])
                           "Hello, World!")
                          "Should contain greeting")))))

(deftest test-request-id-preservation
         (testing "Response IDs match request IDs"
                  (let [requests (for [i (range 5)]
                                      (json/generate-string
                                       {:jsonrpc "2.0"
                                        :method "tools/list"
                                        :params {}
                                        :id i}))
                        {:keys [output]} (with-stdio-capture (conj (vec requests) "")
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have 5 responses
                    (is (= 5 (count output))
                        "Should output 5 responses")

      ;; Verify each response has correct ID
                    (doseq [[idx line] (map-indexed vector output)]
                           (let [response (json/parse-string line true)]
                             (is (= idx (:id response))
                                 (str "Response " idx " should have ID " idx)))))))

(deftest test-response-format
         (testing "Responses are valid JSON with newlines"
                  (let [request (json/generate-string
                                 {:jsonrpc "2.0"
                                  :method "initialize"
                                  :params {:protocolVersion "1.0"
                                           :clientInfo {:name "test" :version "1.0"}}
                                  :id 1})
                        {:keys [output]} (with-stdio-capture [request ""]
                                                             (fn []
                                                               (test-harness/setup!)
                                                               (doseq [line (line-seq (java.io.BufferedReader. *in*))]
                                                                      (when-not (empty? line)
                                                                        (let [response (test-harness/process-json-rpc line)]
                                                                          (println response)
                                                                          (flush))))))]

      ;; Should have one line
                    (is (= 1 (count output))
                        "Should output one line")

      ;; Line should be valid JSON
                    (let [line (first output)]
                      (is (string? line)
                          "Output should be string")
                      (is (not (str/includes? line "\n"))
                          "Individual output line should not contain embedded newlines")

        ;; Should parse as valid JSON
                      (let [parsed (json/parse-string line true)]
                        (is (map? parsed)
                            "Should parse as JSON map")
                        (is (= "2.0" (:jsonrpc parsed))
                            "Should have JSON-RPC version"))))))
