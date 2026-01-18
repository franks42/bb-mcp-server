#!/usr/bin/env bb
;; Datalevin Pod Manager - ensures single instance, provides lifecycle control
;; Usage: bb scripts/datalevin_manager.clj <command>
;;
;; Commands:
;;   status    - Show running Datalevin pod processes
;;   stop      - Stop all Datalevin pod processes
;;   cleanup   - Stop pods + remove db lock files

(ns datalevin-manager
    "Manage Datalevin pod processes for bb-mcp-server."
    (:require [babashka.process :as p]
              [clojure.java.io :as io]
              [clojure.string :as str]))

(def ^:private dtlv-pattern "dtlv")
(def ^:private default-db-paths
     "Common Datalevin database paths to check for locks."
     ["/tmp/code-browser-v2-test-db"
      "/var/db/datalevin/bb-mcp-server"])

(defn- find-dtlv-processes
  "Find all running dtlv (Datalevin pod) processes.
   Returns seq of {:pid PID :cmd CMD} maps."
  []
  (try
   (let [result @(p/process {:cmd ["pgrep" "-fl" dtlv-pattern]
                             :out :string
                             :err :string})]
     (if (zero? (:exit result))
       (->> (str/split-lines (:out result))
            (remove str/blank?)
            (map (fn [line]
                   (let [[pid & cmd-parts] (str/split line #"\s+")]
                     {:pid (parse-long pid)
                      :cmd (str/join " " cmd-parts)}))))
       []))
   (catch Exception _
          [])))

(defn- process-alive?
  "Check if process with given PID is still running."
  [pid]
  (try
   (.isPresent (java.lang.ProcessHandle/of pid))
   (catch Exception _ false)))

(defn- kill-process!
  "Kill a process by PID. Returns true if successful."
  [pid signal]
  (try
   (let [result @(p/process {:cmd ["kill" (str "-" signal) (str pid)]
                             :out :string
                             :err :string})]
     (zero? (:exit result)))
   (catch Exception _ false)))

(defn- find-lock-files
  "Find Datalevin lock files in known locations."
  []
  (->> default-db-paths
       (mapcat (fn [path]
                 (let [dir (io/file path)]
                   (when (.exists dir)
                     (->> (.listFiles dir)
                          (filter #(str/ends-with? (.getName %) ".lock"))
                          (map #(.getAbsolutePath %)))))))
       (into [])))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-status
  "Show status of Datalevin pod processes."
  []
  (let [procs (find-dtlv-processes)
        locks (find-lock-files)]
    (println "Datalevin Pod Status")
    (println "====================")
    (println)
    (if (seq procs)
      (do
       (println (str "Running processes: " (count procs)))
       (doseq [{:keys [pid cmd]} procs]
              (println (str "  PID " pid ": " cmd))))
      (println "No running dtlv processes found."))
    (println)
    (if (seq locks)
      (do
       (println (str "Lock files found: " (count locks)))
       (doseq [lock locks]
              (println (str "  " lock))))
      (println "No lock files found."))
    (println)
    (when (> (count procs) 1)
      (println "⚠️  WARNING: Multiple Datalevin processes detected!")
      (println "   This can cause resource contention and slow queries.")
      (println "   Run 'bb datalevin:stop' to stop all, then restart your server."))
    {:processes (count procs)
     :locks (count locks)}))

(defn cmd-stop
  "Stop all Datalevin pod processes."
  []
  (let [procs (find-dtlv-processes)]
    (if (empty? procs)
      (println "No Datalevin processes to stop.")
      (do
       (println (str "Stopping " (count procs) " Datalevin process(es)..."))
       (doseq [{:keys [pid]} procs]
              (print (str "  PID " pid "... "))
              (flush)
              (if (kill-process! pid "TERM")
                (do
              ;; Wait briefly for graceful shutdown
                 (Thread/sleep 500)
                 (if (process-alive? pid)
                   (do
                    (kill-process! pid "KILL")
                    (println "killed (SIGKILL)"))
                   (println "stopped")))
                (println "failed to signal")))
       (println "Done.")))))

(defn cmd-cleanup
  "Stop all processes and remove lock files."
  []
  (cmd-stop)
  (println)
  (let [locks (find-lock-files)]
    (if (empty? locks)
      (println "No lock files to clean up.")
      (do
       (println (str "Removing " (count locks) " lock file(s)..."))
       (doseq [lock locks]
              (print (str "  " lock "... "))
              (flush)
              (try
               (io/delete-file lock)
               (println "removed")
               (catch Exception e
                      (println (str "failed: " (.getMessage e))))))
       (println "Done.")))))

(defn print-help
  "Print usage help for datalevin manager commands."
  []
  (println "Datalevin Pod Manager")
  (println "")
  (println "Usage: bb datalevin:<command>")
  (println "")
  (println "Commands:")
  (println "  status   - Show running Datalevin pod processes and lock files")
  (println "  stop     - Stop all Datalevin pod processes")
  (println "  cleanup  - Stop processes and remove lock files")
  (println "")
  (println "Examples:")
  (println "  bb datalevin:status   # Check what's running")
  (println "  bb datalevin:stop     # Stop all pods")
  (println "  bb datalevin:cleanup  # Full cleanup before fresh start"))

;; =============================================================================
;; Main - Script entry point
;; =============================================================================

(let [cmd (first *command-line-args*)]
  (case cmd
    "status" (cmd-status)
    "stop" (cmd-stop)
    "cleanup" (cmd-cleanup)
    "--help" (print-help)
    "-h" (print-help)
    (print-help)))
