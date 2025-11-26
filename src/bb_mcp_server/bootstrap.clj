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
   starts lifecycle)."
    (:require [babashka.classpath :as cp]
              [babashka.fs :as fs]
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
