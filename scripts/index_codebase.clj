(ns index-codebase
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [edamame.core :as e]))

;; --- Purity Heuristics ---
(def impure-symbols
  #{'reset! 'swap! 'vreset! 'vswap!
    'println 'print 'prn 'pr-str
    'slurp 'spit
    'future 'thread
    'jdbc/execute! 'jdbc/execute-one!
    'http/get 'http/post 'http/request
    'p/process 'p/shell})

(defn check-purity [form]
  (let [found-impure (atom false)]
    (walk/prewalk
      (fn [x]
        (when (and (symbol? x) (contains? impure-symbols x))
          (reset! found-impure true))
        x)
      form)
    (if @found-impure :impure :pure)))

;; --- Source Extraction ---
(defn get-lines [s]
  (str/split s #"\r?\n" -1))

(defn extract-source [lines {:keys [row end-row]}]
  (when (and row end-row)
    (let [start-idx (dec row)
          end-idx   end-row
          start-idx (max 0 start-idx)
          end-idx   (min (count lines) end-idx)]
      (str/join "\n" (subvec (vec lines) start-idx end-idx)))))

;; --- Parsing ---
(defn analyze-file [file-path]
  (let [content (slurp (str file-path))
        lines   (get-lines content)
        forms   (try
                  (e/parse-string-all content
                                      {:auto-resolve identity
                                       :features #{:clj}
                                       :regex true
                                       :deref true
                                       :quote true
                                       :fn true
                                       :set true
                                       :read-eval true})
                  (catch Exception e
                    (println "Error parsing" file-path ":" (.getMessage e))
                    []))]
    (println (str "\n=== Analyzing: " file-path " ==="))
    (doseq [form forms]
      (let [m (meta form)]
        (when (and (seq? form)
                   (symbol? (first form))
                   (#{'defn 'def 'defmacro 'ns} (first form)))
          (let [type (first form)
                name (second form)
                purity (if (= type 'defn) (check-purity form) :n/a)
                source (extract-source lines m)
                doc (when (string? (nth form 2 nil)) (nth form 2))]
            (println "------------------------------------------------")
            (println "ID:      " name)
            (println "Type:    " type)
            (println "Line:    " (:row m))
            (println "Purity:  " purity)
            (when doc
              (println "Doc:     " (subs doc 0 (min 50 (count doc))) "..."))
            (when source
               (println "Source:  " (subs source 0 (min 60 (count source))) "..."))
            ))))))

(defn -main []
  (println "Starting prototype indexer...")
  ;; Look in both src and modules
  (let [files (concat (fs/glob "src" "**/*.clj") 
                      (fs/glob "modules" "**/*.clj"))]
    (println "Found" (count files) "Clojure files.")
    (doseq [file (take 3 files)] ;; Analyze first 3 files
      (analyze-file file))))

(-main)