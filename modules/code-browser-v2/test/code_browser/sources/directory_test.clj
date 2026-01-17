(ns code-browser.sources.directory-test
    "Integration tests for directory source adapter.

   Tests scanning a real project and populating Datalevin."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [code-browser.sources.protocol :as source-proto]
              [code-browser.sources.directory :as dir]
              [code-browser.db.protocol :as db-proto]
              [code-browser.db.datalevin :as datalevin]
              [code-browser.uri :as uri]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture - Real Project + Temp DB
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db*
     "Dynamic var holding the test database instance."
     nil)

(def ^:dynamic *test-source*
     "Dynamic var holding the test directory source."
     nil)

(def test-project-root
     "Use the bb-mcp-server project itself for testing."
     (System/getProperty "user.dir"))

(defn project-db-fixture
  "Create a temp Datalevin DB and directory source for testing."
  [f]
  (let [temp-dir (str "/tmp/code-browser-dir-test-" (System/currentTimeMillis))
        db (datalevin/create-db {:path temp-dir})
        source (dir/create-directory-source test-project-root)]
    (try
     (binding [*test-db* db
               *test-source* source]
              (f))
     (finally
      (db-proto/close! db)
      (let [dir (java.io.File. temp-dir)]
        (doseq [file (reverse (file-seq dir))]
               (.delete file)))))))

(use-fixtures :each project-db-fixture)

;;; ---------------------------------------------------------------------------
;;; Source Info Tests
;;; ---------------------------------------------------------------------------

(deftest source-info-test
         (testing "DirectorySource provides correct info"
                  (let [info (source-proto/source-info *test-source*)]
                    (is (= :dir (:type info)))
                    (is (= :static (:version-type info)))
                    (is (string? (:description info))))))

;;; ---------------------------------------------------------------------------
;;; Scan Project Tests
;;; ---------------------------------------------------------------------------

(deftest scan-project-test
         (testing "Can scan a real project"
                  (let [result (source-proto/scan-project *test-source*)]
                    (is (some? result) "Scan should return results")
                    (is (map? (:project result)) "Should have project entity")
                    (is (seq (:namespaces result)) "Should find namespaces")
                    (is (seq (:symbols result)) "Should find symbols")))

         (testing "Project entity has correct structure"
                  (let [{:keys [project]} (source-proto/scan-project *test-source*)]
                    (is (= :dir (:uri/source project)))
                    (is (string? (:uri/string project)))
                    (is (string? (:uri/project project)))
                    (is (string? (:uri/version project)))
                    (is (= :static (:uri/version-type project)))
                    (is (string? (:project/root-path project)))))

         (testing "Namespace entities have correct structure"
                  (let [{:keys [namespaces]} (source-proto/scan-project *test-source*)
                        ns-entity (first namespaces)]
                    (is (string? (:uri/string ns-entity)))
                    (is (string? (:ns/name ns-entity)))
                    (is (uri/valid? (:uri/string ns-entity)))))

         (testing "Symbol entities have correct structure"
                  (let [{:keys [symbols]} (source-proto/scan-project *test-source*)
                        sym-entity (first symbols)]
                    (is (string? (:uri/string sym-entity)))
                    (is (string? (:symbol/name sym-entity)))
                    (is (keyword? (:symbol/type sym-entity)))
                    (is (uri/valid? (:uri/string sym-entity))))))

;;; ---------------------------------------------------------------------------
;;; Datalevin Integration Tests
;;; ---------------------------------------------------------------------------

(defn- clean-entity
  "Remove nil values and convert lookup refs to just URI strings for cleaner transact.
   Datalevin has issues with lookup refs in batch transacts via pod."
  [entity]
  (->> entity
       (remove (fn [[_k v]] (nil? v)))
       (remove (fn [[k _v]] (= k :uri/parent)))  ;; Skip parent refs for now
       (into {})))

(defn- populate-db!
  "Helper to scan project and populate database."
  [db source]
  (let [{:keys [project namespaces symbols]} (source-proto/scan-project source)]
    ;; Transact project
    (db-proto/transact! db [(clean-entity project)])
    ;; Transact namespaces (clean and batch)
    (doseq [ns-batch (partition-all 50 namespaces)]
           (db-proto/transact! db (mapv clean-entity ns-batch)))
    ;; Transact symbols in batches
    (doseq [sym-batch (partition-all 100 symbols)]
           (db-proto/transact! db (mapv clean-entity sym-batch)))
    {:project-count 1
     :namespace-count (count namespaces)
     :symbol-count (count symbols)}))

(deftest populate-datalevin-test
         (testing "Can populate Datalevin from directory scan"
                  (let [counts (populate-db! *test-db* *test-source*)]
                    (is (pos? (:namespace-count counts)))
                    (is (pos? (:symbol-count counts)))))

         (testing "Can query namespaces from Datalevin"
                  (populate-db! *test-db* *test-source*)
                  (let [results (db-proto/q *test-db*
                                            '[:find ?name
                                              :where [?e :ns/name ?name]])]
                    (is (seq results) "Should find namespaces")
            ;; Should include some known namespaces from bb-mcp-server
                    (let [ns-names (set (map first results))]
                      (is (contains? ns-names "bb-mcp-server.main")
                          "Should find bb-mcp-server.main namespace"))))

         (testing "Can query symbols from Datalevin"
                  (populate-db! *test-db* *test-source*)
                  (let [results (db-proto/q *test-db*
                                            '[:find ?name ?type
                                              :where
                                              [?e :symbol/name ?name]
                                              [?e :symbol/type ?type]])]
                    (is (seq results) "Should find symbols")
            ;; Check we have various symbol types
                    (let [types (set (map second results))]
                      (is (contains? types :defn) "Should have defn symbols")))))

(deftest query-by-uri-test
         (testing "Can look up entities by URI"
                  (let [{:keys [project namespaces]} (source-proto/scan-project *test-source*)
                        project-uri (:uri/string project)
                        first-ns-uri (:uri/string (first namespaces))]
            ;; Populate
                    (db-proto/transact! *test-db* [project])
                    (db-proto/transact! *test-db* (vec (take 10 namespaces)))

            ;; Query by URI
                    (let [proj-entity (db-proto/pull *test-db* '[*] [:uri/string project-uri])]
                      (is (some? proj-entity))
                      (is (= :dir (:uri/source proj-entity))))

                    (let [ns-entity (db-proto/pull *test-db* '[*] [:uri/string first-ns-uri])]
                      (is (some? ns-entity))
                      (is (string? (:ns/name ns-entity)))))))

;;; ---------------------------------------------------------------------------
;;; URI Validation Tests
;;; ---------------------------------------------------------------------------

(deftest uri-structure-test
         (testing "All generated URIs are valid"
                  (let [{:keys [project namespaces symbols]} (source-proto/scan-project *test-source*)]
            ;; Project URI
                    (is (uri/valid? (:uri/string project)))

            ;; All namespace URIs
                    (doseq [ns-entity namespaces]
                           (is (uri/valid? (:uri/string ns-entity))
                               (str "Invalid namespace URI: " (:uri/string ns-entity))))

            ;; Sample of symbol URIs (checking all would be slow)
                    (doseq [sym-entity (take 100 symbols)]
                           (is (uri/valid? (:uri/string sym-entity))
                               (str "Invalid symbol URI: " (:uri/string sym-entity)))))))
