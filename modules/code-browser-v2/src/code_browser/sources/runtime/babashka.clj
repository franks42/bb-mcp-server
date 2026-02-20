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
;;; runtime-info
;;; ---------------------------------------------------------------------------

(def ^:private runtime-info-code
     "Remote eval code for gathering extended runtime information."
     (str "{:clojure-version (str (:major *clojure-version*) \".\" (:minor *clojure-version*) \".\" (:incremental *clojure-version*))"
          " :java-version (System/getProperty \"java.version\")"
          " :java-vendor (System/getProperty \"java.vendor\")"
          " :os (str (System/getProperty \"os.name\") \" \" (System/getProperty \"os.version\") \" \" (System/getProperty \"os.arch\"))"
          " :processors (.availableProcessors (Runtime/getRuntime))"
          " :max-memory-mb (quot (.maxMemory (Runtime/getRuntime)) 1048576)"
          " :free-memory-mb (quot (.freeMemory (Runtime/getRuntime)) 1048576)"
          " :loaded-lib-count (count (loaded-libs))"
          " :namespace-count (count (all-ns))"
          " :babashka-version (System/getProperty \"babashka.version\")}"))

(defmethod runtime/runtime-info :babashka
           [_runtime-type eval-fn _opts]
           (log/log! {:level :debug
                      :id ::runtime-info
                      :msg "Fetching runtime info via Babashka"})
           (let [result (eval-fn runtime-info-code)]
             (when (map? result)
               result)))

(defmethod runtime/runtime-info :default
           [runtime-type eval-fn opts]
           (log/log! {:level :debug
                      :id ::runtime-info-default
                      :msg "Using default (Babashka) runtime-info"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/runtime-info :babashka) :babashka eval-fn opts))

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
                           " :let [m (meta v)"
                           "       raw (try (deref v) (catch Exception _ nil))"
                           "       type-hint (cond"
                           "         (:macro m) :defmacro"
                           "         (instance? clojure.lang.MultiFn raw) :defmulti"
                           "         (:protocol m) :protocol-fn"
                           "         (and (map? raw) (:on raw) (:on-interface raw)) :defprotocol"
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
          ;; Service detection (clj-statecharts Service deftype via IService protocol)
          is-service? (boolean
                       (try (require 'statecharts.service)
                            (let [proto-var (resolve 'statecharts.service/IService)]
                              (and proto-var (satisfies? @proto-var val)))
                            (catch Exception _ false)))
          svc-info (when is-service?
                     (try
                       (let [state-fn (resolve 'statecharts.service/state)
                             svc-state (state-fn val)]
                         {:current-state (:_state svc-state)
                          :context (dissoc svc-state :_state :_actions)})
                       (catch Exception _ nil)))
          ;; Store detection (SingleStore/ManyStore via IStore protocol)
          is-store? (boolean
                     (try (require 'statecharts.store)
                          (let [proto-var (resolve 'statecharts.store/IStore)]
                            (and proto-var (satisfies? @proto-var val)))
                          (catch Exception _ false)))
          store-type (when is-store?
                       (cond
                         (contains? val :states*) \"many\"
                         (contains? val :state*) \"single\"
                         :else nil))
          store-info (when is-store?
                       (try
                         (case store-type
                           \"single\" {:store-type \"single\"
                                     :current-state (:_state @(:state* val))
                                     :context (dissoc @(:state* val) :_state :_actions)}
                           \"many\" {:store-type \"many\"
                                    :id-key (:id val)
                                    :instance-count (count @(:states* val))
                                    :instance-ids (vec (keys @(:states* val)))
                                    :instances (into {}
                                                 (map (fn [[k v]] [k (:_state v)])
                                                      @(:states* val)))}
                           nil)
                         (catch Exception _ nil)))
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
                  is-service?        \"service\"
                  is-store?          \"store\"
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
       :is-service? is-service?
       :service svc-info
       :is-store? is-store?
       :store store-info
       :var-meta vmeta
       :value-meta val-meta
       :count cnt
       :truncated? truncated?
       :var-id (System/identityHashCode raw-val)
       :value-id (if is-atom? (System/identityHashCode val) (System/identityHashCode raw-val))})))")

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
;;; check-var-fingerprint
;;; ---------------------------------------------------------------------------

(def ^:private check-var-fingerprint-code
     "Remote eval code template for checking if a var's fingerprint changed.
   Placeholders: ns-name, var-name, stored-var-id, stored-value-id."
     "(let [v (resolve (symbol \"%s\" \"%s\"))]
  (if v
    (let [raw-val (deref v)
          is-atom? (instance? clojure.lang.IAtom raw-val)
          val (if is-atom? (deref raw-val) raw-val)
          var-id (System/identityHashCode raw-val)
          value-id (if is-atom? (System/identityHashCode val) var-id)]
      (if (and (= var-id %s) (= value-id %s))
        {:changed? false}
        {:changed? true :var-id var-id :value-id value-id}))
    {:changed? false :error \"var-not-found\"}))")

(defmethod runtime/check-var-fingerprint :babashka
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
               (let [full (runtime/fetch-var-value :babashka eval-fn
                                                   ns-name var-name opts)]
                 (if (map? full)
                   (assoc full :changed? true)
                   {:changed? true :var-id (:var-id result)
                    :value-id (:value-id result)}))
               ;; Unchanged or error
               (if (map? result)
                 result
                 {:changed? false}))))

(defmethod runtime/check-var-fingerprint :default
           [runtime-type eval-fn ns-name var-name fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-var-fingerprint-default
                      :msg "Using default (Babashka) check-var-fingerprint"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/check-var-fingerprint :babashka)
            :babashka eval-fn ns-name var-name fingerprint opts))

;;; ---------------------------------------------------------------------------
;;; check-ns-list-fingerprint
;;; ---------------------------------------------------------------------------

(defmethod runtime/check-ns-list-fingerprint :babashka
           [_runtime-type eval-fn _project-uri fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-ns-list-fingerprint
                      :msg "Checking namespace list fingerprint"})
           (let [;; Fetch current namespace list
                 all-ns-vec (runtime/list-namespaces :babashka eval-fn opts)
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

(defmethod runtime/check-ns-list-fingerprint :default
           [runtime-type eval-fn project-uri fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-ns-list-fingerprint-default
                      :msg "Using default (Babashka) check-ns-list-fingerprint"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/check-ns-list-fingerprint :babashka)
            :babashka eval-fn project-uri fingerprint opts))

;;; ---------------------------------------------------------------------------
;;; check-symbol-list-fingerprint
;;; ---------------------------------------------------------------------------

(defmethod runtime/check-symbol-list-fingerprint :babashka
           [_runtime-type eval-fn ns-name fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-symbol-list-fingerprint
                      :msg "Checking symbol list fingerprint"
                      :data {:ns ns-name}})
           (let [;; Fetch current symbol list
                 symbols-vec (runtime/introspect-namespace :babashka eval-fn ns-name opts)
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

(defmethod runtime/check-symbol-list-fingerprint :default
           [runtime-type eval-fn ns-name fingerprint opts]
           (log/log! {:level :trace
                      :id ::check-symbol-list-fingerprint-default
                      :msg "Using default (Babashka) check-symbol-list-fingerprint"
                      :data {:runtime-type runtime-type}})
           ((get-method runtime/check-symbol-list-fingerprint :babashka)
            :babashka eval-fn ns-name fingerprint opts))

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
                           "                       :let [m (meta v)"
                           "                             raw (try (deref v) (catch Exception _ nil))"
                           "                             type-hint (cond"
                           "                               (:macro m) :defmacro"
                           "                               (instance? clojure.lang.MultiFn raw) :defmulti"
                           "                               (:protocol m) :protocol-fn"
                           "                               (and (map? raw) (:on raw) (:on-interface raw)) :defprotocol"
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
