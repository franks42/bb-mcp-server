(ns mcp-nrepl.state.local-nrepl-server-test
    "Pure state machine transition tests for local nREPL server lifecycle.
   Tests validate all transitions without any I/O — no server is started."
    (:require [clojure.test :refer [deftest is testing]]
              [statecharts.core :as fsm]
              [mcp-nrepl.state.local-nrepl-server :as sut]))

;; =============================================================================
;; Helper: Pure transition (no side effects)
;; =============================================================================

(defn- transition
  "Apply a pure transition with actions executed (for context updates)."
  [state event]
  (fsm/transition sut/nrepl-server-machine state event))

(defn- init-state
  "Create a fresh initial state."
  []
  (fsm/initialize sut/nrepl-server-machine {:exec false}))

;; =============================================================================
;; Initial State Tests
;; =============================================================================

(deftest initial-state-test
         (testing "initial state is :stopped"
                  (let [state (init-state)]
                    (is (= :stopped (:_state state)))))

         (testing "initial context has expected defaults"
                  (let [state (init-state)]
                    (is (nil? (:server-map state)))
                    (is (= "localhost" (:host state)))
                    (is (nil? (:port state)))
                    (is (nil? (:connection state)))
                    (is (nil? (:started-at state)))
                    (is (nil? (:stopped-at state)))
                    (is (= {} (:config state)))
                    (is (nil? (:error state))))))

;; =============================================================================
;; Valid Transition Tests
;; =============================================================================

(deftest start-from-stopped-test
         (testing ":start from :stopped -> :starting with config stored"
                  (let [state (init-state)
                        config {:port 7888 :host "0.0.0.0"}
                        next-state (transition state {:type :start :config config})]
                    (is (= :starting (:_state next-state)))
                    (is (= config (:config next-state)))
                    (is (nil? (:error next-state))))))

(deftest started-from-starting-test
         (testing ":started from :starting -> :running with server info"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {:port 7888}}))
                        server-info {:server-map {:socket :mock}
                                     :host "localhost"
                                     :port 7888
                                     :connection "localhost:7888"
                                     :started-at 1000}
                        next-state (transition state (assoc server-info :type :started))]
                    (is (= :running (:_state next-state)))
                    (is (= {:socket :mock} (:server-map next-state)))
                    (is (= "localhost" (:host next-state)))
                    (is (= 7888 (:port next-state)))
                    (is (= "localhost:7888" (:connection next-state)))
                    (is (= 1000 (:started-at next-state))))))

(deftest failed-from-starting-test
         (testing ":failed from :starting -> :error with error message"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}}))
                        next-state (transition state {:type :failed :error "bind failed"})]
                    (is (= :error (:_state next-state)))
                    (is (= "bind failed" (:error next-state))))))

(deftest stop-from-running-test
         (testing ":stop from :running -> :stopping"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :started
                                               :server-map {:socket :mock}
                                               :host "localhost"
                                               :port 7888
                                               :connection "localhost:7888"
                                               :started-at 1000}))
                        next-state (transition state {:type :stop})]
                    (is (= :stopping (:_state next-state))))))

(deftest stopped-from-stopping-test
         (testing ":stopped from :stopping -> :stopped with timestamp"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :started
                                               :server-map {:socket :mock}
                                               :host "localhost"
                                               :port 7888
                                               :connection "localhost:7888"
                                               :started-at 1000})
                                  (transition {:type :stop}))
                        next-state (transition state {:type :stopped :stopped-at 2000})]
                    (is (= :stopped (:_state next-state)))
                    (is (nil? (:server-map next-state)))
                    (is (= 2000 (:stopped-at next-state)))
                    (is (nil? (:error next-state))))))

(deftest failed-from-stopping-test
         (testing ":failed from :stopping -> :error"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :started
                                               :server-map {:socket :mock}
                                               :host "localhost"
                                               :port 7888
                                               :connection "localhost:7888"
                                               :started-at 1000})
                                  (transition {:type :stop}))
                        next-state (transition state {:type :failed :error "stop failed"})]
                    (is (= :error (:_state next-state)))
                    (is (= "stop failed" (:error next-state))))))

(deftest start-from-error-test
         (testing ":start from :error -> :starting (recovery)"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :failed :error "bind failed"}))
                        next-state (transition state {:type :start :config {:port 9999}})]
                    (is (= :starting (:_state next-state)))
                    (is (= {:port 9999} (:config next-state)))
                    (is (nil? (:error next-state))))))

(deftest reset-from-error-test
         (testing ":reset from :error -> :stopped"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :failed :error "bind failed"}))
                        next-state (transition state {:type :reset})]
                    (is (= :stopped (:_state next-state))))))

;; =============================================================================
;; Invalid Transition Tests
;; =============================================================================

(deftest invalid-transitions-test
         (testing ":stop from :stopped throws"
                  (let [state (init-state)]
                    (is (thrown? Exception (transition state {:type :stop})))))

         (testing ":started from :stopped throws"
                  (let [state (init-state)]
                    (is (thrown? Exception (transition state {:type :started})))))

         (testing ":start from :running throws"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}})
                                  (transition {:type :started
                                               :server-map {:socket :mock}
                                               :host "localhost"
                                               :port 7888
                                               :connection "localhost:7888"
                                               :started-at 1000}))]
                    (is (thrown? Exception (transition state {:type :start :config {}})))))

         (testing ":start from :starting throws"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}}))]
                    (is (thrown? Exception (transition state {:type :start :config {}})))))

         (testing ":stop from :starting throws"
                  (let [state (-> (init-state)
                                  (transition {:type :start :config {}}))]
                    (is (thrown? Exception (transition state {:type :stop}))))))

;; =============================================================================
;; Full Lifecycle Test
;; =============================================================================

(deftest full-lifecycle-test
         (testing "complete lifecycle: stopped -> starting -> running -> stopping -> stopped"
                  (let [s0 (init-state)
                        _ (is (= :stopped (:_state s0)))

                        s1 (transition s0 {:type :start :config {:port 7888}})
                        _ (is (= :starting (:_state s1)))

                        s2 (transition s1 {:type :started
                                           :server-map {:socket :mock}
                                           :host "localhost"
                                           :port 7888
                                           :connection "localhost:7888"
                                           :started-at 1000})
                        _ (is (= :running (:_state s2)))
                        _ (is (= 7888 (:port s2)))

                        s3 (transition s2 {:type :stop})
                        _ (is (= :stopping (:_state s3)))

                        s4 (transition s3 {:type :stopped :stopped-at 2000})
                        _ (is (= :stopped (:_state s4)))
                        _ (is (= 2000 (:stopped-at s4)))
                        _ (is (nil? (:server-map s4)))
          ;; Can start again after full cycle
                        s5 (transition s4 {:type :start :config {:port 9999}})]
                    (is (= :starting (:_state s5)))
                    (is (= {:port 9999} (:config s5))))))

(deftest error-recovery-lifecycle-test
         (testing "error -> start -> running (recovery from error state)"
                  (let [s0 (init-state)
                        s1 (transition s0 {:type :start :config {:port 7888}})
                        s2 (transition s1 {:type :failed :error "port in use"})
                        _ (is (= :error (:_state s2)))
                        _ (is (= "port in use" (:error s2)))

          ;; Recover by starting with different config
                        s3 (transition s2 {:type :start :config {:port 9999}})
                        _ (is (= :starting (:_state s3)))
                        _ (is (nil? (:error s3)))

                        s4 (transition s3 {:type :started
                                           :server-map {:socket :mock}
                                           :host "localhost"
                                           :port 9999
                                           :connection "localhost:9999"
                                           :started-at 3000})]
                    (is (= :running (:_state s4)))
                    (is (= 9999 (:port s4))))))

;; =============================================================================
;; Assign Action Tests
;; =============================================================================

(deftest assign-config-test
         (testing "assign-config stores config and clears error"
                  (let [ctx {:error "old error" :config {} :other "data"}
                        event {:config {:port 7888}}
                        result (sut/assign-config ctx event)]
                    (is (= {:port 7888} (:config result)))
                    (is (nil? (:error result)))
                    (is (= "data" (:other result))))))

(deftest assign-server-info-test
         (testing "assign-server-info merges server details into context"
                  (let [ctx {:config {:port 7888}}
                        event {:server-map {:socket :mock}
                               :host "localhost"
                               :port 7888
                               :connection "localhost:7888"
                               :started-at 1000
                               :extra "ignored"}
                        result (sut/assign-server-info ctx event)]
                    (is (= {:socket :mock} (:server-map result)))
                    (is (= "localhost" (:host result)))
                    (is (= 7888 (:port result)))
                    (is (= "localhost:7888" (:connection result)))
                    (is (= 1000 (:started-at result)))
                    (is (nil? (:extra result))))))

(deftest assign-error-test
         (testing "assign-error stores error message"
                  (let [ctx {:error nil}
                        result (sut/assign-error ctx {:error "bind failed"})]
                    (is (= "bind failed" (:error result))))))

(deftest assign-stopped-test
         (testing "assign-stopped clears server-map, sets stopped-at, clears error"
                  (let [ctx {:server-map {:socket :mock} :error "old" :stopped-at nil}
                        result (sut/assign-stopped ctx {:stopped-at 2000})]
                    (is (nil? (:server-map result)))
                    (is (= 2000 (:stopped-at result)))
                    (is (nil? (:error result))))))
