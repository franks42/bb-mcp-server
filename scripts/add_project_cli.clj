#!/usr/bin/env bb

(ns add-project-cli
    "CLI for adding a project directory to a running Code Browser v2 server.

   Usage: bb add-project <path> [-t <nickname>]

   If -t is omitted, auto-discovers the target when exactly one server is running.

   Examples:
     bb add-project /Users/frank/Development/hasch
     bb add-project /Users/frank/Development/hasch -t cb-v2-test
     bb add-project . -t cb-v2-test
     bb add-project -h"
    (:require [bb-mcp-server.nrepl-direct.client :as client]
              [cheshire.core :as json]
              [clojure.edn :as edn]
              [clojure.string :as str]))

;; =============================================================================
;; Port Discovery
;; =============================================================================

(defn- discover-port
  "Discover nrepl-server port from .ports/<nickname>.json file."
  [nickname]
  (let [port-file (str ".ports/" nickname ".json")]
    (when (.exists (java.io.File. port-file))
      (let [data (json/parse-string (slurp port-file) true)
            ports (:ports data)]
        (get ports :nrepl-server)))))

(defn- list-running-servers
  "List running server nicknames from .ports/*.json files."
  []
  (let [dir (java.io.File. ".ports")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".json"))
           (map #(str/replace (.getName %) ".json" ""))
           sort
           vec))))

(defn- auto-discover-target
  "Auto-discover target when none specified.
   Returns nickname if exactly one server running, else prints error and exits."
  []
  (let [servers (list-running-servers)]
    (case (count servers)
      0 (do (binding [*out* *err*]
                     (println "Error: No running servers found in .ports/"))
            (System/exit 1))
      1 (first servers)
      (do (binding [*out* *err*]
                   (println "Error: Multiple servers running. Specify one with -t:")
                   (doseq [s servers]
                          (println (str "  " s))))
          (System/exit 1)))))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:target nil :path nil}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (or (= arg "-t") (= arg "--target"))
              (recur (rest rest-args) (assoc opts :target (first rest-args)))

              (or (= arg "-h") (= arg "--help"))
              (assoc opts :help true)

              ;; Positional argument = path
              (nil? (:path opts))
              (recur rest-args (assoc opts :path arg))

              :else
              (recur rest-args opts))))))

(defn- print-help
  "Print usage help."
  []
  (println "Usage: bb add-project <path> [-t <nickname>]")
  (println)
  (println "Add a project directory to a running Code Browser v2 server.")
  (println "The directory must contain deps.edn, bb.edn, project.clj, or shadow-cljs.edn.")
  (println)
  (println "If -t is omitted, auto-discovers the target when exactly one server is running.")
  (println)
  (println "Options:")
  (println "  -t, --target NICKNAME   Server nickname (auto-detected if one server running)")
  (println "  -h, --help              Show this help")
  (println)
  (println "Examples:")
  (println "  bb add-project /Users/frank/Development/hasch")
  (println "  bb add-project /Users/frank/Development/hasch -t cb-v2-test")
  (println "  bb add-project . -t cb-v2-test"))

;; =============================================================================
;; Main
;; =============================================================================

(defn- resolve-path
  "Resolve a path to an absolute path."
  [path]
  (str (.toAbsolutePath (.toPath (java.io.File. path)))))

(defn- run
  "Execute the add-project command."
  [{:keys [target path]}]
  (let [target (or target (auto-discover-target))]
    (when (str/blank? path)
      (binding [*out* *err*]
               (println "Error: project path required"))
      (print-help)
      (System/exit 1))

    (let [abs-path (resolve-path path)
          port (discover-port target)]
      (when-not port
        (binding [*out* *err*]
                 (println (str "Error: Cannot find nrepl-server port for '" target "'"))
                 (println "Is the server running? Check: bb server:list"))
        (System/exit 1))

      (println (str "Adding project: " abs-path))
      (println (str "Server: " target " (nREPL port " port ")"))

      (try
       (let [code (str "(code-browser.core/add-source! {:path " (pr-str abs-path) "})")
             result (client/eval! code
                                  :host "localhost"
                                  :port port
                                  :timeout-ms 30000)]
         (if (not= "success" (:status result))
           (do
            (binding [*out* *err*]
                     (println "Error:" (or (:error result) "Unknown error"))
                     (when (:err result)
                       (println (:err result))))
            (System/exit 1))
           (let [value (edn/read-string (or (:value result) "nil"))]
             (if (:success value)
               (do
                (println (str "Added: " (:project-name value)))
                (println (str "Stats: " (:stats value))))
               (do
                (binding [*out* *err*]
                         (println (str "Failed: " (:error value))))
                (System/exit 1))))))
       (catch Exception e
              (binding [*out* *err*]
                       (println (str "Error: " (.getMessage e))))
              (System/exit 1))))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(let [opts (parse-args *command-line-args*)]
  (if (:help opts)
    (print-help)
    (run opts)))
