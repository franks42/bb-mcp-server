(ns code-browser.handlers
    "Event handlers for Code Browser v2.

   Handles browser requests by querying Datalevin and updating synced state.
   Uses the IProjectSource protocol for source fetching."
    (:require [code-browser.sync :as sync]
              [code-browser.db.protocol :as db-proto]
              [code-browser.sources.protocol :as source-proto]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Module State
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Module state: database and sources."}
 !module-state
         (atom {:db nil
                :sources {}}))

(defn get-db
  "Get the current database instance."
  []
  (:db @!module-state))

(defn get-source
  "Get a source adapter by project URI."
  [project-uri]
  (get-in @!module-state [:sources project-uri]))

(defn set-db!
  "Set the database instance."
  [db]
  (swap! !module-state assoc :db db))

(defn register-source!
  "Register a source adapter for a project."
  [project-uri source]
  (swap! !module-state assoc-in [:sources project-uri] source))

(defn unregister-source!
  "Unregister a source adapter."
  [project-uri]
  (swap! !module-state update :sources dissoc project-uri))

;;; ---------------------------------------------------------------------------
;;; Query Helpers
;;; ---------------------------------------------------------------------------

(defn- query-projects
  "Query all projects from Datalevin."
  [db]
  (when db
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :where [?e :uri/source _]
                                [?e :project/root-path _]])]
      (->> results
           (map first)
           (sort-by :uri/project)
           vec))))

(defn- query-namespaces
  "Query namespaces for a project from Datalevin."
  [db project-uri]
  (when (and db project-uri)
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :in $ ?proj-uri
                                :where [?p :uri/string ?proj-uri]
                                [?e :ns/name _]
                                [?e :uri/project ?proj-name]
                                [?p :uri/project ?proj-name]]
                              [project-uri])]
      (->> results
           (map first)
           (sort-by :ns/name)
           vec))))

(defn- query-symbols
  "Query symbols for a namespace from Datalevin."
  [db ns-uri]
  (when (and db ns-uri)
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :in $ ?ns-uri
                                :where [?n :uri/string ?ns-uri]
                                [?n :ns/name ?ns-name]
                                [?e :symbol/name _]
                                [?e :uri/namespace ?ns-name]]
                              [ns-uri])]
      (->> results
           (map first)
           (sort-by (juxt :symbol/type :symbol/name))
           vec))))

(defn- query-aliases
  "Query aliases for a namespace from Datalevin."
  [db ns-name]
  (when (and db ns-name)
    (let [results (db-proto/q db
                              '[:find (pull ?a [:uri/string :alias/name :alias/to-ns])
                                :in $ ?ns-name
                                :where [?a :alias/from-ns ?ns-name]]
                              [ns-name])]
      (->> results
           (map first)
           (sort-by :alias/name)
           vec))))

(defn- query-refers
  "Query refers for a namespace from Datalevin."
  [db ns-name]
  (when (and db ns-name)
    (let [results (db-proto/q db
                              '[:find (pull ?r [:uri/string :refer/symbol :refer/from-ns-source])
                                :in $ ?ns-name
                                :where [?r :refer/from-ns ?ns-name]]
                              [ns-name])]
      (->> results
           (map first)
           (sort-by :refer/symbol)
           vec))))

(defn- fetch-source
  "Fetch source for a symbol using registered source adapters."
  [symbol-uri]
  (let [sources (:sources @!module-state)]
    ;; Try each source until one returns content
    (some (fn [[_proj-uri source]]
            (source-proto/fetch-source source symbol-uri))
          sources)))

;;; ---------------------------------------------------------------------------
;;; Event Handlers
;;; ---------------------------------------------------------------------------

(defn handle-load-projects!
  "Load all projects from database and update state."
  []
  (log/log! {:level :info
             :id ::load-projects
             :msg "Loading projects"})
  (sync/set-loading! true)
  (try
   (if-let [db (get-db)]
           (let [projects (query-projects db)]
             (sync/set-projects! projects)
             (sync/set-loading! false)
             {:success true :count (count projects)})
           (do
            (sync/set-error! "No database configured")
            {:success false :error "No database configured"}))
   (catch Exception e
          (log/log! {:level :error
                     :id ::load-projects-error
                     :msg "Failed to load projects"
                     :data {:error (ex-message e)}})
          (sync/set-error! (str "Failed to load projects: " (ex-message e)))
          {:success false :error (ex-message e)})))

(defn handle-select-project!
  "Select a project and load its namespaces."
  [project-uri]
  (log/log! {:level :info
             :id ::select-project
             :msg "Selecting project"
             :data {:uri project-uri}})
  (sync/set-loading! true)
  (try
   (if-let [db (get-db)]
           (let [namespaces (query-namespaces db project-uri)]
             (sync/select-project! project-uri namespaces)
             {:success true :count (count namespaces)})
           (do
            (sync/set-error! "No database configured")
            {:success false :error "No database configured"}))
   (catch Exception e
          (log/log! {:level :error
                     :id ::select-project-error
                     :msg "Failed to select project"
                     :data {:uri project-uri
                            :error (ex-message e)}})
          (sync/set-error! (str "Failed to select project: " (ex-message e)))
          {:success false :error (ex-message e)})))

(defn handle-select-namespace!
  "Select a namespace and load its symbols, aliases, and refers."
  [ns-uri]
  (log/log! {:level :info
             :id ::select-namespace
             :msg "Selecting namespace"
             :data {:uri ns-uri}})
  (sync/set-loading! true)
  (try
   (if-let [db (get-db)]
     ;; Extract ns-name from the namespace entity
           (let [ns-entity (db-proto/pull db '[:ns/name] [:uri/string ns-uri])
                 ns-name (:ns/name ns-entity)
                 symbols (query-symbols db ns-uri)
                 aliases (when ns-name (query-aliases db ns-name))
                 refers (when ns-name (query-refers db ns-name))]
             (sync/select-namespace! ns-uri symbols aliases refers)
             {:success true
              :symbols (count symbols)
              :aliases (count aliases)
              :refers (count refers)})
           (do
            (sync/set-error! "No database configured")
            {:success false :error "No database configured"}))
   (catch Exception e
          (log/log! {:level :error
                     :id ::select-namespace-error
                     :msg "Failed to select namespace"
                     :data {:uri ns-uri
                            :error (ex-message e)}})
          (sync/set-error! (str "Failed to select namespace: " (ex-message e)))
          {:success false :error (ex-message e)})))

(defn handle-select-symbol!
  "Select a symbol and fetch its source."
  [symbol-uri]
  (log/log! {:level :info
             :id ::select-symbol
             :msg "Selecting symbol"
             :data {:uri symbol-uri}})
  (sync/set-loading! true)
  (try
   (let [source (fetch-source symbol-uri)]
     (sync/select-symbol! symbol-uri source)
     {:success true :has-source (some? source)})
   (catch Exception e
          (log/log! {:level :error
                     :id ::select-symbol-error
                     :msg "Failed to select symbol"
                     :data {:uri symbol-uri
                            :error (ex-message e)}})
          (sync/set-error! (str "Failed to fetch source: " (ex-message e)))
          {:success false :error (ex-message e)})))

;;; ---------------------------------------------------------------------------
;;; Event Dispatch
;;; ---------------------------------------------------------------------------

(defn dispatch-event
  "Dispatch a browser event to the appropriate handler.

   Events:
   - :code-browser-v2/load-projects {}
   - :code-browser-v2/select-project {:uri \"...\"}
   - :code-browser-v2/select-namespace {:uri \"...\"}
   - :code-browser-v2/select-symbol {:uri \"...\"}
   - :code-browser-v2/toggle-sort-mode {}
   - :code-browser-v2/clear-error {}

   Returns: [response-event-id response-data] or nil"
  [event-id data]
  (log/log! {:level :debug
             :id ::dispatch-event
             :msg "Dispatching event"
             :data {:event-id event-id}})
  (case event-id
    :code-browser-v2/load-projects
    [:code-browser-v2/projects-loaded (handle-load-projects!)]

    :code-browser-v2/select-project
    [:code-browser-v2/project-selected (handle-select-project! (:uri data))]

    :code-browser-v2/select-namespace
    [:code-browser-v2/namespace-selected (handle-select-namespace! (:uri data))]

    :code-browser-v2/select-symbol
    [:code-browser-v2/symbol-selected (handle-select-symbol! (:uri data))]

    :code-browser-v2/toggle-sort-mode
    [:code-browser-v2/sort-mode-toggled {:mode (sync/toggle-sort-mode!)}]

    :code-browser-v2/clear-error
    (do (sync/clear-error!)
        [:code-browser-v2/error-cleared {}])

    ;; Unknown event
    (do
     (log/log! {:level :warn
                :id ::unknown-event
                :msg "Unknown event"
                :data {:event-id event-id}})
     nil)))
