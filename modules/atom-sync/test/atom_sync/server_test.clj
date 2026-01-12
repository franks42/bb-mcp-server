(ns atom-sync.server-test
  "Integration tests for atom-sync.server.

   Tests the server layer that wires core to sente-lite transport."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [atom-sync.core :as core]
            [atom-sync.server :as server]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset atom-sync state before and after each test."
  [f]
  (core/reset-all!)
  (server/stop!)
  (f)
  (server/stop!)
  (core/reset-all!))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Tests
;; =============================================================================

(deftest init-test
  (testing "init! subscribes to core and sets up transport functions"
    (let [broadcasts (atom [])
          sends (atom [])
          broadcast-fn (fn [event]
                         (swap! broadcasts conj event)
                         1)
          send-fn (fn [conn-id event]
                    (swap! sends conj [conn-id event])
                    true)]
      ;; Initialize
      (server/init! broadcast-fn send-fn)

      ;; Register an atom
      (let [!state (atom {:count 0})]
        (core/register-synced-atom! :test-state !state)

        ;; Change atom - should trigger broadcast
        (swap! !state assoc :count 1)

        ;; Verify broadcast was called
        (is (= 1 (count @broadcasts)))
        (let [[event-id {:keys [key op path value]}] (first @broadcasts)]
          (is (= :sync/op event-id))
          (is (= :test-state key))
          (is (= :assoc-in op))
          (is (= [:count] path))
          (is (= 1 value)))))))

(deftest on-browser-connected-test
  (testing "on-browser-connected! pushes all atoms to new browser"
    (let [sends (atom [])
          broadcast-fn (fn [_] 1)
          send-fn (fn [conn-id event]
                    (swap! sends conj [conn-id event])
                    true)]
      ;; Initialize
      (server/init! broadcast-fn send-fn)

      ;; Register atoms
      (let [!state1 (atom {:a 1})
            !state2 (atom {:b 2})]
        (core/register-synced-atom! :state1 !state1)
        (core/register-synced-atom! :state2 !state2)

        ;; Simulate browser connect
        (let [op-count (server/on-browser-connected! "conn-123")]
          ;; Should have sent 2 ops (one per atom)
          (is (= 2 op-count))
          (is (= 2 (count @sends)))

          ;; Verify sends went to correct connection
          (is (every? #(= "conn-123" (first %)) @sends))

          ;; Verify both atoms were sent
          (let [sent-keys (set (map #(get-in % [1 1 :key]) @sends))]
            (is (contains? sent-keys :state1))
            (is (contains? sent-keys :state2))))))))

(deftest dispatch-event-resync-test
  (testing "dispatch-event handles :sync/resync-request"
    (let [broadcast-fn (fn [_] 1)
          send-fn (fn [_ _] true)]
      (server/init! broadcast-fn send-fn)

      ;; Register an atom
      (let [!state (atom {:x 42})]
        (core/register-synced-atom! :my-state !state)

        ;; Request resync
        (let [[event-id data] (server/dispatch-event
                               :sync/resync-request
                               {:key :my-state})]
          (is (= :sync/resync-response event-id))
          (is (= :my-state (:key data)))
          (is (seq (:ops data)))

          ;; Verify the op contains full state
          (let [[_ op-data] (first (:ops data))]
            (is (= [] (:path op-data)))
            (is (= {:x 42} (:value op-data)))))

        ;; Request resync for unknown key
        (let [[event-id data] (server/dispatch-event
                               :sync/resync-request
                               {:key :unknown})]
          (is (= :sync/resync-response event-id))
          (is (= :unknown-key (:error data))))))))

(deftest dispatch-event-heartbeat-test
  (testing "dispatch-event handles :sync/heartbeat"
    (let [broadcast-fn (fn [_] 1)
          send-fn (fn [_ _] true)]
      (server/init! broadcast-fn send-fn)

      ;; Register an atom and make some changes
      (let [!state (atom {:x 1})]
        (core/register-synced-atom! :my-state !state)
        (swap! !state assoc :x 2)
        (swap! !state assoc :x 3)
        ;; Server is now at seq 2

        ;; In-sync heartbeat
        (let [[event-id data] (server/dispatch-event
                               :sync/heartbeat
                               {:key :my-state :seq 2})]
          (is (= :sync/heartbeat-response event-id))
          (is (= :in-sync (:status data)))
          (is (= 2 (:server-seq data))))

        ;; Behind heartbeat
        (let [[event-id data] (server/dispatch-event
                               :sync/heartbeat
                               {:key :my-state :seq 0})]
          (is (= :sync/heartbeat-response event-id))
          (is (= :behind (:status data)))
          (is (some? (:resync-ops data))))))))

(deftest dispatch-event-unknown-test
  (testing "dispatch-event returns nil for unknown events"
    (let [broadcast-fn (fn [_] 1)
          send-fn (fn [_ _] true)]
      (server/init! broadcast-fn send-fn)

      ;; Unknown event
      (is (nil? (server/dispatch-event :unknown/event {:foo "bar"})))
      (is (nil? (server/dispatch-event :nrepl/response {:id "123"}))))))

(deftest stop-test
  (testing "stop! unsubscribes and clears transport"
    (let [broadcasts (atom [])
          broadcast-fn (fn [event]
                         (swap! broadcasts conj event)
                         1)
          send-fn (fn [_ _] true)]
      ;; Initialize
      (server/init! broadcast-fn send-fn)

      ;; Register and change
      (let [!state (atom {:a 1})]
        (core/register-synced-atom! :test !state)
        (swap! !state assoc :a 2)
        (is (= 1 (count @broadcasts)))

        ;; Stop
        (server/stop!)

        ;; Change again - should NOT broadcast
        (swap! !state assoc :a 3)
        (is (= 1 (count @broadcasts)) "No new broadcasts after stop")))))
