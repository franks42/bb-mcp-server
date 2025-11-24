(ns http-core.sse-test
    "Tests for SSE utilities."
    (:require [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]
              [http-core.sse :as sse]))

;; =============================================================================
;; SSE Event Formatting Tests
;; =============================================================================

(deftest test-format-sse-event-data-only
         (testing "Format SSE event with data only"
                  (is (= "data: hello\n\n"
                         (sse/format-sse-event {:data "hello"})))))

(deftest test-format-sse-event-with-id
         (testing "Format SSE event with ID"
                  (is (= "id: 123\ndata: hello\n\n"
                         (sse/format-sse-event {:id "123" :data "hello"})))))

(deftest test-format-sse-event-with-event-type
         (testing "Format SSE event with event type"
                  (is (= "event: message\ndata: hello\n\n"
                         (sse/format-sse-event {:event "message" :data "hello"})))))

(deftest test-format-sse-event-full
         (testing "Format SSE event with all fields"
                  (is (= "id: 1\nevent: notification\ndata: test\n\n"
                         (sse/format-sse-event {:id "1" :event "notification" :data "test"})))))

(deftest test-format-sse-event-json-data
         (testing "Format SSE event with map data (JSON encoded)"
                  (let [result (sse/format-sse-event {:data {:foo "bar"}})]
                    (is (str/starts-with? result "data: "))
                    (is (str/includes? result "\"foo\""))
                    (is (str/includes? result "\"bar\""))
                    (is (str/ends-with? result "\n\n")))))

(deftest test-format-json-rpc-event
         (testing "Format JSON-RPC message as SSE event"
                  (let [msg {:jsonrpc "2.0" :method "test" :id 1}
                        result (sse/format-json-rpc-event msg)]
                    (is (str/includes? result "event: message"))
                    (is (str/includes? result "\"jsonrpc\""))
                    (is (str/includes? result "\"2.0\"")))))

(deftest test-format-json-rpc-event-with-id
         (testing "Format JSON-RPC message with event ID"
                  (let [msg {:jsonrpc "2.0" :result {:status "ok"} :id 1}
                        result (sse/format-json-rpc-event msg "evt-42")]
                    (is (str/includes? result "id: evt-42"))
                    (is (str/includes? result "event: message")))))

;; =============================================================================
;; SSE Headers Tests
;; =============================================================================

(deftest test-sse-headers
         (testing "SSE headers contain required fields"
                  (is (= "text/event-stream" (get sse/sse-headers "Content-Type")))
                  (is (contains? sse/sse-headers "Cache-Control"))
                  (is (contains? sse/sse-headers "Connection"))))
