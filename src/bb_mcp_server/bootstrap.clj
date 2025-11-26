(ns bb-mcp-server.bootstrap
    "Bootstrap module classpath discovery.

   This namespace dynamically adds all module src/test directories to the
   classpath, enabling automatic discovery of new modules without manual
   bb.edn updates.

   Call (ensure-module-paths!) early in startup, before requiring module
   namespaces.

   Key insight: The classpath IS the dependency mechanism. Once a directory
   is on the classpath, any namespace within it can be `require`d directly.
   This is separate from 'loading' a module (which registers tools and
   starts lifecycle).

   ## External Modules

   Use (add-external!) to add modules from outside the project tree:

     (add-external! \"/path/to/my-module\")      ; single module with module.edn
     (add-external! \"/path/to/more-modules\")   ; directory of modules

   Auto-detects whether path is a single module or collection based on
   presence of module.edn at root vs in subdirectories."
    (:require [babashka.classpath :as cp]
              [babashka.fs :as fs]
              [clojure.edn :as edn]
              [clojure.string :as str]
              [taoensso.trove :as log]))

(defonce ^:private paths-added? (atom false))

(defn- path-on-classpath?
  "Check if a path is already on the classpath."
  [path]
  (let [path-str (str (fs/absolutize path))
        current-cp (cp/get-classpath)]
    (some #(= (str (fs/absolutize %)) path-str)
          (when current-cp
            (str/split current-cp #":")))))

(defn discover-module-paths
  "Discover all module src and test directories under modules/.

   Returns {:src [paths...] :test [paths...] :total count}"
  ([]
   (discover-module-paths "modules"))
  ([modules-dir]
   (let [src-dirs (mapv str (fs/glob modules-dir "*/src"))
         test-dirs (mapv str (fs/glob modules-dir "*/test"))]
     {:src src-dirs
      :test test-dirs
      :total (+ (count src-dirs) (count test-dirs))})))

(defn add-module-paths!
  "Add all module src/test directories to the classpath.

   Options:
     :modules-dir - Base directory for modules (default: \"modules\")
     :include-test - Include test directories (default: true)
     :force - Add even if already added (default: false)

   Returns map with :added (count of new paths) and :already-present (count skipped)"
  ([]
   (add-module-paths! {}))
  ([{:keys [modules-dir include-test force]
     :or {modules-dir "modules"
          include-test true
          force false}}]
   (if (and @paths-added? (not force))
     (do
      (log/log! {:level :debug
                 :id ::paths-already-added
                 :msg "Module paths already added, skipping"})
      {:added 0 :already-present 0 :skipped true})

     (let [{:keys [src test]} (discover-module-paths modules-dir)
           all-paths (if include-test (concat src test) src)
           results (reduce (fn [acc path]
                             (if (path-on-classpath? path)
                               (update acc :already-present inc)
                               (do
                                (cp/add-classpath path)
                                (update acc :added inc))))
                           {:added 0 :already-present 0}
                           all-paths)]

       (reset! paths-added? true)

       (log/log! {:level :info
                  :id ::module-paths-added
                  :msg "Module classpath discovery complete"
                  :data {:added (:added results)
                         :already-present (:already-present results)
                         :modules-dir modules-dir}})

       results))))

(defn ensure-module-paths!
  "Ensure all module paths are on classpath. Idempotent.

   Call this at the start of any entry point (main, REPL, scripts)
   before requiring module namespaces."
  []
  (add-module-paths!))

(defn list-available-modules
  "List all available modules (directories with module.edn).

   Returns seq of {:name \"module-name\" :path \"modules/module-name\"}"
  ([]
   (list-available-modules "modules"))
  ([modules-dir]
   (->> (fs/glob modules-dir "*/module.edn")
        (map (fn [manifest-path]
               (let [module-dir (fs/parent manifest-path)
                     module-name (fs/file-name module-dir)]
                 {:name (str module-name)
                  :path (str module-dir)})))
        (sort-by :name))))

;; =============================================================================
;; External Module Support
;; =============================================================================

(defonce ^:private external-modules (atom #{}))

(defn- read-module-manifest
  "Read and parse a module.edn file. Returns nil if not found or invalid."
  [path]
  (let [manifest-path (if (str/ends-with? (str path) "module.edn")
                        path
                        (fs/path path "module.edn"))]
    (when (fs/exists? manifest-path)
      (try
       (-> (slurp (str manifest-path))
           (edn/read-string))
       (catch Exception e
              (log/log! {:level :warn
                         :id ::manifest-read-failed
                         :msg "Failed to read module.edn"
                         :data {:path (str manifest-path)}
                         :error e})
              nil)))))

(defn- single-module?
  "Check if path is a single module (has module.edn at root)."
  [path]
  (fs/exists? (fs/path path "module.edn")))

(defn- modules-collection?
  "Check if path is a collection of modules (has subdirs with module.edn)."
  [path]
  (seq (fs/glob path "*/module.edn")))

(defn- add-single-module-paths!
  "Add src/ and test/ from a single module directory to classpath.

   Returns {:name module-name :path module-path :added count :already-present count}"
  [module-path {:keys [include-test] :or {include-test true}}]
  (let [abs-path (str (fs/absolutize module-path))
        manifest (read-module-manifest module-path)
        module-name (or (:name manifest) (fs/file-name module-path))
        src-path (fs/path abs-path "src")
        test-path (fs/path abs-path "test")
        paths-to-add (cond-> []
                             (fs/exists? src-path) (conj (str src-path))
                             (and include-test (fs/exists? test-path)) (conj (str test-path)))
        results (reduce (fn [acc path]
                          (if (path-on-classpath? path)
                            (update acc :already-present inc)
                            (do
                             (cp/add-classpath path)
                             (update acc :added inc))))
                        {:added 0 :already-present 0}
                        paths-to-add)]

    (swap! external-modules conj {:name module-name :path abs-path})

    (log/log! {:level :info
               :id ::external-module-added
               :msg "Added external module to classpath"
               :data {:name module-name
                      :path abs-path
                      :added (:added results)
                      :already-present (:already-present results)}})

    (assoc results :name module-name :path abs-path)))

(defn- add-modules-collection!
  "Add all modules from a collection directory to classpath.

   Returns {:modules [results...] :total-added count}"
  [collection-path opts]
  (let [module-dirs (->> (fs/glob collection-path "*/module.edn")
                         (map fs/parent)
                         (map str))
        results (mapv #(add-single-module-paths! % opts) module-dirs)
        total-added (reduce + 0 (map :added results))]

    (log/log! {:level :info
               :id ::external-collection-added
               :msg "Added external module collection to classpath"
               :data {:path (str collection-path)
                      :modules (mapv :name results)
                      :total-added total-added}})

    {:modules results
     :total-added total-added
     :collection-path (str collection-path)}))

(defn add-external!
  "Add external module(s) to classpath.

   Path can be:
   - Directory with module.edn (single module)
   - Directory with subdirs containing module.edn (collection)
   - Path to module.edn file directly

   Options:
     :include-test - Include test directories (default: true)

   Returns map with :type (:single or :collection) and addition results.

   Examples:
     (add-external! \"/path/to/my-module\")
     (add-external! \"/path/to/modules-dir\")
     (add-external! \"/path/to/my-module/module.edn\")"
  ([path]
   (add-external! path {}))
  ([path opts]
   (let [path (str (fs/absolutize path))
         ;; Handle path to module.edn directly
         path (if (str/ends-with? path "module.edn")
                (str (fs/parent path))
                path)]

     (cond
       ;; Path doesn't exist
       (not (fs/exists? path))
       (do
        (log/log! {:level :error
                   :id ::external-path-not-found
                   :msg "External module path not found"
                   :data {:path path}})
        {:error :not-found :path path})

       ;; Single module (has module.edn at root)
       (single-module? path)
       (assoc (add-single-module-paths! path opts) :type :single)

       ;; Collection of modules (has subdirs with module.edn)
       (modules-collection? path)
       (assoc (add-modules-collection! path opts) :type :collection)

       ;; Neither - invalid path
       :else
       (do
        (log/log! {:level :error
                   :id ::external-path-invalid
                   :msg "Path is neither a module nor a module collection"
                   :data {:path path
                          :hint "Expected module.edn at root or in subdirectories"}})
        {:error :invalid-path :path path})))))

(defn list-external-modules
  "List all external modules that have been added.

   Returns set of {:name module-name :path absolute-path}"
  []
  @external-modules)

(defn add-externals-from-env!
  "Add external modules from BB_MCP_EXTERNAL_MODULES environment variable.

   The env var should be colon-separated paths:
     BB_MCP_EXTERNAL_MODULES=/path/to/mod1:/path/to/mods-dir

   Returns seq of results from add-external! calls."
  []
  (when-let [env-val (System/getenv "BB_MCP_EXTERNAL_MODULES")]
            (let [paths (str/split env-val #":")
                  results (mapv add-external! paths)]
              (log/log! {:level :info
                         :id ::external-modules-from-env
                         :msg "Loaded external modules from environment"
                         :data {:paths paths
                                :count (count paths)}})
              results)))
