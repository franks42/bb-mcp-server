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
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Module State
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Module configuration and lifecycle state."}
 !config
         (atom {:enabled? false
                :db-path nil
                :auto-scan? true}))

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
;;; Public API
;;; ---------------------------------------------------------------------------

(defn init!
  "Initialize Code Browser v2 with configuration.

   Options:
   - :db-path - Path for Datalevin database (required)
   - :sources - Vector of source configs [{:type :dir :path \"...\"}]
   - :auto-scan? - Whether to scan sources on init (default: true)

   Returns the database instance."
  [{:keys [db-path sources auto-scan?] :or {auto-scan? true}}]
  (log/log! {:level :info
             :id ::init
             :msg "Initializing code-browser-v2"
             :data {:db-path db-path
                    :source-count (count sources)
                    :auto-scan? auto-scan?}})
  (let [db (create-db db-path)]
    ;; Store DB in handlers
    (handlers/set-db! db)
    ;; Store config
    (swap! !config assoc
           :db-path db-path
           :auto-scan? auto-scan?)
    ;; Create and optionally scan sources
    (when sources
      (doseq [source-config sources]
             (let [source (create-source source-config)]
               (when auto-scan?
                 (scan-and-populate! db source)))))
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

(defn enable!
  "Enable browser sync. Registers atom with atom-sync and sets up handlers."
  []
  (when-not (:enabled? @!config)
    (log/log! {:level :info
               :id ::enable
               :msg "Enabling code-browser-v2 browser sync"})
    ;; Register synced atom
    (sync/register-sync!)
    ;; Load initial projects
    (handlers/handle-load-projects!)
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

(defn shutdown!
  "Full shutdown - disable sync and close database."
  []
  (log/log! {:level :info
             :id ::shutdown
             :msg "Shutting down code-browser-v2"})
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
;;; Module Initialization
;;; ---------------------------------------------------------------------------

;; Register auto-enable callback at namespace load time.
;; When a browser connects and requests :code-browser-v2 atom, this enables
;; the module if a database has been configured via init! or init-with!.
(register-auto-enable!)
