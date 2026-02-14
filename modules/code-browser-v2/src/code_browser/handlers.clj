(ns code-browser.handlers
    "Event handlers for Code Browser v2.

   Handles browser requests by querying Datalevin and updating synced state.
   Uses the IProjectSource protocol for source fetching."
    (:require [clojure.set :as set]
              [code-browser.sync :as sync]
              [code-browser.db.protocol :as db-proto]
              [code-browser.sources.protocol :as source-proto]
              [code-browser.sources.nrepl :as nrepl-source]
              [code-browser.uri :as uri]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Module State
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Module state: database and sources."}
 !module-state
         (atom {:db nil
                :sources {}
                :fingerprints {}}))

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

(defn store-fingerprint!
  "Store a var fingerprint keyed by ns/var-name."
  [ns-name var-name fingerprint]
  (let [k (str ns-name "/" var-name)]
    (swap! !module-state assoc-in [:fingerprints k] fingerprint)))

(defn get-fingerprint
  "Get a stored fingerprint for a var."
  [ns-name var-name]
  (let [k (str ns-name "/" var-name)]
    (get-in @!module-state [:fingerprints k])))

(defn clear-fingerprint!
  "Remove a stored fingerprint for a var."
  [ns-name var-name]
  (let [k (str ns-name "/" var-name)]
    (swap! !module-state update :fingerprints dissoc k)))

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
                                (or [?e :project/root-path _]
                                    [?e :project/nrepl-host _])])]
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

(defn- fetch-var-value
  "Fetch the current runtime value for a var from nREPL sources.
   Iterates registered sources, finds nREPL sources by :type, calls fetch-var-value."
  [ns-name var-name]
  (let [sources (:sources @!module-state)]
    (some (fn [[_proj-uri source]]
            (when (= :nrepl (:type (source-proto/source-info source)))
              (nrepl-source/fetch-var-value source ns-name var-name {})))
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

               :var-value
               (if (and parsed symbol-name)
                 (if-let [result (fetch-var-value ns-name symbol-name)]
                         (do
                          (when (and (:var-id result) (:value-id result))
                            (store-fingerprint! ns-name symbol-name
                                                {:var-id (:var-id result)
                                                 :value-id (:value-id result)}))
                          {:success true :data result})
                         {:success false
                          :error "Value unavailable (nREPL sources only)"})
                 {:success false
                  :error "var-value requires a symbol-level URI"})

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
;;; Incremental Datalevin Updates
;;; ---------------------------------------------------------------------------

(defn- find-first-nrepl-source
  "Find the first registered nREPL source. Returns [proj-uri source] or nil."
  []
  (some (fn [[proj-uri source]]
          (when (= :nrepl (:type (source-proto/source-info source)))
            [proj-uri source]))
        (:sources @!module-state)))

(defn- update-namespace-symbols!
  "Targeted Datalevin update for one namespace's symbols.
   Retracts existing symbol entities and transacts new ones built from symbols data.
   Also ensures the namespace entity exists (upsert via :uri/string identity)."
  [db ns-name symbols source]
  (let [project-name (:project-name source)
        version (:version source)
        uri-base (:uri-base source)
        ;; Query existing symbol entity IDs for this project+namespace
        existing (db-proto/q db
                             '[:find ?e
                               :in $ ?proj-name ?ns-name
                               :where [?e :symbol/name _]
                               [?e :uri/project ?proj-name]
                               [?e :uri/namespace ?ns-name]]
                             [project-name ns-name])
        retract-tx (mapv (fn [[eid]] [:db/retractEntity eid]) existing)
        ;; Build new symbol entities from the fresh symbols data
        new-symbols (mapv #(nrepl-source/build-symbol-entity
                            % ns-name uri-base project-name version)
                          symbols)
        ;; Ensure namespace entity exists (upsert via :uri/string identity)
        ns-entity {:uri/string (str uri-base "/" ns-name)
                   :uri/source :nrepl
                   :uri/project project-name
                   :uri/version version
                   :uri/version-type :temporal
                   :uri/namespace ns-name
                   :ns/name ns-name}]
    (log/log! {:level :info
               :id ::update-namespace-symbols
               :msg "Targeted symbol update for namespace"
               :data {:ns ns-name
                      :retracted (count retract-tx)
                      :new-count (count new-symbols)}})
    ;; Retract old symbols first, then transact new ones + namespace entity
    (when (seq retract-tx)
      (db-proto/transact! db retract-tx))
    (db-proto/transact! db (into [ns-entity] new-symbols))))

;;; ---------------------------------------------------------------------------
;;; Var Fingerprint Checking
;;; ---------------------------------------------------------------------------

(defn- check-var-fingerprint-via-source
  "Check a var's fingerprint using the first available nREPL source."
  [ns-name var-name fingerprint opts]
  (let [sources (:sources @!module-state)]
    (some (fn [[_proj-uri source]]
            (when (= :nrepl (:type (source-proto/source-info source)))
              (nrepl-source/check-var-fingerprint
               source ns-name var-name fingerprint opts)))
          sources)))

(defn- handle-check-var-fingerprint
  "Handle a fingerprint check request for a var-value widget.
   Returns {:success true :changed? bool :data map-or-nil}."
  [data]
  (let [{:keys [ns-name var-name]} data
        stored (get-fingerprint ns-name var-name)]
    (log/log! {:level :trace
               :id ::check-var-fingerprint
               :msg "Checking var fingerprint"
               :data {:ns ns-name :var var-name
                      :has-stored? (some? stored)}})
    (if stored
      (try
       (let [result (check-var-fingerprint-via-source
                     ns-name var-name stored {})]
         (if (and (map? result) (:changed? result))
           ;; Changed — store new fingerprint and return full data
           (do
            (when (and (:var-id result) (:value-id result))
              (store-fingerprint! ns-name var-name
                                  {:var-id (:var-id result)
                                   :value-id (:value-id result)}))
            (log/log! {:level :debug
                       :id ::var-value-changed
                       :msg "Var value changed"
                       :data {:ns ns-name :var var-name}})
            {:success true :changed? true :data result})
           ;; Unchanged
           {:success true :changed? false}))
       (catch Exception e
              (log/log! {:level :warn
                         :id ::fingerprint-check-error
                         :msg "Fingerprint check failed"
                         :data {:ns ns-name :var var-name
                                :error (ex-message e)}})
              {:success true :changed? false}))
      ;; No stored fingerprint — tell browser to do a full fetch
      {:success true :changed? true :data nil})))

(defn- check-ns-list-fingerprint-via-source
  "Check a project's namespace list fingerprint using the first available nREPL source."
  [project-uri fingerprint opts]
  (let [sources (:sources @!module-state)]
    (some (fn [[_proj-uri source]]
            (when (= :nrepl (:type (source-proto/source-info source)))
              (nrepl-source/check-ns-list-fingerprint
               source project-uri fingerprint opts)))
          sources)))

(defn- update-ns-list-in-datalevin!
  "Diff the current Datalevin namespace list with the new runtime namespaces
   and apply targeted adds/removes. Returns {:added N :removed N}."
  [db project-name new-ns-names source]
  (let [existing-ns (query-namespaces db project-name)
        existing-names (set (map :ns/name existing-ns))
        new-names (set new-ns-names)
        added (set/difference new-names existing-names)
        removed (set/difference existing-names new-names)
        uri-base (:uri-base source)
        version (:version source)]
    (log/log! {:level :info
               :id ::update-ns-list
               :msg "Updating namespace list in Datalevin"
               :data {:project project-name
                      :existing (count existing-names)
                      :new (count new-names)
                      :added (count added)
                      :removed (count removed)}})
    ;; Remove deleted namespaces and their symbols
    (when (seq removed)
      (doseq [ns-name removed]
        ;; Retract symbol entities for this namespace
             (let [sym-eids (db-proto/q db
                                        '[:find ?e
                                          :in $ ?proj-name ?ns-name
                                          :where [?e :symbol/name _]
                                          [?e :uri/project ?proj-name]
                                          [?e :uri/namespace ?ns-name]]
                                        [project-name ns-name])
                   ns-eids (db-proto/q db
                                       '[:find ?e
                                         :in $ ?proj-name ?ns-name
                                         :where [?e :ns/name ?ns-name]
                                         [?e :uri/project ?proj-name]]
                                       [project-name ns-name])
                   all-eids (concat (map first sym-eids) (map first ns-eids))
                   retract-tx (mapv (fn [eid] [:db/retractEntity eid]) all-eids)]
               (when (seq retract-tx)
                 (db-proto/transact! db retract-tx)))))
    ;; Add new namespace entities (symbols populated when user opens widget)
    (when (seq added)
      (let [ns-entities (mapv (fn [ns-name]
                                {:uri/string (str uri-base "/" ns-name)
                                 :uri/source :nrepl
                                 :uri/project project-name
                                 :uri/version version
                                 :uri/version-type :temporal
                                 :uri/namespace ns-name
                                 :ns/name ns-name})
                              added)]
        (db-proto/transact! db ns-entities)))
    {:added (count added) :removed (count removed)}))

(defn- handle-check-ns-list-fingerprint
  "Handle a fingerprint check request for a ns-list widget.
   Returns {:success true :changed? bool :data map-or-nil}.

   When namespace list changes, diffs with Datalevin and adds/removes
   namespace entities incrementally."
  [data]
  (let [{:keys [project-uri]} data
        stored (get-fingerprint (str "ns-list:" project-uri) nil)]
    (log/log! {:level :trace
               :id ::check-ns-list-fingerprint
               :msg "Checking namespace list fingerprint"
               :data {:project project-uri
                      :has-stored? (some? stored)}})
    (if stored
      (try
       (let [result (check-ns-list-fingerprint-via-source
                     project-uri stored {})]
         (if (and (map? result) (:changed? result))
           ;; Changed — store new fingerprint and update Datalevin
           (do
            (when (and (:count result) (:hash result))
              (store-fingerprint! (str "ns-list:" project-uri) nil
                                  {:count (:count result)
                                   :hash (:hash result)}))
            (log/log! {:level :debug
                       :id ::ns-list-changed
                       :msg "Namespace list changed"
                       :data {:project project-uri}})
            (when (and (:namespaces result) (get-db))
              (let [parsed (uri/parse project-uri)
                    project-name (:uri/project parsed)]
                (when-let [[_proj-uri source] (find-first-nrepl-source)]
                          (update-ns-list-in-datalevin!
                           (get-db) project-name
                           (map :name (:namespaces result))
                           source))))
            {:success true :changed? true})
           ;; Unchanged
           {:success true :changed? false}))
       (catch Exception e
              (log/log! {:level :warn
                         :id ::ns-list-fingerprint-check-error
                         :msg "Namespace list fingerprint check failed"
                         :data {:project project-uri
                                :error (ex-message e)}})
              {:success true :changed? false}))
      ;; No stored fingerprint — compute and store initial fingerprint
      (do
       (log/log! {:level :debug
                  :id ::ns-list-first-check
                  :msg "No stored ns-list fingerprint, computing initial"
                  :data {:project project-uri}})
       (let [result (check-ns-list-fingerprint-via-source
                     project-uri nil {})]
         (when (and (map? result) (:count result) (:hash result))
           (store-fingerprint! (str "ns-list:" project-uri) nil
                               {:count (:count result)
                                :hash (:hash result)})))
       {:success true :changed? true}))))

(defn- check-symbol-list-fingerprint-via-source
  "Check a namespace's symbol list fingerprint using the first available nREPL source."
  [ns-name fingerprint opts]
  (log/log! {:level :trace
             :id ::check-symbol-list-fingerprint-via-source
             :msg "Checking symbol list fingerprint via source"
             :data {:ns ns-name
                    :stored-fingerprint fingerprint}})
  (let [sources (:sources @!module-state)
        result (some (fn [[_proj-uri source]]
                       (when (= :nrepl (:type (source-proto/source-info source)))
                         (nrepl-source/check-symbol-list-fingerprint
                          source ns-name fingerprint opts)))
                     sources)]
    (log/log! {:level :trace
               :id ::check-symbol-list-fingerprint-via-source-result
               :msg "Symbol list fingerprint check result"
               :data {:ns ns-name
                      :result result}})
    result))

(defn- handle-check-symbol-list-fingerprint
  "Handle a fingerprint check request for a symbol-list widget.
   Returns {:success true :changed? bool :data map-or-nil}.

   When runtime changes detected, performs targeted Datalevin update for
   just the changed namespace's symbols (not a full rescan)."
  [data]
  (let [{:keys [ns-name]} data
        stored (get-fingerprint (str "symbol-list:" ns-name) nil)]
    (log/log! {:level :trace
               :id ::check-symbol-list-fingerprint
               :msg "Checking symbol list fingerprint"
               :data {:ns ns-name
                      :has-stored? (some? stored)}})
    (if stored
      (try
       (let [result (check-symbol-list-fingerprint-via-source
                     ns-name stored {})]
         (if (and (map? result) (:changed? result))
           ;; Changed — store new fingerprint, do targeted symbol update
           (do
            (when (and (:count result) (:hash result))
              (store-fingerprint! (str "symbol-list:" ns-name) nil
                                  {:count (:count result)
                                   :hash (:hash result)}))
            (log/log! {:level :debug
                       :id ::symbol-list-changed
                       :msg "Symbol list changed, updating namespace"
                       :data {:ns ns-name
                              :symbol-count (count (:symbols result))}})
            (when-let [db (get-db)]
                      (when-let [[_proj-uri source] (find-first-nrepl-source)]
                                (update-namespace-symbols!
                                 db ns-name (:symbols result) source)))
            {:success true :changed? true})
           ;; Unchanged
           {:success true :changed? false}))
       (catch Exception e
              (log/log! {:level :warn
                         :id ::symbol-list-fingerprint-check-error
                         :msg "Symbol list fingerprint check failed"
                         :data {:ns ns-name
                                :error (ex-message e)}})
              {:success true :changed? false}))
      ;; No stored fingerprint — compute and store initial, do targeted update
      (do
       (log/log! {:level :debug
                  :id ::symbol-list-first-check
                  :msg "No stored symbol-list fingerprint, computing initial"
                  :data {:ns ns-name}})
       (let [result (check-symbol-list-fingerprint-via-source
                     ns-name nil {})]
         (when (and (map? result) (:count result) (:hash result))
           (store-fingerprint! (str "symbol-list:" ns-name) nil
                               {:count (:count result)
                                :hash (:hash result)}))
         (when (and (map? result) (:symbols result))
           (when-let [db (get-db)]
                     (when-let [[_proj-uri source] (find-first-nrepl-source)]
                               (update-namespace-symbols!
                                db ns-name (:symbols result) source)))))
       {:success true :changed? true}))))

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
   - :code-browser-v2/check-var-fingerprint {:ns-name :var-name :widget-id}
   - :code-browser-v2/check-ns-list-fingerprint {:project-uri :widget-id}
   - :code-browser-v2/check-symbol-list-fingerprint {:ns-name :widget-id}
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
    (let [result (handle-fetch {:uri (:uri data) :property (:property data)})
          response (assoc result
                          :widget-id (:widget-id data)
                          :property (or (:property data)
                                        (derive-property
                                         (when (:uri data) (uri/parse (:uri data)))
                                         nil)))]
      (log/log! {:level :info
                 :id ::fetch-response-prepared
                 :msg "Fetch response prepared for browser"
                 :data {:widget-id (:widget-id data)
                        :success (:success result)
                        :property (:property response)
                        :data-type (when (:data result)
                                     (str (type (:data result))))
                        :data-count (when (sequential? (:data result))
                                      (count (:data result)))}})
      [:code-browser-v2/fetch-response response])

    :code-browser-v2/toggle-sort-mode
    [:code-browser-v2/sort-mode-toggled {:mode (sync/toggle-sort-mode!)}]

    :code-browser-v2/clear-error
    (do (sync/clear-error!)
        [:code-browser-v2/error-cleared {}])

    :code-browser-v2/check-var-fingerprint
    (let [result (handle-check-var-fingerprint data)]
      [:code-browser-v2/var-fingerprint-result
       (assoc result :widget-id (:widget-id data))])

    :code-browser-v2/check-ns-list-fingerprint
    (let [result (handle-check-ns-list-fingerprint data)]
      [:code-browser-v2/ns-list-fingerprint-result
       (assoc result :widget-id (:widget-id data))])

    :code-browser-v2/check-symbol-list-fingerprint
    (let [result (handle-check-symbol-list-fingerprint data)]
      [:code-browser-v2/symbol-list-fingerprint-result
       (assoc result :widget-id (:widget-id data))])

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