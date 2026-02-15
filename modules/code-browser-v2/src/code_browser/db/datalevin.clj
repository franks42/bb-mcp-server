(ns code-browser.db.datalevin
    "Datalevin implementation of IDatalogDB for Code Browser v2.

   This module wraps the datalevin-pod infrastructure but maintains its own
   connection with the code-browser-specific schema.

   Design decisions:
   - D2: Portable IDatalogDB interface (this is the Datalevin impl)
   - D5: Metadata only - no source code strings stored
   - D12: Schema defined in docs/design/code-browser-schema.md"
    (:require [code-browser.db.protocol :as proto]
              [babashka.pods :as pods]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private default-db-path "/var/db/datalevin/code-browser-v2")
(def ^:private datalevin-version "0.10.5")

(defn- db-path
  "Get database path from env var or use default."
  []
  (or (System/getenv "CODE_BROWSER_DATALEVIN_PATH")
      default-db-path))

;;; ---------------------------------------------------------------------------
;;; Pod Loading (shared with datalevin-pod module)
;;; ---------------------------------------------------------------------------

;; Use cached namespace ref, but check pod exists globally (not our own atom)
(defonce ^:private d-ns (atom nil))

(defn- pod-available?
  "Check if the Datalevin pod namespace is available (loaded by any module)."
  []
  (some? (find-ns 'pod.huahaiy.datalevin)))

(defn- ensure-pod!
  "Ensure the Datalevin pod is loaded. Prefers using existing pod loaded by
   datalevin-pod module. Only loads a new pod if none exists.

   This is idempotent and safe to call multiple times."
  []
  (when-not @d-ns
    (if (pod-available?)
      ;; Pod already loaded (likely by datalevin-pod module) - just use it
      (do
       (log/log! {:level :debug
                  :id ::using-existing-pod
                  :msg "Using existing Datalevin pod (loaded by another module)"})
       (reset! d-ns (find-ns 'pod.huahaiy.datalevin)))
      ;; No pod loaded yet - load it ourselves
      (do
       (log/log! {:level :info
                  :id ::loading-pod
                  :msg "Loading Datalevin pod for code-browser"
                  :data {:version datalevin-version}})
       (pods/load-pod 'huahaiy/datalevin datalevin-version)
       (require '[pod.huahaiy.datalevin])
       (reset! d-ns (find-ns 'pod.huahaiy.datalevin))
       (log/log! {:level :info
                  :id ::pod-loaded
                  :msg "Datalevin pod loaded successfully"})))))

(defn- d-fn
  "Get a function from the datalevin pod namespace."
  [fn-name]
  (when @d-ns
    (ns-resolve @d-ns (symbol fn-name))))

;;; ---------------------------------------------------------------------------
;;; DatalevinDB Record
;;; ---------------------------------------------------------------------------

(defrecord DatalevinDB [conn path]
           proto/IDatalogDB

           (q [_this query]
              (when-let [q-fn (d-fn "q")]
                        (let [db-fn (d-fn "db")
                              start (System/currentTimeMillis)
                              result (q-fn query (db-fn conn))
                              elapsed (- (System/currentTimeMillis) start)]
                          (log/log! {:level :debug
                                     :id ::query
                                     :msg "Query executed"
                                     :data {:elapsed-ms elapsed
                                            :result-count (count result)}})
                          result)))

           (q [_this query args]
              (when-let [q-fn (d-fn "q")]
                        (let [db-fn (d-fn "db")
                              start (System/currentTimeMillis)
                              result (apply q-fn query (db-fn conn) args)
                              elapsed (- (System/currentTimeMillis) start)]
                          (log/log! {:level :debug
                                     :id ::query
                                     :msg "Query executed"
                                     :data {:elapsed-ms elapsed
                                            :result-count (count result)}})
                          result)))

           (pull [_this pattern eid]
                 (when-let [pull-fn (d-fn "pull")]
                           (let [db-fn (d-fn "db")]
                             (pull-fn (db-fn conn) pattern eid))))

           (transact! [_this tx-data]
                      (when-let [transact-fn (d-fn "transact!")]
                                (let [start (System/currentTimeMillis)
                                      result (transact-fn conn tx-data)
                                      elapsed (- (System/currentTimeMillis) start)]
                                  (log/log! {:level :debug
                                             :id ::transact
                                             :msg "Transaction executed"
                                             :data {:tx-count (count tx-data)
                                                    :elapsed-ms elapsed}})
                                  result)))

           (entity [_this eid]
                   (when-let [entity-fn (d-fn "entity")]
                             (let [db-fn (d-fn "db")]
                               (entity-fn (db-fn conn) eid))))

           (db [_this]
               (when-let [db-fn (d-fn "db")]
                         (db-fn conn)))

           (close! [_this]
                   (when conn
                     (when-let [close-fn (d-fn "close")]
                               (log/log! {:level :info
                                          :id ::closing-conn
                                          :msg "Closing code-browser Datalevin connection"
                                          :data {:path path}})
                               (close-fn conn)))))

;;; ---------------------------------------------------------------------------
;;; Constructor
;;; ---------------------------------------------------------------------------

(defn create-db
  "Create a new DatalevinDB instance.

   Options:
     :path   - Database path (default: /var/db/datalevin/code-browser-v2)
     :schema - Schema map (default: proto/base-schema)

   Example:
     (create-db)
     (create-db {:path \"/tmp/test-db\"})
     (create-db {:path \"/tmp/test-db\" :schema my-schema})"
  ([]
   (create-db {}))
  ([{:keys [path schema]
     :or {schema proto/base-schema}}]
   (ensure-pod!)
   (let [path (or path (db-path))
         get-conn-fn (d-fn "get-conn")]
     (log/log! {:level :info
                :id ::creating-db
                :msg "Creating code-browser Datalevin connection"
                :data {:path path
                       :schema-keys (keys schema)}})
     (let [conn (get-conn-fn path schema)]
       (log/log! {:level :info
                  :id ::db-created
                  :msg "Code-browser Datalevin connection created"
                  :data {:path path}})
       (->DatalevinDB conn path)))))

;;; ---------------------------------------------------------------------------
;;; Convenience Functions
;;; ---------------------------------------------------------------------------

(defn query-by-name
  "Execute a named query from proto/queries.

   Example:
     (query-by-name db :projects)
     (query-by-name db :symbols-for-namespace \"dir://proj@v/ns\")"
  ([db query-name]
   (when-let [query (get proto/queries query-name)]
             (proto/q db query)))
  ([db query-name & args]
   (when-let [query (get proto/queries query-name)]
             (proto/q db query args))))

(defn uri->entity
  "Get entity by URI string.

   Example:
     (uri->entity db \"dir://bb-mcp-server@abc123/bb-mcp-server.main\")"
  [db uri-string]
  (proto/pull db '[*] [:uri/string uri-string]))
