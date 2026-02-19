(ns code-browser.sources.runtime
    "Multimethod declarations for runtime introspection.

   Dispatches on runtime type keyword (:babashka, :jvm-clojure, etc.)
   to allow different introspection strategies per runtime.

   Each runtime implementation provides defmethod impls in its own namespace
   (e.g., code-browser.sources.runtime.babashka).

   The eval-fn argument is always (fn [code-string] -> parsed-result)."
    (:require [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Runtime Detection
;;; ---------------------------------------------------------------------------

(defn detect-runtime
  "Detect the runtime type of a connected nREPL server.

   Arguments:
     eval-fn - (fn [code-string] -> parsed-result)

   Returns:
     {:type :babashka|:jvm-clojure :version \"...\"}"
  [eval-fn]
  (log/log! {:level :debug
             :id ::detect-runtime
             :msg "Detecting runtime type"})
  (let [result (eval-fn "(let [bb (System/getProperty \"babashka.version\")] {:type (if bb :babashka :jvm-clojure) :version (or bb (System/getProperty \"java.version\"))})")]
    (if (map? result)
      (do
       (log/log! {:level :info
                  :id ::runtime-detected
                  :msg "Runtime detected"
                  :data result})
       (update result :type keyword))
      (do
       (log/log! {:level :warn
                  :id ::runtime-detect-failed
                  :msg "Failed to detect runtime, defaulting to :jvm-clojure"
                  :data {:result result}})
       {:type :jvm-clojure :version "unknown"}))))

;;; ---------------------------------------------------------------------------
;;; Multimethod Declarations
;;; ---------------------------------------------------------------------------

(defmulti list-namespaces
          "List all namespaces in the runtime.

   Arguments:
     runtime-type - keyword (:babashka, :jvm-clojure, etc.)
     eval-fn      - (fn [code-string] -> parsed-result)
     opts         - options map (e.g., {:exclude-patterns [#\"clojure\\.spec.*\"]})

   Returns:
     [{:name \"ns.name\" :doc \"docstring\"} ...]"
          (fn [runtime-type _eval-fn _opts] runtime-type))

(defmulti introspect-namespace
          "Introspect a single namespace for its public vars.

   Arguments:
     runtime-type - keyword
     eval-fn      - (fn [code-string] -> parsed-result)
     ns-name      - namespace name string
     opts         - options map

   Returns:
     [{:name \"var\" :arglists \"([x])\" :doc \"...\" :macro true :private false :dynamic false} ...]"
          (fn [runtime-type _eval-fn _ns-name _opts] runtime-type))

(defmulti fetch-var-source
          "Fetch source code for a var via runtime introspection.

   Arguments:
     runtime-type - keyword
     eval-fn      - (fn [code-string] -> parsed-result)
     ns-name      - namespace name string
     var-name     - var name string
     opts         - options map

   Returns:
     {:source \"(defn ...code...)\"} or nil if source unavailable."
          (fn [runtime-type _eval-fn _ns-name _var-name _opts] runtime-type))

(defmulti fetch-var-value
          "Fetch the current runtime value of a var.

   Arguments:
     runtime-type - keyword (:babashka, :jvm-clojure, etc.)
     eval-fn      - (fn [code-string] -> parsed-result)
     ns-name      - namespace name string
     var-name     - var name string
     opts         - options map

   Returns a map with:
     :value-str      - pprinted string of the value
     :value-type     - primary type classification string
     :value-class    - Java class name string
     :container-type - nil or \"atom\" if var holds an atom
     :predicates     - vector of predicate name strings that return true
                       (e.g. [\"map?\" \"associative?\" \"counted?\" \"seqable?\"])
     :is-statechart? - boolean
     :statechart     - statechart info map when detected
     :var-meta       - selected var metadata
     :value-meta     - metadata on the value (if any)
     :count          - element count for counted collections
     :truncated?     - true if output was truncated
   Or nil if value unavailable."
          (fn [runtime-type _eval-fn _ns-name _var-name _opts] runtime-type))

(defmulti check-var-fingerprint
          "Check if a var's value has changed by comparing identity fingerprints.

   Arguments:
     runtime-type - keyword (:babashka, :jvm-clojure, etc.)
     eval-fn      - (fn [code-string] -> parsed-result)
     ns-name      - namespace name string
     var-name     - var name string
     fingerprint  - map with :var-id and :value-id from previous fetch
     opts         - options map

   Returns {:changed? false} when fingerprint matches, or the full value
   map (with :changed? true and updated fingerprint fields) when changed."
          (fn [runtime-type _eval-fn _ns-name _var-name _fingerprint _opts]
            runtime-type))

(defmulti check-ns-list-fingerprint
          "Check if the namespace list has changed by comparing count and hash.

   Arguments:
     runtime-type - keyword (:babashka, :jvm-clojure, etc.)
     eval-fn      - (fn [code-string] -> parsed-result)
     project-uri  - project URI string
     fingerprint  - map with :count and :hash from previous check
     opts         - options map with:
                    :exclude-patterns - vector of regex patterns to exclude namespaces

   Returns {:changed? false} when fingerprint matches, or {:changed? true
   :count N :hash \"...\" :namespaces [...]} when changed."
          (fn [runtime-type _eval-fn _project-uri _fingerprint _opts]
            runtime-type))

(defmulti check-symbol-list-fingerprint
          "Check if a namespace's symbol list has changed by comparing count and hash.

   Arguments:
     runtime-type - keyword (:babashka, :jvm-clojure, etc.)
     eval-fn      - (fn [code-string] -> parsed-result)
     ns-name      - namespace name string
     fingerprint  - map with :count and :hash from previous check
     opts         - options map

   Returns {:changed? false} when fingerprint matches, or {:changed? true
   :count N :hash \"...\" :symbols [...]} when changed."
          (fn [runtime-type _eval-fn _ns-name _fingerprint _opts]
            runtime-type))

(defmulti batch-introspect
          "Introspect all namespaces and their vars in a single batch.

   More efficient than calling list-namespaces + introspect-namespace
   individually for each namespace.

   Arguments:
     runtime-type - keyword
     eval-fn      - (fn [code-string] -> parsed-result)
     opts         - options map with:
                    :exclude-patterns - vector of regex patterns to exclude namespaces

   Returns:
     [{:name \"ns.name\" :doc \"...\" :vars [{:name \"var\" :arglists ... :doc ...} ...]} ...]"
          (fn [runtime-type _eval-fn _opts] runtime-type))

;;; ---------------------------------------------------------------------------
;;; Shared Helpers
;;; ---------------------------------------------------------------------------

(defn infer-symbol-type
  "Infer symbol type from var metadata and optional type-hint.

   Arguments:
     var-meta - map with :macro, :arglists, :type-hint keys

   Returns keyword: :defmacro, :defmulti, :defprotocol, :defn, or :def"
  [{:keys [macro arglists type-hint]}]
  (or (when type-hint
        (case type-hint
          :defmacro    :defmacro
          :defmulti    :defmulti
          :defprotocol :defprotocol
          :protocol-fn :defn
          :defn        :defn
          :def         :def
          nil))
      ;; Fallback for older servers without type-hint
      (cond
        macro    :defmacro
        arglists :defn
        :else    :def)))

(def default-exclude-patterns
     "Default namespace patterns to exclude from introspection."
     [#"clojure\.spec\..*"
      #"clojure\.core\.specs\..*"
      #"nrepl\..*"
      #"borkdude\..*"
      #"sci\..*"
      #"edamame\..*"])

(defn excluded-ns?
  "Check if a namespace name matches any exclusion pattern.

   Arguments:
     ns-name  - namespace name string
     patterns - vector of regex patterns

   Returns true if namespace should be excluded."
  [ns-name patterns]
  (some #(re-matches % ns-name) patterns))

;;; ---------------------------------------------------------------------------
;;; Load Runtime Implementations
;;; ---------------------------------------------------------------------------

(require 'code-browser.sources.runtime.babashka)
