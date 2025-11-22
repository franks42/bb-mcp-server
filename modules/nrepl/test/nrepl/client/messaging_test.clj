(ns nrepl.client.messaging-test
    "Tests for nREPL messaging functions - pure functions without I/O"
    (:require [clojure.test :refer [deftest is testing]]
              [nrepl.client.messaging :as msg]))

;; =============================================================================
;; Generate ID Tests
;; =============================================================================

(deftest generate-id-test
         (testing "generate-id returns UUID v7 format with tag"
                  (let [id (msg/generate-id)]
                    (is (string? id) "Should return string")
                    (is (re-matches #"[0-9a-f-]+-msg" id) "Should end with -msg tag")))

         (testing "generate-id with custom tag"
                  (let [id (msg/generate-id :tag "custom")]
                    (is (string? id) "Should return string")
                    (is (re-matches #"[0-9a-f-]+-custom" id) "Should end with custom tag")))

         (testing "generate-id produces unique IDs"
                  (let [ids (repeatedly 100 msg/generate-id)]
                    (is (= 100 (count (set ids))) "All IDs should be unique"))))

;; =============================================================================
;; Byte Array Conversion Tests (testing via merge-responses behavior)
;; =============================================================================

;; Note: convert-bencode-response is private, so we test it indirectly
;; through merge-responses and send-message-async behavior

;; =============================================================================
;; Merge Responses Tests
;; =============================================================================

(deftest merge-responses-empty-test
         (testing "merge-responses with empty list"
                  (let [result (#'msg/merge-responses [])]
                    (is (= {} result) "Should return empty map"))))

(deftest merge-responses-single-test
         (testing "merge-responses with single response"
                  (let [response {:value "42" :status ["done"] :ns "user"}
                        result (#'msg/merge-responses [response])]
                    (is (= "42" (:value result)))
                    (is (= ["done"] (:status result)))
                    (is (= "user" (:ns result))))))

(deftest merge-responses-output-concatenation-test
         (testing "merge-responses concatenates :out"
                  (let [responses [{:out "Hello "} {:out "World"}]
                        result (#'msg/merge-responses responses)]
                    (is (= "Hello World" (:out result)))))

         (testing "merge-responses concatenates :err"
                  (let [responses [{:err "Error 1: "} {:err "Error 2"}]
                        result (#'msg/merge-responses responses)]
                    (is (= "Error 1: Error 2" (:err result)))))

         (testing "merge-responses handles mixed output"
                  (let [responses [{:out "Line 1\n"} {:out "Line 2\n"} {:err "Warning"}]
                        result (#'msg/merge-responses responses)]
                    (is (= "Line 1\nLine 2\n" (:out result)))
                    (is (= "Warning" (:err result))))))

(deftest merge-responses-final-value-test
         (testing "merge-responses takes last :value"
                  (let [responses [{:value "first"} {:value "second"} {:value "final"}]
                        result (#'msg/merge-responses responses)]
                    (is (= "final" (:value result)))))

         (testing "merge-responses takes last :ns"
                  (let [responses [{:ns "user"} {:ns "test.core"} {:status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "test.core" (:ns result)))))

         (testing "merge-responses takes last :session"
                  (let [responses [{:session "sess-1"} {:session "sess-2"}]
                        result (#'msg/merge-responses responses)]
                    (is (= "sess-2" (:session result))))))

(deftest merge-responses-status-test
         (testing "merge-responses takes status from last response"
                  (let [responses [{:status ["eval"]} {:status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= ["done"] (:status result))))))

(deftest merge-responses-error-test
         (testing "merge-responses preserves :ex from last response with exception"
                  (let [responses [{:out "output"} {:ex "ExceptionClass" :status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "ExceptionClass" (:ex result)))
                    (is (= "output" (:out result))))))

(deftest merge-responses-generic-fields-test
         (testing "merge-responses preserves first non-nil generic fields"
                  (let [responses [{:root-ex "RootCause"} {:status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "RootCause" (:root-ex result)))))

         (testing "merge-responses handles multiple generic fields"
                  (let [responses [{:doc "doc string" :arglists "([x])"}
                                   {:status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "doc string" (:doc result)))
                    (is (= "([x])" (:arglists result))))))

(deftest merge-responses-complete-eval-test
         (testing "merge-responses handles complete eval cycle"
                  (let [responses [{:out "println output\n"}
                                   {:value "42"}
                                   {:ns "user" :status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "println output\n" (:out result)))
                    (is (= "42" (:value result)))
                    (is (= "user" (:ns result)))
                    (is (= ["done"] (:status result))))))

(deftest merge-responses-info-operation-test
         (testing "merge-responses handles info operation response"
                  (let [responses [{:doc "Returns the sum of nums"
                                    :ns "clojure.core"
                                    :name "+"
                                    :arglists "([] [x] [x y] [x y & more])"
                                    :status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= "Returns the sum of nums" (:doc result)))
                    (is (= "clojure.core" (:ns result)))
                    (is (= "+" (:name result)))
                    (is (= "([] [x] [x y] [x y & more])" (:arglists result))))))

(deftest merge-responses-completion-test
         (testing "merge-responses handles completions operation response"
                  (let [responses [{:completions [{:candidate "map" :ns "clojure.core"}
                                                  {:candidate "mapv" :ns "clojure.core"}]
                                    :status ["done"]}]
                        result (#'msg/merge-responses responses)]
                    (is (= 2 (count (:completions result))))
                    (is (= "map" (get-in result [:completions 0 :candidate]))))))
