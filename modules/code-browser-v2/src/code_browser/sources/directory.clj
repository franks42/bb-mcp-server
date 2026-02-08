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
              [babashka.pods :as pods]
              [babashka.process :refer [shell]]
              [clojure.edn :as edn]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;;; ---------------------------------------------------------------------------
;;; File Watcher Pod (loaded idempotently)
;;; ---------------------------------------------------------------------------

(pods/load-pod 'org.babashka/fswatcher "0.0.7")
(require '[pod.babashka.fswatcher :as fw])

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
;;; File Watching Helpers
;;; ---------------------------------------------------------------------------

(def ^:private clojure-extensions
     "File extensions to watch for changes."
     #{".clj" ".cljs" ".cljc" ".edn"})

(defn- clojure-file?
  "Return true if the path has a Clojure-related extension."
  [path]
  (let [p (str path)]
    (some #(str/ends-with? p %) clojure-extensions)))

(def ^:private ignore-patterns
     "Path segments that indicate files to ignore."
     ["/.git/" "/.clj-kondo/" "/.lsp/" "/.cpcache/" "/target/"
      "/node_modules/" "/.shadow-cljs/"])

(defn- should-ignore?
  "Return true if the path should be ignored by the watcher."
  [path]
  (let [p (str path)]
    (some #(str/includes? p %) ignore-patterns)))

(defn- event-type
  "Convert fswatcher event type to our protocol event type."
  [type]
  (case type
    :create :created
    :write :changed
    :remove :deleted
    :changed))

(defn- debounced-callback
  "Wrap callback with trailing-edge debounce.
   Returns [trigger-fn! scheduled-atom].
   Calling trigger-fn! resets the timer. Callback fires after delay-ms of quiet."
  [callback delay-ms]
  (let [!scheduled (atom nil)]
    [(fn trigger! [event]
       (when-let [prev @!scheduled] (future-cancel prev))
       (reset! !scheduled
               (future (Thread/sleep delay-ms)
                       (callback event))))
     !scheduled]))

;;; ---------------------------------------------------------------------------
;;; Path Helpers
;;; ---------------------------------------------------------------------------

(defn- relativize-path
  "Convert absolute path to relative path from project root."
  [root-path abs-path]
  (str (fs/relativize root-path abs-path)))

;;; ---------------------------------------------------------------------------
;;; clj-kondo Analysis
;;; ---------------------------------------------------------------------------

(defn- run-kondo-file-analysis
  "Run clj-kondo on a single file.

   Returns clj-kondo analysis map with:
   - :var-definitions
   - :var-usages (for defmethod detection)
   - :namespace-definitions
   - :namespace-usages (for aliases/refers)"
  [file-path]
  (log/log! {:level :debug
             :id ::kondo-scanning-file
             :msg "Scanning single file with clj-kondo"
             :data {:file file-path}})
  (try
   (let [cmd-args ["clj-kondo" "--lint" (str file-path)
                   "--config"
                   "{:output {:analysis {:var-definitions true :var-usages true :namespace-definitions true :namespace-usages true} :format :edn}}"]
         result (apply shell {:out :string :err :string :continue true} cmd-args)
         output (:out result)]
     (when (seq output)
       (let [analysis (edn/read-string output)]
         (log/log! {:level :debug
                    :id ::kondo-file-complete
                    :msg "clj-kondo file analysis complete"
                    :data {:file (str file-path)
                           :var-count (count (get-in analysis [:analysis :var-definitions]))
                           :ns-count (count (get-in analysis [:analysis :namespace-definitions]))}})
         (:analysis analysis))))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::kondo-file-error
                     :msg "clj-kondo file analysis failed"
                     :data {:file (str file-path)
                            :error (ex-message e)}})
          nil)))

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
  "Compute namespace -> files mapping from var-definitions.
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
;;; Source Extraction
;;; ---------------------------------------------------------------------------

(defn- read-file-lines
  "Read a file and return a vector of its lines."
  [file-path]
  (try
   (vec (str/split-lines (slurp file-path)))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::read-file-error
                     :msg "Failed to read file"
                     :data {:file file-path
                            :error (ex-message e)}})
          nil)))

(defn- extract-source-lines
  "Extract source lines from a file given line range.
   Line numbers are 1-indexed (from clj-kondo)."
  [file-path start-line end-line]
  (when-let [lines (read-file-lines file-path)]
            (let [start-idx (dec start-line)  ; Convert to 0-indexed
                  end-idx (if end-line (dec end-line) start-idx)
          ;; Clamp to valid range
                  start-idx (max 0 start-idx)
                  end-idx (min (dec (count lines)) end-idx)]
              (when (and (>= end-idx start-idx) (< start-idx (count lines)))
                (->> lines
                     (drop start-idx)
                     (take (inc (- end-idx start-idx)))
                     (str/join "\n"))))))

;;; ---------------------------------------------------------------------------
;;; DirectorySource Record
;;; ---------------------------------------------------------------------------

(defrecord DirectorySource [root-path project-name version uri-base symbol-cache]
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
            ;; Compute namespace -> files mapping
                                         ns-files (compute-ns-files var-defs)
            ;; Build project entity
                                         project {:uri/string uri-base
                                                  :uri/source :dir
                                                  :uri/project project-name
                                                  :uri/version version
                                                  :uri/version-type :static
                                                  :project/root-path root-path}
            ;; Build namespace entities (without nested aliases/refers)
                                         namespaces (for [ns-def ns-defs
                                                          :let [ns-name (str (:name ns-def))
                                                                files (get ns-files ns-name [(:filename ns-def)])]]
                                                         (merge
                                                          (proto/kondo-ns->namespace-map ns-def uri-base files)
                                                          {:uri/source :dir
                                                           :uri/project project-name
                                                           :uri/version version
                                                           :uri/version-type :static
                                                           :uri/parent [:uri/string uri-base]}))
            ;; Build alias entities from namespace-usages
                                         aliases (for [ns-def ns-defs
                                                       :let [ns-name (str (:name ns-def))
                                                             ns-uri (str uri-base "/" ns-name)]
                                                       alias-map (proto/extract-aliases-from-usages ns-usages ns-name)]
                                                      {:uri/string (str ns-uri "#alias:" (:alias alias-map))
                                                       :uri/source :dir
                                                       :uri/project project-name
                                                       :uri/version version
                                                       :uri/version-type :static
                                                       :alias/from-ns ns-name
                                                       :alias/name (:alias alias-map)
                                                       :alias/to-ns (:ns alias-map)})
            ;; Build refer entities from namespace-usages
                                         refers (for [ns-def ns-defs
                                                      :let [ns-name (str (:name ns-def))
                                                            ns-uri (str uri-base "/" ns-name)]
                                                      refer-map (proto/extract-refers-from-usages ns-usages ns-name)]
                                                     {:uri/string (str ns-uri "#refer:" (:sym refer-map))
                                                      :uri/source :dir
                                                      :uri/project project-name
                                                      :uri/version version
                                                      :uri/version-type :static
                                                      :refer/from-ns ns-name
                                                      :refer/symbol (:sym refer-map)
                                                      :refer/from-ns-source (:from-ns refer-map)})
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
                                         all-symbols (concat symbols-from-vars defmethods)
            ;; Populate symbol cache for fetch-source
                                         cache-entries (into {}
                                                             (map (fn [sym]
                                                                    [(:uri/string sym)
                                                                     {:file (:symbol/file sym)
                                                                      :line (:symbol/line sym)
                                                                      :end-line (:symbol/end-line sym)}])
                                                                  all-symbols))]
                                     (reset! symbol-cache cache-entries)
                                     (log/log! {:level :info
                                                :id ::scan-complete
                                                :msg "Directory scan complete"
                                                :data {:project-name project-name
                                                       :namespace-count (count namespaces)
                                                       :symbol-count (count all-symbols)
                                                       :alias-count (count aliases)
                                                       :refer-count (count refers)
                                                       :cache-size (count cache-entries)}})
                                     {:project project
                                      :namespaces (vec namespaces)
                                      :symbols (vec all-symbols)
                                      :aliases (vec aliases)
                                      :refers (vec refers)})))

           (fetch-source [_this uri-string]
                         (log/log! {:level :debug
                                    :id ::fetch-source
                                    :msg "Fetching source"
                                    :data {:uri uri-string}})
                         (when-let [sym-info (get @symbol-cache uri-string)]
                                   (let [{:keys [file line end-line]} sym-info
            ;; Resolve relative path to absolute
                                         abs-file (if (fs/absolute? file)
                                                    file
                                                    (str root-path "/" file))]
                                     (when (and file line)
                                       (when-let [content (extract-source-lines abs-file line end-line)]
                                                 {:content content
                                                  :file abs-file
                                                  :start-line line
                                                  :end-line (or end-line line)})))))

           (watch! [_this callback]
                   (let [[trigger! !sched] (debounced-callback callback 500)
                         watcher (fw/watch root-path
                                           (fn [{:keys [type path]}]
                                             (when (and (clojure-file? path)
                                                        (not (should-ignore? path)))
                                               (log/log! {:level :debug
                                                          :id ::file-changed
                                                          :msg "File changed"
                                                          :data {:path (str path)
                                                                 :type type}})
                                               (trigger! {:type (event-type type)
                                                          :path (str path)})))
                                           {:recursive true})]
                     (log/log! {:level :info
                                :id ::watch-started
                                :msg "File watcher started"
                                :data {:root-path root-path}})
                     {:watcher watcher :scheduled !sched}))

           (unwatch! [_this handle]
                     (when handle
                       (when-let [w (:watcher handle)]
                                 (fw/unwatch w))
                       (when-let [s @(:scheduled handle)]
                                 (future-cancel s))
                       (log/log! {:level :info
                                  :id ::watch-stopped
                                  :msg "File watcher stopped"
                                  :data {:root-path root-path}})))

           (source-info [_this]
                        {:type :dir
                         :version-type :static
                         :supports-watch? true
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
         uri-base (uri/build {:source :dir :project proj-name :version ver})
         cache (atom {})]
     (log/log! {:level :info
                :id ::creating-directory-source
                :msg "Creating directory source"
                :data {:root-path abs-path
                       :project-name proj-name
                       :version ver
                       :uri-base uri-base}})
     (->DirectorySource abs-path proj-name ver uri-base cache))))

;;; ---------------------------------------------------------------------------
;;; Single-File Scanning (Incremental Update)
;;; ---------------------------------------------------------------------------

(defn scan-file
  "Scan a single file and return metadata for its namespaces and symbols.

   Returns the same structure as scan-project but only for entities in this file.
   Also updates the source's symbol-cache selectively (removes old entries for
   this file, adds new ones).

   Arguments:
     source    - DirectorySource instance
     file-path - Absolute path to the changed file

   Returns:
     {:namespaces [...] :symbols [...] :aliases [...] :refers [...]
      :affected-ns-names #{...}}
     or nil if analysis fails."
  [source file-path]
  (let [{:keys [root-path project-name version uri-base symbol-cache]} source
        rel-path (relativize-path root-path file-path)]
    (log/log! {:level :info
               :id ::scan-file
               :msg "Scanning single file"
               :data {:file rel-path
                      :project project-name}})
    (when-let [analysis (run-kondo-file-analysis file-path)]
              ;; Normalize :filename from absolute to relative paths
              ;; (clj-kondo preserves the path format it receives)
              (let [rel-fn (fn [m]
                             (if (:filename m)
                               (update m :filename
                                       #(relativize-path root-path %))
                               m))
                    var-defs (mapv rel-fn (:var-definitions analysis))
                    var-usages (mapv rel-fn (:var-usages analysis))
                    ns-defs (mapv rel-fn (:namespace-definitions analysis))
                    ns-usages (:namespace-usages analysis)
                    ns-files (compute-ns-files var-defs)
                    affected-ns-names (into #{} (map #(str (:name %))) ns-defs)
            ;; Build namespace entities
                    namespaces (for [ns-def ns-defs
                                     :let [ns-name (str (:name ns-def))
                                           files (get ns-files ns-name
                                                      [(:filename ns-def)])]]
                                    (merge
                                     (proto/kondo-ns->namespace-map ns-def uri-base files)
                                     {:uri/source :dir
                                      :uri/project project-name
                                      :uri/version version
                                      :uri/version-type :static
                                      :uri/parent [:uri/string uri-base]}))
            ;; Build alias entities
                    aliases (for [ns-def ns-defs
                                  :let [ns-name (str (:name ns-def))
                                        ns-uri (str uri-base "/" ns-name)]
                                  alias-map (proto/extract-aliases-from-usages
                                             ns-usages ns-name)]
                                 {:uri/string (str ns-uri "#alias:" (:alias alias-map))
                                  :uri/source :dir
                                  :uri/project project-name
                                  :uri/version version
                                  :uri/version-type :static
                                  :alias/from-ns ns-name
                                  :alias/name (:alias alias-map)
                                  :alias/to-ns (:ns alias-map)})
            ;; Build refer entities
                    refers (for [ns-def ns-defs
                                 :let [ns-name (str (:name ns-def))
                                       ns-uri (str uri-base "/" ns-name)]
                                 refer-map (proto/extract-refers-from-usages
                                            ns-usages ns-name)]
                                {:uri/string (str ns-uri "#refer:" (:sym refer-map))
                                 :uri/source :dir
                                 :uri/project project-name
                                 :uri/version version
                                 :uri/version-type :static
                                 :refer/from-ns ns-name
                                 :refer/symbol (:sym refer-map)
                                 :refer/from-ns-source (:from-ns refer-map)})
            ;; Build symbol entities from var-definitions
                    symbols-from-vars (for [var-def var-defs]
                                           (merge
                                            (proto/kondo-var->symbol-map var-def uri-base)
                                            {:uri/source :dir
                                             :uri/project project-name
                                             :uri/version version
                                             :uri/version-type :static
                                             :uri/parent
                                             [:uri/string
                                              (str uri-base "/" (:ns var-def))]}))
            ;; Extract defmethod symbols from var-usages
                    defmethods (->> var-usages
                                    (filter :defmethod)
                                    (map (fn [usage]
                                           (merge
                                            (proto/kondo-defmethod->symbol-map
                                             usage uri-base)
                                            {:uri/source :dir
                                             :uri/project project-name
                                             :uri/version version
                                             :uri/version-type :static}))))
                    all-symbols (concat symbols-from-vars defmethods)
            ;; Build cache entries for new symbols
                    new-cache (into {}
                                    (map (fn [sym]
                                           [(:uri/string sym)
                                            {:file (:symbol/file sym)
                                             :line (:symbol/line sym)
                                             :end-line (:symbol/end-line sym)}])
                                         all-symbols))]
        ;; Update symbol cache selectively:
        ;; remove old entries whose :file matches rel-path, add new ones
                (swap! symbol-cache
                       (fn [cache]
                         (let [pruned (into {}
                                            (remove (fn [[_uri info]]
                                                      (= (:file info) rel-path))
                                                    cache))]
                           (merge pruned new-cache))))
                (log/log! {:level :info
                           :id ::scan-file-complete
                           :msg "Single file scan complete"
                           :data {:file rel-path
                                  :project project-name
                                  :namespace-count (count namespaces)
                                  :symbol-count (count all-symbols)
                                  :alias-count (count aliases)
                                  :refer-count (count refers)
                                  :affected-ns-names affected-ns-names}})
                {:namespaces (vec namespaces)
                 :symbols (vec all-symbols)
                 :aliases (vec aliases)
                 :refers (vec refers)
                 :affected-ns-names affected-ns-names}))))
