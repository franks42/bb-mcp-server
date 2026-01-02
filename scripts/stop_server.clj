#!/usr/bin/env bb
;; Stop a running bb-mcp-server by port or nickname
;; Usage: bb scripts/stop_server.clj [port|nickname]
;;
;; Looks in .ports/ directory for server info files.

(ns stop-server
    "Stop a running bb-mcp-server by port or nickname."
    (:require [clojure.java.io :as io]
              [cheshire.core :as json]))

(def ^:private ports-dir ".ports")

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
     ;; Try matching by port
     (when as-port
       (first (filter #(= as-port (:port %)) port-files))))))

(defn- process-alive?
  "Check if process with given PID is still running."
  [pid]
  (try
   (.isPresent (java.lang.ProcessHandle/of pid))
   (catch Exception _ false)))

(defn- stop-server!
  "Stop server by sending SIGTERM, then SIGKILL if needed."
  [{:keys [pid port nickname file]}]
  (println (str "Stopping server '" nickname "' (PID " pid ", port " port ")..."))
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
     (io/delete-file file true))))

(defn- list-servers!
  "List all running servers."
  []
  (let [servers (list-port-files)]
    (if (seq servers)
      (do
       (println "Running servers:")
       (doseq [{:keys [nickname port pid]} servers]
              (let [alive? (process-alive? pid)]
                (println (str "  " nickname " - port " port " (PID " pid ") "
                              (if alive? "[running]" "[stale]"))))))
      (println "No servers found in .ports/"))))

;; Main
(let [arg (first *command-line-args*)]
  (cond
    (nil? arg)
    (do
     (println "Usage: bb scripts/stop_server.clj <port|nickname>")
     (println "       bb scripts/stop_server.clj --list")
     (println "")
     (list-servers!))

    (= arg "--list")
    (list-servers!)

    :else
    (if-let [server (find-server arg)]
            (stop-server! server)
            (do
             (println (str "No server found for: " arg))
             (list-servers!)))))
