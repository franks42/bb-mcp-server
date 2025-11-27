(ns datalevin-pod.core-test
    "Tests for datalevin-pod module.

   Note: These tests require the Datalevin pod to be available.
   The tests use a temporary database path to avoid affecting production data."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [datalevin-pod.core :as dl]
              [babashka.fs :as fs]))

(def test-db-path
     "Temporary database path for tests."
     "/tmp/bb-mcp-server-datalevin-test")

(defn cleanup-test-db
  "Remove test database directory if it exists."
  []
  (when (fs/exists? test-db-path)
    (fs/delete-tree test-db-path)))

(defn with-test-db
  "Test fixture that sets up test database path and cleans up after."
  [f]
  (cleanup-test-db)
  ;; Note: We can't actually set env vars in JVM, so we test the default path behavior
  ;; For real isolation, run tests with BB_MCP_DATALEVIN_PATH=/tmp/... env var set
  (try
   (f)
   (finally
    (cleanup-test-db))))

(use-fixtures :each with-test-db)

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest test-schema-definition
         (testing "Schema is defined with expected attributes"
                  (is (map? dl/schema))
                  (is (contains? dl/schema :conversation/id))
                  (is (contains? dl/schema :turn/id))
                  (is (contains? dl/schema :expert/id))
                  (is (= :db.unique/identity
                         (get-in dl/schema [:conversation/id :db/unique])))))

;; =============================================================================
;; Module Status Tests
;; =============================================================================

(deftest test-status-when-disconnected
         (testing "Status shows disconnected when no connection"
                  ;; After other tests run, pod may be loaded but conn closed
                  ;; So we only check that status is disconnected (no conn)
                  (let [status (dl/status nil)]
                    (is (= :disconnected (:status status)))
                    (is (string? (:db-path status)))
                    (is (= "0.9.27" (:version status))))))

;; =============================================================================
;; Pod Loading Tests (require network access to download pod)
;; =============================================================================

;; Note: The following tests require the Datalevin pod to be downloadable.
;; They are designed to be run manually or in CI with network access.
;; Comment out if running in offline mode.

(deftest ^:integration test-module-lifecycle
         (testing "Module starts and stops correctly"
    ;; Start with test db path via env var
    ;; In real test, set BB_MCP_DATALEVIN_PATH before running
                  (let [start-result (dl/start nil {:db-path test-db-path})]
                    (is (map? start-result))
                    (is (string? (:db-path start-result)))
                    (is (= "0.9.27" (:version start-result)))

                    (testing "Connection is established after start"
                             (is (some? (dl/get-conn)))
                             (let [status (dl/status nil)]
                               (is (= :connected (:status status)))
                               (is (true? (:pod-loaded status)))))

                    (testing "Basic transact and query work"
                             (let [tx-result (dl/transact! [{:person/name "Test User"
                                                             :person/email "test@example.com"}])]
                               (is (map? tx-result))
                               (is (contains? tx-result :tx-data)))

                             (let [results (dl/q '[:find ?name
                                                   :where [?e :person/name ?name]])]
                               (is (seq results))
                               (is (= "Test User" (ffirst results)))))

                    (testing "Module stops cleanly"
                             (dl/stop nil)
                             (is (nil? (dl/get-conn)))
                             (let [status (dl/status nil)]
                               (is (= :disconnected (:status status))))))))

(deftest ^:integration test-transact-and-query
         (testing "Full transact/query cycle"
                  (dl/start nil {:db-path test-db-path})
                  (try
      ;; Insert data
                   (dl/transact! [{:conversation/id "conv-1"
                                   :conversation/title "Test Conversation"}])

                   (dl/transact! [{:turn/id "turn-1"
                                   :turn/role :user
                                   :turn/content "Hello, world!"}])

      ;; Query data back
                   (let [convs (dl/q '[:find ?id ?title
                                       :where
                                       [?e :conversation/id ?id]
                                       [?e :conversation/title ?title]])]
                     (is (= 1 (count convs)))
                     (is (= ["conv-1" "Test Conversation"] (first convs))))

                   (let [turns (dl/q '[:find ?id ?role ?content
                                       :where
                                       [?e :turn/id ?id]
                                       [?e :turn/role ?role]
                                       [?e :turn/content ?content]])]
                     (is (= 1 (count turns)))
                     (is (= "turn-1" (first (first turns)))))

                   (finally
                    (dl/stop nil)))))

(deftest ^:integration test-pull
         (testing "Pull retrieves entity attributes"
                  (dl/start nil {:db-path test-db-path})
                  (try
      ;; Insert an expert
                   (dl/transact! [{:expert/id "expert-1"
                                   :expert/name "Claude"
                                   :expert/provider :anthropic-http
                                   :expert/model "claude-3-opus"}])

      ;; Pull by lookup ref
                   (let [expert (dl/pull '[*] [:expert/id "expert-1"])]
                     (is (map? expert))
                     (is (= "Claude" (:expert/name expert)))
                     (is (= :anthropic-http (:expert/provider expert))))

                   (finally
                    (dl/stop nil)))))
