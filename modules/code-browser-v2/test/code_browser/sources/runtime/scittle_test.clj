(ns code-browser.sources.runtime.scittle-test
    "Tests for Scittle runtime introspection multimethods.

   Uses mock eval functions to test without a live nREPL/Scittle connection."
    (:require [clojure.test :refer [deftest testing is]]
              [code-browser.sources.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; detect-runtime — Scittle detection path
;;; ---------------------------------------------------------------------------

(deftest detect-runtime-scittle-test
         (testing "Detects Scittle runtime when JVM detection returns nil"
                  ;; Simulate: JVM detection returns nil (no System/getProperty),
                  ;; then Scittle detection succeeds
                  (let [call-count (atom 0)
                        eval-fn (fn [_code]
                                  (let [n (swap! call-count inc)]
                                    (if (= n 1)
                                      ;; Stage 1: JVM detection fails
                                      nil
                                      ;; Stage 2: Scittle detection succeeds
                                      {:type :scittle
                                       :version "1.0.0"
                                       :user-agent "Mozilla/5.0"})))
                        result (runtime/detect-runtime eval-fn)]
                    (is (= :scittle (:type result)))
                    (is (= "1.0.0" (:version result))))))

(deftest detect-runtime-jvm-still-works-test
         (testing "JVM detection still works (Stage 1 success)"
                  (let [eval-fn (fn [_code]
                                  {:type :babashka :version "1.12.212"})]
                    (is (= {:type :babashka :version "1.12.212"}
                           (runtime/detect-runtime eval-fn))))))

(deftest detect-runtime-fallback-test
         (testing "Falls back to :jvm-clojure when both stages fail"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= {:type :jvm-clojure :version "unknown"}
                           (runtime/detect-runtime eval-fn))))))

;;; ---------------------------------------------------------------------------
;;; list-namespaces — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest list-namespaces-scittle-test
         (testing "Lists namespaces and filters Scittle internals"
                  (let [eval-fn (fn [_code]
                                  [{:name "demo.core" :doc "Demo app"}
                                   {:name "demo.utils" :doc nil}
                                   {:name "sente-lite.client" :doc "Sente internals"}
                                   {:name "nrepl-sente.core" :doc "nREPL bridge"}
                                   {:name "reagent.core" :doc "Reagent"}
                                   {:name "sci.core" :doc "SCI internals"}
                                   {:name "nrepl.server" :doc "nREPL"}])
                        result (runtime/list-namespaces :scittle eval-fn {})]
                    (is (= 2 (count result))
                        "Should exclude sente-lite, nrepl-sente, reagent, sci, nrepl")
                    (is (= #{"demo.core" "demo.utils"}
                           (set (map :name result)))))))

(deftest list-namespaces-scittle-no-exclude-test
         (testing "No exclusion when patterns empty"
                  (let [eval-fn (fn [_code]
                                  [{:name "demo.core" :doc nil}
                                   {:name "sente-lite.client" :doc nil}])
                        result (runtime/list-namespaces :scittle eval-fn
                                                        {:exclude-patterns []})]
                    (is (= 2 (count result))))))

;;; ---------------------------------------------------------------------------
;;; introspect-namespace — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest introspect-namespace-scittle-test
         (testing "Introspects namespace vars from eval result"
                  (let [eval-fn (fn [_code]
                                  [{:name "increment!" :arglists "([x])"
                                    :doc "Increment counter" :macro nil
                                    :private nil :dynamic nil :type-hint :defn}
                                   {:name "!app-state" :arglists nil
                                    :doc "App state atom" :macro nil
                                    :private nil :dynamic nil :type-hint :def}])
                        result (runtime/introspect-namespace :scittle eval-fn
                                                             "demo.core" {})]
                    (is (= 2 (count result)))
                    (is (= "increment!" (:name (first result)))))))

(deftest introspect-namespace-scittle-empty-test
         (testing "Returns empty vec for nil result"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= [] (runtime/introspect-namespace :scittle eval-fn
                                                            "demo.core" {}))))))

;;; ---------------------------------------------------------------------------
;;; fetch-var-source — always nil for Scittle
;;; ---------------------------------------------------------------------------

(deftest fetch-var-source-scittle-test
         (testing "Always returns nil — source unavailable in SCI"
                  (let [eval-fn (fn [_code] "(defn foo [] 42)")]
                    (is (nil? (runtime/fetch-var-source :scittle eval-fn
                                                        "demo.core" "foo" {}))))))

;;; ---------------------------------------------------------------------------
;;; fetch-var-value — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest fetch-var-value-scittle-number-test
         (testing "Returns value map for a simple number"
                  (let [eval-fn (fn [_code]
                                  {:value-str "42"
                                   :value-type "number"
                                   :value-class "number"
                                   :container-type nil
                                   :predicates ["number?" "integer?"]
                                   :is-statechart? false
                                   :statechart nil
                                   :is-service? false
                                   :service nil
                                   :is-store? false
                                   :store nil
                                   :var-meta {:name "my-val"}
                                   :value-meta nil
                                   :count nil
                                   :truncated? false
                                   :var-id 42
                                   :value-id 42})
                        result (runtime/fetch-var-value :scittle eval-fn
                                                        "demo.core" "my-val" {})]
                    (is (some? result))
                    (is (= "number" (:value-type result)))
                    (is (= "42" (:value-str result)))
                    (is (nil? (:container-type result)))
                    (is (false? (:is-statechart? result)))
                    (is (integer? (:var-id result))))))

(deftest fetch-var-value-scittle-atom-test
         (testing "Returns atom container info with hash-based fingerprints"
                  (let [eval-fn (fn [_code]
                                  {:value-str "{:count 0}"
                                   :value-type "map"
                                   :value-class "cljs.core/PersistentArrayMap"
                                   :container-type "atom"
                                   :predicates ["map?" "associative?" "counted?"
                                                "coll?" "seqable?" "ifn?"]
                                   :is-statechart? false
                                   :statechart nil
                                   :is-service? false
                                   :service nil
                                   :is-store? false
                                   :store nil
                                   :var-meta {:name "!state"}
                                   :value-meta nil
                                   :count 1
                                   :truncated? false
                                   :var-id 12345
                                   :value-id 67890})
                        result (runtime/fetch-var-value :scittle eval-fn
                                                        "demo.core" "!state" {})]
                    (is (some? result))
                    (is (= "atom" (:container-type result)))
                    (is (= "map" (:value-type result)))
                    (is (= 1 (:count result)))
                    (is (integer? (:var-id result)))
                    (is (integer? (:value-id result))))))

(deftest fetch-var-value-scittle-nil-eval-test
         (testing "Returns nil when eval returns nil"
                  (let [eval-fn (fn [_code] nil)]
                    (is (nil? (runtime/fetch-var-value :scittle eval-fn
                                                       "demo.core" "x" {}))))))

;;; ---------------------------------------------------------------------------
;;; check-var-fingerprint — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest check-var-fingerprint-scittle-unchanged-test
         (testing "Returns {:changed? false} when fingerprint matches"
                  (let [eval-fn (fn [_code]
                                  {:changed? false})
                        result (runtime/check-var-fingerprint
                                :scittle eval-fn "demo.core" "x"
                                {:var-id 123 :value-id 456} {})]
                    (is (false? (:changed? result))))))

(deftest check-var-fingerprint-scittle-changed-test
         (testing "Returns full value map when fingerprint differs"
                  (let [call-count (atom 0)
                        eval-fn (fn [_code]
                                  (let [n (swap! call-count inc)]
                                    (if (= n 1)
                                      ;; Fingerprint check — changed
                                      {:changed? true :var-id 123 :value-id 789}
                                      ;; Full value fetch
                                      {:value-str "99"
                                       :value-type "number"
                                       :value-class "number"
                                       :container-type nil
                                       :predicates ["number?"]
                                       :is-statechart? false
                                       :statechart nil
                                       :is-service? false
                                       :service nil
                                       :is-store? false
                                       :store nil
                                       :var-meta {}
                                       :value-meta nil
                                       :count nil
                                       :truncated? false
                                       :var-id 123
                                       :value-id 789})))
                        result (runtime/check-var-fingerprint
                                :scittle eval-fn "demo.core" "x"
                                {:var-id 123 :value-id 456} {})]
                    (is (true? (:changed? result)))
                    (is (= "99" (:value-str result))))))

;;; ---------------------------------------------------------------------------
;;; batch-introspect — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest batch-introspect-scittle-test
         (testing "Batch introspects all namespaces with Scittle filtering"
                  (let [eval-fn (fn [_code]
                                  [{:name "demo.core" :doc "Core"
                                    :vars [{:name "init" :arglists "([])"}]}
                                   {:name "demo.utils" :doc nil
                                    :vars [{:name "helper" :arglists "([x])"}]}
                                   {:name "sente-lite.client" :doc "Sente"
                                    :vars [{:name "connect" :arglists "([opts])"}]}
                                   {:name "reagent.core" :doc "Reagent"
                                    :vars [{:name "atom" :arglists "([x])"}]}])
                        result (runtime/batch-introspect :scittle eval-fn {})]
                    (is (= 2 (count result))
                        "Should filter out sente-lite and reagent")
                    (is (= #{"demo.core" "demo.utils"}
                           (set (map :name result)))))))

(deftest batch-introspect-scittle-empty-test
         (testing "Handles empty eval result"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= [] (runtime/batch-introspect :scittle eval-fn {}))))))

;;; ---------------------------------------------------------------------------
;;; check-ns-list-fingerprint — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest check-ns-list-fingerprint-scittle-unchanged-test
         (testing "Returns {:changed? false} when namespace list unchanged"
                  (let [ns-list [{:name "demo.core" :doc nil}
                                 {:name "demo.utils" :doc nil}]
                        eval-fn (fn [_code] ns-list)
                        fp {:count 2
                            :hash (hash (mapv :name ns-list))}
                        result (runtime/check-ns-list-fingerprint
                                :scittle eval-fn "nrepl://test" fp {})]
                    (is (false? (:changed? result))))))

(deftest check-ns-list-fingerprint-scittle-changed-test
         (testing "Returns {:changed? true} when namespace list changed"
                  (let [eval-fn (fn [_code]
                                  [{:name "demo.core" :doc nil}
                                   {:name "demo.utils" :doc nil}
                                   {:name "demo.new" :doc nil}])
                        result (runtime/check-ns-list-fingerprint
                                :scittle eval-fn "nrepl://test"
                                {:count 2 :hash 0} {})]
                    (is (true? (:changed? result)))
                    (is (= 3 (:count result))))))

;;; ---------------------------------------------------------------------------
;;; check-symbol-list-fingerprint — Scittle impl
;;; ---------------------------------------------------------------------------

(deftest check-symbol-list-fingerprint-scittle-unchanged-test
         (testing "Returns {:changed? false} when symbol list unchanged"
                  (let [sym-list [{:name "foo" :arglists "([])"}
                                  {:name "bar" :arglists nil}]
                        eval-fn (fn [_code] sym-list)
                        fp {:count 2
                            :hash (hash (mapv :name sym-list))}
                        result (runtime/check-symbol-list-fingerprint
                                :scittle eval-fn "demo.core" fp {})]
                    (is (false? (:changed? result))))))

(deftest check-symbol-list-fingerprint-scittle-changed-test
         (testing "Returns {:changed? true} when symbol list changed"
                  (let [eval-fn (fn [_code]
                                  [{:name "foo" :arglists "([])"}
                                   {:name "bar" :arglists nil}
                                   {:name "baz" :arglists "([x])"}])
                        result (runtime/check-symbol-list-fingerprint
                                :scittle eval-fn "demo.core"
                                {:count 2 :hash 0} {})]
                    (is (true? (:changed? result)))
                    (is (= 3 (:count result)))
                    (is (= 3 (count (:symbols result)))))))
