(ns directory-browser.core
    "Directory browser with project and IDE workspace detection.

   Provides safe directory listing with property detection:
   - Git repositories
   - Clojure projects (deps.edn, bb.edn, project.clj, shadow-cljs.edn)
   - IDE workspaces (VSCode, Cursor, Windsurf)
   - Node/Python projects
   - Permission checking"
    (:require [babashka.fs :as fs]
              [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private default-config
     {:show-hidden false
      :follow-symlinks false
      :max-entries 1000})

(def ^:private !config (atom default-config))

(defn configure!
  "Set module configuration."
  [config]
  (swap! !config merge config))

;;; ---------------------------------------------------------------------------
;;; Path utilities
;;; ---------------------------------------------------------------------------

(defn expand-path
  "Expand ~ and environment variables in path."
  [path]
  (when path
    (-> path
        (str/replace #"^~" (System/getProperty "user.home"))
        (str/replace #"\$(\w+)" #(or (System/getenv (second %)) (first %)))
        fs/normalize
        str)))

(defn home-directory
  "Get user's home directory."
  []
  (System/getProperty "user.home"))

(defn can-read?
  "Check if path is readable."
  [path]
  (try
   (fs/readable? path)
   (catch Exception _ false)))

(defn can-write?
  "Check if path is writable."
  [path]
  (try
   (fs/writable? path)
   (catch Exception _ false)))

;;; ---------------------------------------------------------------------------
;;; Project/Workspace Detection
;;; ---------------------------------------------------------------------------

(def ^:private project-markers
     "Files/directories that indicate project types."
     {:clojure-project #{"deps.edn" "bb.edn" "project.clj" "shadow-cljs.edn"}
      :node-project #{"package.json"}
      :python-project #{"pyproject.toml" "setup.py" "requirements.txt"}
      :rust-project #{"Cargo.toml"}
      :go-project #{"go.mod"}
      :java-project #{"pom.xml" "build.gradle" "build.gradle.kts"}})

(def ^:private workspace-markers
     "Directories that indicate IDE workspaces."
     {:git ".git"
      :vscode ".vscode"
      :cursor ".cursor"
      :windsurf ".windsurf"
      :idea ".idea"
      :eclipse ".project"})

(defn- detect-project-type
  "Detect project type from directory contents."
  [_dir-path entries]
  (let [entry-names (set (map :name entries))]
    (reduce-kv
     (fn [acc project-type markers]
       (if (some entry-names markers)
         (conj acc project-type)
         acc))
     #{}
     project-markers)))

(defn- detect-workspaces
  "Detect IDE workspaces from directory contents."
  [_dir-path entries]
  (let [entry-names (set (map :name entries))]
    (reduce-kv
     (fn [acc workspace-type marker]
       (if (entry-names marker)
         (conj acc workspace-type)
         acc))
     #{}
     workspace-markers)))

(defn get-directory-properties
  "Get all detected properties for a directory.
   Returns set of keywords like #{:git :clojure-project :vscode}"
  [path]
  (when (and (can-read? path) (fs/directory? path))
    (let [children (try
                    (->> (fs/list-dir path)
                         (map #(hash-map :name (fs/file-name %)))
                         vec)
                    (catch Exception _ []))]
      (into (detect-project-type path children)
            (detect-workspaces path children)))))

;;; ---------------------------------------------------------------------------
;;; Directory Listing
;;; ---------------------------------------------------------------------------

(defn- entry-type
  "Determine entry type: :directory, :file, or :symlink"
  [path]
  (cond
    (fs/sym-link? path) :symlink
    (fs/directory? path) :directory
    :else :file))

(defn- make-entry
  "Create an entry map for a filesystem path."
  [path]
  (let [name (fs/file-name path)
        hidden? (str/starts-with? name ".")
        readable? (can-read? path)
        etype (entry-type path)]
    {:name name
     :path (str path)
     :type etype
     :hidden? hidden?
     :readable? readable?
     :writable? (can-write? path)
     :size (when (and readable? (= etype :file))
             (try (fs/size path) (catch Exception _ nil)))
     :modified (when readable?
                 (try
                  (str (fs/last-modified-time path))
                  (catch Exception _ nil)))}))

(defn- enrich-directory-entry
  "Add properties to a directory entry."
  [entry]
  (if (= :directory (:type entry))
    (assoc entry :properties (get-directory-properties (:path entry)))
    entry))

(defn list-directory
  "List contents of a directory with metadata.

   Options:
   - :show-hidden - include hidden files (default: false)
   - :enrich - detect properties for subdirectories (default: true)

   Returns:
   {:path \"/full/path\"
    :parent \"/parent/path\"
    :entries [{:name \"foo\" :type :directory :properties #{:git}} ...]
    :properties #{:clojure-project :vscode}
    :error nil}"
  ([path] (list-directory path {}))
  ([path opts]
   (let [expanded (expand-path path)
         show-hidden (get opts :show-hidden (:show-hidden @!config))
         enrich (get opts :enrich true)
         max-entries (get opts :max-entries (:max-entries @!config))]
     (cond
       ;; Path doesn't exist
       (not (fs/exists? expanded))
       {:path expanded
        :parent nil
        :entries []
        :properties #{}
        :error {:type :not-found :message (str "Path not found: " expanded)}}

       ;; Not a directory
       (not (fs/directory? expanded))
       {:path expanded
        :parent (str (fs/parent expanded))
        :entries []
        :properties #{}
        :error {:type :not-directory :message (str "Not a directory: " expanded)}}

       ;; Not readable
       (not (can-read? expanded))
       {:path expanded
        :parent (str (fs/parent expanded))
        :entries []
        :properties #{}
        :error {:type :permission-denied :message (str "Cannot read: " expanded)}}

       ;; Success - list contents
       :else
       (try
        (let [raw-entries (->> (fs/list-dir expanded)
                               (take max-entries)
                               (map make-entry))
              filtered (if show-hidden
                         raw-entries
                         (remove :hidden? raw-entries))
              sorted (sort-by (juxt #(if (= :directory (:type %)) 0 1)
                                    :name)
                              filtered)
              enriched (if enrich
                         (map enrich-directory-entry sorted)
                         sorted)]
          {:path expanded
           :parent (when-let [p (fs/parent expanded)]
                             (str p))
           :entries (vec enriched)
           :properties (get-directory-properties expanded)
           :error nil})
        (catch Exception e
               {:path expanded
                :parent (str (fs/parent expanded))
                :entries []
                :properties #{}
                :error {:type :exception :message (.getMessage e)}}))))))

;;; ---------------------------------------------------------------------------
;;; Convenience functions
;;; ---------------------------------------------------------------------------

(defn list-home
  "List user's home directory."
  [& [opts]]
  (list-directory (home-directory) opts))

(defn is-project-root?
  "Check if directory is a project root (has project markers)."
  [path]
  (let [props (get-directory-properties (expand-path path))]
    (boolean (seq (disj props :git :vscode :cursor :windsurf :idea :eclipse)))))

(defn find-projects-in
  "Find all project directories within a path (non-recursive, one level)."
  [path]
  (let [{:keys [entries error]} (list-directory path {:enrich true})]
    (when-not error
      (->> entries
           (filter #(= :directory (:type %)))
           (filter #(seq (disj (:properties %) :git :vscode :cursor :windsurf :idea :eclipse)))
           (map #(select-keys % [:name :path :properties]))
           vec))))

(defn breadcrumbs
  "Generate breadcrumb path segments for navigation.
   Returns vector of {:name \"dir\" :path \"/full/path\"}"
  [path]
  (let [expanded (expand-path path)
        home (home-directory)
        parts (fs/components expanded)]
    (loop [acc []
           remaining parts
           current-path ""]
          (if (empty? remaining)
            acc
            (let [part (str (first remaining))
                  new-path (if (empty? current-path)
                             (str "/" part)
                             (str current-path "/" part))
                  display-name (if (= new-path home) "~" part)]
              (recur (conj acc {:name display-name :path new-path})
                     (rest remaining)
                     new-path))))))

;;; ---------------------------------------------------------------------------
;;; Module lifecycle
;;; ---------------------------------------------------------------------------

(defn start
  "Start the directory-browser module."
  [_deps config]
  (configure! config)
  {:status :started})

(defn stop
  "Stop the directory-browser module."
  []
  (reset! !config default-config)
  {:status :stopped})

(defn status
  "Get directory-browser module status."
  []
  {:status :running
   :config @!config})

;; Module export
(def module
     "Directory browser module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
