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
    ;; Insert test data with unique email for this test
                  (dl/transact! [{:person/name "QueryTestAlice" :person/email "query-test@example.com"}])

                  (let [result (mcp/q-handler {:query "[:find ?name :where [?e :person/name ?name] [?e :person/email \"query-test@example.com\"]]"})]
                    (is (vector? (:result result)))
                    (is (= 1 (:count result)))
                    (is (= [["QueryTestAlice"]] (:result result))))))

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
;; Pull Tool Tests
;; =============================================================================

(deftest ^:integration test-pull-handler-by-id
         (testing "Pull handler retrieves entity by ID"
                  ;; Insert test data
                  (dl/transact! [{:person/name "Charlie" :person/email "charlie@example.com"}])

                  ;; Get the entity ID from a query
                  (let [query-result (dl/q '[:find ?e :where [?e :person/name "Charlie"]])
                        eid (ffirst query-result)
                        result (mcp/pull-handler {:eid (str eid)})]
                    (is (:found result))
                    (is (= "Charlie" (get-in result [:entity :person/name])))
                    (is (= "charlie@example.com" (get-in result [:entity :person/email]))))))

(deftest ^:integration test-pull-handler-with-pattern
         (testing "Pull handler respects attribute pattern"
                  (dl/transact! [{:person/name "Diana" :person/email "diana@example.com"}])

                  (let [query-result (dl/q '[:find ?e :where [?e :person/name "Diana"]])
                        eid (ffirst query-result)
                        result (mcp/pull-handler {:eid (str eid)
                                                  :pattern "[:person/name]"})]
                    (is (:found result))
                    (is (= "Diana" (get-in result [:entity :person/name])))
                    ;; Email should not be in result since not in pattern
                    (is (nil? (get-in result [:entity :person/email]))))))

(deftest ^:integration test-pull-handler-invalid-eid
         (testing "Pull handler returns error for invalid EDN"
                  (let [result (mcp/pull-handler {:eid "[invalid ::"})]
                    (is (:error result))
                    (is (re-find #"Failed to parse" (:error result))))))

(deftest ^:integration test-pull-handler-not-found
         (testing "Pull handler returns entity with only :db/id for non-existent entity"
                  (let [result (mcp/pull-handler {:eid "999999999"})]
                    ;; Should not error
                    (is (not (:error result)))
                    ;; Datalevin returns {:db/id eid} for non-existent entities
                    ;; Check that there are no other attributes besides :db/id
                    (is (= 1 (count (keys (:entity result))))))))

;; =============================================================================
;; Find-by Tool Tests
;; =============================================================================

(deftest ^:integration test-find-by-handler-success
         (testing "Find-by handler finds entities by attribute"
                  (dl/transact! [{:person/name "Eve" :person/email "eve@example.com"}
                                 {:person/name "Frank" :person/email "frank@example.com"}])

                  (let [result (mcp/find-by-handler {:attribute ":person/email"
                                                     :value "\"eve@example.com\""})]
                    (is (= 1 (:count result)))
                    (is (= 1 (:total-matches result)))
                    (is (= "Eve" (get-in result [:entities 0 :person/name]))))))

(deftest ^:integration test-find-by-handler-multiple-results
         (testing "Find-by handler finds multiple entities"
                  (dl/transact! [{:person/name "George" :person/role "admin"}
                                 {:person/name "Hannah" :person/role "admin"}])

                  (let [result (mcp/find-by-handler {:attribute ":person/role"
                                                     :value "\"admin\""})]
                    (is (>= (:count result) 2))
                    (is (>= (:total-matches result) 2)))))

(deftest ^:integration test-find-by-handler-with-limit
         (testing "Find-by handler respects limit"
                  (dl/transact! [{:person/name "User1" :person/group "test"}
                                 {:person/name "User2" :person/group "test"}
                                 {:person/name "User3" :person/group "test"}])

                  (let [result (mcp/find-by-handler {:attribute ":person/group"
                                                     :value "\"test\""
                                                     :limit 2})]
                    (is (= 2 (:count result)))
                    (is (>= (:total-matches result) 3)))))

(deftest ^:integration test-find-by-handler-no-results
         (testing "Find-by handler returns empty for no matches"
                  (let [result (mcp/find-by-handler {:attribute ":person/email"
                                                     :value "\"nonexistent@example.com\""})]
                    (is (= 0 (:count result)))
                    (is (= 0 (:total-matches result)))
                    (is (empty? (:entities result))))))

(deftest ^:integration test-find-by-handler-invalid-attribute
         (testing "Find-by handler returns error for invalid attribute EDN"
                  (let [result (mcp/find-by-handler {:attribute "[invalid"
                                                     :value "\"test\""})]
                    (is (:error result)))))

;; =============================================================================
;; Module Lifecycle Tests
;; =============================================================================

(deftest test-tool-definitions
         (testing "Tool definitions have required fields"
                  (doseq [tool [mcp/schema-tool mcp/q-tool mcp/transact-tool
                                mcp/pull-tool mcp/find-by-tool]]
                         (is (string? (:name tool)))
                         (is (string? (:description tool)))
                         (is (= "datalevin-mcp" (:module tool)))
                         (is (map? (:inputSchema tool)))
                         (is (fn? (:handler tool))))))

(deftest test-status
         (testing "Status returns expected structure"
                  (let [status (mcp/status nil)]
                    (is (= :ok (:status status)))
                    (is (= ["schema" "q" "transact" "pull" "find-by"] (:registered-tools status)))
                    (is (boolean? (:db-connected status))))))
