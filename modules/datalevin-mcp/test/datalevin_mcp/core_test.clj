(ns datalevin-mcp.core-test
    "Tests for datalevin-mcp module.

   These tests verify the MCP tool handlers work correctly with
   the datalevin-pod module."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [datalevin-mcp.core :as mcp]
              [datalevin-pod.core :as dl]
              [babashka.fs :as fs]))

(def test-db-path
     "Temporary database path for tests."
     "/tmp/bb-mcp-server-datalevin-mcp-test")

(defn cleanup-test-db
  "Remove test database directory if it exists."
  []
  (when (fs/exists? test-db-path)
    (fs/delete-tree test-db-path)))

(defn with-test-db
  "Test fixture that starts datalevin-pod, runs test, then stops."
  [f]
  (cleanup-test-db)
  (dl/start nil {:db-path test-db-path})
  (try
   (f)
   (finally
    (dl/stop nil)
    (cleanup-test-db))))

(use-fixtures :each with-test-db)

;; =============================================================================
;; EDN Parsing Tests (no DB required)
;; =============================================================================

(deftest test-parse-edn-valid
         (testing "Valid EDN parses correctly"
                  (is (= {:ok [:find '?name :where ['?e :person/name '?name]]}
                         (#'mcp/parse-edn "[:find ?name :where [?e :person/name ?name]]" "query")))))

(deftest test-parse-edn-invalid
         (testing "Invalid EDN returns error"
                  (let [result (#'mcp/parse-edn "[invalid ::" "query")]
                    (is (:error result))
                    (is (string? (:error result)))
                    (is (re-find #"Failed to parse query" (:error result))))))

;; =============================================================================
;; Schema Tool Tests
;; =============================================================================

(deftest ^:integration test-schema-handler
         (testing "Schema handler returns schema"
                  (let [result (mcp/schema-handler {})]
                    (is (map? (:schema result)))
                    (is (pos? (:attribute-count result)))
                    (is (contains? (:schema result) :conversation/id)))))

;; =============================================================================
;; Query Tool Tests
;; =============================================================================

(deftest ^:integration test-q-handler-simple
         (testing "Simple query returns results"
    ;; Insert test data
                  (dl/transact! [{:person/name "Alice" :person/email "alice@example.com"}])

                  (let [result (mcp/q-handler {:query "[:find ?name :where [?e :person/name ?name]]"})]
                    (is (vector? (:result result)))
                    (is (= 1 (:count result)))
                    (is (= [["Alice"]] (:result result))))))

(deftest ^:integration test-q-handler-invalid-query
         (testing "Invalid query EDN returns error"
                  (let [result (mcp/q-handler {:query "[invalid ::"})]
                    (is (:error result))
                    (is (re-find #"Failed to parse" (:error result))))))

(deftest ^:integration test-q-handler-bad-syntax
         (testing "Malformed Datalog returns error"
                  (let [result (mcp/q-handler {:query "[:find :where]"})]
                    (is (:error result))
                    (is (:hint result)))))

;; =============================================================================
;; Transact Tool Tests
;; =============================================================================

(deftest ^:integration test-transact-handler-success
         (testing "Transact handler inserts data"
                  (let [result (mcp/transact-handler {:tx-data "[{:person/name \"Bob\"}]"})]
                    (is (:success result))
                    (is (= 1 (:tx-data-count result)))
                    (is (pos? (:datoms-added result))))

    ;; Verify data was inserted
                  (let [query-result (dl/q '[:find ?name :where [?e :person/name ?name]])]
                    (is (contains? (set (map first query-result)) "Bob")))))

(deftest ^:integration test-transact-handler-invalid-edn
         (testing "Invalid EDN returns error"
                  (let [result (mcp/transact-handler {:tx-data "[{invalid"})]
                    (is (:error result))
                    (is (re-find #"Failed to parse" (:error result))))))

(deftest ^:integration test-transact-handler-not-vector
         (testing "Non-vector tx-data returns error"
                  (let [result (mcp/transact-handler {:tx-data "{:person/name \"Alice\"}"})]
                    (is (:error result))
                    (is (:hint result)))))

;; =============================================================================
;; Module Lifecycle Tests
;; =============================================================================

(deftest test-tool-definitions
         (testing "Tool definitions have required fields"
                  (doseq [tool [mcp/schema-tool mcp/q-tool mcp/transact-tool]]
                         (is (string? (:name tool)))
                         (is (string? (:description tool)))
                         (is (= "datalevin-mcp" (:module tool)))
                         (is (map? (:inputSchema tool)))
                         (is (fn? (:handler tool))))))

(deftest test-status
         (testing "Status returns expected structure"
                  (let [status (mcp/status nil)]
                    (is (= :ok (:status status)))
                    (is (= ["schema" "q" "transact"] (:registered-tools status)))
                    (is (boolean? (:db-connected status))))))
