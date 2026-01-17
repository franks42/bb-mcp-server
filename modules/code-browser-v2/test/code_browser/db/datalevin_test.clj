(ns code-browser.db.datalevin-test
    "Integration tests for Datalevin backend.

   These tests use real Datalevin with a temp directory."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [code-browser.db.protocol :as proto]
              [code-browser.db.datalevin :as datalevin]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture - Temp DB
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db*
     "Dynamic var holding the test database instance."
     nil)

(defn temp-db-fixture
  "Create a temp Datalevin DB for each test."
  [f]
  (let [temp-dir (str "/tmp/code-browser-test-" (System/currentTimeMillis))
        db (datalevin/create-db {:path temp-dir})]
    (try
     (binding [*test-db* db]
              (f))
     (finally
      (proto/close! db)
        ;; Clean up temp directory
      (let [dir (java.io.File. temp-dir)]
        (doseq [file (reverse (file-seq dir))]
               (.delete file)))))))

(use-fixtures :each temp-db-fixture)

;;; ---------------------------------------------------------------------------
;;; Basic Operations Tests
;;; ---------------------------------------------------------------------------

(deftest transact-and-pull-test
         (testing "Can transact and pull a project entity"
                  (let [project-data {:uri/string "dir://test-project@abc123"
                                      :uri/source :dir
                                      :uri/project "test-project"
                                      :uri/version "abc123"
                                      :uri/version-type :static
                                      :project/root-path "/tmp/test-project"}]
      ;; Transact
                    (proto/transact! *test-db* [project-data])

      ;; Pull by lookup ref
                    (let [result (proto/pull *test-db* '[*] [:uri/string "dir://test-project@abc123"])]
                      (is (some? result) "Should find the entity")
                      (is (= "test-project" (:uri/project result)))
                      (is (= :dir (:uri/source result)))
                      (is (= "/tmp/test-project" (:project/root-path result)))))))

(deftest transact-namespace-test
         (testing "Can transact namespace with ref to project"
    ;; First create project
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://proj@v1"
                                     :uri/source :dir
                                     :uri/project "proj"
                                     :uri/version "v1"}])

    ;; Create namespace with parent ref
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://proj@v1/my.ns"
                                     :uri/source :dir
                                     :uri/project "proj"
                                     :uri/version "v1"
                                     :uri/namespace "my.ns"
                                     :uri/parent [:uri/string "dir://proj@v1"]
                                     :ns/name "my.ns"
                                     :ns/file "src/my/ns.clj"}])

                  (let [ns-entity (proto/pull *test-db* '[*] [:uri/string "dir://proj@v1/my.ns"])]
                    (is (= "my.ns" (:ns/name ns-entity)))
                    (is (= "src/my/ns.clj" (:ns/file ns-entity))))))

(deftest transact-symbol-test
         (testing "Can transact symbol with metadata"
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://proj@v1/my.ns/my-fn"
                                     :uri/source :dir
                                     :uri/project "proj"
                                     :uri/version "v1"
                                     :uri/namespace "my.ns"
                                     :uri/symbol "my-fn"
                                     :symbol/name "my-fn"
                                     :symbol/type :defn
                                     :symbol/file "src/my/ns.clj"
                                     :symbol/line 42
                                     :symbol/end-line 55
                                     :symbol/doc "My function docstring"
                                     :symbol/arglists "([x y])"}])

                  (let [sym (proto/pull *test-db* '[*] [:uri/string "dir://proj@v1/my.ns/my-fn"])]
                    (is (= "my-fn" (:symbol/name sym)))
                    (is (= :defn (:symbol/type sym)))
                    (is (= 42 (:symbol/line sym)))
                    (is (= 55 (:symbol/end-line sym)))
                    (is (= "My function docstring" (:symbol/doc sym))))))

;;; ---------------------------------------------------------------------------
;;; Query Tests
;;; ---------------------------------------------------------------------------

(deftest query-test
         (testing "Can execute Datalog queries"
    ;; Insert test data
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://p1@v1"
                                     :uri/source :dir
                                     :uri/project "p1"
                                     :uri/version "v1"}
                                    {:uri/string "dir://p2@v1"
                                     :uri/source :dir
                                     :uri/project "p2"
                                     :uri/version "v1"}])

    ;; Query all projects
                  (let [results (proto/q *test-db*
                                         '[:find ?project
                                           :where [?e :uri/project ?project]])]
                    (is (= #{["p1"] ["p2"]} (set results))))))

(deftest query-with-args-test
         (testing "Can execute queries with input arguments"
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://proj@v1/ns1/sym1"
                                     :symbol/name "sym1"
                                     :symbol/type :defn}
                                    {:uri/string "dir://proj@v1/ns1/sym2"
                                     :symbol/name "sym2"
                                     :symbol/type :def}
                                    {:uri/string "dir://proj@v1/ns2/sym1"
                                     :symbol/name "sym1"
                                     :symbol/type :defmacro}])

    ;; Find all symbols named "sym1"
                  (let [results (proto/q *test-db*
                                         '[:find ?uri ?type
                                           :in $ ?name
                                           :where
                                           [?e :symbol/name ?name]
                                           [?e :uri/string ?uri]
                                           [?e :symbol/type ?type]]
                                         ["sym1"])]
                    (is (= 2 (count results)))
                    (is (contains? (set results) ["dir://proj@v1/ns1/sym1" :defn]))
                    (is (contains? (set results) ["dir://proj@v1/ns2/sym1" :defmacro])))))

;;; ---------------------------------------------------------------------------
;;; Convenience Function Tests
;;; ---------------------------------------------------------------------------

(deftest uri->entity-test
         (testing "uri->entity retrieves full entity"
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://proj@v1"
                                     :uri/source :dir
                                     :uri/project "proj"
                                     :uri/version "v1"
                                     :project/root-path "/home/user/proj"}])

                  (let [entity (datalevin/uri->entity *test-db* "dir://proj@v1")]
                    (is (= "proj" (:uri/project entity)))
                    (is (= "/home/user/proj" (:project/root-path entity))))))

(deftest db-returns-value-test
         (testing "db returns database value"
                  (is (some? (proto/db *test-db*)))))
