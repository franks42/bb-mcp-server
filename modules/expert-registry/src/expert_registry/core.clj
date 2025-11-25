(ns expert-registry.core
    "Domain expert registry with curriculum management.

   This module manages expert definitions - pre-configured AI instances
   with domain-specific knowledge and capabilities.

   Main functions:
   - init!           - Initialize registry from .experts/ directory
   - list-experts    - Get all expert definitions
   - get-expert      - Get expert by ID
   - spawn-expert!   - Create expert instance with curriculum loaded
   - kill-expert!    - Terminate expert instance

   Expert Definition Structure:
   {:id :clojure-coder
    :name \"Clojure Code Expert\"
    :description \"Expert in Clojure development\"
    :capabilities #{:code-review :refactoring :testing}
    :curriculum {:essential [...] :reference [...]}
    :mcp-server {...}}

   Example:
     (require '[expert-registry.core :as er])

     ;; Initialize from disk
     (er/init!)

     ;; List all experts
     (er/list-experts)

     ;; Spawn an expert instance
     (er/spawn-expert! :clojure-coder \"my-coder\")"
    (:require [clojure.edn :as edn]
              [clojure.java.io :as io]
              [expert-registry.curriculum :as curriculum]
              [port-registry.core :as port-registry]
              [ai-orchestrator.core :as orchestrator]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-experts-dir
     "Default directory for expert definitions."
     ".experts")

(defonce ^:private config
         (atom {:experts-dir default-experts-dir}))

(defn configure!
  "Update configuration.

   Options:
     :experts-dir - Directory containing expert definitions"
  [opts]
  (swap! config merge opts))

;; =============================================================================
;; Registry State
;; =============================================================================

(defonce ^:private expert-definitions
         (atom {}))

(defonce ^:private expert-instances
         (atom {}))

;; =============================================================================
;; Expert Definition Loading
;; =============================================================================

(defn- load-manifest
  "Load manifest.edn from expert directory.

   Arguments:
     expert-dir - File object pointing to expert directory

   Returns:
     Parsed manifest map or nil on error."
  [expert-dir]
  (let [manifest-file (io/file expert-dir "manifest.edn")]
    (when (.exists manifest-file)
      (try
       (edn/read-string (slurp manifest-file))
       (catch Exception e
              (log/log! {:level :error
                         :id    ::manifest-load-failed
                         :msg   "Failed to load manifest"
                         :error e
                         :data  {:path (.getPath manifest-file)}})
              nil)))))

(defn- load-expert-definition
  "Load a single expert definition from directory.

   Arguments:
     expert-dir - File object pointing to expert directory

   Returns:
     Expert definition map or nil on error."
  [expert-dir]
  (log/log! {:level :debug
             :id    ::loading-expert
             :msg   "Loading expert definition"
             :data  {:path (.getPath expert-dir)}})

  (when-let [manifest (load-manifest expert-dir)]
            (let [curriculum-paths (curriculum/discover-curriculum-files expert-dir)]
              (-> manifest
                  (assoc :curriculum-paths curriculum-paths)
                  (assoc :expert-dir (.getPath expert-dir))))))

(defn- scan-experts-directory
  "Scan .experts/ directory for expert definitions.

   Returns:
     Sequence of expert definition maps."
  []
  (let [experts-dir (io/file (:experts-dir @config))]
    (log/log! {:level :info
               :id    ::scanning-experts-dir
               :msg   "Scanning experts directory"
               :data  {:path (.getPath experts-dir)}})

    (if-not (.exists experts-dir)
      (do
       (log/log! {:level :warn
                  :id    ::experts-dir-not-found
                  :msg   "Experts directory not found"
                  :data  {:path (.getPath experts-dir)}})
       [])

      (->> (.listFiles experts-dir)
           (filter #(.isDirectory %))
           (keep load-expert-definition)))))

(defn init!
  "Initialize expert registry from .experts/ directory.

   Loads all expert definitions into memory.

   Returns:
     Number of experts loaded."
  ([] (init! (:experts-dir @config)))
  ([experts-dir]
   (log/log! {:level :info
              :id    ::init
              :msg   "Initializing expert registry"
              :data  {:experts-dir experts-dir}})

   (configure! {:experts-dir experts-dir})

   (let [experts (scan-experts-directory)
         expert-map (into {} (map (juxt :id identity) experts))
         count (count expert-map)]

     (reset! expert-definitions expert-map)

     (log/log! {:level :info
                :id    ::init-complete
                :msg   "Expert registry initialized"
                :data  {:expert-count count
                        :expert-ids (vec (keys expert-map))}})
     count)))

;; =============================================================================
;; Expert Definition Queries
;; =============================================================================

(defn list-experts
  "List all expert definitions.

   Returns:
     Sequence of expert definition maps."
  []
  (vals @expert-definitions))

(defn get-expert
  "Get expert definition by ID.

   Arguments:
     expert-id - Expert ID keyword

   Returns:
     Expert definition map or nil."
  [expert-id]
  (get @expert-definitions expert-id))

(defn find-experts-by-capability
  "Find experts with a specific capability.

   Arguments:
     capability - Capability keyword (e.g., :code-review)

   Returns:
     Sequence of expert definitions with that capability."
  [capability]
  (filter #(contains? (:capabilities %) capability)
          (vals @expert-definitions)))

;; =============================================================================
;; Expert Instance Management
;; =============================================================================

(defn spawn-expert!
  "Spawn an expert instance with curriculum loaded.

   This follows the MCP-first startup sequence:
   1. Allocate port for dedicated MCP server
   2. Start MCP server on that port
   3. Wait for health check
   4. Generate MCP config JSON
   5. Spawn Claude with --strict-mcp-config
   6. Load curriculum into Claude instance
   7. Expert ready

   Arguments:
     expert-id     - Expert ID keyword
     instance-name - Unique name for this instance

   Returns:
     Instance info map on success, or throws on error.

   Example:
     (spawn-expert! :clojure-coder \"my-coder\")"
  [expert-id instance-name]
  (log/log! {:level :info
             :id    ::spawning-expert
             :msg   "Spawning expert instance"
             :data  {:expert-id expert-id :instance-name instance-name}})

  ;; Get expert definition
  (let [expert (get-expert expert-id)]
    (when-not expert
      (throw (ex-info "Expert not found" {:expert-id expert-id})))

    ;; Check if instance name already exists
    (when (get @expert-instances instance-name)
      (throw (ex-info "Instance name already exists" {:instance-name instance-name})))

    ;; Step 1: Allocate port
    (let [port (port-registry/allocate-port!
                {:domain        :clojure-tools  ; TODO: Map expert-id to domain
                 :service-type  :mcp-server
                 :expert-id     expert-id})]

      (when-not port
        (throw (ex-info "No ports available" {:expert-id expert-id})))

      (log/log! {:level :info
                 :id    ::port-allocated
                 :msg   "Port allocated for expert"
                 :data  {:expert-id expert-id :port port}})

      (try
        ;; Step 2-4: TODO - Start dedicated MCP server (Phase 13F)
        ;; For MVP, we'll use mock Claude directly

        ;; Step 5: Start AI instance via orchestrator (using mock for now)
       (let [ai-instance (orchestrator/start-instance! instance-name
                                                       {:provider-type :claude-subprocess
                                                        :cmd ["bb" "modules/claude-subprocess-provider/test/mock_claude.clj"]})]

          ;; Step 6: Load curriculum
         (when-let [curriculum-content (curriculum/load-curriculum expert)]
                   (log/log! {:level :info
                              :id    ::loading-curriculum
                              :msg   "Loading curriculum into expert"
                              :data  {:expert-id expert-id
                                      :instance-name instance-name
                                      :curriculum-size (count curriculum-content)}})

            ;; Send curriculum as first message
                   (orchestrator/ask instance-name curriculum-content))

          ;; Step 7: Register instance
         (let [instance-info {:expert-id     expert-id
                              :instance-name instance-name
                              :port          port
                              :session-id    (:session-id ai-instance)
                              :created-at    (System/currentTimeMillis)}]
           (swap! expert-instances assoc instance-name instance-info)

           (log/log! {:level :info
                      :id    ::expert-spawned
                      :msg   "Expert instance spawned successfully"
                      :data  {:expert-id expert-id :instance-name instance-name :port port}})

           instance-info))

       (catch Exception e
          ;; Cleanup on error
              (port-registry/release-port! port)
              (throw e))))))

(defn kill-expert!
  "Kill an expert instance and release its port.

   Arguments:
     instance-name - Instance name

   Returns:
     true on success, false if instance not found."
  [instance-name]
  (log/log! {:level :info
             :id    ::killing-expert
             :msg   "Killing expert instance"
             :data  {:instance-name instance-name}})

  (if-let [instance (get @expert-instances instance-name)]
          (do
      ;; Stop AI instance via orchestrator
           (orchestrator/stop-instance! instance-name)

      ;; Release port
           (port-registry/release-port! (:port instance))

      ;; Unregister
           (swap! expert-instances dissoc instance-name)

           (log/log! {:level :info
                      :id    ::expert-killed
                      :msg   "Expert instance killed"
                      :data  {:instance-name instance-name}})
           true)

          (do
           (log/log! {:level :warn
                      :id    ::expert-not-found
                      :msg   "Expert instance not found"
                      :data  {:instance-name instance-name}})
           false)))

(defn list-instances
  "List all running expert instances.

   Returns:
     Sequence of instance info maps."
  []
  (vals @expert-instances))

(defn get-instance
  "Get expert instance by name.

   Arguments:
     instance-name - Instance name

   Returns:
     Instance info map or nil."
  [instance-name]
  (get @expert-instances instance-name))

;; =============================================================================
;; Module Entry Point
;; =============================================================================

(defn module
  "Module entry point for expert-registry."
  []
  {:init (fn [_]
           (init!))
   :start (fn [_] nil)
   :stop (fn [_]
           ;; Kill all expert instances on shutdown
           (doseq [instance-name (keys @expert-instances)]
                  (kill-expert! instance-name)))})
