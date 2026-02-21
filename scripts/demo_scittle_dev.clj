#!/usr/bin/env bb
;; Demo Scittle App — Development Environment Manager
;;
;; Usage:
;;   bb dev:demo-scittle start           # Start server, open browser
;;   bb dev:demo-scittle start --no-open # Start without opening browser
;;   bb dev:demo-scittle stop            # Stop server
;;   bb dev:demo-scittle status          # Show server status
;;   bb dev:demo-scittle                 # Same as 'start'
;;
;; Architecture:
;;   This is Process A — the demo Scittle app (the browsing subject).
;;   Process B is the Code Browser (bb dev:cb-v2), which connects to
;;   this server's nREPL proxy (port 2667) to introspect the Scittle runtime.

(ns demo-scittle-dev
    "Demo Scittle app development environment manager."
    (:require [babashka.process :as p]
              [cheshire.core :as json]
              [clojure.java.io :as io]
              [clojure.string :as str]))

(def ^:private nickname "demo-scittle")
(def ^:private config "system-demo-scittle.edn")
(def ^:private browser-url "http://localhost:8191")
(def ^:private ports-dir ".ports")
(def ^:private demo-app-file "demo/scittle-app/demo_app.cljs")

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- server-running?
  "Check if demo-scittle server is currently running."
  []
  (try
   (let [result @(p/process {:cmd ["bb" "server:list"]
                             :out :string
                             :err :string})]
     (str/includes? (str (:out result)) nickname))
   (catch Exception _ false)))

(defn- read-port-file
  "Read port file for the demo-scittle server. Returns map or nil."
  []
  (let [file (io/file ports-dir (str nickname ".json"))]
    (when (.exists file)
      (try
       (json/parse-string (slurp file) true)
       (catch Exception _ nil)))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn- do-stop!
  "Stop demo-scittle server if running."
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

(defn- do-start!
  "Start the demo-scittle server."
  []
  (println (str "  Config: " config))
  (let [result @(p/process {:cmd ["bb" "server:start-wait"
                                  "--nickname" nickname
                                  "--config" config]
                            :out :inherit
                            :err :inherit})]
    (when-not (zero? (:exit result))
      (println "  ERROR: Server failed to start.")
      (System/exit 1))))

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
  (println "  2. Load the demo app:")
  (println (str "     bb nrepl-direct load-local-file " demo-app-file " -t " nickname "/browser-1"))
  (println "  3. Start the Code Browser to browse this runtime:")
  (println "     bb dev:cb-v2 start")
  (println "")
  (println "Useful commands:")
  (println "  bb dev:demo-scittle status                     # Check status")
  (println "  bb dev:demo-scittle stop                       # Stop server")
  (println (str "  bb nrepl-direct list -t " nickname "              # List connections"))
  (println (str "  bb nrepl-direct eval \"(+ 1 2)\" -t " nickname "/browser-1  # Eval in browser")))

(defn cmd-start
  "Start: stop existing, start server, open browser."
  [opts]
  (println "=== Demo Scittle App ===")
  (println "")
  (println "[1/3] Stopping existing server...")
  (do-stop!)
  (println "[2/3] Starting server...")
  (do-start!)
  (if (:open-browser opts)
    (do
     (println "[3/3] Opening browser...")
     (do-open-browser!))
    (println "[3/3] Skipping browser (--no-open)."))
  (print-next-steps))

(defn cmd-stop
  "Stop the demo-scittle server."
  []
  (println "=== Stopping Demo Scittle App ===")
  (println "")
  (do-stop!))

(defn cmd-status
  "Show server status."
  []
  (println "=== Demo Scittle App Status ===")
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
  (println "Browser:")
  (println (str "  " browser-url))
  (println "")
  (println "Demo app file:")
  (println (str "  " demo-app-file)))

(defn print-help
  "Print usage help."
  []
  (println "Demo Scittle App — Development Environment Manager")
  (println "")
  (println "Usage: bb dev:demo-scittle <command> [options]")
  (println "")
  (println "Commands:")
  (println "  start     Start server + open browser [default]")
  (println "  stop      Stop the server")
  (println "  status    Show server status")
  (println "")
  (println "Options:")
  (println "  --no-open   Skip opening browser (start only)")
  (println "  --help      Show this help"))

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
