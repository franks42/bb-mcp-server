(ns code-browser.core
    "Code Browser v2 - Public API and initialization.

   URI-centric architecture with Datalevin backend.

   Usage:
   1. Initialize with database and sources:
      (core/init! {:db-path \"/tmp/code-browser-db\"
                   :sources [{:type :dir :path \"/path/to/project\"}]})

   2. Or initialize with existing DB and source:
      (core/init-with! {:db existing-db :source existing-source})

   3. Enable browser sync (called automatically on browser connect):
      (core/enable!)

   4. Disable and cleanup:
      (core/disable!)"
    (:require [code-browser.sync :as sync]
              [code-browser.handlers :as handlers]
              [code-browser.db.protocol :as db-proto]
              [code-browser.db.datalevin :as datalevin]
              [code-browser.sources.protocol :as source-proto]
              [code-browser.sources.directory :as dir-source]
              [sente-browser.server :as sente-server]
              [babashka.fs :as fs]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Module State
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Module configuration and lifecycle state."}
 !config
         (atom {:enabled? false
                :db-path nil
                :auto-scan? true
                :watch-handles []}))

(defonce ^{:doc "Per-file locks to serialize concurrent rescans of the same file.
   Prevents race conditions where parallel retract/transact operations
   on the same file path interleave and produce empty results."}
 !rescan-locks
         (atom {}))

;;; ---------------------------------------------------------------------------
;;; Initialization Helpers
;;; ---------------------------------------------------------------------------

(defn- create-db
  "Create a Datalevin database at the given path."
  [db-path]
  (log/log! {:level :info
             :id ::create-db
             :msg "Creating code-browser-v2 database"
             :data {:path db-path}})
  (datalevin/create-db {:path db-path}))

(defn- create-source
  "Create a source adapter based on type."
  [{:keys [type path] :as source-config}]
  (log/log! {:level :info
             :id ::create-source
             :msg "Creating source adapter"
             :data source-config})
  (case type
    :dir (dir-source/create-directory-source path)
    (throw (ex-info "Unknown source type" {:type type}))))

(defn- scan-and-populate!
  "Scan a source and populate the database."
  [db source]
  (log/log! {:level :info
             :id ::scan-and-populate
             :msg "Scanning source and populating database"})
  (when-let [{:keys [project namespaces symbols aliases refers]}
             (source-proto/scan-project source)]
            (let [project-uri (:uri/string project)]
      ;; Register source for fetch-source
              (handlers/register-source! project-uri source)
      ;; Clean entities for Datalevin (remove nil values and parent refs)
              (let [clean-entity (fn [e]
                                   (->> e
                                        (remove (fn [[_k v]] (nil? v)))
                                        (remove (fn [[k _v]] (= k :uri/parent)))
                                        (into {})))]
        ;; Transact project
                (db-proto/transact! db [(clean-entity project)])
        ;; Transact namespaces in batches
                (doseq [ns-batch (partition-all 50 namespaces)]
                       (db-proto/transact! db (mapv clean-entity ns-batch)))
        ;; Transact symbols in batches
                (doseq [sym-batch (partition-all 100 symbols)]
                       (db-proto/transact! db (mapv clean-entity sym-batch)))
        ;; Transact aliases in batches
                (when (seq aliases)
                  (doseq [alias-batch (partition-all 100 aliases)]
                         (db-proto/transact! db (mapv clean-entity alias-batch))))
        ;; Transact refers in batches
                (when (seq refers)
                  (doseq [refer-batch (partition-all 100 refers)]
                         (db-proto/transact! db (mapv clean-entity refer-batch)))))
              (log/log! {:level :info
                         :id ::scan-complete
                         :msg "Source scan and database population complete"
                         :data {:project project-uri
                                :namespaces (count namespaces)
                                :symbols (count symbols)
                                :aliases (count aliases)
                                :refers (count refers)}})
              {:project project-uri
               :namespaces (count namespaces)
               :symbols (count symbols)
               :aliases (count aliases)
               :refers (count refers)})))

;;; ---------------------------------------------------------------------------
;;; File Change Re-scan
;;; ---------------------------------------------------------------------------

(defn- retract-project-entities!
  "Retract all entities belonging to a project from the database."
  [db project-name]
  (let [entities (db-proto/q db
                             '[:find ?e
                               :in $ ?proj
                               :where [?e :uri/project ?proj]]
                             [project-name])]
    (when (seq entities)
      (let [tx-data (mapv (fn [[eid]] [:db/retractEntity eid]) entities)]
        (log/log! {:level :debug
                   :id ::retract-entities
                   :msg "Retracting project entities"
                   :data {:project project-name
                          :count (count tx-data)}})
        (db-proto/transact! db tx-data)))))

(defn- retract-file-entities!
  "Retract entities belonging to a specific file from the database.
   Retracts symbols by :symbol/file, namespaces by :ns/file,
   and aliases/refers by :alias/from-ns and :refer/from-ns for affected namespaces."
  [db rel-path ns-names project-name]
  (let [;; Symbols by file + project
        sym-eids (db-proto/q db
                             '[:find ?e
                               :in $ ?file ?proj
                               :where
                               [?e :symbol/file ?file]
                               [?e :uri/project ?proj]]
                             [rel-path project-name])
        ;; Namespaces by file + project
        ns-eids (db-proto/q db
                            '[:find ?e
                              :in $ ?file ?proj
                              :where
                              [?e :ns/file ?file]
                              [?e :uri/project ?proj]]
                            [rel-path project-name])
        ;; Aliases for affected namespaces
        alias-eids (when (seq ns-names)
                     (mapcat
                      (fn [ns-name]
                        (db-proto/q db
                                    '[:find ?e
                                      :in $ ?ns-name
                                      :where
                                      [?e :alias/from-ns ?ns-name]]
                                    [ns-name]))
                      ns-names))
        ;; Refers for affected namespaces
        refer-eids (when (seq ns-names)
                     (mapcat
                      (fn [ns-name]
                        (db-proto/q db
                                    '[:find ?e
                                      :in $ ?ns-name
                                      :where
                                      [?e :refer/from-ns ?ns-name]]
                                    [ns-name]))
                      ns-names))
        all-eids (distinct
                  (concat (map first sym-eids)
                          (map first ns-eids)
                          (map first alias-eids)
                          (map first refer-eids)))]
    (when (seq all-eids)
      (let [tx-data (mapv (fn [eid] [:db/retractEntity eid]) all-eids)]
        (log/log! {:level :debug
                   :id ::retract-file-entities
                   :msg "Retracting file entities"
                   :data {:file rel-path
                          :project project-name
                          :ns-names ns-names
                          :symbol-count (count sym-eids)
                          :ns-count (count ns-eids)
                          :alias-count (count alias-eids)
                          :refer-count (count refer-eids)
                          :total (count tx-data)}})
        (db-proto/transact! db tx-data)))))

(defn- refresh-browser-view!
  "Re-query current browser selection and push updated data via atom-sync."
  []
  (let [state @sync/!state]
    (handlers/handle-load-projects!)
    (when-let [proj (:selected-project state)]
              (handlers/handle-select-project! proj)
              (when-let [ns-uri (:selected-ns state)]
                        (handlers/handle-select-namespace! ns-uri)
                        (when-let [sym-uri (:selected-symbol state)]
                                  (handlers/handle-select-symbol! sym-uri))))))

(defn- find-ns-names-for-file
  "Find namespace names associated with a file path in the database."
  [db rel-path project-name]
  (->> (db-proto/q db
                   '[:find ?ns-name
                     :in $ ?file ?proj
                     :where
                     [?e :ns/file ?file]
                     [?e :uri/project ?proj]
                     [?e :ns/name ?ns-name]]
                   [rel-path project-name])
       (map first)
       set))

(defn- transact-file-entities!
  "Transact new entities from a file scan result into the database."
  [db {:keys [namespaces symbols aliases refers]}]
  (let [clean-entity (fn [e]
                       (->> e
                            (remove (fn [[_k v]] (nil? v)))
                            (remove (fn [[k _v]] (= k :uri/parent)))
                            (into {})))]
    ;; Transact namespaces
    (when (seq namespaces)
      (db-proto/transact! db (mapv clean-entity namespaces)))
    ;; Transact symbols in batches
    (when (seq symbols)
      (doseq [sym-batch (partition-all 100 symbols)]
             (db-proto/transact! db (mapv clean-entity sym-batch))))
    ;; Transact aliases
    (when (seq aliases)
      (db-proto/transact! db (mapv clean-entity aliases)))
    ;; Transact refers
    (when (seq refers)
      (db-proto/transact! db (mapv clean-entity refers)))))

(defn rescan-file!
  "Re-scan a single file and update the database and browser view.
   Much faster than full project rescan (~50ms vs ~2s).
   For deleted files, only retracts entities without scanning.
   Serialized per file path to prevent concurrent retract/transact races."
  [project-name source file-path event-type]
  (let [abs-path (str file-path)
        lock (-> (swap! !rescan-locks update abs-path
                        #(or % (Object.)))
                 (get abs-path))]
    (locking lock
             (let [start-ms (System/currentTimeMillis)
                   rel-path (str (fs/relativize (:root-path source) file-path))]
               (log/log! {:level :info
                          :id ::rescan-file
                          :msg "Re-scanning file after change"
                          :data {:file rel-path
                                 :event-type event-type
                                 :project project-name}})
               ;; Verify actual file state: atomic writes (e.g. editors, Claude
               ;; Code Edit tool) emit :remove then :create events. If the debounce
               ;; captures :remove as the last event but the file exists on disk,
               ;; treat it as :changed instead of :deleted.
               (let [actual-event-type (if (and (= event-type :deleted)
                                                (fs/exists? abs-path))
                                         (do
                                          (log/log! {:level :info
                                                     :id ::rescan-file-corrected
                                                     :msg "File reported deleted but exists on disk, treating as changed"
                                                     :data {:file rel-path
                                                            :project project-name}})
                                          :changed)
                                         event-type)]
                 (when-let [db (handlers/get-db)]
                           (let [old-ns-names (into
                                               (find-ns-names-for-file db abs-path project-name)
                                               (find-ns-names-for-file db rel-path project-name))]
                             (if (= actual-event-type :deleted)
                               (do
                                (retract-file-entities! db abs-path old-ns-names project-name)
                                (retract-file-entities! db rel-path old-ns-names project-name)
                                (log/log! {:level :info
                                           :id ::rescan-file-deleted
                                           :msg "Retracted entities for deleted file"
                                           :data {:file rel-path
                                                  :project project-name}}))
                               (when-let [scan-result (dir-source/scan-file source file-path)]
                                         (let [all-ns-names (into old-ns-names
                                                                  (:affected-ns-names scan-result))]
                                           (retract-file-entities! db abs-path all-ns-names project-name)
                                           (retract-file-entities! db rel-path all-ns-names project-name)
                                           (transact-file-entities! db scan-result)))))
                           (refresh-browser-view!)
                           (let [n (sente-server/broadcast-to-browsers!
                                    [:code-browser-v2/invalidate
                                     {:project project-name}])]
                             (log/log! {:level :info
                                        :id ::rescan-file-complete
                                        :msg "File re-scan complete"
                                        :data {:file rel-path
                                               :event-type actual-event-type
                                               :project project-name
                                               :browsers-notified n
                                               :elapsed-ms (- (System/currentTimeMillis)
                                                              start-ms)}}))))))))

(defn rescan-project!
  "Re-scan a project source and update the database and browser view.
   Full project re-index. Prefer rescan-file! for single-file changes."
  [project-name source]
  (let [start-ms (System/currentTimeMillis)]
    (log/log! {:level :info
               :id ::rescan-project
               :msg "Re-scanning project after file change"
               :data {:project project-name}})
    (when-let [db (handlers/get-db)]
              (retract-project-entities! db project-name)
              (scan-and-populate! db source)
              (refresh-browser-view!)
              ;; Broadcast invalidation to all browser widgets
              (let [n (sente-server/broadcast-to-browsers!
                       [:code-browser-v2/invalidate
                        {:project project-name}])]
                (log/log! {:level :info
                           :id ::rescan-complete
                           :msg "Project re-scan complete"
                           :data {:project project-name
                                  :browsers-notified n
                                  :elapsed-ms (- (System/currentTimeMillis)
                                                 start-ms)}})))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn enable!
  "Enable browser sync. Registers atom with atom-sync and sets up handlers.
   Only loads projects if a database is configured."
  []
  (when-not (:enabled? @!config)
    (log/log! {:level :info
               :id ::enable
               :msg "Enabling code-browser-v2 browser sync"})
    ;; Register synced atom
    (sync/register-sync!)
    ;; Only load projects if database is configured
    (if (handlers/get-db)
      (handlers/handle-load-projects!)
      (log/log! {:level :debug
                 :id ::enable-no-db
                 :msg "Skipping project load - no database configured yet"}))
    (swap! !config assoc :enabled? true)
    :enabled))

(defn disable!
  "Disable browser sync and cleanup resources."
  []
  (when (:enabled? @!config)
    (log/log! {:level :info
               :id ::disable
               :msg "Disabling code-browser-v2"})
    ;; Unregister synced atom
    (sync/unregister-sync!)
    ;; Reset state
    (sync/reset-state!)
    (swap! !config assoc :enabled? false)
    :disabled))

(defn init!
  "Initialize Code Browser v2 with configuration.

   Options:
   - :db-path - Path for Datalevin database (required)
   - :sources - Vector of source configs [{:type :dir :path \"...\"}]
   - :auto-scan? - Whether to scan sources on init (default: true)
   - :auto-enable? - Whether to enable browser sync after init (default: true)

   Returns the database instance."
  [{:keys [db-path sources auto-scan? auto-enable?]
    :or {auto-scan? true auto-enable? true}}]
  (log/log! {:level :info
             :id ::init
             :msg "Initializing code-browser-v2"
             :data {:db-path db-path
                    :source-count (count sources)
                    :auto-scan? auto-scan?
                    :auto-enable? auto-enable?}})
  ;; Clear any previous error state
  (sync/clear-error!)
  (let [db (create-db db-path)]
    ;; Store DB in handlers
    (handlers/set-db! db)
    ;; Store config
    (swap! !config assoc
           :db-path db-path
           :auto-scan? auto-scan?)
    ;; Create and optionally scan sources, then start file watching
    (when sources
      (doseq [source-config sources]
             (let [source (create-source source-config)]
               (when auto-scan?
                 (scan-and-populate! db source))
               ;; Start file watching if supported
               (when (:supports-watch? (source-proto/source-info source))
                 (let [proj-name (:project-name source)
                       watch-handle
                       (source-proto/watch!
                        source
                        (fn [event]
                          (rescan-file! proj-name source
                                        (:path event) (:type event))))]
                   (when watch-handle
                     (swap! !config update :watch-handles
                            conj watch-handle)))))))
    ;; Auto-enable and load projects if requested
    (when auto-enable?
      (enable!)
      ;; Load projects now that we have a database
      (when (handlers/get-db)
        (handlers/handle-load-projects!)))
    db))

(defn init-with!
  "Initialize with existing database and source.
   Useful for testing or when DB is already populated.

   Options:
   - :db - Existing database instance (required)
   - :source - Existing source adapter (optional)
   - :project-uri - URI for the source (required if source provided)"
  [{:keys [db source project-uri]}]
  (log/log! {:level :info
             :id ::init-with
             :msg "Initializing code-browser-v2 with existing instances"
             :data {:has-db (some? db)
                    :has-source (some? source)
                    :project-uri project-uri}})
  (handlers/set-db! db)
  (when (and source project-uri)
    (handlers/register-source! project-uri source))
  db)

(defn shutdown!
  "Full shutdown - stop watchers, disable sync, and close database."
  []
  (log/log! {:level :info
             :id ::shutdown
             :msg "Shutting down code-browser-v2"})
  ;; Stop all file watchers
  (doseq [h (:watch-handles @!config)]
         (source-proto/unwatch! nil h))
  (swap! !config assoc :watch-handles [])
  (disable!)
  (when-let [db (handlers/get-db)]
            (db-proto/close! db)
            (handlers/set-db! nil))
  :shutdown)

;;; ---------------------------------------------------------------------------
;;; Browser Event Dispatch
;;; ---------------------------------------------------------------------------

(defn dispatch-event
  "Dispatch a browser event. Delegates to handlers/dispatch-event."
  [event-id data]
  (handlers/dispatch-event event-id data))

;;; ---------------------------------------------------------------------------
;;; Auto-Enable on Browser Connect
;;; ---------------------------------------------------------------------------

(defn- on-browser-connect!
  "Called when a browser connects. Enables if not already enabled."
  [_conn-id]
  (log/log! {:level :debug
             :id ::browser-connect
             :msg "Browser connected to code-browser-v2"})
  (enable!))

(defn register-auto-enable!
  "Register callback to auto-enable on first browser connection."
  []
  (sync/register-on-connect! on-browser-connect!))

(defn unregister-auto-enable!
  "Unregister auto-enable callback."
  []
  (sync/unregister-on-connect!))

;;; ---------------------------------------------------------------------------
;;; Module Lifecycle (bb-mcp-server module system)
;;; ---------------------------------------------------------------------------

(def module
     "Module lifecycle map for bb-mcp-server module system."
     {:start (fn [_deps config]
               (log/log! {:level :info
                          :id ::module-starting
                          :msg "Starting code-browser-v2 module"
                          :data {:config config}})
               (register-auto-enable!)
               (when (:enabled config)
                 (when-let [db-path (:db-path config)]
                           (init! {:db-path db-path
                                   :sources (:sources config)
                                   :auto-scan? (get config :auto-scan? true)
                                   :auto-enable? (get config :auto-enable? true)})))
               {:status :started
                :config config})
      :stop (fn [_instance]
              (unregister-auto-enable!)
              (shutdown!)
              nil)
      :status (fn [_instance]
                {:status :ok
                 :enabled? (:enabled? @!config)
                 :db-path (:db-path @!config)})})