(ns bb-mcp-server.port-registry
    "Unified port registry for bb-mcp-server instances.

   All modules register their ports here, and a single JSON file is written
   to `.ports/<nickname>.json` containing all service ports.

   Default nickname: \"bb-server\" (used when no nickname specified)

   Port file structure:
   {
     \"pid\": 12345,
     \"nickname\": \"bb-server\",
     \"config\": \"system.edn\",
     \"timestamp\": \"2026-01-20T...\",
     \"ports\": {
       \"mcp-http\": 3000,
       \"http-server\": 8091,
       \"sente-websocket\": 8090,
       \"nrepl-proxy\": 1667,
       \"nrepl-server\": 7888
     }
   }"
    (:require [clojure.java.io :as io]
              [cheshire.core :as json]
              [taoensso.trove :as log])
    (:import [java.time Instant]))

;; =============================================================================
;; Constants
;; =============================================================================

(def default-nickname
     "Default nickname when none specified."
     "bb-server")

(def ports-dir
     "Directory for port files."
     ".ports")

;; =============================================================================
;; State
;; =============================================================================

;; Atom holding registered ports: {:mcp-http 3000, :nrepl-server 7888, ...}
(defonce ^:private !registry (atom {}))

;; Atom holding instance metadata: {:nickname, :config, :pid}
(defonce ^:private !instance-info (atom nil))

;; =============================================================================
;; Port Registration (called by modules)
;; =============================================================================

(defn register-port!
  "Register a service port. Called by modules during startup.

   service-key - keyword like :mcp-http, :nrepl-server, :http-server
   port        - port number (integer)"
  [service-key port]
  (when port
    (swap! !registry assoc service-key port)
    (log/log! {:level :debug
               :id ::port-registered
               :msg "Port registered"
               :data {:service service-key :port port}})))

(defn unregister-port!
  "Unregister a service port. Called by modules during shutdown."
  [service-key]
  (swap! !registry dissoc service-key)
  (log/log! {:level :debug
             :id ::port-unregistered
             :msg "Port unregistered"
             :data {:service service-key}}))

(defn get-registered-ports
  "Get all currently registered ports."
  []
  @!registry)

(defn clear-registry!
  "Clear all registered ports. Used during shutdown."
  []
  (reset! !registry {}))

;; =============================================================================
;; Instance Info
;; =============================================================================

(defn set-instance-info!
  "Set instance metadata. Called once during startup."
  [{:keys [nickname config]}]
  (reset! !instance-info
          {:nickname (or nickname default-nickname)
           :config config
           :pid (.pid (java.lang.ProcessHandle/current))}))

(defn get-instance-info
  "Get current instance metadata."
  []
  @!instance-info)

;; =============================================================================
;; Port File I/O
;; =============================================================================

(defn- ensure-ports-dir!
  "Ensure .ports directory exists."
  []
  (let [dir (io/file ports-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn port-file-path
  "Get path to port file for a nickname."
  [nickname]
  (str ports-dir "/" (or nickname default-nickname) ".json"))

(defn write-port-file!
  "Write unified port file with all registered ports.
   Should be called after all modules have initialized."
  []
  (ensure-ports-dir!)
  (let [{:keys [nickname config pid]} @!instance-info
        nickname (or nickname default-nickname)
        ports @!registry
        data {:pid pid
              :nickname nickname
              :config config
              :timestamp (str (Instant/now))
              :ports (into {} (map (fn [[k v]] [(name k) v]) ports))}
        path (port-file-path nickname)]
    (spit path (json/generate-string data {:pretty true}))
    (log/log! {:level :info
               :id ::port-file-written
               :msg "Port file written"
               :data {:path path :ports (keys ports)}})
    path))

(defn read-port-file
  "Read port file for a nickname. Returns nil if not found."
  ([]
   (read-port-file default-nickname))
  ([nickname]
   (let [path (port-file-path nickname)]
     (when (.exists (io/file path))
       (try
        (json/parse-string (slurp path) true)
        (catch Exception e
               (log/log! {:level :warn
                          :id ::port-file-read-error
                          :msg "Failed to read port file"
                          :data {:path path :error (.getMessage e)}})
               nil))))))

(defn delete-port-file!
  "Delete port file for a nickname."
  ([]
   (delete-port-file! default-nickname))
  ([nickname]
   (let [path (port-file-path nickname)
         file (io/file path)]
     (when (.exists file)
       (.delete file)
       (log/log! {:level :info
                  :id ::port-file-deleted
                  :msg "Port file deleted"
                  :data {:path path}})))))

;; =============================================================================
;; Port Lookup (for scripts and tasks)
;; =============================================================================

(defn get-port
  "Get a specific port from port file.

   service-key - keyword like :mcp-http, :nrepl-server
   nickname    - optional, defaults to \"bb-server\"

   Returns port number or nil if not found."
  ([service-key]
   (get-port service-key default-nickname))
  ([service-key nickname]
   (when-let [data (read-port-file nickname)]
             (get-in data [:ports (keyword service-key)]))))

(defn get-all-ports
  "Get all ports from port file."
  ([]
   (get-all-ports default-nickname))
  ([nickname]
   (when-let [data (read-port-file nickname)]
             (:ports data))))

;; =============================================================================
;; Process Validation
;; =============================================================================

(defn process-alive?
  "Check if a process with given PID is alive."
  [pid]
  (try
   (let [handle (java.lang.ProcessHandle/of pid)]
     (and (.isPresent handle)
          (.isAlive (.get handle))))
   (catch Exception _
          false)))

(defn validate-port-file!
  "Check if port file's PID is still alive. Delete if stale.
   Returns true if valid, false if stale (and deleted)."
  ([]
   (validate-port-file! default-nickname))
  ([nickname]
   (if-let [data (read-port-file nickname)]
           (if (process-alive? (:pid data))
             true
             (do
              (log/log! {:level :warn
                         :id ::stale-port-file
                         :msg "Stale port file detected (PID not running), deleting"
                         :data {:nickname nickname :pid (:pid data)}})
              (delete-port-file! nickname)
              false))
     ;; No file exists
           false)))

(defn check-nickname-available!
  "Check if a nickname is available for starting a new server.
   Cleans up stale port files if PID is not running.

   Returns:
   - {:available true} if nickname can be used
   - {:available false :pid N :ports {...}} if server already running"
  ([nickname]
   (if-let [data (read-port-file nickname)]
           (if (process-alive? (:pid data))
       ;; Server is running - not available
             {:available false
              :pid (:pid data)
              :ports (:ports data)
              :config (:config data)}
       ;; Stale port file - clean up and allow
             (do
              (log/log! {:level :info
                         :id ::stale-port-file-cleaned
                         :msg "Cleaned up stale port file"
                         :data {:nickname nickname :pid (:pid data)}})
              (delete-port-file! nickname)
              {:available true}))
     ;; No port file - available
           {:available true})))

;; =============================================================================
;; List Instances
;; =============================================================================

(defn list-instances
  "List all instances with port files. Validates each one."
  []
  (let [dir (io/file ports-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".json"))
           (map #(let [nickname (subs (.getName %) 0 (- (count (.getName %)) 5))]
                   (when (validate-port-file! nickname)
                     (read-port-file nickname))))
           (remove nil?)
           (vec)))))
