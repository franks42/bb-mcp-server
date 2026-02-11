(ns code-browser.handlers
    "Event handlers for Code Browser v2.

   Handles browser requests by querying Datalevin and updating synced state.
   Uses the IProjectSource protocol for source fetching."
    (:require [code-browser.sync :as sync]
              [code-browser.db.protocol :as db-proto]
              [code-browser.sources.protocol :as source-proto]
              [code-browser.uri :as uri]
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

(defonce ^{:doc "Callback to core/add-source! — avoids circular dep."}
 !add-source-fn (atom nil))

(defn register-add-source-fn!
  "Register the add-source! callback from core (avoids circular dependency)."
  [f]
  (reset! !add-source-fn f))

;;; ---------------------------------------------------------------------------
;;; Query Helpers
;;; ---------------------------------------------------------------------------

(defn- query-projects
  "Query all projects from Datalevin, deduplicated by :uri/project name."
  [db]
  (when db
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :where [?e :uri/source _]
                                [?e :project/root-path _]])]
      (->> results
           (map first)
           (sort-by :uri/project)
           (reduce (fn [acc p]
                     (if (= (:uri/project (peek acc)) (:uri/project p))
                       acc
                       (conj acc p)))
                   [])
           vec))))

(defn- query-namespaces
  "Query namespaces for a project from Datalevin.
   Matches by project name directly, making the query version-agnostic."
  [db project-name]
  (when (and db project-name)
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :in $ ?proj-name
                                :where [?e :ns/name _]
                                [?e :uri/project ?proj-name]]
                              [project-name])]
      (->> results
           (map first)
           (sort-by :ns/name)
           vec))))

(defn- query-symbols
  "Query symbols for a namespace from Datalevin.
   Matches by project name and namespace name directly, version-agnostic."
  [db project-name ns-name]
  (when (and db project-name ns-name)
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :in $ ?proj-name ?ns-name
                                :where [?e :symbol/name _]
                                [?e :uri/project ?proj-name]
                                [?e :uri/namespace ?ns-name]]
                              [project-name ns-name])]
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
           (let [project-name (:uri/project (uri/parse project-uri))
                 namespaces (query-namespaces db project-name)]
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
           (let [parsed (uri/parse ns-uri)
                 project-name (:uri/project parsed)
                 ns-name (:uri/namespace parsed)
                 symbols (query-symbols db project-name ns-name)
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
;;; Stateless Fetch API
;;; ---------------------------------------------------------------------------

(defn- derive-property
  "Derive the property keyword from a parsed URI's query params and level.
   Explicit :property param takes precedence, then URI ?view= query param,
   then default based on URI level."
  [parsed explicit-property]
  (or explicit-property
      (when-let [view (get-in parsed [:uri/query "view"])]
                (keyword view))
      (cond
        (nil? parsed)              :project-list
        (:uri/symbol parsed)       :source
        (:uri/namespace parsed)    :symbol-list
        :else                      :ns-list)))

(defn handle-fetch
  "Stateless query by URI + optional property. Returns data without mutating sync state.

   The view/property can be specified three ways (in priority order):
   1. Explicit :property param (backwards compat)
   2. URI query param ?view=ns-list
   3. Default based on URI level (project→ns-list, namespace→symbol-list, symbol→source)

   Request: {:uri \"dir://...?view=ns-list\"} or {:uri \"dir://...\" :property :ns-list}
   Response: {:success true :data [...]} or {:success false :error \"...\"}

   URI level determines valid properties:
   - Project URI  → :ns-list
   - Namespace URI → :symbol-list, :aliases, :refers
   - Symbol URI   → :source, :doc, :deps, :callers
   - (none)       → :project-list"
  [{:keys [uri property]}]
  (log/log! {:level :info
             :id ::handle-fetch
             :msg "Stateless fetch"
             :data {:uri uri :property property}})
  (try
   (if-let [db (get-db)]
           (let [parsed (when uri (uri/parse uri))
                 base (when uri (uri/base-uri uri))
                 property (derive-property parsed property)
                 project-name (:uri/project parsed)
                 ns-name (:uri/namespace parsed)
                 symbol-name (:uri/symbol parsed)]
             (case property
               :project-list
               {:success true :data (query-projects db)}

               :ns-list
               (if (and parsed (nil? ns-name))
                 {:success true :data (query-namespaces db project-name)}
                 {:success false :error "ns-list requires a project-level URI"})

               :symbol-list
               (if (and parsed ns-name (nil? symbol-name))
                 {:success true :data (query-symbols db project-name ns-name)}
                 {:success false :error "symbol-list requires a namespace-level URI"})

               :aliases
               (if (and parsed ns-name)
                 {:success true :data (query-aliases db ns-name)}
                 {:success false :error "aliases requires a namespace-level URI"})

               :refers
               (if (and parsed ns-name)
                 {:success true :data (query-refers db ns-name)}
                 {:success false :error "refers requires a namespace-level URI"})

               :source
               (if (and parsed symbol-name)
                 {:success true :data (fetch-source base)}
                 {:success false :error "source requires a symbol-level URI"})

               :doc
               (if (and parsed symbol-name)
                 (let [symbols (query-symbols db project-name ns-name)
                       sym (->> symbols
                                (filter #(= (:symbol/name %) symbol-name))
                                first)]
                   {:success true :data sym})
                 {:success false :error "doc requires a symbol-level URI"})

               :deps
               (if (and parsed symbol-name)
                 {:success true :data []}
                 {:success false :error "deps requires a symbol-level URI"})

               :callers
               (if (and parsed symbol-name)
                 {:success true :data []}
                 {:success false :error "callers requires a symbol-level URI"})

          ;; Unknown property
               {:success false :error (str "Unknown property: " property)}))
           {:success false :error "No database configured"})
   (catch Exception e
          (log/log! {:level :error
                     :id ::handle-fetch-error
                     :msg "Fetch failed"
                     :data {:uri uri :property property :error (ex-message e)}})
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
   - :code-browser-v2/fetch {:uri \"...\" :property :ns-list|:symbol-list|... :widget-id :w1}
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

    :code-browser-v2/fetch
    (let [result (handle-fetch {:uri (:uri data) :property (:property data)})]
      [:code-browser-v2/fetch-response
       (assoc result
              :widget-id (:widget-id data)
              :property (or (:property data)
                            (derive-property
                             (when (:uri data) (uri/parse (:uri data)))
                             nil)))])

    :code-browser-v2/toggle-sort-mode
    [:code-browser-v2/sort-mode-toggled {:mode (sync/toggle-sort-mode!)}]

    :code-browser-v2/clear-error
    (do (sync/clear-error!)
        [:code-browser-v2/error-cleared {}])

    :code-browser-v2/add-project
    (let [add-fn @!add-source-fn]
      (if add-fn
        [:code-browser-v2/add-project-result (add-fn data)]
        [:code-browser-v2/add-project-result
         {:success false :error "Not initialized"}]))

    ;; Unknown event
    (do
     (log/log! {:level :warn
                :id ::unknown-event
                :msg "Unknown event"
                :data {:event-id event-id}})
     nil)))
