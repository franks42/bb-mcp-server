(ns code-browser.handlers-test
    "Tests for Code Browser v2 handlers.

   Verifies that queries are version-agnostic — matching on semantic fields
   (:uri/project, :uri/namespace, :symbol/name) rather than exact URI strings.
   This ensures widgets survive version-hash changes from re-indexing or restarts."
    (:require [clojure.string :as str]
              [clojure.test :refer [deftest testing is use-fixtures]]
              [code-browser.db.protocol :as proto]
              [code-browser.db.datalevin :as datalevin]
              [code-browser.handlers :as handlers]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture - Temp DB + Module State
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db*
     "Dynamic var holding the test database instance."
     nil)

(defn temp-db-fixture
  "Create a temp Datalevin DB and wire it into handlers module state."
  [f]
  (let [temp-dir (str "/tmp/code-browser-handlers-test-" (System/currentTimeMillis))
        db (datalevin/create-db {:path temp-dir})]
    (try
     (binding [*test-db* db]
              (handlers/set-db! db)
              (f))
     (finally
      (handlers/set-db! nil)
      (proto/close! db)
      (let [dir (java.io.File. temp-dir)]
        (doseq [file (reverse (file-seq dir))]
               (.delete file)))))))

(use-fixtures :each temp-db-fixture)

;;; ---------------------------------------------------------------------------
;;; Test Data
;;; ---------------------------------------------------------------------------

(def ^:private v1-project
     "Project entity with version hash v1."
     {:uri/string "dir://test-proj@abc111"
      :uri/source :dir
      :uri/project "test-proj"
      :uri/version "abc111"
      :uri/version-type :static
      :project/root-path "/tmp/test-proj"})

(defn- make-ns-entity
  "Build a namespace entity for a given version."
  [version ns-name]
  {:uri/string (str "dir://test-proj@" version "/" ns-name)
   :uri/source :dir
   :uri/project "test-proj"
   :uri/version version
   :uri/namespace ns-name
   :ns/name ns-name
   :ns/file (str "src/" (str/replace ns-name "." "/") ".clj")})

(defn- make-symbol-entity
  "Build a symbol entity for a given version."
  [version ns-name sym-name sym-type]
  {:uri/string (str "dir://test-proj@" version "/" ns-name "/" sym-name)
   :uri/source :dir
   :uri/project "test-proj"
   :uri/version version
   :uri/namespace ns-name
   :uri/symbol sym-name
   :symbol/name sym-name
   :symbol/type sym-type
   :symbol/file (str "src/" (str/replace ns-name "." "/") ".clj")
   :symbol/line 10})

;;; ---------------------------------------------------------------------------
;;; Version-Agnostic Query Tests
;;; ---------------------------------------------------------------------------

(deftest query-namespaces-by-project-name-test
         (testing "Namespaces found by project name regardless of version hash"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (make-ns-entity "abc111" "test-proj.util")])
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@abc111"
                                 :property :ns-list})]
                    (is (:success result))
                    (is (= 2 (count (:data result))))
                    (is (= #{"test-proj.core" "test-proj.util"}
                           (set (map :ns/name (:data result))))))))

(deftest query-namespaces-stale-version-test
         (testing "Namespaces found even with outdated version hash in URI"
                  ;; Index data with version abc111
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (make-ns-entity "abc111" "test-proj.util")])
                  ;; Query with a URI containing a DIFFERENT version hash
                  ;; This simulates a browser widget holding a stale URI after re-index
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@STALE999"
                                 :property :ns-list})]
                    (is (:success result))
                    (is (= 2 (count (:data result)))
                        "Should find namespaces by project name, not URI string"))))

(deftest query-symbols-version-agnostic-test
         (testing "Symbols found by project+namespace name, not URI string"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (make-symbol-entity "abc111" "test-proj.core" "my-fn" :defn)
                                    (make-symbol-entity "abc111" "test-proj.core" "my-var" :def)])
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@abc111/test-proj.core"
                                 :property :symbol-list})]
                    (is (:success result))
                    (is (= 2 (count (:data result))))
                    (is (= #{"my-fn" "my-var"}
                           (set (map :symbol/name (:data result))))))))

(deftest query-symbols-stale-version-test
         (testing "Symbols found even with stale version hash in URI"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (make-symbol-entity "abc111" "test-proj.core" "my-fn" :defn)])
                  ;; Query with stale version hash — should still work
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@STALE999/test-proj.core"
                                 :property :symbol-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result)))
                        "Should find symbols by project+ns name, not URI string"))))

(deftest doc-property-finds-by-symbol-name-test
         (testing ":doc property finds symbol by name, not by URI string"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (assoc (make-symbol-entity "abc111" "test-proj.core" "my-fn" :defn)
                                           :symbol/doc "My function docstring"
                                           :symbol/arglists "([x y])")])
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@abc111/test-proj.core/my-fn"
                                 :property :doc})]
                    (is (:success result))
                    (is (some? (:data result)))
                    (is (= "my-fn" (:symbol/name (:data result))))
                    (is (= "My function docstring" (:symbol/doc (:data result)))))))

(deftest doc-property-stale-version-test
         (testing ":doc finds symbol even with stale version hash"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    (assoc (make-symbol-entity "abc111" "test-proj.core" "my-fn" :defn)
                                           :symbol/doc "Docstring here")])
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@STALE999/test-proj.core/my-fn"
                                 :property :doc})]
                    (is (:success result))
                    (is (= "my-fn" (:symbol/name (:data result)))
                        "Should find symbol by name, not by exact URI string"))))

(deftest aliases-version-agnostic-test
         (testing ":aliases uses ns-name from parsed URI, not DB lookup"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    {:uri/string "dir://test-proj@abc111/test-proj.core#alias:str"
                                     :alias/from-ns "test-proj.core"
                                     :alias/name "str"
                                     :alias/to-ns "clojure.string"}])
                  ;; Use stale version — aliases should still resolve
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@STALE999/test-proj.core"
                                 :property :aliases})]
                    (is (:success result))
                    (is (= 1 (count (:data result))))
                    (is (= "str" (:alias/name (first (:data result))))))))

(deftest refers-version-agnostic-test
         (testing ":refers uses ns-name from parsed URI, not DB lookup"
                  (proto/transact! *test-db*
                                   [v1-project
                                    (make-ns-entity "abc111" "test-proj.core")
                                    {:uri/string "dir://test-proj@abc111/test-proj.core#refer:join"
                                     :refer/from-ns "test-proj.core"
                                     :refer/symbol "join"
                                     :refer/from-ns-source "clojure.string"}])
                  (let [result (handlers/handle-fetch
                                {:uri "dir://test-proj@STALE999/test-proj.core"
                                 :property :refers})]
                    (is (:success result))
                    (is (= 1 (count (:data result))))
                    (is (= "join" (:refer/symbol (first (:data result))))))))

(deftest project-list-test
         (testing ":project-list returns all projects"
                  (proto/transact! *test-db* [v1-project])
                  (let [result (handlers/handle-fetch {:uri nil :property :project-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result))))
                    (is (= "test-proj" (:uri/project (first (:data result))))))))

(deftest project-list-dedup-test
         (testing ":project-list deduplicates projects with same :uri/project"
                  (proto/transact! *test-db*
                                   [{:uri/string "dir://test-proj@v1"
                                     :uri/source :dir
                                     :uri/project "test-proj"
                                     :uri/version "v1"
                                     :uri/version-type :static
                                     :project/root-path "/tmp/test"}
                                    {:uri/string "dir://test-proj@v2"
                                     :uri/source :dir
                                     :uri/project "test-proj"
                                     :uri/version "v2"
                                     :uri/version-type :static
                                     :project/root-path "/tmp/test"}])
                  (let [result (handlers/handle-fetch
                                {:uri nil :property :project-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result)))
                        "Should deduplicate by :uri/project name"))))

(deftest handle-fetch-no-db-test
         (testing "Returns error when no database configured"
                  (handlers/set-db! nil)
                  (let [result (handlers/handle-fetch {:uri "dir://proj@v1"})]
                    (is (not (:success result)))
                    (is (= "No database configured" (:error result))))
                  ;; Restore for other tests
                  (handlers/set-db! *test-db*)))
