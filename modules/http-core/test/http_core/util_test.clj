(ns http-core.util-test
    "Tests for HTTP utilities."
    (:require [clojure.test :refer [deftest is testing]]
              [clojure.string :as str]
              [http-core.util :as util]))

;; =============================================================================
;; JSON Tests
;; =============================================================================

(deftest test-generate-json
         (testing "Generate JSON from map"
                  (let [result (util/generate-json {:foo "bar"})]
                    (is (string? result))
                    (is (str/includes? result "\"foo\""))))

         (testing "Generate JSON from vector"
                  (let [result (util/generate-json [1 2 3])]
                    (is (string? result))
                    (is (str/includes? result "[1,2,3]")))))

(deftest test-parse-json
         (testing "Parse JSON object"
                  (let [result (util/parse-json "{\"foo\":\"bar\"}")]
                    (is (map? result))
                    (is (= "bar" (:foo result)))))

         (testing "Parse JSON array"
                  (let [result (util/parse-json "[1,2,3]")]
                    (is (vector? result))
                    (is (= [1 2 3] result))))

         (testing "Parse invalid JSON returns nil"
                  (is (nil? (util/parse-json "not json")))
                  (is (nil? (util/parse-json nil)))))

;; =============================================================================
;; UUID Tests
;; =============================================================================

(deftest test-generate-uuid
         (testing "Generate UUID string"
                  (let [uuid (util/generate-uuid)]
                    (is (string? uuid))
                    (is (= 36 (count uuid)))
                    (is (re-matches #"[0-9a-f-]+" uuid))))

         (testing "UUIDs are unique"
                  (let [uuids (repeatedly 100 util/generate-uuid)]
                    (is (= 100 (count (set uuids)))))))

;; =============================================================================
;; Time Tests
;; =============================================================================

(deftest test-current-time-ms
         (testing "Returns current time in milliseconds"
                  (let [t1 (util/current-time-ms)
                        t2 (System/currentTimeMillis)]
                    (is (number? t1))
                    (is (< (abs (- t2 t1)) 100)))))

(deftest test-elapsed-ms
         (testing "Calculate elapsed time"
                  (let [start (util/current-time-ms)
                        _ (Thread/sleep 10)
                        elapsed (util/elapsed-ms start)]
                    (is (>= elapsed 10)))))

;; =============================================================================
;; Header Utilities Tests
;; =============================================================================

(deftest test-get-header
         (testing "Get header (Ring-style lowercase keys)"
                  ;; Ring normalizes headers to lowercase, so our utility expects lowercase keys
                  (let [request {:headers {"content-type" "application/json"}}]
                    (is (= "application/json" (util/get-header request "content-type")))
                    (is (= "application/json" (util/get-header request "Content-Type")))))

         (testing "Get header returns nil if not present"
                  (let [request {:headers {}}]
                    (is (nil? (util/get-header request "x-foo"))))))

(deftest test-accepts?
         (testing "Check if request accepts content type"
                  (is (util/accepts? {:headers {"accept" "text/event-stream"}} "text/event-stream"))
                  (is (util/accepts? {:headers {"accept" "text/html, application/json"}} "application/json"))
                  (is (util/accepts? {:headers {"accept" "*/*"}} "application/json"))
                  (is (not (util/accepts? {:headers {"accept" "text/html"}} "application/json")))))

;; =============================================================================
;; JSON-RPC Helpers Tests
;; =============================================================================

(deftest test-json-rpc-error
         (testing "Create JSON-RPC error"
                  (let [error (util/json-rpc-error {:id 1 :code -32600 :message "Invalid"})]
                    (is (= "2.0" (:jsonrpc error)))
                    (is (= 1 (:id error)))
                    (is (= -32600 (get-in error [:error :code])))
                    (is (= "Invalid" (get-in error [:error :message]))))))

(deftest test-json-rpc-response
         (testing "Create JSON-RPC response"
                  (let [response (util/json-rpc-response {:id 1 :result {:foo "bar"}})]
                    (is (= "2.0" (:jsonrpc response)))
                    (is (= 1 (:id response)))
                    (is (= {:foo "bar"} (:result response))))))
