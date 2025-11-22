(ns nrepl.state.connection-test
    "Tests for nREPL connection state management"
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [nrepl.state.connection :as conn]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-state-fixture
  "Reset connection state before each test"
  [f]
  (reset! conn/connection-state {:connections {}
                                 :active-connection nil
                                 :nicknames {}
                                 :connection-counter 0})
  (f)
  ;; Cleanup after test
  (reset! conn/connection-state {:connections {}
                                 :active-connection nil
                                 :nicknames {}
                                 :connection-counter 0}))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; Connection Registration Tests
;; =============================================================================

(deftest register-connection-test
         (testing "register-connection! creates connection entry"
                  (let [mock-socket (Object.)  ;; Use mock object for socket
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)]
                    (is (string? conn-id) "Should return connection ID string")
      ;; Human-readable format: IP:port-UUID
                    (is (re-matches #"[\d\.]+:7888-.+" conn-id) "Connection ID should be IP:port-UUID format")))

         (testing "register-connection! sets active connection"
                  (let [mock-socket (Object.)
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)]
                    (is (= conn-id (:active-connection @conn/connection-state))
                        "Should set as active connection")))

         (testing "register-connection! increments counter"
    ;; Get counter before creating connections within this test
                  (let [initial-counter (:connection-counter @conn/connection-state)
                        mock-socket1 (Object.)
                        mock-socket2 (Object.)
                        _conn1 (conn/register-connection! "localhost" 7900 mock-socket1)
                        _conn2 (conn/register-connection! "localhost" 7901 mock-socket2)]
                    (is (= (+ initial-counter 2) (:connection-counter @conn/connection-state))
                        "Counter should increment by 2 for two new connections"))))

;; =============================================================================
;; Connection Retrieval Tests
;; =============================================================================

(deftest get-connection-test
         (testing "get-connection-by-id returns connection details"
                  (let [mock-socket (Object.)
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)
                        conn-details (conn/get-connection-by-id conn-id)]
                    (is (map? conn-details) "Should return map")
                    (is (= "localhost" (:hostname conn-details)))
                    (is (= 7888 (:port conn-details)))
                    (is (= :connected (:status conn-details)))))

         (testing "get-connection-by-id returns nil for unknown ID"
                  (is (nil? (conn/get-connection-by-id "conn-999"))
                      "Should return nil for unknown connection"))

         (testing "get-active-connection returns active connection"
                  (let [mock-socket (Object.)
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)
                        active (conn/get-active-connection)]
                    (is (map? active) "Should return connection map")
                    (is (= conn-id (:connection-id active))))))

;; =============================================================================
;; Connection Status Tests
;; =============================================================================

(deftest connection-status-test
         (testing "mark-connection-closed! updates status"
                  (let [mock-socket (Object.)
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)]
                    (conn/mark-connection-closed! conn-id :test-close "Test closure")
                    (let [conn-details (conn/get-connection-by-id conn-id)]
                      (is (= :closed (:status conn-details)) "Status should be :closed")
        ;; Error info stored in :error {:type :message}
                      (is (= :test-close (get-in conn-details [:error :type])) "Error type should be set")
                      (is (= "Test closure" (get-in conn-details [:error :message])) "Error message should be set"))))

         (testing "get-connection-status returns current status"
                  (let [mock-socket (Object.)
                        _conn-id (conn/register-connection! "localhost" 7888 mock-socket)]
                    (is (= :connected (conn/get-connection-status))
                        "Should return :connected for active connection"))))

;; =============================================================================
;; Multi-Connection Tests
;; =============================================================================

(deftest multi-connection-test
         (testing "list-all-connections returns all connections"
                  (let [mock-socket1 (Object.)
                        mock-socket2 (Object.)
                        conn1 (conn/register-connection! "localhost" 7888 mock-socket1)
                        conn2 (conn/register-connection! "localhost" 7889 mock-socket2)
                        all-conns (conn/list-all-connections)]
                    (is (= 2 (count all-conns)) "Should have 2 connections")
                    (is (contains? all-conns conn1) "Should contain first connection")
                    (is (contains? all-conns conn2) "Should contain second connection")))

         (testing "last registered connection becomes active"
                  (let [mock-socket1 (Object.)
                        mock-socket2 (Object.)
                        _conn1 (conn/register-connection! "localhost" 7888 mock-socket1)
                        conn2 (conn/register-connection! "localhost" 7889 mock-socket2)]
      ;; conn2 should be active (last registered)
                    (is (= conn2 (:active-connection @conn/connection-state))
                        "Last registered connection should be active"))))

;; =============================================================================
;; Cleanup Tests
;; =============================================================================

(deftest cleanup-test
         (testing "cleanup-old-connections! removes closed connections"
                  (let [mock-socket (Object.)
                        conn-id (conn/register-connection! "localhost" 7888 mock-socket)]
      ;; Close the connection
                    (conn/mark-connection-closed! conn-id :test-close "Test")
      ;; Cleanup with 0 threshold (immediate)
                    (conn/cleanup-old-connections! :threshold-ms 0)
                    (is (nil? (conn/get-connection-by-id conn-id))
                        "Closed connection should be removed"))))
