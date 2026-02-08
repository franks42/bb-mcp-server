(ns telemetry-db.core
    "Queryable in-memory log store using plain atoms.

   Wraps the Trove *log-fn* to intercept structured log data before it reaches
   the Timbre backend (which stringifies it). Stores entries in an atom
   for querying, filtering, and persistence.

   Public API:
     query    - Query logs by level, namespace, event-id, time range
     recent   - Last N log entries
     ingest!  - Ingest external log entries (e.g., from browser via sente)
     dump!    - Serialize logs to EDN file for offline analysis
     get-store - Get the log store atom"
    (:require [taoensso.trove :as trove]
              [com.github.franks42.uuidv7.core :as uuidv7]
              [clojure.edn :as edn]
              [clojure.string :as str]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !instance (atom nil))

(defn get-store
  "Return the log store atom, or nil if module not started."
  []
  (:store @!instance))

;; =============================================================================
;; Store Operations
;; =============================================================================

(defn- add-entry!
  "Add a log entry to the store. Maintains newest-first order.
   Trims to max-entries when limit is exceeded."
  [store entry]
  (let [max-entries (some-> @!instance :max-entries)]
    (swap! store (fn [entries]
                   (let [updated (into [entry] entries)]
                     (if (and max-entries (> (count updated) max-entries))
                       (vec (take max-entries updated))
                       updated))))))

;; =============================================================================
;; Trove Log-Fn Wrapper
;; =============================================================================

(def ^:private level-priority
     "Log level priority map. Higher number = more severe."
     {:trace 0 :debug 10 :info 20 :warn 30 :error 40 :fatal 50
      "trace" 0 "debug" 10 "info" 20 "warn" 30 "error" 40 "fatal" 50})

(defn- should-capture?
  "Check if a log entry should be captured based on wrapper filters.
   Filters are checked BEFORE forcing the lazy delay (fast path for rejection)."
  [ns-str level {:keys [min-level exclude-ns]}]
  (and (or (nil? min-level)
           (>= (get level-priority level 0)
               (get level-priority min-level 0)))
       (or (nil? exclude-ns)
           (not (some #(str/starts-with? (or ns-str "") %) exclude-ns)))))

(defn- make-wrapper-log-fn
  "Wrap existing log-fn to capture structured data into store.
   Applies capture filters to avoid storing noisy low-value entries."
  [store original-log-fn filters]
  (fn [ns-str coords level id lazy_]
    ;; Always forward to original (stderr) first
    (when original-log-fn
      (original-log-fn ns-str coords level id lazy_))
    ;; Then capture structured data (if not filtered out)
    (when (should-capture? ns-str level filters)
      (try
       (let [{:keys [msg data error]} (force lazy_)
             entry (cond-> {:log/id (str (uuidv7/uuidv7))
                            :log/level (name level)
                            :log/ns (or ns-str "unknown")
                            :log/msg (or msg "")
                            :log/timestamp (System/currentTimeMillis)
                            :log/source "server"}
                           id (assoc :log/event-id (str id))
                           data (assoc :log/data (pr-str data))
                           error (assoc :log/error (ex-message error))
                           (and coords (first coords)) (assoc :log/line (first coords)))]
         (add-entry! store entry))
       (catch Exception _e
              nil)))))

;; =============================================================================
;; Query API
;; =============================================================================

(defn- matches-filter?
  "Check if a log entry matches the given filter criteria."
  [entry {:keys [level ns event-id since source]}]
  (and (or (nil? level)
           (= (name level) (:log/level entry)))
       (or (nil? ns)
           (str/starts-with? (or (:log/ns entry) "") ns))
       (or (nil? event-id)
           (str/includes? (or (:log/event-id entry) "") (str event-id)))
       (or (nil? since)
           (>= (or (:log/timestamp entry) 0) since))
       (or (nil? source)
           (str/starts-with? (or (:log/source entry) "") source))))

(defn query
  "Query log entries with filtering.

   Opts:
     :level    - Keyword log level (e.g., :error, :info)
     :ns       - Namespace prefix string
     :event-id - Event ID substring
     :since    - Epoch millis timestamp (entries >= this time)
     :source   - Source prefix string (e.g., \"server\", \"browser\", \"browser:browser-1\")
     :limit    - Max entries to return (default 50)

   Returns vector of maps sorted newest-first."
  ([] (query {}))
  ([opts]
   (when-let [store (get-store)]
             (let [limit (get opts :limit 50)
                   entries @store]
               (->> entries
                    (filter #(matches-filter? % opts))
                    (take limit)
                    vec)))))

(defn recent
  "Return the last N log entries, newest first.

   Args:
     n - Number of entries (default 50)"
  ([] (recent 50))
  ([n]
   (query {:limit n})))

;; =============================================================================
;; External Ingestion
;; =============================================================================

(defn ingest!
  "Ingest a log entry from an external source (e.g., browser via sente).

   Args:
     entry-map - Map with keys: :level :ns :event-id :msg :data :error :source"
  [{:keys [level ns event-id msg data error source]}]
  (when-let [store (get-store)]
            (let [entry (cond-> {:log/id (str (uuidv7/uuidv7))
                                 :log/level (name (or level :info))
                                 :log/ns (or ns "browser")
                                 :log/msg (or msg "")
                                 :log/timestamp (System/currentTimeMillis)
                                 :log/source (or source "browser")}
                                event-id (assoc :log/event-id (str event-id))
                                data (assoc :log/data (if (string? data) data (pr-str data)))
                                error (assoc :log/error (str error)))]
              (add-entry! store entry)
              :ingested)))

;; =============================================================================
;; Persistence
;; =============================================================================

(defn dump!
  "Serialize current logs to EDN file for offline analysis.

   Args:
     path - File path to write EDN to

   Returns path on success."
  [path]
  (when-let [store (get-store)]
            (spit path (pr-str @store))
            path))

(defn load-dump
  "Load a previously dumped EDN file.
   Returns a vector of log entry maps.

   Args:
     path - File path of dumped EDN"
  [path]
  (edn/read-string (slurp path)))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn- start-module
  "Start telemetry-db module.

   Config keys:
     :max-entries  - Maximum log entries to retain (default 10000)
     :min-level    - Minimum level to capture, e.g. :info (default nil = all)
     :exclude-ns   - Set of namespace prefixes to exclude from capture"
  [_deps config]
  (let [max-entries (get config :max-entries 10000)
        filters {:min-level (:min-level config)
                 :exclude-ns (:exclude-ns config)}
        store (atom [])
        original-log-fn trove/*log-fn*
        inst {:store store
              :config config
              :original-log-fn original-log-fn
              :max-entries max-entries
              :filters filters}]
    (reset! !instance inst)
    (let [wrapper (make-wrapper-log-fn store original-log-fn filters)]
      (trove/set-log-fn! wrapper))
    inst))

(defn- stop-module
  "Stop telemetry-db module. Restores original Trove log-fn."
  [instance]
  (when-let [original (:original-log-fn instance)]
            (trove/set-log-fn! original))
  (reset! !instance nil)
  nil)

(defn- status-module
  "Return module status."
  [_instance]
  (if-let [store (get-store)]
          {:status :ok
           :entries (count @store)}
          {:status :stopped}))

(def module
     "Module lifecycle map for bb-mcp-server module system."
     {:start start-module
      :stop stop-module
      :status status-module})
