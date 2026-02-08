#!/usr/bin/env bb

(ns logs-cli
    "CLI for querying telemetry logs from a running bb-mcp-server.

   Connects via nrepl-direct to eval telemetry-db.core/query on the server.

   Usage: bb logs -t <nickname> [options]

   Options:
     -t, --target NICKNAME   Server nickname (required)
     --level LEVEL            Filter by log level (info, error, warn, etc.)
     --ns PREFIX              Filter by namespace prefix
     --event EVENT            Filter by event-id substring
     --since TIME             Filter by time (e.g., 5m, 1h, 30s)
     --source SOURCE          Filter by source prefix (server, browser, browser:browser-1)
     --limit N                Max entries (default 50)
     --dump PATH              Dump full DB to EDN file on server
     --query DATALOG          Raw Datalog query string
     --pprint                 Pretty-print output
     -h, --help               Show this help"
    (:require [bb-mcp-server.nrepl-direct.client :as client]
              [cheshire.core :as json]
              [clojure.edn :as edn]
              [clojure.pprint :as pp]
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

;; =============================================================================
;; Time Parsing
;; =============================================================================

(defn- parse-since
  "Parse a relative time string into epoch millis.
   Supports: 30s, 5m, 1h, 2d"
  [s]
  (let [now (System/currentTimeMillis)]
    (when-let [[_ num unit] (re-matches #"(\d+)(s|m|h|d)" s)]
              (let [n (Long/parseLong num)
                    ms (case unit
                         "s" (* n 1000)
                         "m" (* n 60 1000)
                         "h" (* n 3600 1000)
                         "d" (* n 86400 1000))]
                (- now ms)))))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:target nil
               :level nil
               :ns nil
               :event nil
               :since nil
               :source nil
               :limit 50
               :dump nil
               :query nil
               :pprint false}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (or (= arg "-t") (= arg "--target"))
              (recur (rest rest-args) (assoc opts :target (first rest-args)))

              (= arg "--level")
              (recur (rest rest-args) (assoc opts :level (first rest-args)))

              (= arg "--ns")
              (recur (rest rest-args) (assoc opts :ns (first rest-args)))

              (= arg "--event")
              (recur (rest rest-args) (assoc opts :event (first rest-args)))

              (= arg "--since")
              (recur (rest rest-args) (assoc opts :since (first rest-args)))

              (= arg "--source")
              (recur (rest rest-args) (assoc opts :source (first rest-args)))

              (= arg "--limit")
              (recur (rest rest-args) (assoc opts :limit (Integer/parseInt (first rest-args))))

              (= arg "--dump")
              (recur (rest rest-args) (assoc opts :dump (first rest-args)))

              (= arg "--query")
              (recur (rest rest-args) (assoc opts :query (first rest-args)))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              (or (= arg "-h") (= arg "--help"))
              (assoc opts :help true)

              :else
              (recur rest-args opts))))))

;; =============================================================================
;; Help
;; =============================================================================

(defn- print-help
  "Print usage help."
  []
  (println "Usage: bb logs -t <nickname> [options]")
  (println)
  (println "Query telemetry logs from a running bb-mcp-server.")
  (println)
  (println "Options:")
  (println "  -t, --target NICKNAME   Server nickname (required)")
  (println "  --level LEVEL           Filter by log level (info, error, warn, debug)")
  (println "  --ns PREFIX             Filter by namespace prefix")
  (println "  --event EVENT           Filter by event-id substring")
  (println "  --since TIME            Filter by time (e.g., 5m, 1h, 30s, 2d)")
  (println "  --source SOURCE         Filter by source prefix (server, browser)")
  (println "  --limit N               Max entries to return (default 50)")
  (println "  --dump PATH             Dump full DB to EDN file on server")
  (println "  --query DATALOG         Raw Datalog query string")
  (println "  --pprint                Pretty-print output")
  (println "  -h, --help              Show this help")
  (println)
  (println "Examples:")
  (println "  bb logs -t cb-v2-test")
  (println "  bb logs -t cb-v2-test --level error")
  (println "  bb logs -t cb-v2-test --ns code-browser --since 5m")
  (println "  bb logs -t cb-v2-test --source browser")
  (println "  bb logs -t cb-v2-test --dump /tmp/logs.edn"))

;; =============================================================================
;; Code Generation
;; =============================================================================

(defn- build-query-code
  "Build Clojure code string to eval on server."
  [{:keys [level ns event since source limit]}]
  (let [since-ms (when since (parse-since since))
        opts (cond-> {}
                     level (assoc :level (keyword level))
                     ns (assoc :ns ns)
                     event (assoc :event-id event)
                     since-ms (assoc :since since-ms)
                     source (assoc :source source)
                     limit (assoc :limit limit))]
    (str "(telemetry-db.core/query " (pr-str opts) ")")))

(defn- build-dump-code
  "Build code to dump DB to file."
  [path]
  (str "(telemetry-db.core/dump! " (pr-str path) ")"))

(defn- build-raw-query-code
  "Build code for a raw filter expression on the log store."
  [query-str]
  (str "(let [store @(telemetry-db.core/get-store)]"
       "  (filterv (fn [e] " query-str ") store))"))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn- format-timestamp
  "Format epoch millis to human-readable time."
  [ts]
  (when ts
    (let [inst (java.time.Instant/ofEpochMilli ts)
          zdt (.atZone inst (java.time.ZoneId/systemDefault))
          fmt (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")]
      (.format zdt fmt))))

(defn- format-entry
  "Format a single log entry as a table row."
  [entry]
  (let [ts (format-timestamp (:log/timestamp entry))
        level (str/upper-case (or (:log/level entry) "?"))
        ns-str (or (:log/ns entry) "")
        msg (or (:log/msg entry) "")
        ;; Truncate long messages
        msg (if (> (count msg) 80) (str (subs msg 0 77) "...") msg)]
    (format "%-12s %-5s %-30s %s" ts level ns-str msg)))

(defn- print-table
  "Print log entries as a formatted table."
  [entries]
  (println (format "%-12s %-5s %-30s %s" "TIME" "LEVEL" "NAMESPACE" "MESSAGE"))
  (println (str/join "" (repeat 80 "-")))
  (doseq [entry entries]
         (println (format-entry entry)))
  (println)
  (println (str (count entries) " entries")))

;; =============================================================================
;; Main
;; =============================================================================

(defn- run
  "Execute the logs query."
  [{:keys [target dump query pprint] :as opts}]
  (when-not target
    (binding [*out* *err*]
             (println "Error: --target / -t required"))
    (System/exit 1))

  (let [port (discover-port target)]
    (when-not port
      (binding [*out* *err*]
               (println (str "Error: Cannot find nrepl-server port for '" target "'"))
               (println "Is the server running? Check: bb server:list"))
      (System/exit 1))

    (try
     (let [code (cond
                  dump (build-dump-code dump)
                  query (build-raw-query-code query)
                  :else (build-query-code opts))
           result (client/eval! code
                                :host "localhost"
                                :port port
                                :timeout-ms 10000)]
       (if (not= "success" (:status result))
         (do
          (binding [*out* *err*]
                   (println "Error:" (or (:error result) "Unknown error"))
                   (when (:err result)
                     (println (:err result))))
          (System/exit 1))
         (cond
           dump
           (println (str "DB dumped to: " (:value result)))

           query
           (let [value (edn/read-string (or (:value result) "nil"))]
             (if pprint
               (pp/pprint value)
               (prn value)))

           :else
           (let [entries (edn/read-string (or (:value result) "[]"))]
             (if pprint
               (pp/pprint entries)
               (print-table entries))))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Connection error:" (ex-message e)))
            (System/exit 1)))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(let [opts (parse-args *command-line-args*)]
  (if (:help opts)
    (print-help)
    (run opts)))
