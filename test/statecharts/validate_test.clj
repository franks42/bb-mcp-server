(ns statecharts.validate-test
    "Tests for statechart static analyzer.
   Uses both the real nrepl-server-machine and synthetic test machines."
    (:require [clojure.test :refer [deftest is testing]]
              [statecharts.core :as fsm]
              [statecharts.validate :as sut]
              [mcp-nrepl.state.local-nrepl-server :as nrepl-server]
              [sente-browser.server :as browser-server]))

;; =============================================================================
;; Synthetic Test Machines
;; =============================================================================

(def clean-machine
     "A clean machine with no issues."
     (fsm/machine
      {:id      :clean
       :initial :idle
       :context {}
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

(def no-id-machine
     "Machine without :id."
     (fsm/machine
      {:initial :idle
       :context {}
       :states
       {:idle {:on {:go :idle}}}}))

(def no-context-machine
     "Machine without :context."
     (fsm/machine
      {:id      :no-ctx
       :initial :idle
       :states
       {:idle {:on {:go :idle}}}}))

(def error-no-recovery-machine
     "Machine with error state that has no outgoing transitions."
     (fsm/machine
      {:id      :stuck-error
       :initial :idle
       :context {}
       :states
       {:idle  {:on {:fail :error}}
        :error {}}}))

(def no-return-machine
     "Machine where a state cannot return to initial."
     (fsm/machine
      {:id      :no-return
       :initial :a
       :context {}
       :states
       {:a {:on {:go :b}}
        :b {:on {:next :c}}
        :c {:on {:loop :c}}}}))

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

;; =============================================================================
;; Convention Check Tests
;; =============================================================================

(deftest check-has-id-test
         (testing "no issue when :id present"
                  (is (empty? (sut/check-has-id clean-machine))))

         (testing "flags missing :id"
                  (let [issues (sut/check-has-id no-id-machine)]
                    (is (= 1 (count issues)))
                    (is (= :missing-id (:type (first issues))))
                    (is (= :convention (:severity (first issues)))))))

(deftest check-has-context-test
         (testing "no issue when :context present"
                  (is (empty? (sut/check-has-context clean-machine))))

         (testing "flags missing :context"
                  (let [issues (sut/check-has-context no-context-machine)]
                    (is (= 1 (count issues)))
                    (is (= :missing-context (:type (first issues)))))))

(deftest check-error-recovery-test
         (testing "no issue when error state has recovery"
                  (is (empty? (sut/check-error-recovery nrepl-server/nrepl-server-machine))))

         (testing "flags error state without recovery"
                  (let [issues (sut/check-error-recovery error-no-recovery-machine)]
                    (is (= 1 (count issues)))
                    (is (= :error (:state (first issues))))
                    (is (= :error-no-recovery (:type (first issues)))))))

(deftest check-initial-return-path-test
         (testing "no issue when all states can return to initial"
                  (is (empty? (sut/check-initial-return-path clean-machine))))

         (testing "flags states with no return to initial"
                  (let [issues (sut/check-initial-return-path no-return-machine)]
                    (is (pos? (count issues)))
                    (is (some #(= :no-return-to-initial (:type %)) issues))))

         (testing "nrepl-server-machine has full return paths"
                  (is (empty? (sut/check-initial-return-path nrepl-server/nrepl-server-machine)))))

(deftest validate-conventions-in-nrepl-machine-test
         (testing "nrepl-server-machine passes all convention checks"
                  (let [result (sut/validate nrepl-server/nrepl-server-machine)]
                    (is (empty? (:conventions result)))
                    (is (= 0 (:conventions (:summary result)))))))

;; =============================================================================
;; Shorthand Transition Normalization Tests
;; =============================================================================

(def shorthand-machine
     "Machine using bare keyword shorthand transitions."
     (fsm/machine
      {:id      :shorthand
       :initial :a
       :context {}
       :states
       {:a {:on {:go :b
                 :skip :c}}
        :b {:on {:back {:target :a}}}
        :c {:on {:back :a}}}}))

(def self-transition-machine
     "Machine with a self-transition (no :target, only :actions)."
     (fsm/machine
      {:id      :self-trans
       :initial :idle
       :context {:count 0}
       :states
       {:idle {:on {:tick {:actions [(fsm/assign (fn [ctx _] (update ctx :count inc)))]}
                    :stop :done}}
        :done {}}}))

(deftest extract-edges-shorthand-test
         (testing "extracts edges from shorthand keyword transitions"
                  (let [edges (sut/extract-edges shorthand-machine)]
                    (is (= 4 (count edges)))
                    (is (some #(and (= :a (:source %)) (= :b (:target %)) (= :go (:event %))) edges))
                    (is (some #(and (= :a (:source %)) (= :c (:target %)) (= :skip (:event %))) edges))
                    (is (some #(and (= :b (:source %)) (= :a (:target %)) (= :back (:event %))) edges))
                    (is (some #(and (= :c (:source %)) (= :a (:target %)) (= :back (:event %))) edges))))

         (testing "self-transition (no target) produces no edges"
                  (let [edges (sut/extract-edges self-transition-machine)]
                    ;; Only :stop -> :done edge, no edge for :tick (self-transition)
                    (is (= 1 (count edges)))
                    (is (= :idle (:source (first edges))))
                    (is (= :done (:target (first edges))))
                    (is (= :stop (:event (first edges)))))))

(deftest validate-shorthand-machine-test
         (testing "shorthand machine validates correctly after normalization"
                  (let [result (sut/validate shorthand-machine)]
                    (is (empty? (:errors result)))
                    (is (= 3 (:states (:summary result))))
                    (is (= 4 (:edges (:summary result)))))))

;; =============================================================================
;; Longform Transition Convention Tests
;; =============================================================================

(def shorthand-config
     "Raw config with shorthand transitions for convention check."
     {:id      :shorthand-raw
      :initial :a
      :context {}
      :states
      {:a {:on {:go :b
                :full {:target :c}}}
       :b {:on {:back :a}}
       :c {:on {:back {:target :a}}}}})

(deftest check-longform-transitions-test
         (testing "flags shorthand transitions in raw config"
                  (let [issues (sut/check-longform-transitions shorthand-config)]
                    (is (= 2 (count issues)))
                    (is (every? #(= :shorthand-transition (:type %)) issues))
                    (is (every? #(= :convention (:severity %)) issues))
                    (is (some #(and (= :a (:state %)) (= :go (:event %)) (= :b (:target %))) issues))
                    (is (some #(and (= :b (:state %)) (= :back (:event %)) (= :a (:target %))) issues))))

         (testing "no flags when all transitions use longform"
                  (let [config {:id :all-long :initial :a :context {}
                                :states {:a {:on {:go {:target :b}}}
                                         :b {:on {:back {:target :a}}}}}]
                    (is (empty? (sut/check-longform-transitions config)))))

         (testing "compiled machine has no shorthand (already normalized)"
                  (is (empty? (sut/check-longform-transitions
                               (fsm/machine shorthand-config))))))

;; =============================================================================
;; Terminal Lifecycle Convention Tests
;; =============================================================================

(def terminal-lifecycle-config
     "Per-instance lifecycle with intentional terminal state."
     {:id      :lifecycle
      :initial :pending
      :context {}
      :states
      {:pending {:on {:ok {:target :active}
                      :fail {:target :disconnected}}}
       :active  {:on {:close {:target :disconnected}}}
       :disconnected {}}})

(deftest check-terminal-lifecycle-test
         (testing "terminal state suppresses no-return-to-initial"
                  (let [machine (fsm/machine terminal-lifecycle-config)
                        issues  (sut/check-initial-return-path machine)]
                    ;; :disconnected is terminal — should be excluded
                    ;; :active has no return path but is not terminal
                    (is (= 1 (count issues)))
                    (is (= :active (:state (first issues))))))

         (testing "non-terminal dead-end still flagged"
                  (let [issues (sut/check-initial-return-path no-return-machine)]
                    ;; :b and :c have no return, neither is terminal
                    (is (= 2 (count issues)))
                    (is (some #(= :b (:state %)) issues))
                    (is (some #(= :c (:state %)) issues)))))

;; =============================================================================
;; Browser Connection Machine Validation Tests
;; =============================================================================

(deftest validate-browser-connection-machine-test
         (testing "browser-connection-machine has no errors"
                  (let [result (sut/validate browser-server/browser-connection-machine)]
                    (is (empty? (:errors result)))
                    (is (= 4 (:states (:summary result))))
                    (is (= 6 (:edges (:summary result))))))

         (testing "browser-connection-machine-config flags shorthand transitions"
                  (let [issues (sut/check-longform-transitions
                                browser-server/browser-connection-machine-config)]
                    ;; 4 shorthand: ws-close in 3 states + heartbeat-timeout
                    (is (= 4 (count issues)))
                    (is (every? #(= :shorthand-transition (:type %)) issues))))

         (testing "terminal state :disconnected suppresses no-return convention"
                  (let [issues (sut/check-initial-return-path
                                browser-server/browser-connection-machine)]
                    ;; :disconnected excluded, :validated and :validation-failed remain
                    (is (= 2 (count issues)))
                    (is (not (some #(= :disconnected (:state %)) issues))))))
