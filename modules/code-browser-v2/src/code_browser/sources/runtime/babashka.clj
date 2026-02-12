(ns code-browser.sources.runtime.babashka
    "Babashka runtime introspection implementation.

   Provides defmethod implementations for :babashka runtime type.
   Also registered as :default since vanilla clojure.core introspection
   works for JVM Clojure too.

   All introspection is done via eval-fn which sends code to the remote
   nREPL server for evaluation."
    (:require [code-browser.sources.runtime :as runtime]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; list-namespaces
;;; ---------------------------------------------------------------------------

(defmethod runtime/list-namespaces :babashka
           [_runtime-type eval-fn opts]
           (log/log! {:level :debug
                      :id ::list-namespaces
                      :msg "Listing namespaces via Babashka runtime"})
           (let [patterns (or (:exclude-patterns opts) runtime/default-exclude-patterns)
                 result (eval-fn "(vec (for [n (sort-by str (all-ns))] {:name (str n) :doc (:doc (meta n))}))")
                 all-ns (if (sequential? result) result [])]
             (if (seq patterns)
               (filterv (fn [ns-map]
                          (not (runtime/excluded-ns? (:name ns-map) patterns)))
                        all-ns)
               all-ns)))

(defmethod runtime/list-namespaces :default
           [runtime-type eval-fn opts]
           (log/log! {:level :debug
                      :id ::list-namespaces-default
                      :msg "Using default (Babashka) list-namespaces"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/list-namespaces :babashka) :babashka eval-fn opts))

;;; ---------------------------------------------------------------------------
;;; introspect-namespace
;;; ---------------------------------------------------------------------------

(defmethod runtime/introspect-namespace :babashka
           [_runtime-type eval-fn ns-name _opts]
           (log/log! {:level :debug
                      :id ::introspect-namespace
                      :msg "Introspecting namespace"
                      :data {:ns ns-name}})
           (let [code (str "(vec (for [[sym v] (sort-by key (ns-publics '" ns-name "))"
                           " :let [m (meta v)]]"
                           " {:name (str sym)"
                           " :arglists (when (:arglists m) (pr-str (:arglists m)))"
                           " :doc (:doc m)"
                           " :macro (:macro m)"
                           " :private (:private m)"
                           " :dynamic (:dynamic m)}))")
                 result (eval-fn code)]
             (if (sequential? result) result [])))

(defmethod runtime/introspect-namespace :default
           [runtime-type eval-fn ns-name opts]
           (log/log! {:level :debug
                      :id ::introspect-namespace-default
                      :msg "Using default (Babashka) introspect-namespace"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/introspect-namespace :babashka) :babashka eval-fn ns-name opts))

;;; ---------------------------------------------------------------------------
;;; fetch-var-source
;;; ---------------------------------------------------------------------------

(defmethod runtime/fetch-var-source :babashka
           [_runtime-type eval-fn ns-name var-name _opts]
           (log/log! {:level :debug
                      :id ::fetch-var-source
                      :msg "Fetching var source"
                      :data {:ns ns-name :var var-name}})
           (let [code (str "(clojure.repl/source-fn '" ns-name "/" var-name ")")
                 result (eval-fn code)]
             (when (and (string? result) (seq result))
               {:source result})))

(defmethod runtime/fetch-var-source :default
           [runtime-type eval-fn ns-name var-name opts]
           (log/log! {:level :debug
                      :id ::fetch-var-source-default
                      :msg "Using default (Babashka) fetch-var-source"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/fetch-var-source :babashka) :babashka eval-fn ns-name var-name opts))

;;; ---------------------------------------------------------------------------
;;; fetch-var-value
;;; ---------------------------------------------------------------------------

(def ^:private fetch-var-value-code
     "Remote eval code template for fetching a var's current value.
   The %s placeholders are replaced with ns-name and var-name."
     "(let [v (resolve (symbol \"%s\" \"%s\"))]
  (when v
    (let [raw-val (deref v)
          is-atom? (instance? clojure.lang.IAtom raw-val)
          val (if is-atom? (deref raw-val) raw-val)
          ;; Statechart detection via statecharts.types/statechart? (defrecord)
          sc-check (try (require 'statecharts.types)
                        ((resolve 'statecharts.types/statechart?) val)
                        (catch Exception _ false))
          is-sc? (boolean sc-check)
          sc-info (when is-sc?
                    {:id (:id val)
                     :initial (:initial val)
                     :compiled? (boolean
                                 (try ((resolve 'statecharts.types/compiled?) val)
                                      (catch Exception _ false)))
                     :states (when-let [s (:states val)]
                               (vec (keys s)))})
          ;; Predicate probing — test all core predicates against the value
          preds (vec
                 (keep (fn [[pred-name pred-fn]]
                         (try (when (pred-fn val) pred-name)
                              (catch Exception _ nil)))
                       [[\"nil?\" nil?]
                        [\"map?\" map?]
                        [\"vector?\" vector?]
                        [\"list?\" list?]
                        [\"set?\" set?]
                        [\"seq?\" seq?]
                        [\"sequential?\" sequential?]
                        [\"associative?\" associative?]
                        [\"counted?\" counted?]
                        [\"indexed?\" indexed?]
                        [\"reversible?\" reversible?]
                        [\"sorted?\" sorted?]
                        [\"coll?\" coll?]
                        [\"seqable?\" seqable?]
                        [\"string?\" string?]
                        [\"number?\" number?]
                        [\"integer?\" integer?]
                        [\"float?\" float?]
                        [\"rational?\" rational?]
                        [\"ratio?\" ratio?]
                        [\"boolean?\" boolean?]
                        [\"keyword?\" keyword?]
                        [\"symbol?\" symbol?]
                        [\"fn?\" fn?]
                        [\"ifn?\" ifn?]
                        [\"record?\" record?]
                        [\"inst?\" inst?]
                        [\"uuid?\" uuid?]
                        [\"uri?\" uri?]
                        [\"tagged-literal?\" tagged-literal?]
                        [\"volatile?\" volatile?]
                        [\"delay?\" delay?]
                        [\"future?\" future?]]))
          ;; Primary type (for title/classification)
          vtype (cond
                  is-sc?             \"statechart\"
                  (nil? val)         \"nil\"
                  (fn? val)          \"function\"
                  (map? val)         \"map\"
                  (vector? val)      \"vector\"
                  (set? val)         \"set\"
                  (seq? val)         \"seq\"
                  (string? val)      \"string\"
                  (number? val)      \"number\"
                  (boolean? val)     \"boolean\"
                  (keyword? val)     \"keyword\"
                  (symbol? val)      \"symbol\"
                  :else              \"other\")
          ;; pprint with truncation
          max-len 4096
          sw (java.io.StringWriter.)
          _ (binding [*print-length* 20 *print-level* 5]
              (clojure.pprint/pprint val sw))
          s (str sw)
          truncated? (> (count s) max-len)
          value-str (if truncated?
                      (str (subs s 0 max-len) \"\\n... (truncated)\")
                      s)
          ;; Var metadata
          vmeta (let [m (meta v)]
                  (into {}
                        (keep (fn [[k v]]
                                (when (contains?
                                       #{:doc :arglists :file :line :column
                                         :name :ns :macro :private :dynamic
                                         :added :deprecated :tag} k)
                                  [k (if (or (string? v) (number? v)
                                            (boolean? v) (keyword? v) (nil? v))
                                       v
                                       (pr-str v))])))
                        m))
          ;; Value metadata
          val-meta (try
                     (let [m (meta val)]
                       (when (and m (seq m))
                         (let [s (pr-str m)]
                           (when (< (count s) 2000) s))))
                     (catch Exception _ nil))
          ;; Collection count
          cnt (when (counted? val) (count val))]
      {:value-str value-str
       :value-type vtype
       :value-class (str (type val))
       :container-type (when is-atom? \"atom\")
       :predicates preds
       :is-statechart? is-sc?
       :statechart sc-info
       :var-meta vmeta
       :value-meta val-meta
       :count cnt
       :truncated? truncated?})))")

(defmethod runtime/fetch-var-value :babashka
           [_runtime-type eval-fn ns-name var-name _opts]
           (log/log! {:level :debug
                      :id ::fetch-var-value
                      :msg "Fetching var value"
                      :data {:ns ns-name :var var-name}})
           (let [code (format fetch-var-value-code ns-name var-name)
                 result (eval-fn code)]
             (when (map? result)
               result)))

(defmethod runtime/fetch-var-value :default
           [runtime-type eval-fn ns-name var-name opts]
           (log/log! {:level :debug
                      :id ::fetch-var-value-default
                      :msg "Using default (Babashka) fetch-var-value"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/fetch-var-value :babashka)
            :babashka eval-fn ns-name var-name opts))

;;; ---------------------------------------------------------------------------
;;; batch-introspect
;;; ---------------------------------------------------------------------------

(defmethod runtime/batch-introspect :babashka
           [_runtime-type eval-fn opts]
           (log/log! {:level :info
                      :id ::batch-introspect
                      :msg "Batch introspecting all namespaces"})
           (let [patterns (or (:exclude-patterns opts) runtime/default-exclude-patterns)
                 code (str "(vec (for [n (sort-by str (all-ns))"
                           " :let [ns-name (str n)"
                           "       ns-meta (meta n)"
                           "       vars (vec (for [[sym v] (sort-by key (ns-publics n))"
                           "                       :let [m (meta v)]]"
                           "                      {:name (str sym)"
                           "                       :arglists (when (:arglists m) (pr-str (:arglists m)))"
                           "                       :doc (:doc m)"
                           "                       :macro (:macro m)"
                           "                       :private (:private m)"
                           "                       :dynamic (:dynamic m)}))]]"
                           " {:name ns-name :doc (:doc ns-meta) :vars vars}))")
                 result (eval-fn code)
                 all-ns (if (sequential? result) result [])]
             (log/log! {:level :info
                        :id ::batch-introspect-raw
                        :msg "Raw batch introspection complete"
                        :data {:total-ns (count all-ns)}})
             (let [filtered (if (seq patterns)
                              (filterv (fn [ns-map]
                                         (not (runtime/excluded-ns? (:name ns-map) patterns)))
                                       all-ns)
                              all-ns)]
               (log/log! {:level :info
                          :id ::batch-introspect-complete
                          :msg "Batch introspection complete"
                          :data {:total-ns (count all-ns)
                                 :filtered-ns (count filtered)
                                 :total-vars (reduce + (map #(count (:vars %)) filtered))}})
               filtered)))

(defmethod runtime/batch-introspect :default
           [runtime-type eval-fn opts]
           (log/log! {:level :debug
                      :id ::batch-introspect-default
                      :msg "Using default (Babashka) batch-introspect"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/batch-introspect :babashka) :babashka eval-fn opts))
