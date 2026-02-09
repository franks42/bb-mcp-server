(ns mcp-nrepl.state.local-nrepl-server-integration-test
    "Integration tests for local nREPL server lifecycle.
   These tests start and stop real nREPL servers to verify the
   statecharts-backed API works end-to-end."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [mcp-nrepl.state.local-nrepl-server :as sut]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset state atom before each test and ensure cleanup after."
  [f]
  (sut/reset-state!)
  (try
   (f)
   (finally
    (when (sut/running?)
      (sut/stop-server!))
    (sut/reset-state!))))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Start / Stop Lifecycle
;; =============================================================================

(deftest start-server-integration-test
         (testing "start-server! starts a real nREPL server"
                  (let [result (sut/start-server! {:port 0})]
                    (is (= :running (:status result)))
                    (is (= "localhost" (:host result)))
                    (is (pos-int? (:port result)))
                    (is (string? (:connection result)))
                    (is (pos-int? (:started-at result)))))

         (testing "state is :running after start"
                  (is (sut/running?))
                  (is (= :running (sut/get-status))))

         (testing "connection info reflects running server"
                  (let [info (sut/get-connection-info)]
                    (is (= :running (:status info)))
                    (is (pos-int? (:port info)))
                    (is (= "localhost" (:host info)))
                    (is (string? (:connection info))))))

(deftest stop-server-integration-test
         (testing "stop-server! stops a running server"
                  (sut/start-server! {:port 0})
                  (let [result (sut/stop-server!)]
                    (is (= :stopped (:status result)))
                    (is (pos-int? (:stopped-at result)))))

         (testing "state is :stopped after stop"
                  (is (not (sut/running?)))
                  (is (= :stopped (sut/get-status)))))

(deftest full-lifecycle-integration-test
         (testing "stopped -> start -> running -> stop -> stopped"
                  (is (= :stopped (sut/get-status)))

                  (let [start-result (sut/start-server! {:port 0})
                        port (:port start-result)]
                    (is (= :running (sut/get-status)))
                    (is (pos-int? port))

                    (let [stop-result (sut/stop-server!)]
                      (is (= :stopped (sut/get-status)))
                      (is (= :stopped (:status stop-result)))))))

;; =============================================================================
;; Invalid Transition Tests (real server)
;; =============================================================================

(deftest double-start-integration-test
         (testing "starting an already-running server throws"
                  (sut/start-server! {:port 0})
                  (is (thrown-with-msg? Exception
                                        #"unknown event :start when in state :running"
                                        (sut/start-server! {:port 0})))
                  (is (sut/running?) "server should still be running after failed double-start")))

(deftest double-stop-integration-test
         (testing "stopping an already-stopped server throws"
                  (is (thrown-with-msg? Exception
                                        #"unknown event :stop when in state :stopped"
                                        (sut/stop-server!)))))

;; =============================================================================
;; Restart Tests
;; =============================================================================

(deftest restart-from-stopped-integration-test
         (testing "restart from stopped state starts fresh"
                  (let [result (sut/restart-server! {:port 0})]
                    (is (= :running (:status result)))
                    (is (sut/running?)))))

(deftest restart-from-running-integration-test
         (testing "restart from running state stops then starts on new port"
                  (sut/start-server! {:port 0})
                  (let [result (sut/restart-server! {:port 0})]
                    (is (= :running (:status result)))
                    (is (pos-int? (:port result)))
                    (is (sut/running?)))))

;; =============================================================================
;; Query Function Tests (with real server)
;; =============================================================================

(deftest get-uptime-integration-test
         (testing "uptime is nil when stopped"
                  (is (nil? (sut/get-uptime-ms))))

         (testing "uptime is positive when running"
                  (sut/start-server! {:port 0})
                  (Thread/sleep 10)
                  (let [uptime (sut/get-uptime-ms)]
                    (is (pos-int? uptime))
                    (is (>= uptime 10)))))

(deftest get-server-map-integration-test
         (testing "server-map is nil when stopped"
                  (is (nil? (sut/get-server-map))))

         (testing "server-map has :socket when running"
                  (sut/start-server! {:port 0})
                  (let [sm (sut/get-server-map)]
                    (is (some? sm))
                    (is (some? (:socket sm))))))

(deftest get-full-state-integration-test
         (testing "full state includes :_state and context fields"
                  (sut/start-server! {:port 0})
                  (let [state (sut/get-full-state)]
                    (is (= :running (:_state state)))
                    (is (some? (:server-map state)))
                    (is (pos-int? (:port state)))
                    (is (= "localhost" (:host state)))
                    (is (string? (:connection state))))))

;; =============================================================================
;; Reset Tests
;; =============================================================================

(deftest reset-state-integration-test
         (testing "reset-state! returns to initial :stopped state"
                  (sut/start-server! {:port 0})
                  (is (sut/running?))
    ;; Stop the actual server first, then reset state
                  (sut/stop-server!)
                  (sut/reset-state!)
                  (is (= :stopped (sut/get-status)))
                  (is (nil? (:server-map (sut/get-full-state))))))
