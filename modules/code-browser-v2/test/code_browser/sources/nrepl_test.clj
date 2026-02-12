(ns code-browser.sources.nrepl-test
    "Integration tests for nREPL source adapter.

   Verifies that nREPL-sourced entities work correctly through the handler
   query pipeline. Uses direct Datalevin transactions (no live nREPL needed)
   to test that the entity format is compatible with existing queries."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [code-browser.db.protocol :as proto]
              [code-browser.db.datalevin :as datalevin]
              [code-browser.handlers :as handlers]
              [code-browser.sources.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture - Temp DB + Module State
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-db*
     "Dynamic var holding the test database instance."
     nil)

(defn temp-db-fixture
  "Create a temp Datalevin DB and wire it into handlers module state."
  [f]
  (let [temp-dir (str "/tmp/code-browser-nrepl-test-" (System/currentTimeMillis))
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
;;; Test Data — nREPL Entity Factories
;;; ---------------------------------------------------------------------------

(defn- clean-entity
  "Remove nil values from entity map (Datalevin rejects nil values)."
  [m]
  (into {} (remove (fn [[_k v]] (nil? v)) m)))

(def ^:private nrepl-project
     "nREPL project entity for testing."
     {:uri/string "nrepl://localhost:7888@01950a3b-1234-7def"
      :uri/source :nrepl
      :uri/project "localhost:7888"
      :uri/version "01950a3b-1234-7def"
      :uri/version-type :temporal
      :project/nrepl-host "localhost:7888"})

(defn- make-nrepl-ns
  "Build an nREPL namespace entity."
  [ns-name]
  (clean-entity
   {:uri/string (str "nrepl://localhost:7888@01950a3b-1234-7def/" ns-name)
    :uri/source :nrepl
    :uri/project "localhost:7888"
    :uri/version "01950a3b-1234-7def"
    :uri/version-type :temporal
    :uri/namespace ns-name
    :ns/name ns-name}))

(defn- make-nrepl-symbol
  "Build an nREPL symbol entity."
  [ns-name sym-name sym-type]
  (clean-entity
   {:uri/string (str "nrepl://localhost:7888@01950a3b-1234-7def/" ns-name "/" sym-name)
    :uri/source :nrepl
    :uri/project "localhost:7888"
    :uri/version "01950a3b-1234-7def"
    :uri/version-type :temporal
    :uri/namespace ns-name
    :uri/symbol sym-name
    :symbol/name sym-name
    :symbol/type sym-type}))

;;; ---------------------------------------------------------------------------
;;; Entity Format Tests
;;; ---------------------------------------------------------------------------

(deftest nrepl-entity-uri-format-test
         (testing "nREPL entity URIs follow expected format"
                  (is (= "nrepl://localhost:7888@01950a3b-1234-7def"
                         (:uri/string nrepl-project)))
                  (let [ns-entity (make-nrepl-ns "my-app.core")]
                    (is (= "nrepl://localhost:7888@01950a3b-1234-7def/my-app.core"
                           (:uri/string ns-entity)))
                    (is (= :nrepl (:uri/source ns-entity))))
                  (let [sym-entity (make-nrepl-symbol "my-app.core" "init" :defn)]
                    (is (= "nrepl://localhost:7888@01950a3b-1234-7def/my-app.core/init"
                           (:uri/string sym-entity)))
                    (is (= :temporal (:uri/version-type sym-entity))))))

;;; ---------------------------------------------------------------------------
;;; Handler Integration Tests
;;; ---------------------------------------------------------------------------

(deftest nrepl-project-appears-in-project-list-test
         (testing "nREPL project shows up in handle-fetch :project-list"
                  (proto/transact! *test-db* [nrepl-project])
                  (let [result (handlers/handle-fetch {:uri nil :property :project-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result))))
                    (is (= "localhost:7888" (:uri/project (first (:data result))))))))

(deftest nrepl-project-coexists-with-dir-project-test
         (testing "nREPL and dir projects both appear in project list"
                  (proto/transact! *test-db*
                                   [nrepl-project
                                    {:uri/string "dir://test-proj@abc111"
                                     :uri/source :dir
                                     :uri/project "test-proj"
                                     :uri/version "abc111"
                                     :uri/version-type :static
                                     :project/root-path "/tmp/test-proj"}])
                  (let [result (handlers/handle-fetch {:uri nil :property :project-list})]
                    (is (:success result))
                    (is (= 2 (count (:data result))))
                    (is (= #{"localhost:7888" "test-proj"}
                           (set (map :uri/project (:data result))))))))

(deftest nrepl-namespaces-queryable-test
         (testing "nREPL namespaces returned by handle-fetch :ns-list"
                  (proto/transact! *test-db*
                                   [nrepl-project
                                    (make-nrepl-ns "my-app.core")
                                    (make-nrepl-ns "my-app.util")])
                  (let [result (handlers/handle-fetch
                                {:uri "nrepl://localhost:7888@01950a3b-1234-7def"
                                 :property :ns-list})]
                    (is (:success result))
                    (is (= 2 (count (:data result))))
                    (is (= #{"my-app.core" "my-app.util"}
                           (set (map :ns/name (:data result))))))))

(deftest nrepl-namespaces-version-agnostic-test
         (testing "nREPL namespaces found with stale version hash"
                  (proto/transact! *test-db*
                                   [nrepl-project
                                    (make-nrepl-ns "my-app.core")])
                  (let [result (handlers/handle-fetch
                                {:uri "nrepl://localhost:7888@STALE_VERSION"
                                 :property :ns-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result)))))))

(deftest nrepl-symbols-queryable-test
         (testing "nREPL symbols returned by handle-fetch :symbol-list"
                  (proto/transact! *test-db*
                                   [nrepl-project
                                    (make-nrepl-ns "my-app.core")
                                    (make-nrepl-symbol "my-app.core" "init" :defn)
                                    (make-nrepl-symbol "my-app.core" "config" :def)
                                    (make-nrepl-symbol "my-app.core" "when-debug" :defmacro)])
                  (let [result (handlers/handle-fetch
                                {:uri "nrepl://localhost:7888@01950a3b-1234-7def/my-app.core"
                                 :property :symbol-list})]
                    (is (:success result))
                    (is (= 3 (count (:data result))))
                    (is (= #{:defn :def :defmacro}
                           (set (map :symbol/type (:data result))))))))

(deftest nrepl-symbols-version-agnostic-test
         (testing "nREPL symbols found with stale version hash"
                  (proto/transact! *test-db*
                                   [nrepl-project
                                    (make-nrepl-ns "my-app.core")
                                    (make-nrepl-symbol "my-app.core" "init" :defn)])
                  (let [result (handlers/handle-fetch
                                {:uri "nrepl://localhost:7888@STALE_VERSION/my-app.core"
                                 :property :symbol-list})]
                    (is (:success result))
                    (is (= 1 (count (:data result)))))))

;;; ---------------------------------------------------------------------------
;;; infer-symbol-type Integration
;;; ---------------------------------------------------------------------------

(deftest infer-symbol-type-produces-correct-entities-test
         (testing "runtime/infer-symbol-type produces valid symbol types for DB"
                  (let [fn-type (runtime/infer-symbol-type {:arglists "([x])"})
                        macro-type (runtime/infer-symbol-type {:macro true})
                        def-type (runtime/infer-symbol-type {})]
                    (proto/transact! *test-db*
                                     [nrepl-project
                                      (make-nrepl-ns "my-app.core")
                                      (make-nrepl-symbol "my-app.core" "my-fn" fn-type)
                                      (make-nrepl-symbol "my-app.core" "my-macro" macro-type)
                                      (make-nrepl-symbol "my-app.core" "my-var" def-type)])
                    (let [result (handlers/handle-fetch
                                  {:uri "nrepl://localhost:7888@01950a3b-1234-7def/my-app.core"
                                   :property :symbol-list})]
                      (is (:success result))
                      (is (= 3 (count (:data result))))
                      (is (= #{:defn :defmacro :def}
                             (set (map :symbol/type (:data result)))))))))
