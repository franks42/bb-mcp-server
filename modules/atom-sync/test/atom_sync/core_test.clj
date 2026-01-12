(ns atom-sync.core-test
    "Tests for atom-sync core functionality.

   Tests sync logic locally without any network transport:
   - deep-diff->ops generates correct ops
   - apply-sync-op applies ops correctly
   - Two-atom sync: source changes propagate to target"
    (:require [clojure.test :refer [deftest is testing]]
              [atom-sync.core :as core]))

;; =============================================================================
;; deep-diff->ops Tests
;; =============================================================================

(deftest deep-diff->ops-flat-maps
         (testing "identical values produce no ops"
                  (is (= [] (core/deep-diff->ops :s {:a 1} {:a 1}))))

         (testing "add key"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:b] :value 2}]]
                         (core/deep-diff->ops :s {:a 1} {:a 1 :b 2}))))

         (testing "remove key"
                  (is (= [[:sync/op {:key :s :op :dissoc-in :path [:b]}]]
                         (core/deep-diff->ops :s {:a 1 :b 2} {:a 1}))))

         (testing "change value"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:a] :value 99}]]
                         (core/deep-diff->ops :s {:a 1} {:a 99}))))

         (testing "multiple changes"
                  (let [ops (core/deep-diff->ops :s
                                                 {:a 1 :b 2 :c 3}
                                                 {:a 1 :b 99 :d 4})]
      ;; :b changed, :c removed, :d added
                    (is (= 3 (count ops)))
                    (is (some #(= % [:sync/op {:key :s :op :assoc-in :path [:b] :value 99}]) ops))
                    (is (some #(= % [:sync/op {:key :s :op :dissoc-in :path [:c]}]) ops))
                    (is (some #(= % [:sync/op {:key :s :op :assoc-in :path [:d] :value 4}]) ops)))))

(deftest deep-diff->ops-nested-maps
         (testing "nested change produces nested path"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:user :name] :value "new"}]]
                         (core/deep-diff->ops :s
                                              {:user {:name "old" :age 30}}
                                              {:user {:name "new" :age 30}}))))

         (testing "add nested key"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:user :email] :value "a@b.c"}]]
                         (core/deep-diff->ops :s
                                              {:user {:name "x"}}
                                              {:user {:name "x" :email "a@b.c"}}))))

         (testing "remove nested key"
                  (is (= [[:sync/op {:key :s :op :dissoc-in :path [:user :age]}]]
                         (core/deep-diff->ops :s
                                              {:user {:name "x" :age 30}}
                                              {:user {:name "x"}}))))

         (testing "deep nesting"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:a :b :c :d] :value 99}]]
                         (core/deep-diff->ops :s
                                              {:a {:b {:c {:d 1}}}}
                                              {:a {:b {:c {:d 99}}}})))))

(deftest deep-diff->ops-vectors
         (testing "vectors replaced wholesale"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:items] :value [1 2 3 4]}]]
                         (core/deep-diff->ops :s
                                              {:items [1 2 3]}
                                              {:items [1 2 3 4]}))))

         (testing "vector to empty"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:items] :value []}]]
                         (core/deep-diff->ops :s
                                              {:items [1 2 3]}
                                              {:items []})))))

(deftest deep-diff->ops-full-replace
         (testing "nil to value produces path []"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [] :value {:a 1}}]]
                         (core/deep-diff->ops :s nil {:a 1}))))

         (testing "value to nil produces path []"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [] :value nil}]]
                         (core/deep-diff->ops :s {:a 1} nil))))

         (testing "type change (map to vector) produces path []"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [] :value [1 2 3]}]]
                         (core/deep-diff->ops :s {:a 1} [1 2 3])))))

;; =============================================================================
;; apply-sync-op Tests
;; =============================================================================

(deftest apply-sync-op-assoc-in
         (testing "full replace (path [])"
                  (let [a (atom {:old "value"})]
                    (core/apply-sync-op a [:sync/op {:op :assoc-in :path [] :value {:new "value"}}])
                    (is (= {:new "value"} @a))))

         (testing "top-level assoc"
                  (let [a (atom {:a 1})]
                    (core/apply-sync-op a [:sync/op {:op :assoc-in :path [:b] :value 2}])
                    (is (= {:a 1 :b 2} @a))))

         (testing "nested assoc"
                  (let [a (atom {:user {:name "x"}})]
                    (core/apply-sync-op a [:sync/op {:op :assoc-in :path [:user :age] :value 30}])
                    (is (= {:user {:name "x" :age 30}} @a))))

         (testing "deep nested assoc"
                  (let [a (atom {:a {:b {:c 1}}})]
                    (core/apply-sync-op a [:sync/op {:op :assoc-in :path [:a :b :c] :value 99}])
                    (is (= {:a {:b {:c 99}}} @a)))))

(deftest apply-sync-op-dissoc-in
         (testing "top-level dissoc"
                  (let [a (atom {:a 1 :b 2})]
                    (core/apply-sync-op a [:sync/op {:op :dissoc-in :path [:b]}])
                    (is (= {:a 1} @a))))

         (testing "nested dissoc"
                  (let [a (atom {:user {:name "x" :age 30}})]
                    (core/apply-sync-op a [:sync/op {:op :dissoc-in :path [:user :age]}])
                    (is (= {:user {:name "x"}} @a))))

         (testing "deep nested dissoc"
                  (let [a (atom {:a {:b {:c 1 :d 2}}})]
                    (core/apply-sync-op a [:sync/op {:op :dissoc-in :path [:a :b :c]}])
                    (is (= {:a {:b {:d 2}}} @a)))))

(deftest apply-sync-op-roundtrip
         (testing "diff then apply produces original"
                  (let [old {:user {:name "old" :age 30} :count 1}
                        new {:user {:name "new" :age 30} :count 2 :extra true}
                        ops (core/deep-diff->ops :s old new)
                        result (atom old)]
                    (doseq [op ops]
                           (core/apply-sync-op result op))
                    (is (= new @result))))

         (testing "roundtrip with removal"
                  (let [old {:a 1 :b {:x 1 :y 2} :c 3}
                        new {:a 1 :b {:x 1}}
                        ops (core/deep-diff->ops :s old new)
                        result (atom old)]
                    (doseq [op ops]
                           (core/apply-sync-op result op))
                    (is (= new @result)))))

;; =============================================================================
;; Local Two-Atom Sync Tests
;; =============================================================================

(deftest local-sync-basic
         (testing "source change propagates to target"
                  (core/reset-all!)
                  (let [!source (atom {:count 0})
                        !target (atom nil)
                        received-ops (atom [])]
      ;; Subscribe to receive ops
                    (core/subscribe! :test-sub
                                     (fn [ops]
                                       (swap! received-ops into ops)
                                       (doseq [op ops]
                                              (core/apply-sync-op !target op))))

      ;; Register source
                    (core/register-synced-atom! :my-state !source)

      ;; Change source
                    (swap! !source assoc :count 1)

      ;; Target should match source
                    (is (= {:count 1} @!target))
                    (is (= 1 (count @received-ops)))

      ;; Verify op structure
                    (let [[op-type op-data] (first @received-ops)]
                      (is (= :sync/op op-type))
                      (is (= :my-state (:key op-data)))
                      (is (= 1 (:seq op-data)))
                      (is (= :assoc-in (:op op-data)))))))

(deftest local-sync-multiple-changes
         (testing "multiple changes produce sequential seqs"
                  (core/reset-all!)
                  (let [!source (atom {:count 0})
                        !target (atom nil)
                        received-ops (atom [])]
                    (core/subscribe! :test-sub
                                     (fn [ops]
                                       (swap! received-ops into ops)
                                       (doseq [op ops]
                                              (core/apply-sync-op !target op))))

                    (core/register-synced-atom! :s !source)

      ;; Multiple changes
                    (swap! !source assoc :count 1)
                    (swap! !source assoc :count 2)
                    (swap! !source assoc :count 3)

      ;; Target should have final value
                    (is (= {:count 3} @!target))

      ;; Should have 3 ops with sequential seqs
                    (is (= 3 (count @received-ops)))
                    (is (= [1 2 3] (map #(get-in % [1 :seq]) @received-ops))))))

(deftest local-sync-nested-changes
         (testing "nested changes produce nested paths"
                  (core/reset-all!)
                  (let [!source (atom {:user {:name "old" :age 30}})
                        !target (atom {:user {:name "old" :age 30}})
                        received-ops (atom [])]
                    (core/subscribe! :test-sub
                                     (fn [ops]
                                       (swap! received-ops into ops)
                                       (doseq [op ops]
                                              (core/apply-sync-op !target op))))

                    (core/register-synced-atom! :s !source)

      ;; Nested change
                    (swap! !source assoc-in [:user :name] "new")

      ;; Target should match
                    (is (= {:user {:name "new" :age 30}} @!target))

      ;; Op should have nested path
                    (let [[_ op-data] (first @received-ops)]
                      (is (= [:user :name] (:path op-data)))
                      (is (= "new" (:value op-data)))))))

(deftest local-sync-unregister
         (testing "unregister stops sync"
                  (core/reset-all!)
                  (let [!source (atom {:count 0})
                        received-count (atom 0)]
                    (core/subscribe! :test-sub
                                     (fn [_ops]
                                       (swap! received-count inc)))

                    (core/register-synced-atom! :s !source)

      ;; Change triggers sync
                    (swap! !source assoc :count 1)
                    (is (= 1 @received-count))

      ;; Unregister
                    (core/unregister-synced-atom! :s)

      ;; Change should not trigger sync
                    (swap! !source assoc :count 2)
                    (is (= 1 @received-count)))))

(deftest local-sync-multiple-atoms
         (testing "multiple atoms sync independently"
                  (core/reset-all!)
                  (let [!a (atom {:a 0})
                        !b (atom {:b 0})
                        !target-a (atom nil)
                        !target-b (atom nil)]
                    (core/subscribe! :test-sub
                                     (fn [ops]
                                       (doseq [[_ {:keys [key] :as op-data}] ops]
                                              (case key
                                                :atom-a (core/apply-sync-op !target-a [:sync/op op-data])
                                                :atom-b (core/apply-sync-op !target-b [:sync/op op-data])))))

                    (core/register-synced-atom! :atom-a !a)
                    (core/register-synced-atom! :atom-b !b)

                    (swap! !a assoc :a 1)
                    (swap! !b assoc :b 1)

                    (is (= {:a 1} @!target-a))
                    (is (= {:b 1} @!target-b)))))

;; =============================================================================
;; Seq Tracking Tests
;; =============================================================================

(deftest seq-tracking
         (testing "seq starts at 0 and increments"
                  (core/reset-all!)
                  (let [!source (atom {:x 0})]
                    (core/register-synced-atom! :s !source)

      ;; Initial seq is 0
                    (is (= {:s 0} (core/get-sync-status)))

      ;; After change, seq is 1
                    (core/subscribe! :dummy (fn [_] nil))
                    (swap! !source assoc :x 1)
                    (is (= {:s 1} (core/get-sync-status)))

      ;; After another change, seq is 2
                    (swap! !source assoc :x 2)
                    (is (= {:s 2} (core/get-sync-status))))))

;; =============================================================================
;; Full Sync Generation Tests
;; =============================================================================

(deftest full-sync-ops
         (testing "generate-full-sync-ops returns full state op"
                  (core/reset-all!)
                  (let [!source (atom {:a 1 :b 2})]
                    (core/register-synced-atom! :s !source)

                    (let [ops (core/generate-full-sync-ops :s)
                          [_ op-data] (first ops)]
                      (is (= 1 (count ops)))
                      (is (= :s (:key op-data)))
                      (is (= 0 (:seq op-data)))
                      (is (= :assoc-in (:op op-data)))
                      (is (= [] (:path op-data)))
                      (is (= {:a 1 :b 2} (:value op-data))))))

         (testing "generate-all-full-sync-ops for multiple atoms"
                  (core/reset-all!)
                  (let [!a (atom {:a 1})
                        !b (atom {:b 2})]
                    (core/register-synced-atom! :atom-a !a)
                    (core/register-synced-atom! :atom-b !b)

                    (let [ops (core/generate-all-full-sync-ops)]
                      (is (= 2 (count ops)))
                      (is (some #(= :atom-a (get-in % [1 :key])) ops))
                      (is (some #(= :atom-b (get-in % [1 :key])) ops))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest edge-cases
         (testing "empty map changes"
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:a] :value 1}]]
                         (core/deep-diff->ops :s {} {:a 1})))
                  (is (= [[:sync/op {:key :s :op :dissoc-in :path [:a]}]]
                         (core/deep-diff->ops :s {:a 1} {}))))

         (testing "falsey values"
    ;; nil as value
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:a] :value nil}]]
                         (core/deep-diff->ops :s {:a 1} {:a nil})))
    ;; false as value
                  (is (= [[:sync/op {:key :s :op :assoc-in :path [:a] :value false}]]
                         (core/deep-diff->ops :s {:a true} {:a false}))))

         (testing "subscriber error doesn't break other subscribers"
                  (core/reset-all!)
                  (let [!source (atom {:x 0})
                        results (atom [])]
      ;; First subscriber throws
                    (core/subscribe! :bad (fn [_] (throw (ex-info "boom" {}))))
      ;; Second subscriber should still work
                    (core/subscribe! :good (fn [ops] (swap! results into ops)))

                    (core/register-synced-atom! :s !source)
                    (swap! !source assoc :x 1)

      ;; Good subscriber received the op despite bad subscriber's error
                    (is (= 1 (count @results))))))

;; =============================================================================
;; Seq Validation Tests
;; =============================================================================

(deftest apply-sync-op-validated-normal
         (testing "sequential ops return :applied"
                  (let [!target (atom nil)
                        !seqs (atom {})
                        op1 [:sync/op {:key :s :seq 1 :op :assoc-in :path [] :value {:a 1}}]
                        op2 [:sync/op {:key :s :seq 2 :op :assoc-in :path [:b] :value 2}]
                        op3 [:sync/op {:key :s :seq 3 :op :assoc-in :path [:c] :value 3}]]
                    (is (= :applied (core/apply-sync-op-validated !target op1 !seqs)))
                    (is (= {:a 1} @!target))
                    (is (= {:s 1} @!seqs))

                    (is (= :applied (core/apply-sync-op-validated !target op2 !seqs)))
                    (is (= {:a 1 :b 2} @!target))
                    (is (= {:s 2} @!seqs))

                    (is (= :applied (core/apply-sync-op-validated !target op3 !seqs)))
                    (is (= {:a 1 :b 2 :c 3} @!target))
                    (is (= {:s 3} @!seqs)))))

(deftest apply-sync-op-validated-stale
         (testing "old seq returns :stale and doesn't apply"
                  (let [!target (atom {:a 1})
                        !seqs (atom {:s 5})  ; Already seen seq 5
                        stale-op [:sync/op {:key :s :seq 3 :op :assoc-in :path [:a] :value 999}]]
                    (is (= :stale (core/apply-sync-op-validated !target stale-op !seqs)))
      ;; Target unchanged
                    (is (= {:a 1} @!target))
      ;; Seq tracking unchanged
                    (is (= {:s 5} @!seqs))))

         (testing "duplicate seq returns :stale"
                  (let [!target (atom {:a 1})
                        !seqs (atom {:s 5})
                        dup-op [:sync/op {:key :s :seq 5 :op :assoc-in :path [:a] :value 999}]]
                    (is (= :stale (core/apply-sync-op-validated !target dup-op !seqs)))
                    (is (= {:a 1} @!target)))))

(deftest apply-sync-op-validated-gap
         (testing "seq gap returns :gap but still applies"
                  (let [!target (atom {:a 1})
                        !seqs (atom {:s 2})  ; Expecting seq 3
                        gap-op [:sync/op {:key :s :seq 7 :op :assoc-in :path [:a] :value 99}]]
                    (is (= :gap (core/apply-sync-op-validated !target gap-op !seqs)))
      ;; Op was applied (newer state is better)
                    (is (= {:a 99} @!target))
      ;; Seq tracking updated to received seq
                    (is (= {:s 7} @!seqs)))))

(deftest apply-sync-op-validated-multiple-keys
         (testing "tracks seqs per key independently"
                  (let [!target-a (atom nil)
                        !target-b (atom nil)
                        !seqs (atom {})
                        op-a1 [:sync/op {:key :a :seq 1 :op :assoc-in :path [] :value {:x 1}}]
                        op-b1 [:sync/op {:key :b :seq 1 :op :assoc-in :path [] :value {:y 1}}]
                        op-a2 [:sync/op {:key :a :seq 2 :op :assoc-in :path [:x] :value 2}]]
                    (is (= :applied (core/apply-sync-op-validated !target-a op-a1 !seqs)))
                    (is (= :applied (core/apply-sync-op-validated !target-b op-b1 !seqs)))
                    (is (= {:a 1 :b 1} @!seqs))

                    (is (= :applied (core/apply-sync-op-validated !target-a op-a2 !seqs)))
                    (is (= {:a 2 :b 1} @!seqs)))))

(deftest reset-expected-seq-test
         (testing "reset-expected-seq! updates tracking"
                  (let [!seqs (atom {:s 5})]
                    (core/reset-expected-seq! !seqs :s 10)
                    (is (= {:s 10} @!seqs)))

                  (let [!seqs (atom {})]
                    (core/reset-expected-seq! !seqs :new-key 42)
                    (is (= {:new-key 42} @!seqs)))))

;; =============================================================================
;; Heartbeat Tests
;; =============================================================================

(deftest get-server-seq-test
         (testing "returns seq for registered atom"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (is (= 0 (core/get-server-seq :s)))

      ;; After change, seq increments
                    (core/subscribe! :dummy (fn [_]))
                    (swap! !source assoc :x 2)
                    (is (= 1 (core/get-server-seq :s)))))

         (testing "returns nil for unregistered key"
                  (core/reset-all!)
                  (is (nil? (core/get-server-seq :nonexistent)))))

(deftest check-sync-status-test
         (testing "in-sync when seqs match"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (is (= :in-sync (core/check-sync-status :s 0)))))

         (testing "behind when client-seq < server-seq"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (core/subscribe! :dummy (fn [_]))
                    (swap! !source assoc :x 2)
                    (swap! !source assoc :x 3)
      ;; Server is at seq 2, client at seq 0
                    (is (= :behind (core/check-sync-status :s 0)))
                    (is (= :behind (core/check-sync-status :s 1)))))

         (testing "ahead when client-seq > server-seq (shouldn't happen)"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (is (= :ahead (core/check-sync-status :s 99)))))

         (testing "unknown for unregistered key"
                  (core/reset-all!)
                  (is (= :unknown (core/check-sync-status :nonexistent 5)))))

(deftest handle-heartbeat-test
         (testing "returns in-sync with no resync ops"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (let [result (core/handle-heartbeat :s 0)]
                      (is (= :in-sync (:status result)))
                      (is (= 0 (:server-seq result)))
                      (is (nil? (:resync-ops result))))))

         (testing "returns behind with resync ops"
                  (core/reset-all!)
                  (let [!source (atom {:x 1})]
                    (core/register-synced-atom! :s !source)
                    (core/subscribe! :dummy (fn [_]))
                    (swap! !source assoc :x 2)
                    (swap! !source assoc :x 3)
      ;; Server at seq 2, client claims seq 0
                    (let [result (core/handle-heartbeat :s 0)]
                      (is (= :behind (:status result)))
                      (is (= 2 (:server-seq result)))
                      (is (some? (:resync-ops result)))
        ;; Resync ops contain full state
                      (let [[_ op-data] (first (:resync-ops result))]
                        (is (= [] (:path op-data)))
                        (is (= {:x 3} (:value op-data)))))))

         (testing "returns unknown for unregistered key"
                  (core/reset-all!)
                  (let [result (core/handle-heartbeat :nonexistent 5)]
                    (is (= :unknown (:status result)))
                    (is (nil? (:server-seq result)))
                    (is (nil? (:resync-ops result))))))
