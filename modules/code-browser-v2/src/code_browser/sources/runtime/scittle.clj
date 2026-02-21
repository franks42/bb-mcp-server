(ns code-browser.sources.runtime.scittle
    "Scittle (SCI-in-browser) runtime introspection implementation.

   Provides defmethod implementations for :scittle runtime type.
   Scittle runs ClojureScript via SCI in the browser, connected through
   an nREPL proxy (sente WebSocket bridge).

   Key differences from Babashka/JVM:
   - No clojure.lang.MultiFn, clojure.lang.IAtom — use (instance? Atom val)
   - No System/identityHashCode — use (hash val) (value-based, not identity)
   - No java.io.StringWriter/clojure.pprint — use pr-str with print bindings
   - No clojure.repl/source-fn — source unavailable
   - catch :default instead of catch Exception
   - No ratio?, uri?, delay?, future? predicates
   - No statechart/Service/Store detection (JVM class-based)"
    (:require [code-browser.sources.runtime :as runtime]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Scittle-specific exclude patterns
;;; ---------------------------------------------------------------------------

(def ^:private scittle-exclude-patterns
     "Additional namespace patterns to exclude for Scittle runtimes.
   Includes SCI internals and sente-lite infrastructure."
     (into runtime/default-exclude-patterns
           [#"sente-lite\..*"
            #"nrepl-sente\..*"
            #"reagent\..*"
            #"cljs\..*"]))

;;; ---------------------------------------------------------------------------
;;; runtime-info
;;; ---------------------------------------------------------------------------

(def ^:private runtime-info-code
     "Remote eval code for gathering Scittle runtime information.
   Uses only SCI-safe constructs (no JVM interop)."
     (str "(let [ns-count (count (all-ns))]"
          " {:clojure-version (str (:major *clojure-version*) \".\" (:minor *clojure-version*) \".\" (:incremental *clojure-version*))"
          "  :namespace-count ns-count"
          "  :platform \"browser/scittle\""
          "  :scittle-version (try (str js/scittle.core.version) (catch :default _ \"unknown\"))"
          "  :user-agent (try (str js/navigator.userAgent) (catch :default _ \"unknown\"))"
          "  :location (try (str js/window.location.href) (catch :default _ \"unknown\"))})"))

(defmethod runtime/runtime-info :scittle
           [_runtime-type eval-fn _opts]
           (log/log! {:level :debug
                      :id ::runtime-info
                      :msg "Fetching runtime info via Scittle"})
           (let [result (eval-fn runtime-info-code)]
             (when (map? result)
               result)))

;;; ---------------------------------------------------------------------------
;;; list-namespaces
;;; ---------------------------------------------------------------------------

(defmethod runtime/list-namespaces :scittle
           [_runtime-type eval-fn opts]
           (log/log! {:level :debug
                      :id ::list-namespaces
                      :msg "Listing namespaces via Scittle runtime"})
           (let [patterns (or (:exclude-patterns opts) scittle-exclude-patterns)
                 result (eval-fn "(vec (for [n (sort-by str (all-ns))] {:name (str n) :doc (:doc (meta n))}))")
                 all-ns (if (sequential? result) result [])]
             (if (seq patterns)
               (filterv (fn [ns-map]
                          (not (runtime/excluded-ns? (:name ns-map) patterns)))
                        all-ns)
               all-ns)))

;;; ---------------------------------------------------------------------------
;;; introspect-namespace
;;; ---------------------------------------------------------------------------

(defmethod runtime/introspect-namespace :scittle
           [_runtime-type eval-fn ns-name _opts]
           (log/log! {:level :debug
                      :id ::introspect-namespace
                      :msg "Introspecting namespace"
                      :data {:ns ns-name}})
           (let [code (str "(vec (for [[sym v] (sort-by key (ns-publics '" ns-name "))"
                           " :let [m (meta v)"
                           "       raw (try (deref v) (catch :default _ nil))"
                           "       type-hint (cond"
                           "         (:macro m) :defmacro"
                           "         (:arglists m) :defn"
                           "         :else :def)]]"
                           " {:name (str sym)"
                           " :arglists (when (:arglists m) (pr-str (:arglists m)))"
                           " :doc (:doc m)"
                           " :macro (:macro m)"
                           " :private (:private m)"
                           " :dynamic (:dynamic m)"
                           " :type-hint type-hint}))")
                 result (eval-fn code)]
             (if (sequential? result) result [])))

;;; ---------------------------------------------------------------------------
;;; fetch-var-source
;;; ---------------------------------------------------------------------------

(defmethod runtime/fetch-var-source :scittle
           [_runtime-type _eval-fn _ns-name _var-name _opts]
           (log/log! {:level :debug
                      :id ::fetch-var-source
                      :msg "Source not available in Scittle runtime"})
           nil)

;;; ---------------------------------------------------------------------------
;;; fetch-var-value
;;; ---------------------------------------------------------------------------

(def ^:private fetch-var-value-code
     "Remote eval code template for fetching a var's current value in Scittle.
   Uses SCI-safe constructs only. The %s placeholders are replaced with
   ns-name and var-name."
     "(let [v (resolve (symbol \"%s\" \"%s\"))]
  (when v
    (let [raw-val (deref v)
          is-atom? (instance? Atom raw-val)
          val (if is-atom? (deref raw-val) raw-val)
          ;; Predicate probing — test SCI-available predicates against the value
          preds (vec
                 (keep (fn [[pred-name pred-fn]]
                         (try (when (pred-fn val) pred-name)
                              (catch :default _ nil)))
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
                        [\"boolean?\" boolean?]
                        [\"keyword?\" keyword?]
                        [\"symbol?\" symbol?]
                        [\"fn?\" fn?]
                        [\"ifn?\" ifn?]
                        [\"inst?\" inst?]
                        [\"uuid?\" uuid?]
                        [\"volatile?\" volatile?]]))
          ;; Primary type (for title/classification)
          vtype (cond
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
          ;; pr-str with truncation (no pprint in SCI)
          max-len 4096
          s (binding [*print-length* 20 *print-level* 5]
              (pr-str val))
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
                     (catch :default _ nil))
          ;; Collection count
          cnt (when (counted? val) (count val))]
      {:value-str value-str
       :value-type vtype
       :value-class (str (type val))
       :container-type (when is-atom? \"atom\")
       :predicates preds
       :is-statechart? false
       :statechart nil
       :is-service? false
       :service nil
       :is-store? false
       :store nil
       :var-meta vmeta
       :value-meta val-meta
       :count cnt
       :truncated? truncated?
       :var-id (hash raw-val)
       :value-id (if is-atom? (hash val) (hash raw-val))}))))")

(defmethod runtime/fetch-var-value :scittle
           [_runtime-type eval-fn ns-name var-name _opts]
           (log/log! {:level :debug
                      :id ::fetch-var-value
                      :msg "Fetching var value"
                      :data {:ns ns-name :var var-name}})
           (let [code (format fetch-var-value-code ns-name var-name)
                 result (eval-fn code)]
             (when (map? result)
               result)))

;;; ---------------------------------------------------------------------------
;;; check-var-fingerprint
;;; ---------------------------------------------------------------------------

(def ^:private check-var-fingerprint-code
     "Remote eval code template for checking if a var's fingerprint changed in Scittle.
   Uses (hash val) instead of System/identityHashCode.
   Placeholders: ns-name, var-name, stored-var-id, stored-value-id."
     "(let [v (resolve (symbol \"%s\" \"%s\"))]
  (if v
    (let [raw-val (deref v)
          is-atom? (instance? Atom raw-val)
          val (if is-atom? (deref raw-val) raw-val)
          var-id (hash raw-val)
          value-id (if is-atom? (hash val) var-id)]
      (if (and (= var-id %s) (= value-id %s))
        {:changed? false}
        {:changed? true :var-id var-id :value-id value-id}))
    {:changed? false :error \"var-not-found\"}))")

(defmethod runtime/check-var-fingerprint :scittle
           [_runtime-type eval-fn ns-name var-name fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-var-fingerprint
                      :msg "Checking var fingerprint"
                      :data {:ns ns-name :var var-name}})
           (let [stored-var-id (:var-id fingerprint)
                 stored-value-id (:value-id fingerprint)
                 code (format check-var-fingerprint-code
                              ns-name var-name
                              stored-var-id stored-value-id)
                 result (eval-fn code)]
             (if (and (map? result) (:changed? result))
               ;; Changed — do a full fetch to get the new value
               (let [full (runtime/fetch-var-value :scittle eval-fn
                                                   ns-name var-name opts)]
                 (if (map? full)
                   (assoc full :changed? true)
                   {:changed? true :var-id (:var-id result)
                    :value-id (:value-id result)}))
               ;; Unchanged or error
               (if (map? result)
                 result
                 {:changed? false}))))

;;; ---------------------------------------------------------------------------
;;; check-ns-list-fingerprint
;;; ---------------------------------------------------------------------------

(defmethod runtime/check-ns-list-fingerprint :scittle
           [_runtime-type eval-fn _project-uri fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-ns-list-fingerprint
                      :msg "Checking namespace list fingerprint"})
           (let [all-ns-vec (runtime/list-namespaces :scittle eval-fn opts)
                 current-count (count all-ns-vec)
                 current-hash (hash (mapv :name all-ns-vec))
                 stored-count (:count fingerprint)
                 stored-hash (:hash fingerprint)]
             (if (and (= current-count stored-count)
                      (= current-hash stored-hash))
               {:changed? false}
               {:changed? true
                :count current-count
                :hash current-hash
                :namespaces all-ns-vec})))

;;; ---------------------------------------------------------------------------
;;; check-symbol-list-fingerprint
;;; ---------------------------------------------------------------------------

(defmethod runtime/check-symbol-list-fingerprint :scittle
           [_runtime-type eval-fn ns-name fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-symbol-list-fingerprint
                      :msg "Checking symbol list fingerprint"
                      :data {:ns ns-name}})
           (let [symbols-vec (runtime/introspect-namespace :scittle eval-fn ns-name opts)
                 current-count (count symbols-vec)
                 current-hash (hash (mapv :name symbols-vec))
                 stored-count (:count fingerprint)
                 stored-hash (:hash fingerprint)]
             (if (and (= current-count stored-count)
                      (= current-hash stored-hash))
               {:changed? false}
               {:changed? true
                :count current-count
                :hash current-hash
                :symbols symbols-vec})))

;;; ---------------------------------------------------------------------------
;;; batch-introspect
;;; ---------------------------------------------------------------------------

(defmethod runtime/batch-introspect :scittle
           [_runtime-type eval-fn opts]
           (log/log! {:level :info
                      :id ::batch-introspect
                      :msg "Batch introspecting all namespaces (Scittle)"})
           (let [patterns (or (:exclude-patterns opts) scittle-exclude-patterns)
                 code (str "(vec (for [n (sort-by str (all-ns))"
                           " :let [ns-name (str n)"
                           "       ns-meta (meta n)"
                           "       vars (vec (for [[sym v] (sort-by key (ns-publics n))"
                           "                       :let [m (meta v)"
                           "                             raw (try (deref v) (catch :default _ nil))"
                           "                             type-hint (cond"
                           "                               (:macro m) :defmacro"
                           "                               (:arglists m) :defn"
                           "                               :else :def)]]"
                           "                      {:name (str sym)"
                           "                       :arglists (when (:arglists m) (pr-str (:arglists m)))"
                           "                       :doc (:doc m)"
                           "                       :macro (:macro m)"
                           "                       :private (:private m)"
                           "                       :dynamic (:dynamic m)"
                           "                       :type-hint type-hint}))]]"
                           " {:name ns-name :doc (:doc ns-meta) :vars vars}))")
                 result (eval-fn code)
                 all-ns (if (sequential? result) result [])]
             (log/log! {:level :info
                        :id ::batch-introspect-raw
                        :msg "Raw batch introspection complete (Scittle)"
                        :data {:total-ns (count all-ns)}})
             (let [filtered (if (seq patterns)
                              (filterv (fn [ns-map]
                                         (not (runtime/excluded-ns? (:name ns-map) patterns)))
                                       all-ns)
                              all-ns)]
               (log/log! {:level :info
                          :id ::batch-introspect-complete
                          :msg "Batch introspection complete (Scittle)"
                          :data {:total-ns (count all-ns)
                                 :filtered-ns (count filtered)
                                 :total-vars (reduce + (map #(count (:vars %)) filtered))}})
               filtered)))
