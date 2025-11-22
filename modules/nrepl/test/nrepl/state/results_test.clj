(ns nrepl.state.results-test
    "Tests for nREPL result queue state management"
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [nrepl.state.results :as results]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset result state before each test"
  [f]
  (reset! results/connection-result-queues {})
  (f)
  (reset! results/connection-result-queues {}))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Queue Management Tests
;; =============================================================================

(deftest ensure-result-queue-test
         (testing "ensure-connection-result-queue! creates queue structure"
                  (results/ensure-connection-result-queue! "conn-1")
                  (let [queue-state (get @results/connection-result-queues "conn-1")]
                    (is (some? queue-state) "Queue should exist")
                    (is (empty? (:result-promises queue-state)))
                    (is (empty? (:completed-results queue-state)))
                    (is (empty? (:error-results queue-state)))))

         (testing "ensure-connection-result-queue! is idempotent"
                  (results/ensure-connection-result-queue! "conn-1")
                  (swap! results/connection-result-queues
                         assoc-in ["conn-1" :completed-results "msg-1"] {:result "test"})
                  (results/ensure-connection-result-queue! "conn-1")
                  (is (= {:result "test"}
                         (get-in @results/connection-result-queues ["conn-1" :completed-results "msg-1"]))
                      "Should not reset existing queue")))

;; =============================================================================
;; Promise Creation Tests
;; =============================================================================

(deftest create-result-promise-test
         (testing "create-result-promise! returns promise"
                  (let [p (results/create-result-promise! "conn-1" "msg-1")]
                    (is (instance? clojure.lang.IPending p) "Should return a promise")))

         (testing "create-result-promise! stores promise in queue"
                  (results/create-result-promise! "conn-1" "msg-1")
                  (let [stored-promise (get-in @results/connection-result-queues
                                               ["conn-1" :result-promises "msg-1"])]
                    (is (some? stored-promise) "Promise should be stored"))))

;; =============================================================================
;; Result Delivery Tests
;; =============================================================================

(deftest deliver-result-test
         (testing "deliver-result! delivers to waiting promise"
                  (let [p (results/create-result-promise! "conn-1" "msg-1")]
                    (results/deliver-result! "msg-1" {:value "42"})
                    (let [result (deref p 100 :timeout)]
                      (is (not= :timeout result) "Promise should be delivered")
                      (is (= :success (:status result)))
                      (is (= {:value "42"} (:result result))))))

         (testing "deliver-result! moves to completed-results"
                  (let [_p (results/create-result-promise! "conn-1" "msg-1")]
                    (results/deliver-result! "msg-1" {:value "42"})
                    (let [completed (get-in @results/connection-result-queues
                                            ["conn-1" :completed-results "msg-1"])]
                      (is (some? completed) "Should be in completed-results")
                      (is (= {:value "42"} (:result completed))))))

         (testing "deliver-result! removes from promises"
                  (let [_p (results/create-result-promise! "conn-1" "msg-1")]
                    (results/deliver-result! "msg-1" {:value "42"})
                    (is (nil? (get-in @results/connection-result-queues
                                      ["conn-1" :result-promises "msg-1"]))
                        "Promise should be removed"))))

;; =============================================================================
;; Error Delivery Tests
;; =============================================================================

(deftest deliver-error-test
         (testing "deliver-error! delivers error to promise"
                  (let [p (results/create-result-promise! "conn-1" "msg-1")]
                    (results/deliver-error! "msg-1" "Connection failed")
                    (let [result (deref p 100 :timeout)]
                      (is (= :error (:status result)))
                      (is (= "Connection failed" (:error result))))))

         (testing "deliver-error! moves to error-results"
                  (let [_p (results/create-result-promise! "conn-1" "msg-1")]
                    (results/deliver-error! "msg-1" "Connection failed")
                    (let [error-record (get-in @results/connection-result-queues
                                               ["conn-1" :error-results "msg-1"])]
                      (is (some? error-record))
                      (is (= "Connection failed" (:error error-record)))))))

;; =============================================================================
;; Get Result Tests
;; =============================================================================

(deftest get-result-test
         (testing "get-result returns completed result immediately"
                  (results/ensure-connection-result-queue! "conn-1")
                  (swap! results/connection-result-queues
                         assoc-in ["conn-1" :completed-results "completed-msg"]
                         {:result {:value "42"}})
                  (let [result (results/get-result "completed-msg" 100)]
                    (is (= :success (:status result)))
                    (is (= {:value "42"} (:result result)))))

         (testing "get-result returns error result immediately"
                  (results/ensure-connection-result-queue! "conn-2")
                  (swap! results/connection-result-queues
                         assoc-in ["conn-2" :error-results "error-msg"]
                         {:error "Something failed"})
                  (let [result (results/get-result "error-msg" 100)]
                    (is (= :error (:status result)))
                    (is (= "Something failed" (:error result)))))

         (testing "get-result waits for promise delivery"
                  (let [p (results/create-result-promise! "conn-3" "async-msg")]
      ;; Deliver in background
                    (future
                     (Thread/sleep 50)
                     (deliver p {:status :success :result {:value "async"}}))
                    (let [result (results/get-result "async-msg" 500)]
                      (is (= :success (:status result))))))

         (testing "get-result returns timeout when promise not delivered"
                  (results/create-result-promise! "conn-4" "timeout-msg")
                  (let [result (results/get-result "timeout-msg" 50)]
                    (is (= :timeout (:status result)))))

         (testing "get-result returns error for unknown message"
                  (let [result (results/get-result "unknown-msg" 50)]
                    (is (= :error (:status result)))
                    (is (= "Unknown message-id" (:error result))))))

;; =============================================================================
;; Summary Tests
;; =============================================================================

(deftest result-summary-test
         (testing "get-result-summary returns correct counts"
                  (results/ensure-connection-result-queue! "conn-1")
                  (results/create-result-promise! "conn-1" "msg-1")
                  (results/create-result-promise! "conn-1" "msg-2")
                  (swap! results/connection-result-queues
                         assoc-in ["conn-1" :completed-results "msg-3"] {:result "done"})

                  (let [summary (results/get-result-summary)]
                    (is (= 1 (:connection-count summary)))
                    (is (= 2 (:total-promises summary)))
                    (is (= 1 (:total-completed summary))))))

;; =============================================================================
;; Cleanup Tests
;; =============================================================================

(deftest clear-results-test
         (testing "clear-all-results! resets everything"
                  (results/ensure-connection-result-queue! "conn-1")
                  (results/ensure-connection-result-queue! "conn-2")
                  (results/clear-all-results!)
                  (is (empty? @results/connection-result-queues)))

         (testing "clear-results-for-connection! removes specific connection"
                  (results/ensure-connection-result-queue! "conn-1")
                  (results/ensure-connection-result-queue! "conn-2")
                  (results/clear-results-for-connection! "conn-1")
                  (is (not (contains? @results/connection-result-queues "conn-1")))
                  (is (contains? @results/connection-result-queues "conn-2"))))
