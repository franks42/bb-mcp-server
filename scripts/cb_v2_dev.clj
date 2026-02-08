#!/usr/bin/env bb
;; Code Browser v2 — Development Environment Manager
;;
;; Usage:
;;   bb dev:cb-v2 start           # Clean start (stop, clean DB, start, open browser)
;;   bb dev:cb-v2 start --no-open # Clean start without opening browser
;;   bb dev:cb-v2 stop            # Stop server
;;   bb dev:cb-v2 restart         # Restart (stop + clean DB + start)
;;   bb dev:cb-v2 status          # Show server and database status
;;   bb dev:cb-v2                 # Same as 'start'
;;
;; See also: docs/SCITTLE_DEV_ENVIRONMENT.md

(ns cb-v2-dev
    "Code Browser v2 development environment manager."
    (:require [babashka.process :as p]
              [cheshire.core :as json]
              [clojure.java.io :as io]
              [clojure.string :as str]))

(def ^:private nickname "cb-v2-test")
(def ^:private config "system-cb-v2-test.edn")
(def ^:private db-path "/tmp/cb-v2-test")
(def ^:private browser-url "http://localhost:8091")
(def ^:private ports-dir ".ports")
(def ^:private catalog-dir "telemetry-catalogs")

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- server-running?
  "Check if cb-v2-test server is currently running."
  []
  (try
   (let [result @(p/process {:cmd ["bb" "server:list"]
                             :out :string
                             :err :string})]
     (str/includes? (str (:out result)) nickname))
   (catch Exception _ false)))

(defn- read-port-file
  "Read port file for the cb-v2-test server. Returns map or nil."
  []
  (let [file (io/file ports-dir (str nickname ".json"))]
    (when (.exists file)
      (try
       (json/parse-string (slurp file) true)
       (catch Exception _ nil)))))

(defn- db-exists?
  "Check if the Datalevin database directory exists."
  []
  (.exists (io/file db-path)))

(defn- do-stop!
  "Stop cb-v2-test server if running. Returns true if was running."
  []
  (if (server-running?)
    (do
     @(p/process {:cmd ["bb" "server:stop" nickname]
                  :out :inherit
                  :err :inherit})
     (Thread/sleep 1000)
     (println (str "  Stopped " nickname "."))
     true)
    (do
     (println (str "  No existing " nickname " server found."))
     false)))

(defn- do-clean-db!
  "Remove stale Datalevin database."
  []
  (let [db-dir (io/file db-path)
        lock-dir (io/file (str db-path ".lock"))
        cleaned (atom false)]
    (when (.exists db-dir)
      (run! io/delete-file (reverse (file-seq db-dir)))
      (reset! cleaned true))
    (when (.exists lock-dir)
      (run! io/delete-file (reverse (file-seq lock-dir)))
      (reset! cleaned true))
    (if @cleaned
      (println (str "  Removed " db-path " and lock file."))
      (println "  No stale database found."))))

(defn- do-start!
  "Start server with code-browser-v2 auto-initialization."
  []
  (println (str "  Config: " config))
  (println "  This scans sources and populates the database — may take 10-20 seconds.")
  (let [result @(p/process {:cmd ["bb" "server:start-wait"
                                  "--nickname" nickname
                                  "--config" config]
                            :out :inherit
                            :err :inherit})]
    (when-not (zero? (:exit result))
      (println "  ERROR: Server failed to start.")
      (System/exit 1)))
  (if (db-exists?)
    (println (str "  Database created at " db-path))
    (println (str "  WARNING: Database not found at " db-path " — init may have failed."))))

(defn- do-generate-catalog!
  "Generate telemetry catalog with timestamped filename."
  []
  (let [dir (io/file catalog-dir)
        ts (.format (java.time.LocalDateTime/now)
                    (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        filename (str "telemetry-catalog-" ts ".edn")]
    (.mkdirs dir)
    (let [result @(p/process {:cmd ["bb" "telemetry:catalog" "--save"]
                              :out :string
                              :err :string
                              :dir "."
                              :extra-env {"TELEMETRY_CATALOG_PATH"
                                          (str catalog-dir "/" filename)}})]
      ;; --save writes to telemetry-catalog.edn by default,
      ;; so move it to the timestamped name
      (let [default-file (io/file "telemetry-catalog.edn")
            target-file (io/file catalog-dir filename)]
        (when (.exists default-file)
          (io/copy default-file target-file)
          (.delete default-file)))
      (if (zero? (:exit result))
        (println (str "  Saved " catalog-dir "/" filename))
        (println "  WARNING: Catalog generation failed (non-fatal).")))))

(defn- do-open-browser!
  "Open browser to bootstrap page."
  []
  @(p/process {:cmd ["open" browser-url]
               :out :inherit
               :err :inherit})
  (println (str "  Opened " browser-url)))

(defn- print-next-steps
  "Print next steps after server start."
  []
  (println "")
  (println "=== Ready ===")
  (println "")
  (println "Next steps:")
  (println "  1. Wait for browser to connect (green status bar)")
  (println "  2. Click 'Load Code Browser' button")
  (println "  3. Projects appear automatically — click to browse")
  (println "")
  (println "Useful commands:")
  (println "  bb dev:cb-v2 status                     # Check status")
  (println "  bb dev:cb-v2 restart                    # Restart with fresh data")
  (println "  bb dev:cb-v2 stop                       # Stop server")
  (println (str "  bb nrepl-direct list -t " nickname "       # List browser connections"))
  (println (str "  bb nrepl-direct eval '(+ 1 2)' -t " nickname "/browser-1  # Eval in browser")))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-start
  "Clean start: stop existing, clean DB, start server, open browser."
  [opts]
  (println "=== Code Browser v2 Dev Environment ===")
  (println "")
  (println "[1/5] Generating telemetry catalog...")
  (do-generate-catalog!)
  (println "[2/5] Stopping existing server...")
  (do-stop!)
  (println "[3/5] Cleaning stale database...")
  (do-clean-db!)
  (println "[4/5] Starting server (auto-initializes code-browser-v2)...")
  (do-start!)
  (if (:open-browser opts)
    (do
     (println "[5/5] Opening browser...")
     (do-open-browser!))
    (println "[5/5] Skipping browser (--no-open)."))
  (print-next-steps))

(defn cmd-stop
  "Stop the cb-v2-test server."
  []
  (println "=== Stopping Code Browser v2 ===")
  (println "")
  (do-stop!))

(defn cmd-restart
  "Restart: stop, clean DB, start server."
  [_opts]
  (println "=== Restarting Code Browser v2 ===")
  (println "")
  (println "[1/4] Generating telemetry catalog...")
  (do-generate-catalog!)
  (println "[2/4] Stopping server...")
  (do-stop!)
  (println "[3/4] Cleaning stale database...")
  (do-clean-db!)
  (println "[4/4] Starting server (auto-initializes code-browser-v2)...")
  (do-start!)
  (print-next-steps))

(defn cmd-status
  "Show server and database status."
  []
  (println "=== Code Browser v2 Status ===")
  (println "")
  (println "Server:")
  (if (server-running?)
    (do
     (println (str "  " nickname " is RUNNING"))
     (when-let [port-info (read-port-file)]
               (println (str "  Ports: " (pr-str (:ports port-info))))
               (println (str "  PID: " (:pid port-info)))))
    (println (str "  " nickname " is NOT running")))
  (println "")
  (println "Database:")
  (if (db-exists?)
    (println (str "  " db-path " exists"))
    (println (str "  " db-path " does not exist")))
  (println "")
  (println "Browser:")
  (println (str "  " browser-url)))

(defn print-help
  "Print usage help."
  []
  (println "Code Browser v2 — Development Environment Manager")
  (println "")
  (println "Usage: bb dev:cb-v2 <command> [options]")
  (println "")
  (println "Commands:")
  (println "  start     Clean start (stop, clean DB, start, open browser) [default]")
  (println "  stop      Stop the server")
  (println "  restart   Restart with fresh database")
  (println "  status    Show server and database status")
  (println "")
  (println "Options:")
  (println "  --no-open   Skip opening browser (start only)")
  (println "  --help      Show this help")
  (println "")
  (println "Examples:")
  (println "  bb dev:cb-v2                # Clean start + open browser")
  (println "  bb dev:cb-v2 start --no-open")
  (println "  bb dev:cb-v2 restart")
  (println "  bb dev:cb-v2 status")
  (println "  bb dev:cb-v2 stop"))

;; =============================================================================
;; Main - Parse command and flags
;; =============================================================================

(let [args *command-line-args*
      cmd (first args)
      flags (set (rest args))
      opts {:open-browser (not (contains? flags "--no-open"))}]
  (case cmd
    "start" (cmd-start opts)
    "stop" (cmd-stop)
    "restart" (cmd-restart opts)
    "status" (cmd-status)
    "--help" (print-help)
    "-h" (print-help)
    ;; Default: start
    (nil) (cmd-start opts)
    ;; Unknown
    (do (println (str "Unknown command: " cmd))
        (println "")
        (print-help)
        (System/exit 1))))
