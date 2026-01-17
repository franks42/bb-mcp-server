(ns code-browser.sources.protocol
    "Protocol for project source adapters.

   Each source type (directory, jar, github, nrepl) implements this protocol
   to provide a uniform interface for:
   - Scanning projects for namespaces and symbols
   - Fetching source content on demand
   - Watching for changes (optional)

   Design decisions:
   - D3: URI-centric - all methods work with URI strings
   - D5: Metadata only - scan returns metadata, content fetched separately
   - D7: Isolate volatile sources - nrepl may have different semantics")

;;; ---------------------------------------------------------------------------
;;; Protocol Definition
;;; ---------------------------------------------------------------------------

(defprotocol IProjectSource
             "Protocol for project source adapters.

   Implementations: DirectorySource, JarSource, GitHubSource, NreplSource"

             (scan-project [this]
                           "Scan the project and return metadata for all namespaces and symbols.

     Returns:
       {:project   {:uri/string \"dir://proj@v1\"
                    :uri/source :dir
                    :uri/project \"proj\"
                    :uri/version \"abc123\"
                    :project/root-path \"/path/to/proj\"}
        :namespaces [{:uri/string \"dir://proj@v1/ns.name\"
                      :ns/name \"ns.name\"
                      :ns/file \"src/ns/name.clj\"
                      :ns/files [\"src/ns/name.clj\" \"src/ns/name_impl.clj\"]
                      :ns/doc \"Namespace docstring\"
                      :ns/aliases [{:alias \"str\" :ns \"clojure.string\"}]
                      :ns/refers [{:sym \"join\" :from-ns \"clojure.string\"}]}
                     ...]
        :symbols [{:uri/string \"dir://proj@v1/ns.name/my-fn\"
                   :symbol/name \"my-fn\"
                   :symbol/type :defn
                   :symbol/file \"src/ns/name.clj\"
                   :symbol/line 42
                   :symbol/end-line 55
                   :symbol/doc \"Function docstring\"
                   :symbol/arglists \"([x y])\"
                   :symbol/private? false}
                  ...]}

     Note: Does NOT include source code content (D5).")

             (fetch-source [this uri-string]
                           "Fetch source code for a symbol or namespace.

     Arguments:
       uri-string - Symbol or namespace URI

     Returns:
       {:content \"(defn my-fn ...source code...)\"
        :file \"/absolute/path/to/file.clj\"
        :start-line 42
        :end-line 55}

     Or nil if source cannot be fetched.")

             (watch! [this callback]
                     "Start watching for changes. Optional - may be no-op for static sources.

     Arguments:
       callback - (fn [event] ...) called when changes detected
                  event: {:type :modified|:created|:deleted
                          :uri-string \"affected URI\"}

     Returns:
       Watch handle (for stopping) or nil if not supported.")

             (unwatch! [this handle]
                       "Stop watching for changes. Optional - may be no-op.

     Arguments:
       handle - Watch handle returned by watch!")

             (source-info [this]
                          "Get information about this source.

     Returns:
       {:type :dir|:jar|:github|:nrepl
        :version-type :static|:temporal
        :supports-watch? true|false
        :description \"Human-readable description\"}"))

;;; ---------------------------------------------------------------------------
;;; clj-kondo Analysis Helpers
;;; ---------------------------------------------------------------------------

(def ^:private defined-by->type
     "Map clj-kondo :defined-by to our symbol types.
   Extended from v1's defined-by->label with additional types."
     {'clojure.core/defn        :defn
      'clojure.core/defn-       :defn-
      'clojure.core/def         :def
      'clojure.core/defonce     :defonce
      'clojure.core/declare     :declare
      'clojure.core/defmacro    :defmacro
      'clojure.core/defmulti    :defmulti
      'clojure.core/defmethod   :defmethod
      'clojure.core/defprotocol :defprotocol
      'clojure.core/deftype     :deftype
      'clojure.core/defrecord   :defrecord
      'clojure.test/deftest     :deftest
   ;; Spec definitions
      'clojure.spec.alpha/def   :spec
      'clojure.spec.alpha/fdef  :fspec
   ;; Component/system
      'integrant.core/init-key  :init-key
      'mount.core/defstate      :defstate})

(defn kondo-defined-by->type
  "Convert clj-kondo :defined-by to our symbol type keyword.
   Returns :def as fallback for unknown types."
  [defined-by]
  (get defined-by->type defined-by :def))

(defn kondo-var->symbol-map
  "Convert a clj-kondo var-definition to our symbol attribute map.

   Arguments:
     var-def - clj-kondo var-definition map
     uri-base - Base URI for the project (e.g., \"dir://proj@v1\")

   Returns symbol attribute map suitable for Datalevin transact."
  [var-def uri-base]
  (let [ns-name (str (:ns var-def))
        sym-name (str (:name var-def))
        sym-type (kondo-defined-by->type (:defined-by var-def))]
    {:uri/string (str uri-base "/" ns-name "/" sym-name)
     :uri/namespace ns-name
     :uri/symbol sym-name
     :symbol/name sym-name
     :symbol/type sym-type
     :symbol/file (:filename var-def)
     :symbol/line (:row var-def)
     :symbol/end-line (:end-row var-def)
     :symbol/col (:col var-def)
     :symbol/doc (:doc var-def)
     :symbol/arglists (when-let [args (:arglist-strs var-def)]
                                (pr-str args))
     :symbol/private? (:private var-def)
     :symbol/macro? (:macro var-def)}))

(defn kondo-defmethod->symbol-map
  "Convert a clj-kondo defmethod usage to our symbol attribute map.

   Arguments:
     usage - clj-kondo var-usage with :defmethod true
     uri-base - Base URI for the project

   Returns symbol attribute map for the defmethod."
  [usage uri-base]
  (let [ns-name (str (:from-var usage))  ;; namespace where defmethod is defined
        multi-name (str (:name usage))
        dispatch-val (str (:dispatch-val-str usage))
        ;; Symbol name includes dispatch value for uniqueness
        sym-name (str multi-name " " dispatch-val)]
    {:uri/string (str uri-base "/" ns-name "/" sym-name)
     :uri/namespace ns-name
     :uri/symbol sym-name
     :symbol/name sym-name
     :symbol/type :defmethod
     :symbol/file (:filename usage)
     :symbol/line (:row usage)
     :symbol/end-line (:end-row usage)
     :symbol/col (:col usage)
     :symbol/dispatch-val dispatch-val}))

(defn kondo-ns->namespace-map
  "Convert clj-kondo namespace-definition to our namespace attribute map.

   Arguments:
     ns-def - clj-kondo namespace-definition map
     uri-base - Base URI for the project
     files - Vector of files for this namespace (from var-definitions)

   Returns namespace attribute map suitable for Datalevin transact."
  [ns-def uri-base files]
  (let [ns-name (str (:name ns-def))]
    {:uri/string (str uri-base "/" ns-name)
     :uri/namespace ns-name
     :ns/name ns-name
     :ns/file (:filename ns-def)
     :ns/files (vec files)
     :ns/doc (:doc ns-def)}))

(defn extract-aliases-from-usages
  "Extract namespace aliases from clj-kondo namespace-usages.

   Arguments:
     ns-usages - Vector of clj-kondo namespace-usage maps
     ns-name - Namespace name to filter by

   Returns vector of {:alias \"x\" :ns \"full.ns.name\"}"
  [ns-usages ns-name]
  (->> ns-usages
       (filter #(= (str (:from %)) ns-name))
       (filter :alias)
       (map (fn [u]
              {:alias (str (:alias u))
               :ns (str (:to u))}))
       (distinct)
       (vec)))

(defn extract-refers-from-usages
  "Extract referred symbols from clj-kondo namespace-usages.

   Arguments:
     ns-usages - Vector of clj-kondo namespace-usage maps
     ns-name - Namespace name to filter by

   Returns vector of {:sym \"x\" :from-ns \"full.ns.name\"}"
  [ns-usages ns-name]
  (->> ns-usages
       (filter #(= (str (:from %)) ns-name))
       (filter :refer)
       (mapcat (fn [u]
                 (map (fn [sym]
                        {:sym (str sym)
                         :from-ns (str (:to u))})
                      (:refer u))))
       (distinct)
       (vec)))
