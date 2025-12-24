(ns sente-browser.server-test
    "Unit tests for sente-browser server state management.

   Tests the pure state functions without requiring WebSocket connections."
    (:require [clojure.test :refer [deftest is testing]]
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
