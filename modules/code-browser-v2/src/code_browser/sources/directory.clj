(ns code-browser.sources.directory
    "Directory source adapter for Code Browser v2.

   Analyzes local directory projects using clj-kondo for:
   - Namespace discovery
   - Symbol extraction (functions, vars, macros, etc.)
   - Dependency analysis

   Design decisions:
   - Uses clj-kondo for accurate analysis (handles in-ns, multi-file namespaces)
   - Git SHA as version for static versioning (D4)
   - Source content fetched on demand (D5)"
    (:require [code-browser.sources.protocol :as proto]
              [code-browser.uri :as uri]
              [babashka.fs :as fs]
              [babashka.process :refer [shell]]
              [clojure.edn :as edn]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; Git Helpers
;;; ---------------------------------------------------------------------------

(defn- get-git-sha
  "Get current git SHA for a directory. Returns nil if not a git repo."
  [dir]
  (try
   (let [result (shell {:dir dir :out :string :err :string :continue true}
                       "git" "rev-parse" "--short" "HEAD")]
     (when (zero? (:exit result))
       (str/trim (:out result))))
   (catch Exception _
          nil)))

(defn- get-project-name
  "Extract project name from directory path."
  [dir]
  (fs/file-name dir))

;;; ---------------------------------------------------------------------------
;;; clj-kondo Analysis
;;; ---------------------------------------------------------------------------

(defn- run-kondo-project-analysis
  "Run clj-kondo on a project directory.

   Returns clj-kondo analysis map with:
   - :var-definitions
   - :var-usages (for defmethod detection)
   - :namespace-definitions
   - :namespace-usages (for aliases/refers)"
  [project-root]
  (let [src-dir (str project-root "/src")
        test-dir (str project-root "/test")
        modules-dir (str project-root "/modules")
        lint-paths (filterv fs/exists? [src-dir test-dir modules-dir])]
    (when (seq lint-paths)
      (log/log! {:level :debug
                 :id ::kondo-scanning
                 :msg "Scanning project with clj-kondo"
                 :data {:project-root project-root
                        :lint-paths lint-paths}})
      (try
       (let [cmd-args (concat ["clj-kondo"]
                              (mapcat #(vector "--lint" %) lint-paths)
                              ["--config"
                               "{:output {:analysis {:var-definitions true :var-usages true :namespace-definitions true :namespace-usages true} :format :edn}}"])
             result (apply shell {:out :string :err :string :continue true} cmd-args)
             output (:out result)]
         (when (seq output)
           (let [analysis (edn/read-string output)]
             (log/log! {:level :debug
                        :id ::kondo-complete
                        :msg "clj-kondo analysis complete"
                        :data {:var-count (count (get-in analysis [:analysis :var-definitions]))
                               :ns-count (count (get-in analysis [:analysis :namespace-definitions]))}})
             (:analysis analysis))))
       (catch Exception e
              (log/log! {:level :warn
                         :id ::kondo-error
                         :msg "clj-kondo analysis failed"
                         :data {:project-root project-root
                                :error (ex-message e)}})
              nil)))))

(defn- compute-ns-files
  "Compute namespace → files mapping from var-definitions.
   Returns {ns-name [file1 file2 ...]}."
  [var-defs]
  (->> var-defs
       (filter (fn [v] (and (:ns v) (:filename v))))
       (group-by :ns)
       (map (fn [[ns-name vars]]
              [(str ns-name) (->> vars
                                  (map :filename)
                                  distinct
                                  vec)]))
       (into {})))

;;; ---------------------------------------------------------------------------
;;; DirectorySource Record
;;; ---------------------------------------------------------------------------

(defrecord DirectorySource [root-path project-name version uri-base]
           proto/IProjectSource

           (scan-project [_this]
                         (log/log! {:level :info
                                    :id ::scanning-project
                                    :msg "Scanning directory project"
                                    :data {:root-path root-path
                                           :project-name project-name
                                           :version version}})
                         (when-let [analysis (run-kondo-project-analysis root-path)]
                                   (let [var-defs (:var-definitions analysis)
                                         var-usages (:var-usages analysis)
                                         ns-defs (:namespace-definitions analysis)
                                         ns-usages (:namespace-usages analysis)
          ;; Compute namespace → files mapping
                                         ns-files (compute-ns-files var-defs)
          ;; Build project entity
                                         project {:uri/string uri-base
                                                  :uri/source :dir
                                                  :uri/project project-name
                                                  :uri/version version
                                                  :uri/version-type :static
                                                  :project/root-path root-path}
          ;; Build namespace entities
                                         namespaces (for [ns-def ns-defs
                                                          :let [ns-name (str (:name ns-def))
                                                                files (get ns-files ns-name [(:filename ns-def)])]]
                                                         (merge
                                                          (proto/kondo-ns->namespace-map ns-def uri-base files)
                                                          {:uri/source :dir
                                                           :uri/project project-name
                                                           :uri/version version
                                                           :uri/version-type :static
                                                           :uri/parent [:uri/string uri-base]
                                                           :ns/aliases (proto/extract-aliases-from-usages ns-usages ns-name)
                                                           :ns/refers (proto/extract-refers-from-usages ns-usages ns-name)}))
          ;; Build symbol entities from var-definitions
                                         symbols-from-vars (for [var-def var-defs]
                                                                (merge
                                                                 (proto/kondo-var->symbol-map var-def uri-base)
                                                                 {:uri/source :dir
                                                                  :uri/project project-name
                                                                  :uri/version version
                                                                  :uri/version-type :static
                                                                  :uri/parent [:uri/string (str uri-base "/" (:ns var-def))]}))
          ;; Extract defmethod symbols from var-usages
                                         defmethods (->> var-usages
                                                         (filter :defmethod)
                                                         (map (fn [usage]
                                                                (merge
                                                                 (proto/kondo-defmethod->symbol-map usage uri-base)
                                                                 {:uri/source :dir
                                                                  :uri/project project-name
                                                                  :uri/version version
                                                                  :uri/version-type :static}))))
                                         all-symbols (concat symbols-from-vars defmethods)]
                                     (log/log! {:level :info
                                                :id ::scan-complete
                                                :msg "Directory scan complete"
                                                :data {:project-name project-name
                                                       :namespace-count (count namespaces)
                                                       :symbol-count (count all-symbols)}})
                                     {:project project
                                      :namespaces (vec namespaces)
                                      :symbols (vec all-symbols)})))

           (fetch-source [_this uri-string]
                         (when-let [parsed (uri/parse uri-string)]
                                   (when-let [sym-name (:uri/symbol parsed)]
            ;; For now, we need to look up the symbol in the database to get file/line info
            ;; This will be enhanced when we integrate with the DB
                                             (log/log! {:level :debug
                                                        :id ::fetch-source
                                                        :msg "Fetching source for symbol"
                                                        :data {:uri uri-string
                                                               :symbol sym-name}})
            ;; TODO: Implement source extraction using file coordinates
                                             nil)))

           (watch! [_this _callback]
    ;; TODO: Implement file watching with fs/watch
                   (log/log! {:level :debug
                              :id ::watch-not-implemented
                              :msg "File watching not yet implemented"})
                   nil)

           (unwatch! [_this _handle]
                     nil)

           (source-info [_this]
                        {:type :dir
                         :version-type :static
                         :supports-watch? false  ;; TODO: implement
                         :description (str "Directory: " root-path)}))

;;; ---------------------------------------------------------------------------
;;; Constructor
;;; ---------------------------------------------------------------------------

(defn create-directory-source
  "Create a DirectorySource for a local project.

   Arguments:
     root-path - Absolute path to project root

   Options:
     :version - Override version (default: git SHA or \"local\")
     :project-name - Override project name (default: directory name)

   Example:
     (create-directory-source \"/path/to/my-project\")
     (create-directory-source \"/path/to/proj\" {:version \"v1.0.0\"})"
  ([root-path]
   (create-directory-source root-path {}))
  ([root-path {:keys [version project-name]}]
   (let [abs-path (str (fs/absolutize root-path))
         proj-name (or project-name (get-project-name abs-path))
         ver (or version (get-git-sha abs-path) "local")
         uri-base (uri/build {:source :dir :project proj-name :version ver})]
     (log/log! {:level :info
                :id ::creating-directory-source
                :msg "Creating directory source"
                :data {:root-path abs-path
                       :project-name proj-name
                       :version ver
                       :uri-base uri-base}})
     (->DirectorySource abs-path proj-name ver uri-base))))
