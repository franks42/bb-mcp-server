(ns expert-registry.curriculum
    "Curriculum loading and management for domain experts.

   Curriculum files are organized in expert directories:
     .experts/clojure-coder/
       ├── manifest.edn
       ├── essential/          # Must-read files (loaded every time)
       │   ├── standards.md
       │   └── telemetry.md
       └── reference/          # Optional reference docs
           └── architecture.md

   Essential files are loaded in order and concatenated into a single
   context string that's sent to the Claude instance on startup."
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; Curriculum Discovery
;; =============================================================================

(defn discover-curriculum-files
  "Discover all curriculum files in an expert directory.

   Arguments:
     expert-dir - File object pointing to expert directory

   Returns:
     Map with :essential and :reference file paths.

   Example:
     {:essential [\"essential/standards.md\" \"essential/telemetry.md\"]
      :reference [\"reference/architecture.md\"]}"
  [expert-dir]
  (let [essential-dir (io/file expert-dir "essential")
        reference-dir (io/file expert-dir "reference")

        discover-files (fn [dir]
                         (when (.exists dir)
                           (->> (.listFiles dir)
                                (filter #(.isFile %))
                                (map #(.getPath %))
                                (sort)
                                vec)))]

    {:essential (or (discover-files essential-dir) [])
     :reference (or (discover-files reference-dir) [])}))

;; =============================================================================
;; Curriculum Loading
;; =============================================================================

(defn- load-curriculum-file
  "Load a single curriculum file.

   Arguments:
     file-path - Path to curriculum file

   Returns:
     File content as string, or nil on error."
  [file-path]
  (try
   (log/log! {:level :debug
              :id    ::loading-curriculum-file
              :msg   "Loading curriculum file"
              :data  {:path file-path}})
   (slurp file-path)
   (catch Exception e
          (log/log! {:level :error
                     :id    ::curriculum-file-load-failed
                     :msg   "Failed to load curriculum file"
                     :error e
                     :data  {:path file-path}})
          nil)))

(defn- format-curriculum-section
  "Format curriculum section with file header.

   Arguments:
     file-path - Path to the file
     content   - File content

   Returns:
     Formatted string with header and content."
  [file-path content]
  (let [filename (last (str/split file-path #"/"))]
    (str "# " filename "\n\n" content "\n\n")))

(defn load-essential-curriculum
  "Load all essential curriculum files for an expert.

   Essential files are loaded in alphabetical order and concatenated
   with headers between sections.

   Arguments:
     expert-def - Expert definition map

   Returns:
     Concatenated curriculum string, or nil if no files."
  [expert-def]
  (let [essential-files (get-in expert-def [:curriculum-paths :essential] [])]
    (when (seq essential-files)
      (log/log! {:level :info
                 :id    ::loading-essential-curriculum
                 :msg   "Loading essential curriculum"
                 :data  {:expert-id (:id expert-def)
                         :file-count (count essential-files)}})

      (->> essential-files
           (keep (fn [file-path]
                   (when-let [content (load-curriculum-file file-path)]
                             (format-curriculum-section file-path content))))
           (str/join "\n---\n\n")))))

(defn load-curriculum
  "Load complete curriculum for an expert.

   Currently loads only essential curriculum.
   Future: Could load reference on-demand or via separate commands.

   Arguments:
     expert-def - Expert definition map

   Returns:
     Curriculum content string or nil."
  [expert-def]
  (log/log! {:level :info
             :id    ::loading-curriculum
             :msg   "Loading curriculum"
             :data  {:expert-id (:id expert-def)}})

  (let [essential (load-essential-curriculum expert-def)
        intro (format "You are %s.\n\n%s\n\nThe following curriculum has been loaded for you:\n\n"
                      (:name expert-def "an AI expert")
                      (:description expert-def ""))]

    (when essential
      (str intro essential))))

;; =============================================================================
;; Curriculum Metadata
;; =============================================================================

(defn get-curriculum-stats
  "Get statistics about curriculum files.

   Arguments:
     expert-def - Expert definition map

   Returns:
     Map with :essential-count, :reference-count, :total-size."
  [expert-def]
  (let [essential-files (get-in expert-def [:curriculum-paths :essential] [])
        reference-files (get-in expert-def [:curriculum-paths :reference] [])

        file-size (fn [path]
                    (try
                     (.length (io/file path))
                     (catch Exception _ 0)))]

    {:essential-count (count essential-files)
     :reference-count (count reference-files)
     :total-size (+ (reduce + 0 (map file-size essential-files))
                    (reduce + 0 (map file-size reference-files)))}))
