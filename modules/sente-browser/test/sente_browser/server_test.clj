(ns sente-browser.server-test
    "Unit tests for sente-browser server state management.

   Tests the pure state functions without requiring WebSocket connections,
   plus pure state machine transition tests for per-connection lifecycle."
    (:require [clojure.test :refer [deftest is testing]]
              [statecharts.core :as fsm]
              [sente-browser.server :as server]))

;; =============================================================================
;; State Access Tests
;; =============================================================================

(deftest test-browser-count
         (testing "browser-count returns a number"
                  (is (number? (server/browser-count)))
                  (is (>= (server/browser-count) 0))))

(deftest test-get-browser-connections
         (testing "get-browser-connections returns a map"
                  (is (map? (server/get-browser-connections)))))

(deftest test-get-connection-health
         (testing "get-connection-health returns a map"
                  (is (map? (server/get-connection-health)))))

;; =============================================================================
;; Lookup Functions
;; =============================================================================

(deftest test-get-mcp-conn-id-not-found
         (testing "get-mcp-conn-id returns nil for unknown connection"
                  (is (nil? (server/get-mcp-conn-id "nonexistent-conn-id")))))

(deftest test-get-sente-conn-id-not-found
         (testing "get-sente-conn-id returns nil for unknown MCP connection"
                  (is (nil? (server/get-sente-conn-id "nonexistent-mcp-id")))))

;; =============================================================================
;; Broadcast Tests (No-op without connections)
;; =============================================================================

(deftest test-broadcast-no-connections
         (testing "broadcast-to-browsers! returns 0 when no browsers connected"
    ;; This should work even with no connections
                  (let [count-before (server/browser-count)]
                    (when (zero? count-before)
                      (is (= 0 (server/broadcast-to-browsers! [:test/event {}])))))))

;; =============================================================================
;; Query Helper Tests
;; =============================================================================

(deftest test-validated-unknown-connection
         (testing "validated? returns false for unknown connection"
                  (is (false? (server/validated? "nonexistent-conn-id")))))

(deftest test-get-connection-state-unknown
         (testing "get-connection-state returns nil for unknown connection"
                  (is (nil? (server/get-connection-state "nonexistent-conn-id")))))

;; =============================================================================
;; Pure State Machine Transition Tests
;; =============================================================================

(defn- transition
  "Apply a pure transition (actions executed for context updates)."
  [state event]
  (fsm/transition server/browser-connection-machine state event))

(defn- init-state
  "Create a fresh initial state for the browser connection machine."
  []
  (fsm/initialize server/browser-connection-machine {:exec false}))

;; -----------------------------------------------------------------------------
;; Initial State Tests
;; -----------------------------------------------------------------------------

(deftest initial-state-test
         (testing "initial state is :pending-validation"
                  (let [state (init-state)]
                    (is (= :pending-validation (:_state state)))))

         (testing "initial context has expected defaults"
                  (let [state (init-state)]
                    (is (nil? (:sente-conn-id state)))
                    (is (nil? (:session-id state)))
                    (is (nil? (:mcp-conn-id state)))
                    (is (nil? (:nickname state)))
                    (is (nil? (:probe-id state)))
                    (is (nil? (:capabilities state)))
                    (is (nil? (:connected-at state)))
                    (is (nil? (:last-heartbeat state)))
                    (is (nil? (:error state))))))

;; -----------------------------------------------------------------------------
;; Valid Transition Tests
;; -----------------------------------------------------------------------------

(deftest describe-ok-from-pending-test
         (testing ":describe-ok from :pending-validation -> :validated with context"
                  (let [state (init-state)
                        next-state (transition state {:type :describe-ok
                                                      :mcp-conn-id "browser-1-abc"
                                                      :nickname "browser-1"
                                                      :capabilities {:ops ["eval"]}})]
                    (is (= :validated (:_state next-state)))
                    (is (= "browser-1-abc" (:mcp-conn-id next-state)))
                    (is (= "browser-1" (:nickname next-state)))
                    (is (= {:ops ["eval"]} (:capabilities next-state)))
                    (is (nil? (:error next-state))))))

(deftest describe-failed-from-pending-test
         (testing ":describe-failed from :pending-validation -> :validation-failed"
                  (let [state (init-state)
                        next-state (transition state {:type :describe-failed
                                                      :error "no eval capability"})]
                    (is (= :validation-failed (:_state next-state)))
                    (is (= "no eval capability" (:error next-state))))))

(deftest ws-close-from-pending-test
         (testing ":ws-close from :pending-validation -> :disconnected"
                  (let [state (init-state)
                        next-state (transition state {:type :ws-close})]
                    (is (= :disconnected (:_state next-state))))))

(deftest ws-close-from-validated-test
         (testing ":ws-close from :validated -> :disconnected"
                  (let [state (-> (init-state)
                                  (transition {:type :describe-ok
                                               :mcp-conn-id "browser-1"
                                               :nickname "b1"
                                               :capabilities {}}))
                        next-state (transition state {:type :ws-close})]
                    (is (= :disconnected (:_state next-state)))
                    ;; Context preserved through disconnect
                    (is (= "browser-1" (:mcp-conn-id next-state))))))

(deftest ws-close-from-validation-failed-test
         (testing ":ws-close from :validation-failed -> :disconnected"
                  (let [state (-> (init-state)
                                  (transition {:type :describe-failed
                                               :error "no eval"}))
                        next-state (transition state {:type :ws-close})]
                    (is (= :disconnected (:_state next-state))))))

(deftest heartbeat-pong-self-transition-test
         (testing ":heartbeat-pong stays in :validated and updates last-heartbeat"
                  (let [state (-> (init-state)
                                  (transition {:type :describe-ok
                                               :mcp-conn-id "browser-1"
                                               :nickname "b1"
                                               :capabilities {}}))
                        ;; Verify starts with nil last-heartbeat (from initial context)
                        _ (is (nil? (:last-heartbeat state)))
                        next-state (transition state {:type :heartbeat-pong})]
                    (is (= :validated (:_state next-state)))
                    ;; assign-heartbeat sets last-heartbeat to current time
                    (is (number? (:last-heartbeat next-state)))
                    (is (> (:last-heartbeat next-state) 0)))))

(deftest heartbeat-timeout-from-validated-test
         (testing ":heartbeat-timeout from :validated -> :disconnected"
                  (let [state (-> (init-state)
                                  (transition {:type :describe-ok
                                               :mcp-conn-id "browser-1"
                                               :nickname "b1"
                                               :capabilities {}}))
                        next-state (transition state {:type :heartbeat-timeout})]
                    (is (= :disconnected (:_state next-state))))))

;; -----------------------------------------------------------------------------
;; Terminal State Tests
;; -----------------------------------------------------------------------------

(deftest disconnected-is-terminal-test
         (testing "no transitions from :disconnected"
                  (let [state (-> (init-state)
                                  (transition {:type :ws-close}))]
                    (is (= :disconnected (:_state state)))
                    ;; Any event from :disconnected should throw
                    (is (thrown? Exception
                                 (transition state {:type :describe-ok
                                                    :mcp-conn-id "x"
                                                    :nickname "x"
                                                    :capabilities {}})))
                    (is (thrown? Exception
                                 (transition state {:type :ws-close})))
                    (is (thrown? Exception
                                 (transition state {:type :heartbeat-pong}))))))

;; -----------------------------------------------------------------------------
;; Invalid Transition Tests
;; -----------------------------------------------------------------------------

(deftest invalid-transitions-test
         (testing ":heartbeat-pong from :pending-validation throws"
                  (let [state (init-state)]
                    (is (thrown? Exception
                                 (transition state {:type :heartbeat-pong})))))

         (testing ":heartbeat-timeout from :pending-validation throws"
                  (let [state (init-state)]
                    (is (thrown? Exception
                                 (transition state {:type :heartbeat-timeout})))))

         (testing ":describe-ok from :validated throws"
                  (let [state (-> (init-state)
                                  (transition {:type :describe-ok
                                               :mcp-conn-id "b1"
                                               :nickname "b1"
                                               :capabilities {}}))]
                    (is (thrown? Exception
                                 (transition state {:type :describe-ok
                                                    :mcp-conn-id "b2"
                                                    :nickname "b2"
                                                    :capabilities {}}))))))

;; -----------------------------------------------------------------------------
;; Full Lifecycle Test
;; -----------------------------------------------------------------------------

(deftest full-lifecycle-test
         (testing "complete lifecycle: pending -> validated -> disconnected"
                  (let [s0 (init-state)
                        _ (is (= :pending-validation (:_state s0)))

                        s1 (transition s0 {:type :describe-ok
                                           :mcp-conn-id "browser-1-abc"
                                           :nickname "browser-1"
                                           :capabilities {:ops ["eval" "describe"]}})
                        _ (is (= :validated (:_state s1)))
                        _ (is (= "browser-1-abc" (:mcp-conn-id s1)))

                        ;; Receive a heartbeat pong
                        s2 (transition s1 {:type :heartbeat-pong})
                        _ (is (= :validated (:_state s2)))
                        _ (is (number? (:last-heartbeat s2)))

                        ;; Disconnect
                        s3 (transition s2 {:type :ws-close})]
                    (is (= :disconnected (:_state s3)))
                    ;; Context preserved
                    (is (= "browser-1-abc" (:mcp-conn-id s3))))))

(deftest validation-failure-lifecycle-test
         (testing "lifecycle: pending -> validation-failed -> disconnected"
                  (let [s0 (init-state)
                        s1 (transition s0 {:type :describe-failed
                                           :error "no eval"})
                        _ (is (= :validation-failed (:_state s1)))
                        _ (is (= "no eval" (:error s1)))

                        s2 (transition s1 {:type :ws-close})]
                    (is (= :disconnected (:_state s2))))))

(deftest heartbeat-timeout-lifecycle-test
         (testing "lifecycle: pending -> validated -> heartbeat-timeout -> disconnected"
                  (let [s0 (init-state)
                        s1 (transition s0 {:type :describe-ok
                                           :mcp-conn-id "browser-1"
                                           :nickname "b1"
                                           :capabilities {}})
                        _ (is (= :validated (:_state s1)))

                        s2 (transition s1 {:type :heartbeat-timeout})]
                    (is (= :disconnected (:_state s2))))))

;; -----------------------------------------------------------------------------
;; Assign Action Tests
;; -----------------------------------------------------------------------------

(deftest assign-validated-test
         (testing "assign-validated stores connection info and clears error"
                  (let [ctx {:error "old error" :mcp-conn-id nil}
                        event {:mcp-conn-id "browser-1"
                               :nickname "b1"
                               :capabilities {:ops ["eval"]}}
                        result (server/assign-validated ctx event)]
                    (is (= "browser-1" (:mcp-conn-id result)))
                    (is (= "b1" (:nickname result)))
                    (is (= {:ops ["eval"]} (:capabilities result)))
                    (is (nil? (:error result))))))

(deftest assign-error-test
         (testing "assign-error stores error message"
                  (let [ctx {:error nil}
                        result (server/assign-error ctx {:error "no eval"})]
                    (is (= "no eval" (:error result))))))

(deftest assign-heartbeat-test
         (testing "assign-heartbeat sets last-heartbeat to current time"
                  (let [ctx {:last-heartbeat nil}
                        before (System/currentTimeMillis)
                        result (server/assign-heartbeat ctx {})
                        after (System/currentTimeMillis)]
                    (is (number? (:last-heartbeat result)))
                    (is (>= (:last-heartbeat result) before))
                    (is (<= (:last-heartbeat result) after)))))
