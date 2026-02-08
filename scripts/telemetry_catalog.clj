#!/usr/bin/env bb
;; Telemetry Catalog Generator
;;
;; Scans all .clj, .cljs, .cljc, and .bb files for trove/log! and log/log! calls, extracts structured
;; metadata (level, event-id, msg, file, line, enclosing function), and outputs
;; a queryable catalog as EDN.
;;
;; Usage:
;;   bb telemetry:catalog                   # generate catalog to stdout
;;   bb telemetry:catalog --save            # save to telemetry-catalog.edn
;;   bb telemetry:catalog --report          # summary by level/namespace
;;   bb telemetry:catalog --level debug     # list all debug-level points
;;   bb telemetry:catalog --ns sente        # filter by namespace prefix
;;   bb telemetry:catalog --diff            # compare with last saved catalog

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.pprint :as pp])

;; =============================================================================
;; Kondo-based extraction
;; =============================================================================

(def ^:private source-dirs
     "Directories to scan for telemetry calls."
     ["src" "modules"])

(def ^:private clojure-extensions
     "File extensions to scan for telemetry calls."
     #{".clj" ".cljs" ".cljc" ".bb"})

(defn- find-clj-files
  "Find all Clojure source files (.clj, .cljs, .cljc, .bb) in the given directories."
  [dirs]
  (->> dirs
       (mapcat (fn [dir]
                 (let [f (io/file dir)]
                   (when (.exists f)
                     (->> (file-seq f)
                          (filter #(.isFile %))
                          (filter #(some (fn [ext] (str/ends-with? (.getName %) ext))
                                         clojure-extensions))
                          (map #(.getPath %)))))))
       sort
       vec))

;; =============================================================================
;; Regex-based log! extraction
;; =============================================================================

(defn- extract-log-calls-from-file
  "Extract log! call metadata from a single file using regex + simple parsing."
  [file-path]
  (let [content (slurp file-path)
        lines (str/split-lines content)
        ;; Find the namespace
        ns-match (re-find #"\(ns\s+([\w.\-]+)" content)
        file-ns (when ns-match (second ns-match))]
    (->> (map-indexed
          (fn [idx line]
            (when (re-find #"log!" line)
              (let [line-num (inc idx)
                    ;; Look at this line and the next several lines to find the map
                    context (str/join "\n" (subvec (vec lines) idx (min (+ idx 10) (count lines))))
                    ;; Extract :level
                    level-match (re-find #":level\s+:(\w+)" context)
                    level (when level-match (keyword (second level-match)))
                    ;; Extract :id
                    id-match (re-find #":id\s+:?:?([\w.*/\-]+)" context)
                    event-id (when id-match (second id-match))
                    ;; Extract :msg (string literal or str expression)
                    msg-match (re-find #":msg\s+\"([^\"]+)\"" context)
                    msg (when msg-match (second msg-match))
                    ;; Find enclosing defn by scanning upward
                    enclosing-fn (loop [i idx]
                                       (when (>= i 0)
                                         (let [l (nth lines i)]
                                           (if-let [m (re-find #"\(defn-?\s+([\w\-!?*<>]+)" l)]
                                                   (second m)
                                                   (recur (dec i))))))]
                (when level
                  {:telemetry/file file-path
                   :telemetry/line line-num
                   :telemetry/ns (or file-ns "unknown")
                   :telemetry/fn enclosing-fn
                   :telemetry/level level
                   :telemetry/event-id event-id
                   :telemetry/msg msg}))))
          lines)
         (remove nil?)
         vec)))

(defn- generate-catalog
  "Generate the full telemetry catalog from source files."
  []
  (let [files (find-clj-files source-dirs)]
    (->> files
         (mapcat extract-log-calls-from-file)
         (sort-by (juxt :telemetry/ns :telemetry/file :telemetry/line))
         vec)))

;; =============================================================================
;; Reporting
;; =============================================================================

(defn- print-report
  "Print a summary report of the catalog."
  [catalog]
  (let [by-level (->> catalog (map :telemetry/level) frequencies (sort-by val) reverse)
        by-ns (->> catalog
                   (map #(first (str/split (:telemetry/ns %) #"\.")))
                   frequencies
                   (sort-by val)
                   reverse
                   (take 20))
        total (count catalog)]
    (println (format "=== Telemetry Catalog: %d log points ===" total))
    (println)
    (println "By level:")
    (doseq [[level cnt] by-level]
           (println (format "  %-8s %4d  (%2.0f%%)" (name level) cnt (* 100.0 (/ cnt total)))))
    (println)
    (println "By top-level namespace:")
    (doseq [[ns cnt] by-ns]
           (println (format "  %-35s %4d" ns cnt)))
    (println)
    (println "By level + namespace:")
    (let [groups (->> catalog
                      (group-by (fn [e]
                                  [(first (str/split (:telemetry/ns e) #"\."))
                                   (:telemetry/level e)]))
                      (map (fn [[k v]] [(first k) (second k) (count v)]))
                      (sort-by (fn [[ns level _]] [ns (case level :trace 0 :debug 1 :info 2 :warn 3 :error 4 5)])))]
      (doseq [[ns level cnt] groups]
             (println (format "  %-35s %-8s %4d" ns (name level) cnt))))))

(defn- print-filtered
  "Print catalog entries matching filters."
  [catalog {:keys [level ns]}]
  (let [filtered (cond->> catalog
                          level (filter #(= (keyword level) (:telemetry/level %)))
                          ns (filter #(str/starts-with? (:telemetry/ns %) ns)))]
    (println (format "%-8s %-40s %-35s %s"
                     "LEVEL" "EVENT-ID" "MSG" "FILE:LINE"))
    (println (apply str (repeat 120 "-")))
    (doseq [e filtered]
           (println (format "%-8s %-40s %-35s %s:%d"
                            (name (:telemetry/level e))
                            (or (:telemetry/event-id e) "-")
                            (or (:telemetry/msg e) "-")
                            (:telemetry/file e)
                            (:telemetry/line e))))
    (println)
    (println (format "%d entries" (count filtered)))))

(defn- print-diff
  "Compare current catalog with last saved version."
  [catalog saved-path]
  (if (.exists (io/file saved-path))
    (let [saved (edn/read-string (slurp saved-path))
          saved-set (set (map #(select-keys % [:telemetry/file :telemetry/line :telemetry/event-id :telemetry/level]) saved))
          current-set (set (map #(select-keys % [:telemetry/file :telemetry/line :telemetry/event-id :telemetry/level]) catalog))
          added (set/difference current-set saved-set)
          removed (set/difference saved-set current-set)]
      (println (format "=== Diff: %d added, %d removed ===" (count added) (count removed)))
      (when (seq added)
        (println "\nAdded:")
        (doseq [e (sort-by :telemetry/file added)]
               (println (format "  + %-8s %-45s %s:%d"
                                (name (:telemetry/level e))
                                (or (:telemetry/event-id e) "-")
                                (:telemetry/file e)
                                (:telemetry/line e)))))
      (when (seq removed)
        (println "\nRemoved:")
        (doseq [e (sort-by :telemetry/file removed)]
               (println (format "  - %-8s %-45s %s:%d"
                                (name (:telemetry/level e))
                                (or (:telemetry/event-id e) "-")
                                (:telemetry/file e)
                                (:telemetry/line e)))))
      (when (and (empty? added) (empty? removed))
        (println "No changes.")))
    (println "No saved catalog found at" saved-path ". Run with --save first.")))

;; =============================================================================
;; CLI
;; =============================================================================

(def ^:private catalog-path "telemetry-catalog.edn")

(defn- parse-args
  "Parse CLI arguments."
  [args]
  (loop [args args
         opts {}]
        (if (empty? args)
          opts
          (case (first args)
            "--save" (recur (rest args) (assoc opts :save true))
            "--report" (recur (rest args) (assoc opts :report true))
            "--diff" (recur (rest args) (assoc opts :diff true))
            "--level" (recur (drop 2 args) (assoc opts :level (second args)))
            "--ns" (recur (drop 2 args) (assoc opts :ns (second args)))
            "--pprint" (recur (rest args) (assoc opts :pprint true))
            "--help" (recur (rest args) (assoc opts :help true))
        ;; Unknown arg
            (do (println "Unknown argument:" (first args))
                (recur (rest args) opts))))))

(defn- print-help
  "Print usage help."
  []
  (println "Usage: bb telemetry:catalog [OPTIONS]")
  (println)
  (println "Options:")
  (println "  --save       Save catalog to telemetry-catalog.edn")
  (println "  --report     Print summary report (level/namespace breakdown)")
  (println "  --level LVL  Filter by level (trace, debug, info, warn, error)")
  (println "  --ns PREFIX  Filter by namespace prefix")
  (println "  --diff       Compare with last saved catalog")
  (println "  --pprint     Pretty-print full catalog as EDN")
  (println "  --help       Show this help"))

(let [opts (parse-args *command-line-args*)
      catalog (generate-catalog)]
  (cond
    (:help opts)
    (print-help)

    (:report opts)
    (print-report catalog)

    (:diff opts)
    (print-diff catalog catalog-path)

    (:save opts)
    (do (spit catalog-path (with-out-str (pp/pprint catalog)))
        (println (format "Saved %d telemetry points to %s" (count catalog) catalog-path)))

    (:pprint opts)
    (pp/pprint catalog)

    (or (:level opts) (:ns opts))
    (print-filtered catalog opts)

    :else
    (print-report catalog)))
