(ns bb-mcp-server.module.system
    "System map for bb-mcp-server module lifecycle management.

  The system map coordinates module startup and shutdown in dependency order.
  It provides Component-style lifecycle management for Babashka.

  Usage:
    (def system (create-system \"modules\"))
    (start-system! system)
    (stop-system! system)
    (system-status system)

  NOTE: Uses ns_loader for elegant module loading via babashka's
        native classpath + require mechanism."
    (:require [bb-mcp-server.module.deps :as deps]
              [bb-mcp-server.module.ns-loader :as ns-loader]
              [bb-mcp-server.module.protocol :as proto]
              [clojure.edn :as edn]
              [clojure.java.io :as io]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; System State
;; -----------------------------------------------------------------------------

(def ^:private system-state
     "Atom holding the current system state.

  Structure:
    {:status :stopped|:starting|:running|:stopping
     :modules {}        ; module-name -> loaded module data
     :instances {}      ; module-name -> running instance
     :start-order []    ; modules in load order
     :config {}}"
     (atom {:status :stopped
            :modules {}
            :instances {}
            :start-order []
            :config {}}))

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(def ^:private system-metrics
     "Atom holding system telemetry metrics."
     (atom {:starts 0
            :stops 0
            :last-start-at nil
            :last-stop-at nil
            :last-start-duration-ms 0
            :last-stop-duration-ms 0
            :module-start-times {}
            :module-stop-times {}}))

(defn get-system-metrics
  "Get current system metrics.

  Returns:
    Map with keys for start/stop counts, timestamps, and per-module timing"
  []
  @system-metrics)

(defn reset-system-metrics!
  "Reset system metrics to initial state."
  []
  (reset! system-metrics
          {:starts 0
           :stops 0
           :last-start-at nil
           :last-stop-at nil
           :last-start-duration-ms 0
           :last-stop-duration-ms 0
           :module-start-times {}
           :module-stop-times {}}))

(defn- record-system-start!
  "Record system start metrics."
  [duration-ms module-times]
  (swap! system-metrics
         (fn [m]
           (-> m
               (update :starts inc)
               (assoc :last-start-at (System/currentTimeMillis))
               (assoc :last-start-duration-ms duration-ms)
               (assoc :module-start-times module-times)))))

(defn- record-system-stop!
  "Record system stop metrics."
  [duration-ms module-times]
  (swap! system-metrics
         (fn [m]
           (-> m
               (update :stops inc)
               (assoc :last-stop-at (System/currentTimeMillis))
               (assoc :last-stop-duration-ms duration-ms)
               (assoc :module-stop-times module-times)))))

;; -----------------------------------------------------------------------------
;; System Accessors
;; -----------------------------------------------------------------------------

(defn get-system-state
  "Get current system state.

  Returns:
    Map with :status, :modules, :instances, :start-order, :config"
  []
  @system-state)

(defn system-status
  "Get high-level system status.

  Returns:
    Map with :status and per-module status"
  []
  (let [state @system-state
        module-statuses (reduce (fn [acc [name instance-data]]
                                  (let [module (get-in state [:modules name :module])
                                        instance (:instance instance-data)]
                                    (assoc acc name
                                           (if (and module instance)
                                             (proto/module-status module instance)
                                             {:status :stopped}))))
                                {}
                                (:instances state))]
    {:status (:status state)
     :modules module-statuses
     :module-count (count (:modules state))
     :running-count (count (:instances state))}))

(defn get-module-instance
  "Get a running module instance by name.

  Args:
    module-name - Name of the module

  Returns:
    Module instance or nil if not running"
  [module-name]
  (get-in @system-state [:instances module-name :instance]))

(defn get-all-instances
  "Get all running module instances.

  Returns:
    Map of module-name -> instance"
  []
  (->> (:instances @system-state)
       (map (fn [[name data]] [name (:instance data)]))
       (into {})))

;; -----------------------------------------------------------------------------
;; Config File Loading
;; -----------------------------------------------------------------------------

(def default-config-file
     "Default system configuration file path."
     "system.edn")

(defn- read-config-file
  "Read and parse system configuration file.

  Args:
    config-path - Path to system.edn file

  Returns:
    {:success config-map} or {:error error-info}"
  [config-path]
  (try
   (let [file (io/file config-path)]
     (if (.exists file)
       (let [config (edn/read-string (slurp file))]
         (log/log! {:level :info
                    :id ::config-loaded
                    :msg "System configuration loaded"
                    :data {:path config-path
                           :modules-dir (:modules-dir config)
                           :module-count (count (:modules config))}})
         {:success config})
       {:error {:type :config-not-found
                :path config-path
                :message (str "Config file not found: " config-path)}}))
   (catch Exception e
          (log/log! {:level :error
                     :id ::config-read-error
                     :msg "Failed to read config file"
                     :error e
                     :data {:path config-path}})
          {:error {:type :config-read-error
                   :path config-path
                   :message (ex-message e)}})))

;; -----------------------------------------------------------------------------
;; Module Discovery & Loading (using ns_loader)
;; -----------------------------------------------------------------------------

(defn- discover-module-dirs
  "Discover module directories in the given path.

  Args:
    modules-dir  - Path to modules directory
    module-names - Optional seq of module names to filter

  Returns:
    Seq of module directory paths"
  [modules-dir module-names]
  (let [dir (io/file modules-dir)]
    (if (.exists dir)
      (let [subdirs (->> (.listFiles dir)
                         (filter #(.isDirectory %))
                         (filter #(.exists (io/file % "module.edn")))
                         (map #(.getPath %)))]
        (if module-names
          (let [name-set (set module-names)]
            (filter #(contains? name-set (.getName (io/file %))) subdirs))
          subdirs))
      [])))

(defn- load-modules-with-ns-loader
  "Load modules using the elegant ns_loader.

  Args:
    modules-dir  - Path to modules directory
    module-names - Optional seq of module names to load

  Returns:
    Map of module-name -> {:manifest ... :module ...} or {:error ...}"
  [modules-dir module-names]
  (let [module-dirs (discover-module-dirs modules-dir module-names)]
    (into {}
          (for [dir module-dirs]
               (let [result (ns-loader/load-module dir)
                     dir-name (.getName (io/file dir))]
                 (if (:success result)
                   [(get-in result [:success :manifest :name]) (:success result)]
                   [dir-name {:error (:error result)}]))))))

;; -----------------------------------------------------------------------------
;; System Lifecycle
;; -----------------------------------------------------------------------------

(defn create-system
  "Create a system from modules in a directory.

  Args:
    modules-dir  - Path to modules directory (default: 'modules')
    config       - Optional configuration map for modules
    module-names - Optional seq of module names to load (loads all if nil)

  Returns:
    {:success system-data} or {:error error-info}

  NOTE: Uses ns_loader for elegant module loading via babashka's
        native classpath + require mechanism."
  ([]
   (create-system "modules" {} nil))
  ([modules-dir]
   (create-system modules-dir {} nil))
  ([modules-dir config]
   (create-system modules-dir config nil))
  ([modules-dir config module-names]
   (log/log! {:level :info
              :id ::create-system
              :msg "Creating system (using ns_loader)"
              :data {:modules-dir modules-dir
                     :explicit-modules (some? module-names)
                     :module-count (when module-names (count module-names))}})

   ;; Reset ns_loader state for clean loading
   (ns-loader/reset-state!)

   (let [loaded (load-modules-with-ns-loader modules-dir module-names)
         successful (->> loaded
                         (filter (fn [[_name data]] (not (:error data))))
                         (mapv first))
         failed (->> loaded
                     (filter (fn [[_name data]] (:error data)))
                     (mapv first))]

     (when (seq failed)
       (log/log! {:level :warn
                  :id ::modules-failed-to-load
                  :msg "Some modules failed to load"
                  :data {:failed failed}}))

     (if (empty? successful)
       {:error {:type :no-modules
                :message "No modules loaded successfully"
                :failed failed}}

       ;; Validate dependencies
       (let [dep-validation (deps/validate-dependencies loaded)]
         (if-not (:valid dep-validation)
           {:error {:type :missing-dependencies
                    :missing (:missing dep-validation)}}

           ;; Resolve load order
           (let [order-result (deps/resolve-order loaded)]
             (if (:error order-result)
               {:error (:error order-result)}

               ;; System created successfully
               (do
                (reset! system-state
                        {:status :stopped
                         :modules (select-keys loaded successful)
                         :instances {}
                         :start-order (:success order-result)
                         :config config})
                (log/log! {:level :info
                           :id ::system-created
                           :msg "System created successfully"
                           :data {:modules successful
                                  :start-order (:success order-result)}})
                {:success {:modules successful
                           :start-order (:success order-result)}})))))))))

(defn create-system-from-config
  "Create a system from a configuration file.

  Args:
    config-path - Path to system.edn file (default: 'system.edn')

  Config file format:
    {:modules-dir \"modules\"
     :modules [\"hello\" \"math\"]
     :config {\"hello\" {:greeting \"Hi\"}}}

  Returns:
    {:success system-data} or {:error error-info}"
  ([]
   (create-system-from-config default-config-file))
  ([config-path]
   (log/log! {:level :info
              :id ::create-from-config
              :msg "Creating system from config file"
              :data {:config-path config-path}})
   (let [config-result (read-config-file config-path)]
     (if (:error config-result)
       config-result
       (let [{:keys [modules-dir modules config]} (:success config-result)
             dir (or modules-dir "modules")
             module-config (or config {})]
         (if (empty? modules)
           {:error {:type :no-modules-configured
                    :message "No modules specified in config file"
                    :config-path config-path}}
           (create-system dir module-config modules)))))))

(defn- start-module-with-deps
  "Start a single module with its dependencies available.

  Args:
    module-name - Name of the module to start
    module-data - Module data from loader
    config      - System configuration

  Returns:
    {:success instance} or {:error error-info}"
  [module-name module-data config]
  (let [start-time (System/currentTimeMillis)
        module (:module module-data)
        module-config (get config module-name {})
        deps-instances (get-all-instances)]
    (try
     (log/log! {:level :info
                :id ::starting-module
                :msg "Starting module"
                :data {:module module-name}})
     (let [instance (proto/start-module module deps-instances module-config)
           duration (- (System/currentTimeMillis) start-time)]
       (log/log! {:level :info
                  :id ::module-started
                  :msg "Module started"
                  :data {:module module-name
                         :duration-ms duration}})
       {:success instance :duration-ms duration})
     (catch Exception e
            (log/log! {:level :error
                       :id ::module-start-failed
                       :msg "Failed to start module"
                       :error e
                       :data {:module module-name
                              :error (ex-message e)}})
            {:error {:type :start-failed
                     :module module-name
                     :message (ex-message e)}}))))

;; Forward declaration for rollback in start-system!
(declare stop-system!)

(defn start-system!
  "Start all modules in dependency order.

  Returns:
    {:success {:started [...]}} or {:error error-info}

  Side effects:
    - Updates system-state atom
    - Starts all modules
    - Records telemetry"
  []
  (let [state @system-state]
    (when (not= :stopped (:status state))
      (throw (ex-info "System is not stopped"
                      {:type :invalid-state
                       :current-status (:status state)})))

    (swap! system-state assoc :status :starting)
    (log/log! {:level :info
               :id ::system-starting
               :msg "Starting system"
               :data {:module-count (count (:start-order state))}})

    (let [start-time (System/currentTimeMillis)
          results (atom {:started []
                         :failed []
                         :times {}})]

      ;; Start modules in order
      (doseq [module-name (:start-order state)]
             (let [module-data (get (:modules state) module-name)
                   result (start-module-with-deps module-name module-data (:config state))]
               (if (:success result)
                 (do
                  (swap! system-state assoc-in [:instances module-name]
                         {:instance (:success result)
                          :started-at (System/currentTimeMillis)})
                  (swap! results update :started conj module-name)
                  (swap! results assoc-in [:times module-name] (:duration-ms result)))
                 (do
                  (swap! results update :failed conj {:module module-name
                                                      :error (:error result)})
              ;; Stop already started modules on failure
                  (log/log! {:level :warn
                             :id ::rolling-back
                             :msg "Rolling back started modules"
                             :data {:started (:started @results)}})))))

      (let [final-results @results
            duration (- (System/currentTimeMillis) start-time)]

        (if (empty? (:failed final-results))
          (do
           (swap! system-state assoc :status :running)
           (record-system-start! duration (:times final-results))
           (log/log! {:level :info
                      :id ::system-started
                      :msg "System started successfully"
                      :data {:started (:started final-results)
                             :duration-ms duration}})
           {:success {:started (:started final-results)
                      :duration-ms duration}})

          (do
            ;; Rollback on failure
           (stop-system!)
           (swap! system-state assoc :status :stopped)
           (log/log! {:level :error
                      :id ::system-start-failed
                      :msg "System start failed"
                      :data {:failed (:failed final-results)}})
           {:error {:type :partial-start
                    :started (:started final-results)
                    :failed (:failed final-results)}}))))))

(defn stop-system!
  "Stop all modules in reverse dependency order.

  Returns:
    {:success {:stopped [...]}} or {:error error-info}

  Side effects:
    - Updates system-state atom
    - Stops all modules
    - Records telemetry"
  []
  (let [state @system-state]
    (if (= :stopped (:status state))
      (do
       (log/log! {:level :info
                  :id ::already-stopped
                  :msg "System already stopped"})
       {:success {:stopped []}})

      (do
       (swap! system-state assoc :status :stopping)
       (log/log! {:level :info
                  :id ::system-stopping
                  :msg "Stopping system"
                  :data {:running-modules (keys (:instances state))}})

       (let [start-time (System/currentTimeMillis)
          ;; Stop in reverse order
             stop-order (reverse (:start-order state))
             results (atom {:stopped []
                            :failed []
                            :times {}})]

         (doseq [module-name stop-order]
                (when-let [instance-data (get (:instances state) module-name)]
                          (let [module-start (System/currentTimeMillis)
                                module (get-in state [:modules module-name :module])
                                instance (:instance instance-data)]
                            (try
                             (log/log! {:level :info
                                        :id ::stopping-module
                                        :msg "Stopping module"
                                        :data {:module module-name}})
                             (proto/stop-module module instance)
                             (let [duration (- (System/currentTimeMillis) module-start)]
                               (swap! system-state update :instances dissoc module-name)
                               (swap! results update :stopped conj module-name)
                               (swap! results assoc-in [:times module-name] duration)
                               (log/log! {:level :info
                                          :id ::module-stopped
                                          :msg "Module stopped"
                                          :data {:module module-name
                                                 :duration-ms duration}}))
                             (catch Exception e
                                    (log/log! {:level :error
                                               :id ::module-stop-failed
                                               :msg "Failed to stop module"
                                               :error e
                                               :data {:module module-name
                                                      :error (ex-message e)}})
                                    (swap! results update :failed conj {:module module-name
                                                                        :error (ex-message e)}))))))

         (let [final-results @results
               duration (- (System/currentTimeMillis) start-time)]
           (swap! system-state assoc :status :stopped)
           (record-system-stop! duration (:times final-results))
           (log/log! {:level :info
                      :id ::system-stopped
                      :msg "System stopped"
                      :data {:stopped (:stopped final-results)
                             :duration-ms duration}})
           {:success {:stopped (:stopped final-results)
                      :failed (:failed final-results)
                      :duration-ms duration}}))))))

(defn restart-system!
  "Stop and restart the system.

  Returns:
    {:success ...} or {:error ...}"
  []
  (log/log! {:level :info
             :id ::system-restarting
             :msg "Restarting system"})
  (stop-system!)
  (start-system!))

;; -----------------------------------------------------------------------------
;; Module Hot Reload
;; -----------------------------------------------------------------------------

(defn reload-module!
  "Reload a single module without affecting others.

  Args:
    module-name - Name of module to reload

  Returns:
    {:success ...} or {:error ...}

  Note: Will stop dependent modules first, then restart them."
  [module-name]
  (let [state @system-state
        dependents (deps/get-dependents (:modules state) module-name)]
    (log/log! {:level :info
               :id ::module-reloading
               :msg "Reloading module"
               :data {:module module-name
                      :dependents (vec dependents)}})

    ;; Stop dependents first (reverse order)
    (doseq [dep (reverse (vec dependents))]
           (when-let [instance-data (get (:instances state) dep)]
                     (let [module (get-in state [:modules dep :module])
                           instance (:instance instance-data)]
                       (proto/stop-module module instance)
                       (swap! system-state update :instances dissoc dep))))

    ;; Stop the target module
    (when-let [instance-data (get (:instances state) module-name)]
              (let [module (get-in state [:modules module-name :module])
                    instance (:instance instance-data)]
                (proto/stop-module module instance)
                (swap! system-state update :instances dissoc module-name)))

    ;; Reload the module using ns_loader's hot-reload
    (let [reload-result (ns-loader/reload-module module-name)]
      (if (:success reload-result)
        ;; Get updated module data from ns-loader's registry
        (let [ns-loader-modules (ns-loader/get-loaded-modules)
              updated-module-data (get ns-loader-modules module-name)]
          (if updated-module-data
            (do
             ;; Sync updated module to system-state
             (swap! system-state assoc-in [:modules module-name] updated-module-data)
              ;; Restart the module
             (let [start-result (start-module-with-deps
                                 module-name
                                 updated-module-data
                                 (:config state))]
               (if (:success start-result)
                 (do
                  (swap! system-state assoc-in [:instances module-name]
                         {:instance (:success start-result)
                          :started-at (System/currentTimeMillis)})
                    ;; Restart dependents
                  (doseq [dep dependents]
                         (let [dep-data (get (:modules @system-state) dep)
                               dep-result (start-module-with-deps dep dep-data (:config @system-state))]
                           (when (:success dep-result)
                             (swap! system-state assoc-in [:instances dep]
                                    {:instance (:success dep-result)
                                     :started-at (System/currentTimeMillis)}))))
                  {:success {:reloaded module-name
                             :restarted-dependents (vec dependents)}})
                 {:error (:error start-result)})))
            {:error {:type :reload-sync-failed
                     :message "Module not found in ns-loader after reload"}}))
        {:error (:error reload-result)}))))
