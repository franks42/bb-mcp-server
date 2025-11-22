(ns nrepl.state.messages-test
    "Tests for nREPL message queue state management"
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [nrepl.state.messages :as msg]
              [nrepl.state.connection :as conn]
              [nrepl.state.results :as results]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset all state before each test"
  [f]
  ;; Reset connection state
  (reset! conn/connection-state {:connections {}
                                 :active-connection-id nil
                                 :connection-counter 0})
  ;; Reset message queues
  (reset! msg/connection-message-queues {})
  ;; Reset result queues
  (reset! results/connection-result-queues {})
  (f)
  ;; Cleanup after test
  (reset! msg/connection-message-queues {})
  (reset! results/connection-result-queues {})
  (reset! conn/connection-state {:connections {}
                                 :active-connection-id nil
                                 :connection-counter 0}))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Queue Management Tests
;; =============================================================================

(deftest ensure-queue-test
         (testing "ensure-connection-queue! creates queue structure"
                  (msg/ensure-connection-queue! "conn-1")
                  (let [queue-state (get @msg/connection-message-queues "conn-1")]
                    (is (some? queue-state) "Queue should exist")
                    (is (empty? (:send-queue queue-state)) "Send queue should be empty")
                    (is (empty? (:pending-messages queue-state)) "Pending messages should be empty")
                    (is (= 0 (:message-counter queue-state)) "Counter should be 0")))

         (testing "ensure-connection-queue! is idempotent"
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues assoc-in ["conn-1" :message-counter] 5)
                  (msg/ensure-connection-queue! "conn-1")
                  (is (= 5 (get-in @msg/connection-message-queues ["conn-1" :message-counter]))
                      "Should not reset existing queue")))

(deftest cleanup-queue-test
         (testing "cleanup-connection-queue! removes queue"
                  (msg/ensure-connection-queue! "conn-1")
                  (is (contains? @msg/connection-message-queues "conn-1"))
                  (msg/cleanup-connection-queue! "conn-1")
                  (is (not (contains? @msg/connection-message-queues "conn-1"))
                      "Queue should be removed")))

;; =============================================================================
;; Message Status Tests
;; =============================================================================

(deftest message-status-test
         (testing "update-message-status! updates status"
    ;; Setup: create a mock pending message entry
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-1"]
                         {:message-id "msg-1" :status :pending})

                  (msg/update-message-status! "msg-1" :sending :sent-at 12345)

                  (let [msg-entry (get-in @msg/connection-message-queues ["conn-1" :pending-messages "msg-1"])]
                    (is (= :sending (:status msg-entry)) "Status should be updated")
                    (is (= 12345 (:sent-at msg-entry)) "sent-at should be set")))

         (testing "update-message-status! with error"
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-2"]
                         {:message-id "msg-2" :status :sending})

                  (msg/update-message-status! "msg-2" :error :error "Connection failed")

                  (let [msg-entry (get-in @msg/connection-message-queues ["conn-1" :pending-messages "msg-2"])]
                    (is (= :error (:status msg-entry)))
                    (is (= "Connection failed" (:error msg-entry))))))

;; =============================================================================
;; Pending Message Tests
;; =============================================================================

(deftest pending-message-test
         (testing "get-pending-message returns message"
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-1"]
                         {:message-id "msg-1" :data "test"})

                  (let [msg (msg/get-pending-message "msg-1")]
                    (is (= "msg-1" (:message-id msg)))
                    (is (= "test" (:data msg)))))

         (testing "get-pending-message returns nil for unknown"
                  (is (nil? (msg/get-pending-message "unknown-msg"))))

         (testing "remove-pending-message! removes message"
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-1"]
                         {:message-id "msg-1"})

                  (msg/remove-pending-message! "msg-1")
                  (is (nil? (msg/get-pending-message "msg-1"))
                      "Message should be removed")))

;; =============================================================================
;; Message Summary Tests
;; =============================================================================

(deftest message-summary-test
         (testing "get-message-summary returns correct counts"
                  (msg/ensure-connection-queue! "conn-1")
                  (msg/ensure-connection-queue! "conn-2")

    ;; Add some pending messages
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-1"] {:message-id "msg-1"})
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-2" :pending-messages "msg-2"] {:message-id "msg-2"})
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-2" :pending-messages "msg-3"] {:message-id "msg-3"})

                  (let [summary (msg/get-message-summary)]
                    (is (= 2 (:connection-count summary)))
                    (is (= 3 (:total-pending summary))))))

;; =============================================================================
;; Clear Messages Tests
;; =============================================================================

(deftest clear-messages-test
         (testing "clear-all-messages! resets everything"
                  (msg/ensure-connection-queue! "conn-1")
                  (msg/ensure-connection-queue! "conn-2")
                  (msg/clear-all-messages!)
                  (is (empty? @msg/connection-message-queues)))

         (testing "clear-connection-messages! resets specific connection"
                  (msg/ensure-connection-queue! "conn-1")
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :pending-messages "msg-1"] {:message-id "msg-1"})
                  (swap! msg/connection-message-queues
                         assoc-in ["conn-1" :message-counter] 5)

                  (msg/clear-connection-messages! "conn-1")

                  (let [queue-state (get @msg/connection-message-queues "conn-1")]
                    (is (empty? (:pending-messages queue-state)))
                    (is (= 0 (:message-counter queue-state))))))
