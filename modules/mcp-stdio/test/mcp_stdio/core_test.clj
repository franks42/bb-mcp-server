(ns mcp-stdio.core-test
    "Tests for mcp-stdio transport module.

  Tests the transport layer behavior independent of the processor:
  - Handler invocation
  - I/O protocol compliance
  - Error handling for handler exceptions
  - Response formatting"
    (:require [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]
              [mcp-stdio.core :as stdio]
              [bb-mcp-server.telemetry :as telemetry]
              [bb-mcp-server.test-harness :as harness]
              [bb-mcp-server.protocol.processor :as processor]
              [cheshire.core :as json]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn with-stdio-capture
  "Execute function with captured stdin/stdout.

  Args:
  - input-lines: Vector of strings to provide as stdin (one per line)
  - f: Function to execute (takes no args)

  Returns: Map with :output (vector of output lines) and :result (function return value)"
  [input-lines f]
  ;; Initialize telemetry with :fatal level to suppress logging during tests
  (telemetry/init! {:level :fatal})
  (let [input-str (str (str/join "\n" input-lines) "\n")
        input-stream (java.io.ByteArrayInputStream. (.getBytes input-str))
        output-stream (java.io.ByteArrayOutputStream.)
        output-writer (java.io.OutputStreamWriter. output-stream)]

    (binding [*in* (java.io.BufferedReader. (java.io.InputStreamReader. input-stream))
              *out* output-writer]
             (let [result (f)]
               (.flush output-writer)
               {:output (vec (remove empty? (str/split-lines (.toString output-stream))))
                :result result}))))

;; =============================================================================
;; Module Tests
;; =============================================================================

(deftest module-metadata-test
         (testing "Module has correct metadata"
                  (is (= "mcp-stdio" (:name stdio/module)))
                  (is (= "0.1.0" (:version stdio/module)))
                  (is (string? (:description stdio/module)))))

;; =============================================================================
;; Transport Tests (using mock handlers)
;; =============================================================================

(deftest test-handler-called-for-each-line
         (testing "Handler is called for each input line"
                  (let [calls (atom [])
                        mock-handler (fn [line]
                                       (swap! calls conj line)
                                       (json/generate-string {:jsonrpc "2.0" :result "ok" :id 1}))
                        {:keys [output]} (with-stdio-capture ["line1" "line2" "line3"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 3 (count @calls)))
                    (is (= ["line1" "line2" "line3"] @calls))
                    (is (= 3 (count output))))))

(deftest test-handler-response-written-to-stdout
         (testing "Handler response is written to stdout"
                  (let [mock-handler (fn [_line]
                                       (json/generate-string {:jsonrpc "2.0" :result {:echo "test"} :id 42}))
                        {:keys [output]} (with-stdio-capture ["input"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 1 (count output)))
                    (let [response (json/parse-string (first output) true)]
                      (is (= "2.0" (:jsonrpc response)))
                      (is (= 42 (:id response)))
                      (is (= {:echo "test"} (:result response)))))))

(deftest test-nil-response-not-written
         (testing "Nil response (notification) is not written to stdout"
                  (let [mock-handler (fn [_line] nil)  ; Return nil for notifications
                        {:keys [output]} (with-stdio-capture ["notification1" "notification2"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 0 (count output))
                        "No output for nil responses"))))

(deftest test-mixed-responses-and-notifications
         (testing "Mix of responses and notifications"
                  (let [call-count (atom 0)
                        mock-handler (fn [_line]
                                       (let [n (swap! call-count inc)]
                                         (if (odd? n)
                                           (json/generate-string {:jsonrpc "2.0" :result n :id n})
                                           nil)))  ; Even calls return nil
                        {:keys [output]} (with-stdio-capture ["a" "b" "c" "d"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 2 (count output))
                        "Only odd calls produce output")
                    (let [r1 (json/parse-string (first output) true)
                          r2 (json/parse-string (second output) true)]
                      (is (= 1 (:id r1)))
                      (is (= 3 (:id r2)))))))

(deftest test-handler-exception-produces-error-response
         (testing "Handler exception produces JSON-RPC error response"
                  (let [mock-handler (fn [_line]
                                       (throw (ex-info "Handler failed" {:reason "test"})))
                        {:keys [output]} (with-stdio-capture ["input"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 1 (count output)))
                    (let [response (json/parse-string (first output) true)]
                      (is (= "2.0" (:jsonrpc response)))
                      (is (contains? response :error))
                      (is (= -32603 (get-in response [:error :code])))
                      (is (= "Internal error" (get-in response [:error :message])))
                      (is (str/includes? (get-in response [:error :data]) "Handler failed"))))))

(deftest test-multiple-lines-with-one-exception
         (testing "Exception on one line doesn't stop processing"
                  (let [call-count (atom 0)
                        mock-handler (fn [_line]
                                       (let [n (swap! call-count inc)]
                                         (if (= n 2)
                                           (throw (ex-info "Error on line 2" {}))
                                           (json/generate-string {:jsonrpc "2.0" :result n :id n}))))
                        {:keys [output]} (with-stdio-capture ["a" "b" "c"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 3 (count output))
                        "All three lines produce output")
                    (let [r1 (json/parse-string (first output) true)
                          r2 (json/parse-string (second output) true)
                          r3 (json/parse-string (nth output 2) true)]
                      ;; First response is success
                      (is (= 1 (:result r1)))
                      ;; Second response is error
                      (is (contains? r2 :error))
                      (is (= -32603 (get-in r2 [:error :code])))
                      ;; Third response is success (processing continues)
                      (is (= 3 (:result r3)))))))

(deftest test-empty-lines-skipped
         (testing "Empty lines are passed to handler (handler decides what to do)"
                  (let [calls (atom [])
                        mock-handler (fn [line]
                                       (swap! calls conj line)
                                       ;; Only respond to non-empty lines
                                       (when-not (empty? line)
                                         (json/generate-string {:jsonrpc "2.0" :result "ok" :id 1})))
                        {:keys [output]} (with-stdio-capture ["" "valid" ""]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    ;; All lines passed to handler (including empty ones)
                    (is (= 3 (count @calls)))
                    ;; Only non-empty line produces output
                    (is (= 1 (count output))))))

(deftest test-response-is-single-line
         (testing "Each response is a single line (no embedded newlines)"
                  (let [mock-handler (fn [_line]
                                       ;; Response with data that might be multi-line if pretty-printed
                                       (json/generate-string
                                        {:jsonrpc "2.0"
                                         :result {:nested {:data "value"
                                                           :list [1 2 3]}}
                                         :id 1}))
                        {:keys [output]} (with-stdio-capture ["input"]
                                                             #(stdio/run-stdio-loop! mock-handler))]
                    (is (= 1 (count output)))
                    (is (not (str/includes? (first output) "\n"))
                        "Response should be single line"))))

;; =============================================================================
;; Integration Tests (with real processor - optional)
;; =============================================================================

(deftest test-with-real-processor
         (testing "Integration with real processor"
                  (harness/setup!)

                  (let [ctx (processor/make-stdio-ctx)
                        handler (fn [line] (processor/process-request-str ctx line))
                        init-req (json/generate-string
                                  {:jsonrpc "2.0"
                                   :method "initialize"
                                   :params {:protocolVersion "1.0"
                                            :clientInfo {:name "test" :version "1.0"}}
                                   :id 1})
                        {:keys [output]} (with-stdio-capture [init-req]
                                                             #(stdio/run-stdio-loop! handler))]
                    (is (= 1 (count output)))
                    (let [response (json/parse-string (first output) true)]
                      (is (= "2.0" (:jsonrpc response)))
                      (is (= 1 (:id response)))
                      (is (contains? response :result))))))
