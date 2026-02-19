(ns code-browser.db.protocol
    "Portable Datalog interface for Code Browser v2.

   This protocol abstracts over Datalevin (production) and Datascript (tests).
   All code browser queries go through this interface, making the DB backend
   swappable without changing application code.

   Design decision D2: Datalevin with portable IDatalogDB interface")

;;; ---------------------------------------------------------------------------
;;; Protocol Definition
;;; ---------------------------------------------------------------------------

(defprotocol IDatalogDB
             "Protocol for Datalog database operations.
   Implementations: DatalevinDB, DatascriptDB (for tests)"

             (q [this query] [this query args]
                "Execute a Datalog query.
     query - Datalog query vector
     args  - Additional query inputs (after $db)")

             (pull [this pattern eid]
                   "Pull entity data.
     pattern - Pull pattern like '[*]' or '[:symbol/name :symbol/type]'
     eid     - Entity id or lookup ref like [:uri/string \"...\"]")

             (transact! [this tx-data]
                        "Transact data into the database.
     tx-data - Vector of transaction data (maps or datoms)")

             (entity [this eid]
                     "Get entity as a map.
     eid - Entity id or lookup ref")

             (db [this]
                 "Get current database value (for raw Datalog if needed)")

             (close! [this]
                     "Close the database connection. No-op for in-memory DBs."))

;;; ---------------------------------------------------------------------------
;;; Schema Constants
;;; ---------------------------------------------------------------------------

(def base-schema
     "Base schema for code browser - see docs/design/code-browser-schema.md for details.

   Key design decisions:
   - D3: URI is the universal identifier (:uri/string is :db.unique/identity)
   - D5: Metadata only - no source code strings stored
   - Hierarchy via refs (project → namespace → symbol)"

     {;; === URI Identity ===
   ;; Every entity is addressable via URI
      :uri/string       {:db/unique :db.unique/identity
                         :db/doc "Full URI string - primary identifier"}
      :uri/source       {:db/doc "Source type: :dir :jar :github :nrepl"}
      :uri/project      {:db/doc "Project identifier"}
      :uri/version      {:db/doc "Version/SHA/snapshot-id"}
      :uri/version-type {:db/doc "Version type: :static or :temporal"}
      :uri/namespace    {:db/doc "Namespace name (if applicable)"}
      :uri/symbol       {:db/doc "Symbol name (if applicable)"}

   ;; === Hierarchy Refs ===
      :uri/parent       {:db/valueType :db.type/ref
                         :db/doc "Parent entity (symbol→ns, ns→project)"}

   ;; === Project Attributes ===
      :project/root-path   {:db/doc "Filesystem path for :dir sources"}
      :project/jar-path    {:db/doc "JAR file path for :jar sources"}
      :project/github-url  {:db/doc "GitHub URL for :github sources"}
      :project/nrepl-host  {:db/doc "nREPL host:port for :nrepl sources"}
      :project/namespaces  {:db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/many
                            :db/doc "Namespaces in this project"}

   ;; === Namespace Attributes ===
      :ns/name         {:db/doc "Namespace name (e.g., 'clojure.core')"}
      :ns/file         {:db/doc "Primary source file path"}
      :ns/files        {:db/cardinality :db.cardinality/many
                        :db/doc "All source files (for multi-file namespaces)"}
      :ns/doc          {:db/doc "Namespace docstring"}
      :ns/symbols      {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/doc "Symbols defined in this namespace"}

   ;; === Alias Entities ===
   ;; URI: dir://proj@v/ns.name#alias:str
      :alias/from-ns   {:db/doc "Namespace name where alias is defined"}
      :alias/name      {:db/doc "Alias name (e.g., 'str')"}
      :alias/to-ns     {:db/doc "Target namespace (e.g., 'clojure.string')"}

   ;; === Refer Entities ===
   ;; URI: dir://proj@v/ns.name#refer:join
      :refer/from-ns   {:db/doc "Namespace name where refer is used"}
      :refer/symbol    {:db/doc "Symbol name being referred (e.g., 'join')"}
      :refer/from-ns-source {:db/doc "Namespace the symbol comes from"}

   ;; === Symbol Attributes (METADATA ONLY - D5) ===
      :symbol/name     {:db/doc "Symbol name"}
      :symbol/type     {:db/valueType :db.type/keyword
                        :db/doc "Symbol type: :defn :def :defmacro :defmulti :defmethod :defprotocol :defrecord :deftype :ns etc"}
      :symbol/file     {:db/doc "Source file path (content fetched on demand)"}
      :symbol/line     {:db/doc "Start line number"}
      :symbol/end-line {:db/doc "End line number (for source extraction)"}
      :symbol/col      {:db/doc "Start column"}
      :symbol/doc      {:db/doc "Docstring (small, ok to store)"}
      :symbol/arglists {:db/doc "Argument lists for functions/macros"}
      :symbol/private? {:db/doc "Is this a private var?"}
      :symbol/macro?   {:db/doc "Is this a macro?"}
      :symbol/dynamic? {:db/doc "Is this a dynamic var?"}

   ;; === Relationships ===
      :symbol/deps     {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/doc "Symbols this symbol calls/uses"}
      :symbol/callers  {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/doc "Symbols that call/use this symbol"}

   ;; === Top-Level Forms (non-defining) ===
      :symbol/top-level?     {:db/doc "Is this a top-level non-defining form?"}
      :symbol/top-level-kind {:db/valueType :db.type/keyword
                              :db/doc "Kind: :comment :side-effect :config :require :load :in-ns :form"}

   ;; === Protocol/Multimethod Specifics ===
      :symbol/protocol      {:db/valueType :db.type/ref
                             :db/doc "For protocol methods: ref to protocol"}
      :symbol/dispatch-val  {:db/doc "For defmethod: dispatch value as string"}
      :symbol/impls         {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many
                             :db/doc "For protocols/multimethods: implementations"}})

;;; ---------------------------------------------------------------------------
;;; Common Queries
;;; ---------------------------------------------------------------------------

(def queries
     "Named queries for common operations. Use with (q db query args)."

     {:projects
      '[:find [(pull ?p [:uri/string :uri/project :uri/version :uri/source
                         :project/root-path]) ...]
        :where [?p :uri/source _]
        [?p :uri/project _]
        (not [?p :uri/namespace _])]

      :namespaces-for-project
      '[:find [(pull ?ns [:uri/string :ns/name :ns/file :ns/doc]) ...]
        :in $ ?project-uri
        :where [?p :uri/string ?project-uri]
        [?p :project/namespaces ?ns]]

      :symbols-for-namespace
      '[:find [(pull ?sym [:uri/string :symbol/name :symbol/type :symbol/line
                           :symbol/file :symbol/doc :symbol/arglists
                           :symbol/private? :symbol/top-level?
                           :symbol/top-level-kind]) ...]
        :in $ ?ns-uri
        :where [?ns :uri/string ?ns-uri]
        [?ns :ns/symbols ?sym]]

      :symbol-detail
      '[:find (pull ?sym [*]) .
        :in $ ?sym-uri
        :where [?sym :uri/string ?sym-uri]]

      :symbol-callers
      '[:find [(pull ?caller [:uri/string :symbol/name :symbol/type]) ...]
        :in $ ?sym-uri
        :where [?sym :uri/string ?sym-uri]
        [?caller :symbol/deps ?sym]]

      :symbol-deps
      '[:find [(pull ?dep [:uri/string :symbol/name :symbol/type]) ...]
        :in $ ?sym-uri
        :where [?sym :uri/string ?sym-uri]
        [?sym :symbol/deps ?dep]]

      :find-symbol-by-name
      '[:find [(pull ?sym [:uri/string :symbol/name :symbol/type :uri/namespace]) ...]
        :in $ ?name
        :where [?sym :symbol/name ?name]]

      :aliases-for-namespace
      '[:find [(pull ?a [:uri/string :alias/name :alias/to-ns]) ...]
        :in $ ?ns-name
        :where [?a :alias/from-ns ?ns-name]]

      :refers-for-namespace
      '[:find [(pull ?r [:uri/string :refer/symbol :refer/from-ns-source]) ...]
        :in $ ?ns-name
        :where [?r :refer/from-ns ?ns-name]]})
