(ns bb-mcp-server.module.loader
    "Module loader for bb-mcp-server.

  Discovers and loads modules from the filesystem.
  Modules are directories containing a module.edn manifest.

  Directory structure:
    modules/
      hello/
        module.edn       ; Module manifest
        src/
          hello/core.clj ; Entry namespace

  module.edn schema:
    {:name \"hello\"
     :version \"1.0.0\"
     :entry-ns hello.core
     :deps []
     :config-schema {...}}"
    (:require [babashka.classpath :as cp]
              [bb-mcp-server.module.protocol :as proto]
              [clojure.edn :as edn]
              [clojure.java.io :as io]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def manifest-filename
     "Standard filename for module manifests."
     "module.edn")

(def default-modules-dir
     "Default directory to search for modules."
     "modules")

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(def ^:private loader-metrics
     "Atom holding loader telemetry metrics."
     (atom {:modules-discovered 0
            :modules-loaded 0
            :modules-failed 0
            :total-load-time-ms 0
            :last-load-at nil
            :load-times {}
            :failed-modules []}))

(defn reset-metrics!
  "Reset all loader metrics to initial state."
  []
  (reset! loader-metrics
          {:modules-discovered 0
           :modules-loaded 0
           :modules-failed 0
           :total-load-time-ms 0
           :last-load-at nil
           :load-times {}
           :failed-modules []}))

(defn get-metrics
  "Get current loader metrics.

  Returns:
    Map with keys:
      :modules-discovered - Number of modules found
      :modules-loaded     - Number successfully loaded
      :modules-failed     - Number that failed to load
      :total-load-time-ms - Total time spent loading
      :last-load-at       - Timestamp of last load operation
      :load-times         - Map of module-name -> load-time-ms
      :failed-modules     - Vector of module names that failed"
  []
  @loader-metrics)

(defn- record-discovery!
  "Record module discovery metrics."
  [count]
  (swap! loader-metrics assoc :modules-discovered count))

(defn- record-load-success!
  "Record successful module load with timing."
  [module-name load-time-ms]
  (swap! loader-metrics
         (fn [m]
           (-> m
               (update :modules-loaded inc)
               (update :total-load-time-ms + load-time-ms)
               (assoc :last-load-at (System/currentTimeMillis))
               (assoc-in [:load-times module-name] load-time-ms)))))

(defn- record-load-failure!
  "Record module load failure with module name."
  [module-name]
  (swap! loader-metrics
         (fn [m]
           (-> m
               (update :modules-failed inc)
               (update :failed-modules conj module-name)
               (assoc :last-load-at (System/currentTimeMillis))))))

;; -----------------------------------------------------------------------------
;; Manifest Schema
;; -----------------------------------------------------------------------------

(def manifest-schema
     "Required keys in a module manifest.

  - :name     - Unique module identifier (string)
  - :version  - Module version (string, semver recommended)
  - :entry-ns - Clojure namespace symbol for module entry point
  - :deps     - Vector of dependency module names (optional)
  - :config-schema - Malli schema for module configuration (optional)"
     {:required #{:name :version :entry-ns}
      :optional #{:deps :config-schema :description}})

;; -----------------------------------------------------------------------------
;; Manifest Validation
;; -----------------------------------------------------------------------------

(defn validate-manifest
  "Validate a module manifest.

  Args:
    manifest - Parsed module.edn map

  Returns:
    {:valid true} or {:valid false :errors [...]}"
  [manifest]
  (let [errors (cond-> []
                       (not (map? manifest))
                       (conj "Manifest must be a map")

                       (and (map? manifest)
                            (not (string? (:name manifest))))
                       (conj "Manifest :name must be a string")

                       (and (map? manifest)
                            (not (string? (:version manifest))))
                       (conj "Manifest :version must be a string")

                       (and (map? manifest)
                            (not (symbol? (:entry-ns manifest))))
                       (conj "Manifest :entry-ns must be a symbol")

                       (and (map? manifest)
                            (:deps manifest)
                            (not (vector? (:deps manifest))))
                       (conj "Manifest :deps must be a vector if present"))]
    (if (empty? errors)
      {:valid true}
      {:valid false :errors errors})))

;; -----------------------------------------------------------------------------
;; Module Discovery
;; -----------------------------------------------------------------------------

(defn- directory?
  "Check if a file is a directory."
  [file]
  (.isDirectory file))

(defn- has-manifest?
  "Check if a directory contains a module.edn manifest."
  [dir]
  (.exists (io/file dir manifest-filename)))

(defn discover-modules
  "Discover module directories in the given path.

  Args:
    modules-dir - Path to modules directory (default: 'modules')

  Returns:
    Vector of module directory paths that contain module.edn files"
  ([]
   (discover-modules default-modules-dir))
  ([modules-dir]
   (let [dir (io/file modules-dir)]
     (if (.exists dir)
       (let [subdirs (->> (.listFiles dir)
                          (filter directory?)
                          (filter has-manifest?)
                          (mapv #(.getPath %)))]
         (record-discovery! (count subdirs))
         (log/log! {:level :info
                    :id ::discover-modules
                    :msg "Discovered modules"
                    :data {:dir modules-dir
                           :count (count subdirs)
                           :modules (mapv #(.getName (io/file %)) subdirs)}})
         subdirs)
       (do
        (log/log! {:level :warn
                   :id ::modules-dir-not-found
                   :msg "Modules directory not found"
                   :data {:dir modules-dir}})
        [])))))

;; -----------------------------------------------------------------------------
;; Manifest Loading
;; -----------------------------------------------------------------------------

(defn load-manifest
  "Load and validate a module manifest from a directory.

  Args:
    module-dir - Path to module directory

  Returns:
    {:success manifest-map} or {:error error-info}

  The manifest is read from module.edn in the given directory."
  [module-dir]
  (let [manifest-path (io/file module-dir manifest-filename)]
    (if (.exists manifest-path)
      (try
       (let [manifest (-> manifest-path slurp edn/read-string)
             validation (validate-manifest manifest)]
         (if (:valid validation)
           (do
            (log/log! {:level :info
                       :id ::manifest-loaded
                       :msg "Module manifest loaded"
                       :data {:module (:name manifest)
                              :version (:version manifest)}})
            {:success (assoc manifest :module-dir module-dir)})
           (do
            (log/log! {:level :error
                       :id ::manifest-invalid
                       :msg "Invalid module manifest"
                       :data {:dir module-dir
                              :errors (:errors validation)}})
            {:error {:type :invalid-manifest
                     :dir module-dir
                     :errors (:errors validation)}})))
       (catch Exception e
              (log/log! {:level :error
                         :id ::manifest-parse-error
                         :msg "Failed to parse module manifest"
                         :error e
                         :data {:dir module-dir
                                :error (ex-message e)}})
              {:error {:type :parse-error
                       :dir module-dir
                       :message (ex-message e)}}))
      {:error {:type :not-found
               :dir module-dir
               :message "module.edn not found"}})))

;; -----------------------------------------------------------------------------
;; Module Loading
;; -----------------------------------------------------------------------------

(defn- add-module-to-classpath!
  "Add module's src directory to the classpath.

  Args:
    module-dir - Path to module directory

  Side effects:
    Adds {module-dir}/src to babashka's classpath"
  [module-dir]
  (let [src-dir (io/file module-dir "src")]
    (when (.exists src-dir)
      (cp/add-classpath (.getPath src-dir))
      (log/log! {:level :debug
                 :id ::classpath-added
                 :msg "Added module to classpath"
                 :data {:src-dir (.getPath src-dir)}}))))

(defn load-module
  "Load a module from its directory.

  Args:
    module-dir - Path to module directory

  Returns:
    {:success loaded-module} or {:error error-info}

  Process:
    1. Load and validate manifest
    2. Add module src to classpath
    3. Require entry namespace
    4. Extract and validate module implementation

  Telemetry:
    Records load time and success/failure metrics"
  [module-dir]
  (let [start-time (System/currentTimeMillis)
        manifest-result (load-manifest module-dir)]
    (if (:error manifest-result)
      (do
       (record-load-failure! (.getName (io/file module-dir)))
       manifest-result)
      (let [manifest (:success manifest-result)
            entry-ns (:entry-ns manifest)
            module-name (:name manifest)]
        (try
          ;; Add to classpath
         (add-module-to-classpath! module-dir)

          ;; Require the entry namespace
         (log/log! {:level :info
                    :id ::requiring-module
                    :msg "Requiring module namespace"
                    :data {:module module-name
                           :entry-ns entry-ns}})
         (require entry-ns)

          ;; Get the module var
         (let [module-var (ns-resolve entry-ns 'module)]
           (if module-var
             (let [module @module-var
                   validation (proto/validate-module module)
                   load-time (- (System/currentTimeMillis) start-time)]
               (if (:valid validation)
                 (do
                  (record-load-success! module-name load-time)
                  (log/log! {:level :info
                             :id ::module-loaded
                             :msg "Module loaded successfully"
                             :data {:module module-name
                                    :version (:version manifest)
                                    :load-time-ms load-time}})
                  {:success {:manifest manifest
                             :module module
                             :load-time-ms load-time}})
                 (do
                  (record-load-failure! module-name)
                  {:error {:type :invalid-module
                           :module module-name
                           :errors (:errors validation)}})))
             (do
              (record-load-failure! module-name)
              {:error {:type :missing-module-var
                       :module module-name
                       :message (str "No 'module' var found in " entry-ns)}})))
         (catch Exception e
                (record-load-failure! module-name)
                (log/log! {:level :error
                           :id ::module-load-error
                           :msg "Failed to load module"
                           :error e
                           :data {:module module-name
                                  :error (ex-message e)}})
                {:error {:type :load-error
                         :module module-name
                         :message (ex-message e)}}))))))

;; -----------------------------------------------------------------------------
;; Batch Loading
;; -----------------------------------------------------------------------------

(defn load-all-modules
  "Discover and load all modules from a directory.

  Args:
    modules-dir - Path to modules directory (default: 'modules')

  Returns:
    Map of module-name -> {:manifest ... :module ...} or {:error ...}

  Telemetry:
    Resets metrics before loading, tracks overall operation"
  ([]
   (load-all-modules default-modules-dir))
  ([modules-dir]
   (reset-metrics!)
   (let [start-time (System/currentTimeMillis)
         module-dirs (discover-modules modules-dir)
         results (for [dir module-dirs]
                      (let [result (load-module dir)]
                        (if (:success result)
                          [(:name (:manifest (:success result))) (:success result)]
                          [(.getName (io/file dir)) {:error (:error result)}])))
         result-map (into {} results)
         total-time (- (System/currentTimeMillis) start-time)
         metrics (get-metrics)]
     (log/log! {:level :info
                :id ::load-all-complete
                :msg "Module loading complete"
                :data {:total-time-ms total-time
                       :discovered (:modules-discovered metrics)
                       :loaded (:modules-loaded metrics)
                       :failed (:modules-failed metrics)}})
     result-map)))

(defn loaded-module-names
  "Get names of successfully loaded modules.

  Args:
    loaded-modules - Map returned by load-all-modules

  Returns:
    Vector of module names that loaded successfully"
  [loaded-modules]
  (->> loaded-modules
       (filter (fn [[_name data]] (not (:error data))))
       (mapv first)))

(defn failed-module-names
  "Get names of modules that failed to load.

  Args:
    loaded-modules - Map returned by load-all-modules

  Returns:
    Vector of module names that failed to load"
  [loaded-modules]
  (->> loaded-modules
       (filter (fn [[_name data]] (:error data)))
       (mapv first)))
