(ns code-browser.sources.runtime-test
    "Tests for runtime introspection multimethods.

   Uses mock eval functions to test without a live nREPL connection."
    (:require [clojure.test :refer [deftest testing is]]
              [code-browser.sources.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; detect-runtime Tests
;;; ---------------------------------------------------------------------------

(deftest detect-runtime-babashka-test
         (testing "Detects Babashka runtime from system property"
                  (let [eval-fn (fn [_code]
                                  {:type :babashka :version "1.12.212"})]
                    (is (= {:type :babashka :version "1.12.212"}
                           (runtime/detect-runtime eval-fn))))))

(deftest detect-runtime-jvm-test
         (testing "Detects JVM Clojure runtime"
                  (let [eval-fn (fn [_code]
                                  {:type :jvm-clojure :version "21.0.1"})]
                    (is (= {:type :jvm-clojure :version "21.0.1"}
                           (runtime/detect-runtime eval-fn))))))

(deftest detect-runtime-string-type-test
         (testing "Converts string :type to keyword"
                  (let [eval-fn (fn [_code]
                                  {:type "babashka" :version "1.12.212"})]
                    (is (= :babashka
                           (:type (runtime/detect-runtime eval-fn)))))))

(deftest detect-runtime-failure-test
         (testing "Defaults to :jvm-clojure on failure"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= {:type :jvm-clojure :version "unknown"}
                           (runtime/detect-runtime eval-fn))))))

;;; ---------------------------------------------------------------------------
;;; infer-symbol-type Tests
;;; ---------------------------------------------------------------------------

(deftest infer-symbol-type-macro-test
         (testing "Macro metadata produces :defmacro"
                  (is (= :defmacro (runtime/infer-symbol-type {:macro true :arglists "([x])"})))
                  (is (= :defmacro (runtime/infer-symbol-type {:macro true})))))

(deftest infer-symbol-type-fn-test
         (testing "Arglists without macro produces :defn"
                  (is (= :defn (runtime/infer-symbol-type {:arglists "([x y])"})))))

(deftest infer-symbol-type-def-test
         (testing "No macro or arglists produces :def"
                  (is (= :def (runtime/infer-symbol-type {})))
                  (is (= :def (runtime/infer-symbol-type {:macro false :arglists nil})))))

;;; ---------------------------------------------------------------------------
;;; excluded-ns? Tests
;;; ---------------------------------------------------------------------------

(deftest excluded-ns-matches-test
         (testing "Matches against exclusion patterns"
                  (is (runtime/excluded-ns? "clojure.spec.alpha" runtime/default-exclude-patterns))
                  (is (runtime/excluded-ns? "nrepl.middleware" runtime/default-exclude-patterns))
                  (is (not (runtime/excluded-ns? "my-app.core" runtime/default-exclude-patterns)))
                  (is (not (runtime/excluded-ns? "clojure.core" runtime/default-exclude-patterns)))))

;;; ---------------------------------------------------------------------------
;;; list-namespaces Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

(deftest list-namespaces-babashka-test
         (testing "Lists namespaces from eval result"
                  (let [eval-fn (fn [_code]
                                  [{:name "my-app.core" :doc "Core ns"}
                                   {:name "my-app.util" :doc nil}
                                   {:name "nrepl.server" :doc "nREPL server"}])
                        result (runtime/list-namespaces :babashka eval-fn {})]
                    (is (= 2 (count result)) "Should exclude nrepl.server")
                    (is (= #{"my-app.core" "my-app.util"}
                           (set (map :name result)))))))

(deftest list-namespaces-no-exclude-test
         (testing "No exclusion when patterns empty"
                  (let [eval-fn (fn [_code]
                                  [{:name "my-app.core" :doc nil}
                                   {:name "nrepl.server" :doc nil}])
                        result (runtime/list-namespaces :babashka eval-fn
                                                        {:exclude-patterns []})]
                    (is (= 2 (count result))))))

(deftest list-namespaces-empty-result-test
         (testing "Handles empty or non-sequential eval result"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= [] (runtime/list-namespaces :babashka eval-fn {}))))
                  (let [eval-fn (fn [_code] "error")]
                    (is (= [] (runtime/list-namespaces :babashka eval-fn {}))))))

;;; ---------------------------------------------------------------------------
;;; introspect-namespace Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

(deftest introspect-namespace-babashka-test
         (testing "Introspects namespace vars from eval result"
                  (let [eval-fn (fn [_code]
                                  [{:name "my-fn" :arglists "([x y])" :doc "A function"
                                    :macro nil :private nil :dynamic nil}
                                   {:name "my-var" :arglists nil :doc "A var"
                                    :macro nil :private nil :dynamic nil}])
                        result (runtime/introspect-namespace :babashka eval-fn
                                                             "my-app.core" {})]
                    (is (= 2 (count result)))
                    (is (= "my-fn" (:name (first result))))
                    (is (= "([x y])" (:arglists (first result)))))))

(deftest introspect-namespace-empty-test
         (testing "Returns empty vec for nil result"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= [] (runtime/introspect-namespace :babashka eval-fn
                                                            "my-app.core" {}))))))

;;; ---------------------------------------------------------------------------
;;; fetch-var-source Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

(deftest fetch-var-source-found-test
         (testing "Returns source when available"
                  (let [eval-fn (fn [_code] "(defn my-fn [x] (inc x))")]
                    (is (= {:source "(defn my-fn [x] (inc x))"}
                           (runtime/fetch-var-source :babashka eval-fn
                                                     "my-app.core" "my-fn" {}))))))

(deftest fetch-var-source-nil-test
         (testing "Returns nil when source unavailable"
                  (let [eval-fn (fn [_code] nil)]
                    (is (nil? (runtime/fetch-var-source :babashka eval-fn
                                                        "my-app.core" "my-fn" {}))))))

(deftest fetch-var-source-empty-string-test
         (testing "Returns nil for empty string"
                  (let [eval-fn (fn [_code] "")]
                    (is (nil? (runtime/fetch-var-source :babashka eval-fn
                                                        "my-app.core" "my-fn" {}))))))

;;; ---------------------------------------------------------------------------
;;; batch-introspect Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; fetch-var-value Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

(deftest fetch-var-value-simple-number-test
         (testing "Returns value map for a simple number"
                  (let [eval-fn (fn [_code]
                                  {:value-str "42\n"
                                   :value-type "number"
                                   :value-class "java.lang.Long"
                                   :container-type nil
                                   :predicates ["number?" "integer?" "rational?"]
                                   :is-statechart? false
                                   :statechart nil
                                   :var-meta {:name "my-val" :ns "my-app.core"}
                                   :value-meta nil
                                   :count nil
                                   :truncated? false})
                        result (runtime/fetch-var-value :babashka eval-fn
                                                        "my-app.core" "my-val" {})]
                    (is (some? result))
                    (is (= "number" (:value-type result)))
                    (is (= "42\n" (:value-str result)))
                    (is (nil? (:container-type result)))
                    (is (= ["number?" "integer?" "rational?"] (:predicates result)))
                    (is (false? (:is-statechart? result)))
                    (is (false? (:truncated? result))))))

(deftest fetch-var-value-nil-eval-test
         (testing "Returns nil when eval returns nil"
                  (let [eval-fn (fn [_code] nil)]
                    (is (nil? (runtime/fetch-var-value :babashka eval-fn
                                                       "my-app.core" "my-val" {}))))))

(deftest fetch-var-value-atom-test
         (testing "Returns atom container info with predicates"
                  (let [eval-fn (fn [_code]
                                  {:value-str "{:count 0}\n"
                                   :value-type "map"
                                   :value-class "clojure.lang.PersistentArrayMap"
                                   :container-type "atom"
                                   :predicates ["map?" "associative?" "counted?"
                                                "coll?" "seqable?" "ifn?"]
                                   :is-statechart? false
                                   :statechart nil
                                   :var-meta {:name "!state"}
                                   :value-meta nil
                                   :count 1
                                   :truncated? false})
                        result (runtime/fetch-var-value :babashka eval-fn
                                                        "my-app.core" "!state" {})]
                    (is (some? result))
                    (is (= "atom" (:container-type result)))
                    (is (= "map" (:value-type result)))
                    (is (= 1 (:count result)))
                    (is (vector? (:predicates result)))
                    (is (some #{"map?"} (:predicates result))))))

(deftest fetch-var-value-statechart-test
         (testing "Returns statechart info when detected"
                  (let [eval-fn (fn [_code]
                                  {:value-str "#statecharts.types.Statechart{...}\n"
                                   :value-type "statechart"
                                   :value-class "statecharts.types.Statechart"
                                   :container-type nil
                                   :predicates ["map?" "associative?" "counted?"
                                                "coll?" "seqable?" "ifn?" "record?"]
                                   :is-statechart? true
                                   :statechart {:id :my-machine
                                                :initial :idle
                                                :compiled? true
                                                :states [:idle :running :done]}
                                   :var-meta {:name "my-machine"}
                                   :value-meta nil
                                   :count nil
                                   :truncated? false})
                        result (runtime/fetch-var-value :babashka eval-fn
                                                        "my-app.fsm" "my-machine" {})]
                    (is (true? (:is-statechart? result)))
                    (is (some? (:statechart result)))
                    (is (= :my-machine (get-in result [:statechart :id])))
                    (is (= [:idle :running :done]
                           (get-in result [:statechart :states]))))))

(deftest fetch-var-value-default-method-test
         (testing "Default method delegates to :babashka impl"
                  (let [eval-fn (fn [_code]
                                  {:value-str "\"hello\"\n"
                                   :value-type "string"
                                   :value-class "java.lang.String"
                                   :container-type nil
                                   :predicates ["string?" "seqable?"]
                                   :is-statechart? false
                                   :statechart nil
                                   :var-meta {}
                                   :value-meta nil
                                   :count nil
                                   :truncated? false})
                        result (runtime/fetch-var-value :jvm-clojure eval-fn
                                                        "my-app.core" "my-str" {})]
                    (is (some? result))
                    (is (= "string" (:value-type result))))))

(deftest fetch-var-value-non-map-result-test
         (testing "Returns nil when eval returns non-map"
                  (let [eval-fn (fn [_code] "error string")]
                    (is (nil? (runtime/fetch-var-value :babashka eval-fn
                                                       "my-app.core" "x" {}))))))

;;; ---------------------------------------------------------------------------
;;; batch-introspect Tests (Babashka impl with mock eval)
;;; ---------------------------------------------------------------------------

(deftest batch-introspect-babashka-test
         (testing "Batch introspects all namespaces with filtering"
                  (let [eval-fn (fn [_code]
                                  [{:name "my-app.core" :doc "Core"
                                    :vars [{:name "init" :arglists "([])"}]}
                                   {:name "my-app.util" :doc nil
                                    :vars [{:name "helper" :arglists "([x])"}]}
                                   {:name "nrepl.server" :doc "nREPL"
                                    :vars [{:name "start" :arglists "([opts])"}]}])
                        result (runtime/batch-introspect :babashka eval-fn {})]
                    (is (= 2 (count result)) "Should filter out nrepl.server")
                    (is (= #{"my-app.core" "my-app.util"}
                           (set (map :name result))))
                    (is (= 1 (count (:vars (first result))))))))

(deftest batch-introspect-empty-test
         (testing "Handles empty eval result"
                  (let [eval-fn (fn [_code] nil)]
                    (is (= [] (runtime/batch-introspect :babashka eval-fn {}))))))

(deftest batch-introspect-default-method-test
         (testing "Default method delegates to :babashka impl"
                  (let [eval-fn (fn [_code]
                                  [{:name "my-app.core" :doc nil :vars []}])
                        result (runtime/batch-introspect :jvm-clojure eval-fn
                                                         {:exclude-patterns []})]
                    (is (= 1 (count result)))
                    (is (= "my-app.core" (:name (first result)))))))
