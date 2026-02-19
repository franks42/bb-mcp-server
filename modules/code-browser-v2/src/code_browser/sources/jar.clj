(ns code-browser.sources.jar
    "JAR source adapter for Code Browser v2.

   Scans JAR dependency files using clj-kondo for:
   - Namespace discovery
   - Symbol extraction (functions, vars, macros, etc.)
   - Source code reading from ZIP entries

   Uses Maven artifact coordinates + version as URI identity so the DB
   acts as a shared cache: if two projects depend on the same jar@version,
   it's scanned once.

   URI format: jar://org.clojure.clojure@1.12.3/clojure.core/map

   Design decisions:
   - clj-kondo for analysis (same as directory source)
   - Static: no file watching needed
   - DB-as-cache: check DB before scanning
   - Source read from JAR ZIP entries (no temp extraction)"
    (:require [code-browser.sources.protocol :as proto]
              [code-browser.uri :as uri]
              [babashka.process :refer [shell]]
              [clojure.edn :as edn]
              [clojure.string :as str]
              [taoensso.trove :as log])
    (:import [java.util.jar JarFile]))

;;; ---------------------------------------------------------------------------
;;; JAR Introspection Helpers
;;; ---------------------------------------------------------------------------

(defn- ns->jar-entry-path
  "Convert a namespace name to a JAR entry base path (without extension).
   e.g., 'cheshire.core' -> 'cheshire/core'
   Caller must try .clj, .cljs, .cljc extensions."
  [ns-name]
  (-> ns-name
      (str/replace "." "/")
      (str/replace "-" "_")))

;;; ---------------------------------------------------------------------------
;;; clj-kondo JAR Analysis
;;; ---------------------------------------------------------------------------

(defn- run-kondo-jar-analysis
  "Run clj-kondo analysis on a JAR file.
   Returns clj-kondo analysis map or nil on failure."
  [jar-path]
  (log/log! {:level :info
             :id ::kondo-scanning-jar
             :msg "Analyzing JAR with clj-kondo"
             :data {:jar jar-path}})
  (try
   (let [result (shell {:out :string :err :string :continue true :timeout 120000}
                       "clj-kondo" "--lint" jar-path
                       "--config"
                       "{:output {:analysis {:var-definitions true :var-usages true :namespace-definitions true :namespace-usages true} :format :edn}}")
         output (:out result)]
     (when (seq output)
       (let [analysis (edn/read-string output)]
         (log/log! {:level :info
                    :id ::kondo-jar-complete
                    :msg "clj-kondo JAR analysis complete"
                    :data {:jar jar-path
                           :var-count (count (get-in analysis [:analysis :var-definitions]))
                           :ns-count (count (get-in analysis [:analysis :namespace-definitions]))}})
         (:analysis analysis))))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::kondo-jar-error
                     :msg "clj-kondo JAR analysis failed"
                     :data {:jar jar-path
                            :error (ex-message e)}})
          nil)))

;;; ---------------------------------------------------------------------------
;;; Source Reading from JAR
;;; ---------------------------------------------------------------------------

(defn- read-source-from-jar
  "Read source code from a JAR file without extracting.
   entry-path should be like 'cheshire/core.clj'.
   Returns the source code string or nil."
  [jar-path entry-path]
  (try
   (let [jar-file (JarFile. jar-path)
         entry (.getEntry jar-file entry-path)
         result (when entry
                  (slurp (.getInputStream jar-file entry)))]
     (.close jar-file)
     result)
   (catch Exception e
          (log/log! {:level :debug
                     :id ::jar-read-error
                     :msg "Failed to read from JAR"
                     :data {:jar jar-path :entry entry-path
                            :error (ex-message e)}})
          nil)))

(defn- extract-source-lines
  "Extract source lines from content string given line range.
   Line numbers are 1-indexed (from clj-kondo)."
  [content start-line end-line]
  (when content
    (let [lines (str/split-lines content)
          start-idx (max 0 (dec start-line))
          end-idx (if end-line
                    (min (dec (count lines)) (dec end-line))
                    start-idx)]
      (when (and (>= end-idx start-idx) (< start-idx (count lines)))
        (->> lines
             (drop start-idx)
             (take (inc (- end-idx start-idx)))
             (str/join "\n"))))))

(defn- find-jar-entry-path
  "Find the actual JAR entry path for a namespace, trying .cljc, .clj, .cljs extensions.
   Returns the entry path string or nil."
  [jar-path ns-name]
  (let [base-path (ns->jar-entry-path ns-name)
        extensions [".cljc" ".clj" ".cljs"]]
    (some (fn [ext]
            (let [path (str base-path ext)]
              (try
               (let [jar-file (JarFile. jar-path)
                     entry (.getEntry jar-file path)]
                 (.close jar-file)
                 (when entry path))
               (catch Exception _ nil))))
          extensions)))

;;; ---------------------------------------------------------------------------
;;; Namespace -> Files Computation
;;; ---------------------------------------------------------------------------

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
;;; Dependency Extraction
;;; ---------------------------------------------------------------------------

(defn- build-deps-tx
  "Build dependency transactions from var-usages (intra-jar deps only).
   Returns vector of [:db/add caller-uri :symbol/deps dep-ref] transactions."
  [var-usages uri-base all-symbol-uris]
  (let [uri-set (set all-symbol-uris)]
    (->> var-usages
         (keep (fn [usage]
                 (let [from-ns (str (:from usage))
                       from-var (str (:from-var usage))
                       to-ns (str (:to usage))
                       to-name (str (:name usage))
                       caller-uri (str uri-base "/" from-ns "/" from-var)
                       dep-uri (str uri-base "/" to-ns "/" to-name)]
                   (when (and (contains? uri-set caller-uri)
                              (contains? uri-set dep-uri)
                              (not= caller-uri dep-uri))
                     [:db/add [:uri/string caller-uri]
                      :symbol/deps [:uri/string dep-uri]]))))
         distinct
         vec)))

;;; ---------------------------------------------------------------------------
;;; Maven Coordinate Extraction
;;; ---------------------------------------------------------------------------

(def ^:private maven-path-pattern
     "Regex to extract Maven coordinates from a .m2 repository path.
   Group 1: group path (org/clojure), Group 2: artifact, Group 3: version."
     #".*/\.m2/repository/(.+)/([^/]+)/([^/]+)/[^/]+\.jar$")

(defn parse-maven-coordinates
  "Parse Maven coordinates from a JAR file path.
   Returns {:group \"org.clojure\" :artifact \"clojure\" :version \"1.12.3\"
            :project-name \"org.clojure.clojure\" :jar-path \"/path/to/jar\"}
   or nil if path doesn't match Maven repository layout."
  [jar-path]
  (when-let [match (re-matches maven-path-pattern jar-path)]
            (let [[_ group-path artifact version] match
                  group (str/replace group-path "/" ".")]
              {:group group
               :artifact artifact
               :version version
               :project-name (str group "." artifact)
               :jar-path jar-path})))

(defn- jar-path->project-identity
  "Extract project identity from a JAR path.
   Tries Maven coordinates first, falls back to filename + hash prefix."
  [jar-path]
  (or (parse-maven-coordinates jar-path)
      (let [filename (last (str/split jar-path #"/"))
            base (str/replace filename #"\.jar$" "")
            hash-prefix (subs (str (hash jar-path)) 0 8)]
        {:project-name base
         :version hash-prefix
         :jar-path jar-path})))

;;; ---------------------------------------------------------------------------
;;; JarSource Record
;;; ---------------------------------------------------------------------------

(defrecord JarSource [jar-path project-name version uri-base symbol-cache]
           proto/IProjectSource

           (scan-project [_this]
                         (log/log! {:level :info
                                    :id ::scanning-jar
                                    :msg "Scanning JAR project"
                                    :data {:jar-path jar-path
                                           :project-name project-name
                                           :version version}})
                         (when-let [analysis (run-kondo-jar-analysis jar-path)]
                                   (let [var-defs (:var-definitions analysis)
                                         var-usages (:var-usages analysis)
                                         ns-defs (:namespace-definitions analysis)
                                         ns-usages (:namespace-usages analysis)
                                         ns-files (compute-ns-files var-defs)
                                         ;; Build project entity
                                         project {:uri/string uri-base
                                                  :uri/source :jar
                                                  :uri/project project-name
                                                  :uri/version version
                                                  :uri/version-type :static
                                                  :project/jar-path jar-path}
                                         ;; Build namespace entities
                                         namespaces (for [ns-def ns-defs
                                                          :let [ns-name (str (:name ns-def))
                                                                files (get ns-files ns-name
                                                                           [(:filename ns-def)])]]
                                                         (merge
                                                          (proto/kondo-ns->namespace-map
                                                           ns-def uri-base files)
                                                          {:uri/source :jar
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
                                                      {:uri/string (str ns-uri "#alias:"
                                                                        (:alias alias-map))
                                                       :uri/source :jar
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
                                                     {:uri/string (str ns-uri "#refer:"
                                                                       (:sym refer-map))
                                                      :uri/source :jar
                                                      :uri/project project-name
                                                      :uri/version version
                                                      :uri/version-type :static
                                                      :refer/from-ns ns-name
                                                      :refer/symbol (:sym refer-map)
                                                      :refer/from-ns-source (:from-ns refer-map)})
                                         ;; Build symbol entities from var-definitions
                                         symbols (for [var-def var-defs]
                                                      (merge
                                                       (proto/kondo-var->symbol-map var-def uri-base)
                                                       {:uri/source :jar
                                                        :uri/project project-name
                                                        :uri/version version
                                                        :uri/version-type :static
                                                        :uri/parent
                                                        [:uri/string
                                                         (str uri-base "/" (:ns var-def))]}))
                                         ;; Extract defmethod symbols
                                         defmethods (->> var-usages
                                                         (filter :defmethod)
                                                         (map (fn [usage]
                                                                (merge
                                                                 (proto/kondo-defmethod->symbol-map
                                                                  usage uri-base)
                                                                 {:uri/source :jar
                                                                  :uri/project project-name
                                                                  :uri/version version
                                                                  :uri/version-type :static}))))
                                         all-symbols (concat symbols defmethods)
                                         ;; Populate symbol cache for fetch-source
                                         cache-entries
                                         (into {}
                                               (map (fn [sym]
                                                      [(:uri/string sym)
                                                       {:file (:symbol/file sym)
                                                        :ns (:uri/namespace sym)
                                                        :line (:symbol/line sym)
                                                        :end-line (:symbol/end-line sym)}])
                                                    all-symbols))
                                         ;; Build deps transactions
                                         all-symbol-uris (mapv :uri/string all-symbols)
                                         deps-tx (build-deps-tx var-usages uri-base
                                                                all-symbol-uris)]
                                     (reset! symbol-cache cache-entries)
                                     (log/log! {:level :info
                                                :id ::jar-scan-complete
                                                :msg "JAR scan complete"
                                                :data {:project-name project-name
                                                       :namespace-count (count namespaces)
                                                       :symbol-count (count all-symbols)
                                                       :alias-count (count aliases)
                                                       :refer-count (count refers)
                                                       :deps-tx-count (count deps-tx)
                                                       :cache-size (count cache-entries)}})
                                     {:project project
                                      :namespaces (vec namespaces)
                                      :symbols (vec all-symbols)
                                      :aliases (vec aliases)
                                      :refers (vec refers)
                                      :deps-tx deps-tx})))

           (fetch-source [_this uri-string]
                         (log/log! {:level :debug
                                    :id ::fetch-jar-source
                                    :msg "Fetching source from JAR"
                                    :data {:uri uri-string}})
                         (when-let [sym-info (get @symbol-cache uri-string)]
                                   (let [{:keys [ns line end-line]} sym-info]
                                     (when (and ns line)
                                       (when-let [entry-path (find-jar-entry-path
                                                              jar-path ns)]
                                                 (when-let [content (read-source-from-jar
                                                                     jar-path entry-path)]
                                                           (when-let [source-text
                                                                      (extract-source-lines
                                                                       content line end-line)]
                                                                     {:content source-text
                                                                      :file (str "jar:" jar-path
                                                                                 "!" entry-path)
                                                                      :start-line line
                                                                      :end-line (or end-line
                                                                                    line)})))))))

           (watch! [_ _] nil)

           (unwatch! [_ _] nil)

           (source-info [_]
                        {:type :jar
                         :version-type :static
                         :supports-watch? false
                         :description (str "JAR: " jar-path)}))

;;; ---------------------------------------------------------------------------
;;; Constructor
;;; ---------------------------------------------------------------------------

(defn create-jar-source
  "Create a JarSource for a JAR file.

   Arguments:
     jar-path - Absolute path to the JAR file

   Options:
     :project-name - Override project name
     :version - Override version

   Example:
     (create-jar-source \"/path/to/clojure-1.12.3.jar\")
     (create-jar-source \"/path/to/lib.jar\"
       {:project-name \"my.lib\" :version \"1.0.0\"})"
  ([jar-path]
   (create-jar-source jar-path {}))
  ([jar-path {:keys [project-name version]}]
   (let [identity (jar-path->project-identity jar-path)
         proj-name (or project-name (:project-name identity))
         ver (or version (:version identity))
         uri-base (uri/build {:source :jar :project proj-name :version ver})
         cache (atom {})]
     (log/log! {:level :info
                :id ::creating-jar-source
                :msg "Creating JAR source"
                :data {:jar-path jar-path
                       :project-name proj-name
                       :version ver
                       :uri-base uri-base}})
     (->JarSource jar-path proj-name ver uri-base cache))))

;;; ---------------------------------------------------------------------------
;;; Classpath Resolution
;;; ---------------------------------------------------------------------------

(defn resolve-project-jars
  "Resolve JAR dependencies for a project directory.

   Runs `clojure -Spath` in the project directory, filters for .jar files,
   and extracts Maven coordinates from each path.

   Arguments:
     project-root - Absolute path to project root (must have deps.edn)

   Returns vector of maps:
     [{:group \"org.clojure\" :artifact \"clojure\" :version \"1.12.3\"
       :project-name \"org.clojure.clojure\" :jar-path \"/path/to/jar\"}
      ...]

   Returns nil if classpath resolution fails."
  [project-root]
  (log/log! {:level :info
             :id ::resolving-classpath
             :msg "Resolving project classpath"
             :data {:project-root project-root}})
  (try
   (let [result (shell {:dir project-root
                        :out :string :err :string
                        :continue true :timeout 60000}
                       "clojure" "-Spath")
         cp (when (zero? (:exit result))
              (str/trim (:out result)))]
     (when (seq cp)
       (let [paths (str/split cp #":")
             jars (->> paths
                       (filter #(str/ends-with? % ".jar"))
                       (mapv jar-path->project-identity))]
         (log/log! {:level :info
                    :id ::classpath-resolved
                    :msg "Classpath resolved"
                    :data {:total-paths (count paths)
                           :jar-count (count jars)}})
         jars)))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::classpath-error
                     :msg "Failed to resolve classpath"
                     :data {:project-root project-root
                            :error (ex-message e)}})
          nil)))
