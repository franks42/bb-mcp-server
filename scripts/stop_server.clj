#!/usr/bin/env bb
;; Stop a running bb-mcp-server by port or nickname
;; Usage: bb scripts/stop_server.clj [port|nickname]
;;
;; If no argument is provided, uses the default nickname "bb-server".
;; Looks in .ports/ directory for server info files.

(ns stop-server
    "Stop a running bb-mcp-server by port or nickname."
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [cheshire.core :as json]))

(def ^:private ports-dir ".ports")
(def ^:private default-nickname "bb-server")

(defn- list-port-files
  "List all port files in .ports directory."
  []
  (let [dir (io/file ports-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(and (.isFile %) (.endsWith (.getName %) ".json")))
           (map (fn [f]
                  (try
                   (assoc (json/parse-string (slurp f) true)
                          :file (.getAbsolutePath f))
                   (catch Exception _ nil))))
           (remove nil?)))))

(defn- find-server
  "Find server by port number or nickname."
  [identifier]
  (let [port-files (list-port-files)
        as-port (try (parse-long identifier) (catch Exception _ nil))]
    (or
     ;; Try matching by nickname
     (first (filter #(= identifier (:nickname %)) port-files))
     ;; Try matching by port (check all ports in the ports map)
     (when as-port
       (first (filter #(some (fn [[_ p]] (= as-port p)) (:ports %)) port-files))))))

(defn- process-alive?
  "Check if process with given PID is still running."
  [pid]
  (try
   (.isPresent (java.lang.ProcessHandle/of pid))
   (catch Exception _ false)))

(defn- stop-server!
  "Stop server by sending SIGTERM, then SIGKILL if needed."
  [{:keys [pid ports nickname file]}]
  (let [mcp-port (get ports :mcp-http)]
    (println (str "Stopping server '" nickname "' (PID " pid
                  (when mcp-port (str ", MCP port " mcp-port)) ")..."))
    (if (process-alive? pid)
      (try
        (let [exit-code (.waitFor (-> (Runtime/getRuntime)
                                      (.exec (str "kill -TERM " pid))))]
          (if (zero? exit-code)
            (do
              (println "  SIGTERM sent, waiting for shutdown...")
              (loop [attempts 10]
                (Thread/sleep 500)
                (if (process-alive? pid)
                  (if (pos? attempts)
                    (recur (dec attempts))
                    (do
                      (println "  Process didn't exit, sending SIGKILL...")
                      (.waitFor (-> (Runtime/getRuntime)
                                    (.exec (str "kill -9 " pid))))
                      (io/delete-file file true)
                      (println "  Server killed")))
                  (do
                    (io/delete-file file true)
                    (println "  Server stopped gracefully")))))
            (println "  Failed to send signal")))
        (catch Exception e
          (println "  Error:" (.getMessage e))))
      (do
        (println (str "  Process " pid " not running, cleaning up stale port file"))
        (io/delete-file file true)))))

(defn- list-servers!
  "List all running servers."
  []
  (let [servers (list-port-files)]
    (if (seq servers)
      (do
        (println "Running servers:")
        (doseq [{:keys [nickname ports pid]} servers]
          (let [alive? (process-alive? pid)
                port-summary (if (seq ports)
                               (str/join ", "
                                 (map (fn [[k v]] (str (name k) "=" v)) ports))
                               "no ports")]
            (println (str "  " nickname " - " port-summary " (PID " pid ") "
                          (if alive? "[running]" "[stale]"))))))
      (println "No servers found in .ports/"))))

(defn- cleanup-stale!
  "Remove all stale port files (where PID is no longer running)."
  []
  (let [servers (list-port-files)
        stale (filter #(not (process-alive? (:pid %))) servers)]
    (if (seq stale)
      (do
        (println "Cleaning up stale port files:")
        (doseq [{:keys [nickname pid file]} stale]
          (println (str "  " nickname " (PID " pid " not running)"))
          (io/delete-file file true))
        (println (str "Removed " (count stale) " stale file(s)")))
      (println "No stale port files found"))))

;; Main
(let [arg (first *command-line-args*)]
  (cond
    (= arg "--help")
    (do
      (println "bb server:stop - Stop a running server")
      (println "")
      (println "Usage: bb server:stop [port|nickname]")
      (println "       bb server:stop --list")
      (println "       bb server:stop --cleanup")
      (println "")
      (println (str "If no argument given, stops the default server '" default-nickname "'"))
      (println "")
      (println "Options:")
      (println "  --list     List all servers with their status")
      (println "  --cleanup  Remove stale port files (PIDs not running)"))

    (= arg "--list")
    (list-servers!)

    (= arg "--cleanup")
    (cleanup-stale!)

    :else
    (let [identifier (or arg default-nickname)]
      (if-let [server (find-server identifier)]
        (stop-server! server)
        (do
          (println (str "No server found for: " identifier))
          (list-servers!))))))
