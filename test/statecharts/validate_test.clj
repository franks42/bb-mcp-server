(ns statecharts.validate-test
    "Tests for statechart static analyzer.
   Uses both the real nrepl-server-machine and synthetic test machines."
    (:require [clojure.test :refer [deftest is testing]]
              [statecharts.core :as fsm]
              [statecharts.validate :as sut]
              [mcp-nrepl.state.local-nrepl-server :as nrepl-server]))

;; =============================================================================
;; Synthetic Test Machines
;; =============================================================================

(def clean-machine
     "A clean machine with no issues."
     (fsm/machine
      {:id      :clean
       :initial :idle
       :states
       {:idle    {:on {:start :running}}
        :running {:on {:stop :idle}}}}))

(def unreachable-machine
     "Machine with an unreachable state."
     (fsm/machine
      {:id      :unreachable
       :initial :a
       :states
       {:a      {:on {:go :b}}
        :b      {:on {:back :a}}
        :orphan {:on {:go :a}}}}))

(def dead-end-machine
     "Machine with a dead-end state (no outgoing transitions)."
     (fsm/machine
      {:id      :dead-end
       :initial :start
       :states
       {:start {:on {:go :middle}}
        :middle {:on {:finish :final}}
        :final  {}}}))

(def non-deterministic-machine
     "Machine with non-deterministic event (multiple transitions, no guards)."
     (fsm/machine
      {:id      :non-det
       :initial :start
       :states
       {:start {:on {:go [{:target :a} {:target :b}]}}
        :a     {:on {:back :start}}
        :b     {:on {:back :start}}}}))

(def guarded-machine
     "Machine with multiple transitions but all guarded — not non-deterministic."
     (fsm/machine
      {:id      :guarded
       :initial :start
       :states
       {:start {:on {:go [{:target :a :guard (fn [_ _] true)}
                          {:target :b :guard (fn [_ _] false)}]}}
        :a     {:on {:back :start}}
        :b     {:on {:back :start}}}}))

(def self-only-machine
     "Machine with a state that only has self-transitions."
     (fsm/machine
      {:id      :self-only
       :initial :start
       :states
       {:start {:on {:go :loop}}
        :loop  {:on {:tick :loop}}}}))

;; =============================================================================
;; State Extraction Tests
;; =============================================================================

(deftest extract-states-test
         (testing "extracts all states from clean machine"
                  (is (= #{:idle :running} (sut/extract-states clean-machine))))

         (testing "extracts all states including unreachable"
                  (is (= #{:a :b :orphan} (sut/extract-states unreachable-machine))))

         (testing "extracts states from nrepl-server-machine"
                  (is (= #{:stopped :starting :running :stopping :error}
                         (sut/extract-states nrepl-server/nrepl-server-machine)))))

;; =============================================================================
;; Edge Extraction Tests
;; =============================================================================

(deftest extract-edges-test
         (testing "extracts edges from clean machine"
                  (let [edges (sut/extract-edges clean-machine)]
                    (is (= 2 (count edges)))
                    (is (some #(and (= :idle (:source %)) (= :running (:target %)) (= :start (:event %))) edges))
                    (is (some #(and (= :running (:source %)) (= :idle (:target %)) (= :stop (:event %))) edges))))

         (testing "extracts all edges from nrepl-server-machine"
                  (let [edges (sut/extract-edges nrepl-server/nrepl-server-machine)]
      ;; stopped->starting, starting->running, starting->error,
      ;; running->stopping, stopping->stopped, stopping->error,
      ;; error->starting, error->stopped
                    (is (= 8 (count edges))))))

;; =============================================================================
;; Reachability Tests
;; =============================================================================

(deftest reachable-states-test
         (testing "all states reachable in clean machine"
                  (is (= #{:idle :running} (sut/reachable-states clean-machine))))

         (testing "orphan state is unreachable"
                  (is (= #{:a :b} (sut/reachable-states unreachable-machine))))

         (testing "all states reachable in nrepl-server-machine"
                  (is (= #{:stopped :starting :running :stopping :error}
                         (sut/reachable-states nrepl-server/nrepl-server-machine)))))

;; =============================================================================
;; Validation Check Tests
;; =============================================================================

(deftest find-unreachable-test
         (testing "no unreachable states in clean machine"
                  (is (empty? (sut/find-unreachable clean-machine))))

         (testing "detects unreachable state"
                  (let [issues (sut/find-unreachable unreachable-machine)]
                    (is (= 1 (count issues)))
                    (is (= :orphan (:state (first issues))))
                    (is (= :error (:severity (first issues))))))

         (testing "no unreachable states in nrepl-server-machine"
                  (is (empty? (sut/find-unreachable nrepl-server/nrepl-server-machine)))))

(deftest find-dead-ends-test
         (testing "no dead ends in clean machine"
                  (is (empty? (sut/find-dead-ends clean-machine))))

         (testing "detects dead-end state"
                  (let [issues (sut/find-dead-ends dead-end-machine)]
                    (is (= 1 (count issues)))
                    (is (= :final (:state (first issues))))
                    (is (= :warning (:severity (first issues))))))

         (testing "no dead ends in nrepl-server-machine"
                  (is (empty? (sut/find-dead-ends nrepl-server/nrepl-server-machine)))))

(deftest find-non-deterministic-test
         (testing "no non-determinism in clean machine"
                  (is (empty? (sut/find-non-deterministic clean-machine))))

         (testing "detects non-deterministic event"
                  (let [issues (sut/find-non-deterministic non-deterministic-machine)]
                    (is (= 1 (count issues)))
                    (is (= :start (:state (first issues))))
                    (is (= :go (:event (first issues))))
                    (is (= :warning (:severity (first issues))))))

         (testing "guarded transitions are not flagged"
                  (is (empty? (sut/find-non-deterministic guarded-machine))))

         (testing "no non-determinism in nrepl-server-machine"
                  (is (empty? (sut/find-non-deterministic nrepl-server/nrepl-server-machine)))))

(deftest find-orphans-test
         (testing "no orphans in clean machine"
                  (is (empty? (sut/find-orphans clean-machine))))

         (testing "no orphans in nrepl-server-machine"
                  (is (empty? (sut/find-orphans nrepl-server/nrepl-server-machine)))))

(deftest find-self-only-test
         (testing "no self-only states in clean machine"
                  (is (empty? (sut/find-self-only clean-machine))))

         (testing "detects self-only state"
                  (let [issues (sut/find-self-only self-only-machine)]
                    (is (= 1 (count issues)))
                    (is (= :loop (:state (first issues))))
                    (is (= :info (:severity (first issues)))))))

;; =============================================================================
;; Graph Extraction Tests
;; =============================================================================

(deftest machine->graph-test
         (testing "extracts graph from nrepl-server-machine"
                  (let [graph (sut/machine->graph nrepl-server/nrepl-server-machine)]
                    (is (= :stopped (:initial graph)))
                    (is (= :nrepl-server (:id graph)))
                    (is (= 5 (count (:states graph))))
                    (is (= 8 (count (:edges graph)))))))

;; =============================================================================
;; Full Validation Tests
;; =============================================================================

(deftest validate-clean-machine-test
         (testing "clean machine passes validation"
                  (let [result (sut/validate clean-machine)]
                    (is (empty? (:errors result)))
                    (is (empty? (:warnings result)))
                    (is (empty? (:info result)))
                    (is (= 0 (:errors (:summary result))))
                    (is (= 0 (:warnings (:summary result)))))))

(deftest validate-nrepl-server-machine-test
         (testing "nrepl-server-machine passes validation"
                  (let [result (sut/validate nrepl-server/nrepl-server-machine)]
                    (is (empty? (:errors result)))
                    (is (empty? (:warnings result)))
                    (is (= 5 (:states (:summary result))))
                    (is (= 8 (:edges (:summary result)))))))

(deftest validate-unreachable-machine-test
         (testing "detects unreachable state as error"
                  (let [result (sut/validate unreachable-machine)]
                    (is (= 1 (count (:errors result))))
                    (is (= :unreachable-state (:type (first (:errors result))))))))

(deftest validate-dead-end-machine-test
         (testing "detects dead-end state as warning"
                  (let [result (sut/validate dead-end-machine)]
                    (is (pos? (count (:warnings result))))
                    (is (some #(= :dead-end (:type %)) (:warnings result))))))

(deftest validate-non-deterministic-machine-test
         (testing "detects non-deterministic event as warning"
                  (let [result (sut/validate non-deterministic-machine)]
                    (is (pos? (count (:warnings result))))
                    (is (some #(= :non-deterministic (:type %)) (:warnings result))))))
