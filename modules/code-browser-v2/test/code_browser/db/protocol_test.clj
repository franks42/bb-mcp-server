(ns code-browser.db.protocol-test
    "Unit tests for code-browser.db.protocol module."
    (:require [clojure.test :refer [deftest testing is]]
              [code-browser.db.protocol :as proto]))

;;; ---------------------------------------------------------------------------
;;; Mock DB Implementation for Testing
;;; ---------------------------------------------------------------------------

(defrecord MockDB [data-atom]
           proto/IDatalogDB

           (q [_this _query]
    ;; Return all data for simplicity
              @data-atom)

           (q [_this _query _args]
    ;; Filter would go here in real impl
              @data-atom)

           (pull [_this _pattern eid]
    ;; Lookup by :uri/string
                 (let [uri (if (vector? eid) (second eid) eid)]
                   (first (filter #(= uri (:uri/string %)) @data-atom))))

           (transact! [_this tx-data]
                      (swap! data-atom into tx-data)
                      {:tx-data tx-data})

           (entity [this eid]
                   (proto/pull this '[*] eid))

           (db [_this]
               @data-atom)

           (close! [_this]
                   (reset! data-atom [])))

(defn create-mock-db
  "Create a mock database for testing."
  ([]
   (create-mock-db []))
  ([initial-data]
   (->MockDB (atom initial-data))))

;;; ---------------------------------------------------------------------------
;;; Schema Tests
;;; ---------------------------------------------------------------------------

(deftest base-schema-test
         (testing "Schema contains required URI attributes"
                  (is (contains? proto/base-schema :uri/string))
                  (is (contains? proto/base-schema :uri/source))
                  (is (contains? proto/base-schema :uri/project))
                  (is (contains? proto/base-schema :uri/version)))

         (testing ":uri/string is unique identity"
                  (is (= :db.unique/identity
                         (get-in proto/base-schema [:uri/string :db/unique]))))

         (testing "Schema contains project attributes"
                  (is (contains? proto/base-schema :project/root-path))
                  (is (contains? proto/base-schema :project/namespaces)))

         (testing "Schema contains namespace attributes"
                  (is (contains? proto/base-schema :ns/name))
                  (is (contains? proto/base-schema :ns/symbols)))

         (testing "Schema contains symbol attributes"
                  (is (contains? proto/base-schema :symbol/name))
                  (is (contains? proto/base-schema :symbol/type))
                  (is (contains? proto/base-schema :symbol/line))
                  (is (contains? proto/base-schema :symbol/deps)))

         (testing "Schema has proper cardinality for many-valued attrs"
                  (is (= :db.cardinality/many
                         (get-in proto/base-schema [:project/namespaces :db/cardinality])))
                  (is (= :db.cardinality/many
                         (get-in proto/base-schema [:ns/symbols :db/cardinality])))
                  (is (= :db.cardinality/many
                         (get-in proto/base-schema [:symbol/deps :db/cardinality])))))

;;; ---------------------------------------------------------------------------
;;; Protocol Operation Tests (with Mock)
;;; ---------------------------------------------------------------------------

(deftest mock-db-transact!-test
         (testing "transact! adds data to database"
                  (let [db (create-mock-db)
                        tx-data [{:uri/string "dir://proj@v1"
                                  :uri/source :dir
                                  :uri/project "proj"
                                  :uri/version "v1"}]]
                    (proto/transact! db tx-data)
                    (is (= 1 (count (proto/db db))))
                    (is (= "proj" (:uri/project (first (proto/db db))))))))

(deftest mock-db-pull-test
         (testing "pull retrieves entity by lookup ref"
                  (let [db (create-mock-db [{:uri/string "dir://proj@v1"
                                             :uri/source :dir
                                             :uri/project "proj"
                                             :uri/version "v1"}])
                        entity (proto/pull db '[*] [:uri/string "dir://proj@v1"])]
                    (is (= "proj" (:uri/project entity)))
                    (is (= :dir (:uri/source entity))))))

(deftest mock-db-close!-test
         (testing "close! clears database"
                  (let [db (create-mock-db [{:uri/string "dir://proj@v1"}])]
                    (is (= 1 (count (proto/db db))))
                    (proto/close! db)
                    (is (= 0 (count (proto/db db)))))))

;;; ---------------------------------------------------------------------------
;;; Named Queries Tests
;;; ---------------------------------------------------------------------------

(deftest queries-exist-test
         (testing "Named queries are defined"
                  (is (contains? proto/queries :projects))
                  (is (contains? proto/queries :namespaces-for-project))
                  (is (contains? proto/queries :symbols-for-namespace))
                  (is (contains? proto/queries :symbol-detail))
                  (is (contains? proto/queries :symbol-callers))
                  (is (contains? proto/queries :symbol-deps))
                  (is (contains? proto/queries :find-symbol-by-name)))

         (testing "Queries are vectors (Datalog queries)"
                  (is (vector? (:projects proto/queries)))
                  (is (vector? (:symbols-for-namespace proto/queries)))))
