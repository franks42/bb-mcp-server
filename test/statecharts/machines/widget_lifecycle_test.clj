(ns statecharts.machines.widget-lifecycle-test
    "Pure state machine transition tests for Code Browser v2 widget lifecycle.
   Tests validate all transitions without any I/O — no browser or server needed.

   Includes bug-documenting tests for the version-hash staleness issue:
   when a project changes, widgets re-fetch using stale version hashes."
    (:require [clojure.test :refer [deftest is testing]]
              [statecharts.core :as fsm]
              [statecharts.validate :as validate]
              [statecharts.machines.widget-lifecycle :as sut]))

;; =============================================================================
;; Helper: Pure transition (no side effects)
;; =============================================================================

(defn- transition
  "Apply a pure transition with actions executed (for context updates)."
  [state event]
  (fsm/transition sut/widget-lifecycle-statechart-compiled state event))

(defn- init-state
  "Create a fresh initial state."
  []
  (fsm/initialize sut/widget-lifecycle-statechart-compiled {:exec false}))

;; =============================================================================
;; 1. Initial State Tests
;; =============================================================================

(deftest initial-state-test
         (testing "initial state is :loading"
                  (let [state (init-state)]
                    (is (= :loading (:_state state)))))

         (testing "initial context has expected defaults"
                  (let [state (init-state)]
                    (is (nil? (:id state)))
                    (is (nil? (:type state)))
                    (is (nil? (:uri state)))
                    (is (nil? (:data state)))
                    (is (nil? (:error state)))
                    (is (nil? (:version-hash state))))))

;; =============================================================================
;; 2. Happy Path Transitions
;; =============================================================================

(deftest loading-fetch-success-test
         (testing ":fetch-success from :loading -> :ready with data stored"
                  (let [state (init-state)
                        next-state (transition state {:type :fetch-success
                                                      :data [{:name "echo.core"}]
                                                      :version-hash "abc123"})]
                    (is (= :ready (:_state next-state)))
                    (is (= [{:name "echo.core"}] (:data next-state)))
                    (is (= "abc123" (:version-hash next-state)))
                    (is (nil? (:error next-state))))))

(deftest ready-invalidate-test
         (testing ":invalidate from :ready -> :refreshing with version-hash updated"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "echo.core"}]
                                               :version-hash "abc123"}))
                        next-state (transition state {:type :invalidate
                                                      :new-version-hash "def456"})]
                    (is (= :refreshing (:_state next-state)))
                    (is (= "def456" (:version-hash next-state)))
                    (is (= [{:name "echo.core"}] (:data next-state))))))

(deftest refreshing-fetch-success-test
         (testing ":fetch-success from :refreshing -> :ready with new data"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "echo.core"}]
                                               :version-hash "abc123"})
                                  (transition {:type :invalidate
                                               :new-version-hash "def456"}))
                        next-state (transition state {:type :fetch-success
                                                      :data [{:name "echo.core"}
                                                             {:name "echo.util"}]
                                                      :version-hash "def456"})]
                    (is (= :ready (:_state next-state)))
                    (is (= [{:name "echo.core"} {:name "echo.util"}] (:data next-state)))
                    (is (= "def456" (:version-hash next-state))))))

(deftest close-from-any-state-test
         (testing ":close from :loading -> :closed"
                  (let [state (init-state)
                        next-state (transition state {:type :close})]
                    (is (= :closed (:_state next-state)))))

         (testing ":close from :ready -> :closed"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [:some-data]
                                               :version-hash "v1"}))
                        next-state (transition state {:type :close})]
                    (is (= :closed (:_state next-state)))))

         (testing ":close from :refreshing -> :closed"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [:some-data]
                                               :version-hash "v1"})
                                  (transition {:type :invalidate
                                               :new-version-hash "v2"}))
                        next-state (transition state {:type :close})]
                    (is (= :closed (:_state next-state)))))

         (testing ":close from :error -> :closed"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-error
                                               :error "network error"}))
                        next-state (transition state {:type :close})]
                    (is (= :closed (:_state next-state))))))

;; =============================================================================
;; 3. Error Paths
;; =============================================================================

(deftest loading-fetch-error-test
         (testing ":fetch-error from :loading -> :error with error stored"
                  (let [state (init-state)
                        next-state (transition state {:type :fetch-error
                                                      :error "404 not found"})]
                    (is (= :error (:_state next-state)))
                    (is (= "404 not found" (:error next-state)))
                    (is (nil? (:data next-state))))))

(deftest refreshing-fetch-error-preserves-data-test
         (testing ":fetch-error from :refreshing -> :error with stale data preserved"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "echo.core"}]
                                               :version-hash "abc123"})
                                  (transition {:type :invalidate
                                               :new-version-hash "def456"}))
                        next-state (transition state {:type :fetch-error
                                                      :error "server error"})]
                    (is (= :error (:_state next-state)))
                    (is (= "server error" (:error next-state)))
                    (is (= [{:name "echo.core"}] (:data next-state))))))

(deftest error-retry-test
         (testing ":retry from :error -> :loading with error cleared"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-error
                                               :error "network error"}))
                        next-state (transition state {:type :retry})]
                    (is (= :loading (:_state next-state)))
                    (is (nil? (:error next-state))))))

;; =============================================================================
;; 4. Invalid Transitions
;; =============================================================================

(deftest invalid-transitions-test
         (testing ":fetch-success from :ready is invalid (no duplicate fetch)"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [:data]
                                               :version-hash "v1"}))]
                    (is (thrown? Exception
                                 (transition state {:type :fetch-success
                                                    :data [:new-data]
                                                    :version-hash "v2"})))))

         (testing ":invalidate from :loading is invalid (no data yet)"
                  (let [state (init-state)]
                    (is (thrown? Exception
                                 (transition state {:type :invalidate
                                                    :new-version-hash "v2"})))))

         (testing ":invalidate from :error is invalid"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-error :error "fail"}))]
                    (is (thrown? Exception
                                 (transition state {:type :invalidate
                                                    :new-version-hash "v2"})))))

         (testing ":closed is terminal — all events throw"
                  (let [state (-> (init-state)
                                  (transition {:type :close}))]
                    (is (thrown? Exception
                                 (transition state {:type :fetch-success
                                                    :data [:data]
                                                    :version-hash "v1"})))
                    (is (thrown? Exception
                                 (transition state {:type :retry})))
                    (is (thrown? Exception
                                 (transition state {:type :invalidate
                                                    :new-version-hash "v2"}))))))

;; =============================================================================
;; 5. Context Invariant Tests (Bug-Exposing)
;; =============================================================================

(deftest data-never-nil-after-fetch-success-test
         (testing "after :fetch-success, :data is never nil"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "x"}]
                                               :version-hash "v1"}))]
                    (is (some? (:data state))))))

(deftest invalidate-updates-version-hash-test
         (testing "after :invalidate -> :fetch-success, data reflects NEW version"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "old"}]
                                               :version-hash "v1"})
                                  (transition {:type :invalidate
                                               :new-version-hash "v2"})
                                  (transition {:type :fetch-success
                                               :data [{:name "new"}]
                                               :version-hash "v2"}))]
                    (is (= "v2" (:version-hash state)))
                    (is (= [{:name "new"}] (:data state))))))

(deftest stale-data-preserved-on-refresh-error-test
         (testing "after :fetch-error from :refreshing, stale :data is preserved"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "original"}]
                                               :version-hash "v1"})
                                  (transition {:type :invalidate
                                               :new-version-hash "v2"})
                                  (transition {:type :fetch-error
                                               :error "server down"}))]
                    (is (= [{:name "original"}] (:data state)))
                    (is (= "server down" (:error state))))))

(deftest bug-test-version-hash-must-change-on-invalidate
         (testing "BUG DOC: invalidation without version-hash update leaves stale hash"
    ;; This documents the real bug: if invalidate doesn't update version-hash,
    ;; the widget will re-fetch using the old version hash and get stale/empty data.
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "echo.core"}]
                                               :version-hash "abc123"})
                    ;; Correct: invalidate WITH new hash
                                  (transition {:type :invalidate
                                               :new-version-hash "def456"}))]
      ;; The version-hash MUST be different after invalidation
                    (is (not= "abc123" (:version-hash state))
                        "Version hash must change on invalidation to avoid stale fetches"))))

(deftest bug-test-fetch-with-stale-hash-could-return-empty-data
         (testing "BUG DOC: fetch-success with empty data documents staleness risk"
    ;; This test documents what happens when the implementation fetches
    ;; with a stale version hash — the server returns empty results.
    ;; The statechart itself allows empty data (it's a valid server response),
    ;; but the test documents that this indicates a staleness bug.
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-success
                                               :data [{:name "echo.core"}]
                                               :version-hash "abc123"})
                                  (transition {:type :invalidate
                                               :new-version-hash "def456"})
                    ;; Simulating a fetch that used the OLD hash and got empty data
                                  (transition {:type :fetch-success
                                               :data []
                                               :version-hash "abc123"}))]
      ;; Data is empty because stale hash was used — THIS IS THE BUG
                    (is (= [] (:data state))
                        "Empty data after refresh indicates stale version-hash was used")
      ;; The version-hash reverted to the stale one
                    (is (= "abc123" (:version-hash state))
                        "Version hash should have been def456 but reverted to stale abc123"))))

(deftest retry-clears-error-test
         (testing "after :retry, error is cleared"
                  (let [state (-> (init-state)
                                  (transition {:type :fetch-error :error "fail"})
                                  (transition {:type :retry}))]
                    (is (nil? (:error state))))))

;; =============================================================================
;; 6. Full Lifecycle Scenarios
;; =============================================================================

(deftest complete-happy-path-test
         (testing "loading -> ready -> refreshing -> ready -> closed"
                  (let [s0 (init-state)
                        _ (is (= :loading (:_state s0)))

                        s1 (transition s0 {:type :fetch-success
                                           :data [{:name "ns1"}]
                                           :version-hash "v1"})
                        _ (is (= :ready (:_state s1)))

                        s2 (transition s1 {:type :invalidate
                                           :new-version-hash "v2"})
                        _ (is (= :refreshing (:_state s2)))

                        s3 (transition s2 {:type :fetch-success
                                           :data [{:name "ns1"} {:name "ns2"}]
                                           :version-hash "v2"})
                        _ (is (= :ready (:_state s3)))

                        s4 (transition s3 {:type :close})]
                    (is (= :closed (:_state s4))))))

(deftest error-recovery-lifecycle-test
         (testing "loading -> error -> retry -> loading -> ready"
                  (let [s0 (init-state)
                        _ (is (= :loading (:_state s0)))

                        s1 (transition s0 {:type :fetch-error :error "timeout"})
                        _ (is (= :error (:_state s1)))
                        _ (is (= "timeout" (:error s1)))

                        s2 (transition s1 {:type :retry})
                        _ (is (= :loading (:_state s2)))
                        _ (is (nil? (:error s2)))

                        s3 (transition s2 {:type :fetch-success
                                           :data [{:name "recovered"}]
                                           :version-hash "v1"})]
                    (is (= :ready (:_state s3)))
                    (is (= [{:name "recovered"}] (:data s3))))))

(deftest double-invalidation-test
         (testing "ready -> refreshing -> ready -> refreshing -> ready"
                  (let [s0 (-> (init-state)
                               (transition {:type :fetch-success
                                            :data [{:name "v1-data"}]
                                            :version-hash "v1"}))
                        _ (is (= :ready (:_state s0)))

                        s1 (transition s0 {:type :invalidate
                                           :new-version-hash "v2"})
                        _ (is (= :refreshing (:_state s1)))

                        s2 (transition s1 {:type :fetch-success
                                           :data [{:name "v2-data"}]
                                           :version-hash "v2"})
                        _ (is (= :ready (:_state s2)))

                        s3 (transition s2 {:type :invalidate
                                           :new-version-hash "v3"})
                        _ (is (= :refreshing (:_state s3)))

                        s4 (transition s3 {:type :fetch-success
                                           :data [{:name "v3-data"}]
                                           :version-hash "v3"})]
                    (is (= :ready (:_state s4)))
                    (is (= [{:name "v3-data"}] (:data s4)))
                    (is (= "v3" (:version-hash s4))))))

(deftest error-during-refresh-recovery-test
         (testing "ready -> refreshing -> error -> retry -> loading -> ready"
                  (let [s0 (-> (init-state)
                               (transition {:type :fetch-success
                                            :data [{:name "original"}]
                                            :version-hash "v1"}))
                        _ (is (= :ready (:_state s0)))

                        s1 (transition s0 {:type :invalidate
                                           :new-version-hash "v2"})
                        _ (is (= :refreshing (:_state s1)))

                        s2 (transition s1 {:type :fetch-error :error "503"})
                        _ (is (= :error (:_state s2)))
                        _ (is (= [{:name "original"}] (:data s2)))

                        s3 (transition s2 {:type :retry})
                        _ (is (= :loading (:_state s3)))

                        s4 (transition s3 {:type :fetch-success
                                           :data [{:name "refreshed"}]
                                           :version-hash "v2"})]
                    (is (= :ready (:_state s4)))
                    (is (= [{:name "refreshed"}] (:data s4))))))

;; =============================================================================
;; 7. Static Validator Integration
;; =============================================================================

(deftest machine-validation-test
         (testing "widget-lifecycle-statechart-compiled passes static validation with no errors"
                  (let [result (validate/validate sut/widget-lifecycle-statechart-compiled)]
                    (is (empty? (:errors result)))))

         (testing ":closed is terminal (dead-end warning expected)"
                  (let [result (validate/validate sut/widget-lifecycle-statechart-compiled)]
                    (is (= 1 (count (:warnings result))))
                    (is (= :closed (:state (first (:warnings result)))))
                    (is (= :dead-end (:type (first (:warnings result)))))))

         (testing "all states reachable from initial"
                  (let [reachable (validate/reachable-states sut/widget-lifecycle-statechart-compiled)
                        all-states (validate/extract-states sut/widget-lifecycle-statechart-compiled)]
                    (is (= all-states reachable))))

         (testing "no non-deterministic transitions"
                  (is (empty? (validate/find-non-deterministic sut/widget-lifecycle-statechart-compiled)))))

(deftest convention-checks-test
         (testing "machine config has no shorthand transitions"
                  (is (empty? (validate/check-longform-transitions
                               sut/widget-lifecycle-statechart))))

         (testing "machine has :id"
                  (is (empty? (validate/check-has-id sut/widget-lifecycle-statechart-compiled))))

         (testing "machine has :context"
                  (is (empty? (validate/check-has-context sut/widget-lifecycle-statechart-compiled))))

         (testing ":closed terminal state suppresses no-return-to-initial convention"
                  (let [issues (validate/check-initial-return-path sut/widget-lifecycle-statechart-compiled)]
      ;; :closed is terminal — should be excluded from no-return issues
                    (is (not (some #(= :closed (:state %)) issues))))))

;; =============================================================================
;; 8. Graph Extraction
;; =============================================================================

(deftest graph-extraction-test
         (testing "machine->graph returns expected state count"
                  (let [graph (validate/machine->graph sut/widget-lifecycle-statechart-compiled)]
                    (is (= 5 (count (:states graph))))))

         (testing "edge count matches expected transitions"
                  (let [graph (validate/machine->graph sut/widget-lifecycle-statechart-compiled)]
      ;; :loading -> :ready, :loading -> :error, :loading -> :closed (3)
      ;; :ready -> :refreshing, :ready -> :closed (2)
      ;; :refreshing -> :ready, :refreshing -> :error, :refreshing -> :closed (3)
      ;; :error -> :loading, :error -> :closed (2)
      ;; Total: 10 edges
                    (is (= 10 (count (:edges graph)))))))

;; =============================================================================
;; 9. Assign Action Unit Tests
;; =============================================================================

(deftest assign-widget-config-test
         (testing "stores widget configuration"
                  (let [ctx {:id nil :type nil :uri nil :version-hash nil}
                        result (sut/assign-widget-config ctx {:id :w1
                                                              :type :ns-list
                                                              :uri "dir://.@abc/echo"
                                                              :version-hash "abc"})]
                    (is (= :w1 (:id result)))
                    (is (= :ns-list (:type result)))
                    (is (= "dir://.@abc/echo" (:uri result)))
                    (is (= "abc" (:version-hash result))))))

(deftest assign-data-test
         (testing "stores data and clears error"
                  (let [ctx {:data nil :error "old error" :version-hash "v1"}
                        result (sut/assign-data ctx {:data [{:name "ns1"}]
                                                     :version-hash "v2"})]
                    (is (= [{:name "ns1"}] (:data result)))
                    (is (nil? (:error result)))
                    (is (= "v2" (:version-hash result))))))

(deftest assign-error-test
         (testing "stores error without clearing data"
                  (let [ctx {:data [{:name "stale"}] :error nil}
                        result (sut/assign-error ctx {:error "server error"})]
                    (is (= "server error" (:error result)))
                    (is (= [{:name "stale"}] (:data result))))))

(deftest assign-invalidate-test
         (testing "updates version-hash, preserves data"
                  (let [ctx {:data [{:name "current"}] :version-hash "v1"}
                        result (sut/assign-invalidate ctx {:new-version-hash "v2"})]
                    (is (= "v2" (:version-hash result)))
                    (is (= [{:name "current"}] (:data result))))))

(deftest assign-retry-test
         (testing "clears error"
                  (let [ctx {:error "fail" :data nil}
                        result (sut/assign-retry ctx {})]
                    (is (nil? (:error result)))
                    (is (nil? (:data result))))))
