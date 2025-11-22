(ns bb-mcp-server.module.dynamic-loader
    "Dynamic module loader using load-file.

  Loads modules based on module.edn configuration without requiring
  classpath modifications. Supports hot-reload by re-evaluating files.

  Key differences from loader.clj:
  - Uses load-file instead of require (true dynamic loading)
  - Reads :load-order from module.edn
  - Paths in module.edn are relative to module root
  - No classpath manipulation needed

  module.edn schema:
    {:name \"hello\"
     :version \"0.1.0\"
     :requires []           ; Module dependencies
     :load-order [...]      ; Files to load in order
     :entry \"ns/var\"       ; Entry point
     :defaults {...}}       ; Default configuration"
    (:require [babashka.fs :as fs]
              [bb-mcp-server.module.protocol :as proto]
              [clojure.edn :as edn]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Map of module-name -> {:manifest :module :instance}
(defonce ^:private loaded-modules (atom {}))

;; Metrics for module loading operations.
(defonce ^:private load-metrics
         (atom {:total-loads 0
                :successful-loads 0
                :failed-loads 0
                :reload-count 0
                :last-operation nil}))

;; =============================================================================
;; Manifest Loading
;; =============================================================================

(defn- resolve-path
  "Resolve a path relative to module root.
   If path starts with /, treat as absolute."
  [module-root path]
  (if (str/starts-with? path "/")
    path
    (str (fs/path module-root path))))

(defn- validate-module-edn
  "Validate module.edn structure."
  [manifest]
  (cond
    (not (map? manifest))
    {:valid false :errors ["module.edn must be a map"]}

    (not (string? (:name manifest)))
    {:valid false :errors [":name must be a string"]}

    (not (string? (:version manifest)))
    {:valid false :errors [":version must be a string"]}

    (not (vector? (:load-order manifest)))
    {:valid false :errors [":load-order must be a vector"]}

    (not (string? (:entry manifest)))
    {:valid false :errors [":entry must be a string (e.g., \"ns/var\")"]}

    :else
    {:valid true}))

(defn load-module-edn
  "Load module.edn from a module directory.

  Args:
    module-dir - Path to module directory (string or path)

  Returns:
    {:success manifest} or {:error {:type ... :message ...}}"
  [module-dir]
  (let [manifest-path (fs/path module-dir "module.edn")]
    (if (fs/exists? manifest-path)
      (try
       (let [manifest (-> manifest-path str slurp edn/read-string)
             validation (validate-module-edn manifest)]
         (if (:valid validation)
           {:success (assoc manifest :module-root (str module-dir))}
           {:error {:type :invalid-manifest
                    :path (str manifest-path)
                    :errors (:errors validation)}}))
       (catch Exception e
              {:error {:type :parse-error
                       :path (str manifest-path)
                       :message (ex-message e)}}))
      {:error {:type :not-found
               :path (str manifest-path)
               :message "module.edn not found"}})))

;; =============================================================================
;; File Loading
;; =============================================================================

(defn- load-file-safely
  "Load a file using load-file with error handling.

  Returns:
    {:success true} or {:error {:file ... :message ...}}"
  [file-path]
  (if (fs/exists? file-path)
    (try
     (load-file (str file-path))
     {:success true :file (str file-path)}
     (catch Exception e
            {:error {:file (str file-path)
                     :message (ex-message e)
                     :cause (ex-cause e)}}))
    {:error {:file (str file-path)
             :message "File not found"}}))

(defn- load-files-in-order
  "Load files in specified order.

  Args:
    module-root - Base path for relative paths
    load-order  - Vector of file paths

  Returns:
    {:success {:loaded [files...]}}
    or {:error {:failed-at file :message ...}}"
  [module-root load-order]
  (loop [files load-order
         loaded []]
        (if (empty? files)
          {:success {:loaded loaded}}
          (let [file (first files)
                path (resolve-path module-root file)
                result (load-file-safely path)]
            (if (:error result)
              {:error (assoc (:error result)
                             :load-order load-order
                             :loaded-so-far loaded)}
              (recur (rest files)
                     (conj loaded path)))))))

;; =============================================================================
;; Entry Point Resolution
;; =============================================================================

(defn- resolve-entry
  "Resolve entry point string to var.

  Entry format: \"namespace/var-name\"
  Example: \"hello.core/module\"

  Returns:
    {:success var} or {:error {:message ...}}"
  [entry-str]
  (try
   (let [[ns-str var-str] (str/split entry-str #"/")
         ns-sym (symbol ns-str)
         var-sym (symbol var-str)]
     (if-let [the-var (ns-resolve ns-sym var-sym)]
             {:success the-var}
             {:error {:message (str "Var not found: " entry-str)
                      :namespace ns-str
                      :var var-str}}))
   (catch Exception e
          {:error {:message (str "Failed to resolve entry: " entry-str)
                   :cause (ex-message e)}})))

;; =============================================================================
;; Module Loading
;; =============================================================================

(defn load-module
  "Dynamically load a module using load-file.

  Args:
    module-dir - Path to module directory

  Process:
    1. Read module.edn
    2. Load files in :load-order using load-file
    3. Resolve :entry to get module var
    4. Validate module protocol

  Returns:
    {:success {:manifest ... :module ... :load-time-ms ...}}
    or {:error {...}}"
  [module-dir]
  (let [start-time (System/currentTimeMillis)]
    (log/log! {:level :info
               :id ::loading-module
               :msg "Loading module"
               :data {:dir (str module-dir)}})

    ;; Step 1: Load module.edn
    (let [manifest-result (load-module-edn module-dir)]
      (if (:error manifest-result)
        (do
         (swap! load-metrics update :failed-loads inc)
         manifest-result)

        (let [manifest (:success manifest-result)
              module-name (:name manifest)
              module-root (:module-root manifest)]

          ;; Step 2: Load files in order
          (let [files-result (load-files-in-order module-root (:load-order manifest))]
            (if (:error files-result)
              (do
               (swap! load-metrics update :failed-loads inc)
               (log/log! {:level :error
                          :id ::file-load-failed
                          :msg "Failed to load module files"
                          :data {:module module-name
                                 :error (:error files-result)}})
               {:error (assoc (:error files-result) :module module-name)})

              ;; Step 3: Resolve entry point
              (let [entry-result (resolve-entry (:entry manifest))]
                (if (:error entry-result)
                  (do
                   (swap! load-metrics update :failed-loads inc)
                   {:error (assoc (:error entry-result) :module module-name)})

                  ;; Step 4: Validate module protocol
                  (let [module @(:success entry-result)
                        validation (proto/validate-module module)
                        load-time (- (System/currentTimeMillis) start-time)]
                    (if (:valid validation)
                      (do
                       (swap! load-metrics
                              #(-> %
                                   (update :total-loads inc)
                                   (update :successful-loads inc)
                                   (assoc :last-operation {:module module-name
                                                           :operation :load
                                                           :time (System/currentTimeMillis)})))
                       (log/log! {:level :info
                                  :id ::module-loaded
                                  :msg "Module loaded successfully"
                                  :data {:module module-name
                                         :version (:version manifest)
                                         :files-loaded (count (:load-order manifest))
                                         :load-time-ms load-time}})
                       {:success {:manifest manifest
                                  :module module
                                  :load-time-ms load-time}})

                      (do
                       (swap! load-metrics update :failed-loads inc)
                       {:error {:type :invalid-module
                                :module module-name
                                :errors (:errors validation)}}))))))))))))

;; =============================================================================
;; Hot Reload
;; =============================================================================

(defn reload-module
  "Hot-reload a module by re-evaluating its files.

  Args:
    module-name - Name of module to reload

  Process:
    1. Stop the module if running
    2. Re-load all files using load-file
    3. Re-resolve entry point
    4. Restart module with previous config

  Returns:
    {:success {:reloaded true}} or {:error {...}}"
  [module-name]
  (if-let [current (get @loaded-modules module-name)]
          (let [manifest (:manifest current)
                instance (:instance current)
                module (:module current)]

      ;; Stop current instance
            (when instance
              (try
               (proto/stop-module module instance)
               (catch Exception e
                      (log/log! {:level :warn
                                 :id ::stop-on-reload-failed
                                 :msg "Failed to stop module during reload"
                                 :data {:module module-name
                                        :error (ex-message e)}}))))

      ;; Re-load files
            (let [files-result (load-files-in-order
                                (:module-root manifest)
                                (:load-order manifest))]
              (if (:error files-result)
                {:error (assoc (:error files-result) :module module-name)}

          ;; Re-resolve entry
                (let [entry-result (resolve-entry (:entry manifest))]
                  (if (:error entry-result)
                    {:error (assoc (:error entry-result) :module module-name)}

                    (let [new-module @(:success entry-result)]
                      (swap! loaded-modules assoc-in [module-name :module] new-module)
                      (swap! load-metrics
                             #(-> %
                                  (update :reload-count inc)
                                  (assoc :last-operation {:module module-name
                                                          :operation :reload
                                                          :time (System/currentTimeMillis)})))
                      (log/log! {:level :info
                                 :id ::module-reloaded
                                 :msg "Module hot-reloaded"
                                 :data {:module module-name}})
                      {:success {:reloaded true :module module-name}}))))))

          {:error {:type :not-loaded
                   :module module-name
                   :message "Module not loaded"}}))

;; =============================================================================
;; Module Lifecycle Management
;; =============================================================================

(defn start-module!
  "Start a loaded module.

  Args:
    module-name - Name of module to start
    deps        - Map of dependency instances
    config      - Configuration map

  Returns:
    {:success instance} or {:error {...}}"
  [module-name deps config]
  (if-let [loaded (get @loaded-modules module-name)]
          (let [module (:module loaded)
                manifest (:manifest loaded)
                effective-config (merge (:defaults manifest {}) config)]
            (try
             (let [instance (proto/start-module module deps effective-config)]
               (swap! loaded-modules assoc-in [module-name :instance] instance)
               (log/log! {:level :info
                          :id ::module-started
                          :msg "Module started"
                          :data {:module module-name}})
               {:success instance})
             (catch Exception e
                    {:error {:type :start-failed
                             :module module-name
                             :message (ex-message e)}})))
          {:error {:type :not-loaded
                   :module module-name
                   :message "Module not loaded"}}))

(defn stop-module!
  "Stop a running module.

  Args:
    module-name - Name of module to stop

  Returns:
    {:success true} or {:error {...}}"
  [module-name]
  (if-let [loaded (get @loaded-modules module-name)]
          (if-let [instance (:instance loaded)]
                  (try
                   (proto/stop-module (:module loaded) instance)
                   (swap! loaded-modules assoc-in [module-name :instance] nil)
                   (log/log! {:level :info
                              :id ::module-stopped
                              :msg "Module stopped"
                              :data {:module module-name}})
                   {:success true}
                   (catch Exception e
                          {:error {:type :stop-failed
                                   :module module-name
                                   :message (ex-message e)}}))
                  {:error {:type :not-running
                           :module module-name}})
          {:error {:type :not-loaded
                   :module module-name}}))

;; =============================================================================
;; Bulk Operations
;; =============================================================================

(defn load-modules-from-system
  "Load modules specified in system.edn.

  Args:
    system-config - Parsed system.edn {:modules-dir \"...\" :modules [...]}

  Returns:
    Map of module-name -> load result"
  [system-config]
  (let [modules-dir (:modules-dir system-config "modules")
        module-names (:modules system-config [])]
    (into {}
          (for [name module-names]
               (let [module-dir (fs/path modules-dir name)
                     result (load-module module-dir)]
                 (when (:success result)
                   (swap! loaded-modules assoc name (:success result)))
                 [name result])))))

(defn start-all-modules!
  "Start all loaded modules in dependency order.

  Args:
    configs - Map of module-name -> config

  Returns:
    Map of module-name -> start result"
  [configs]
  ;; TODO: Implement dependency ordering from :requires
  (into {}
        (for [[name _loaded] @loaded-modules]
             [name (start-module! name {} (get configs name {}))])))

(defn stop-all-modules!
  "Stop all running modules."
  []
  (doseq [[name loaded] @loaded-modules
          :when (:instance loaded)]
         (stop-module! name)))

;; =============================================================================
;; Introspection
;; =============================================================================

(defn get-loaded-modules
  "Get map of all loaded modules."
  []
  @loaded-modules)

(defn get-module-status
  "Get status of a specific module."
  [module-name]
  (when-let [loaded (get @loaded-modules module-name)]
            (let [module (:module loaded)
                  instance (:instance loaded)]
              {:name module-name
               :version (get-in loaded [:manifest :version])
               :loaded true
               :running (some? instance)
               :status (when instance
                         (try
                          (proto/module-status module instance)
                          (catch Exception e
                                 {:error (ex-message e)})))})))

(defn get-metrics
  "Get loader metrics."
  []
  @load-metrics)

(defn reset-state!
  "Reset all loader state. Use for testing."
  []
  (stop-all-modules!)
  (reset! loaded-modules {})
  (reset! load-metrics {:total-loads 0
                        :successful-loads 0
                        :failed-loads 0
                        :reload-count 0
                        :last-operation nil}))
