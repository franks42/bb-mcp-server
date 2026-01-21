#!/usr/bin/env bb
;; List ports for a running bb-mcp-server
;; Usage: bb scripts/list_ports.clj [nickname]
;;
;; If no nickname is provided, uses the default "bb-server".
;; Displays all registered service ports from the unified port file.

(ns list-ports
  "List ports for a running bb-mcp-server."
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]))

(def ^:private ports-dir ".ports")
(def ^:private default-nickname "bb-server")

(defn- process-alive?
  "Check if process with given PID is still running."
  [pid]
  (try
    (.isPresent (java.lang.ProcessHandle/of pid))
    (catch Exception _ false)))

(defn- read-port-file
  "Read port file for given nickname."
  [nickname]
  (let [file (io/file ports-dir (str nickname ".json"))]
    (when (.exists file)
      (try
        (json/parse-string (slurp file) true)
        (catch Exception _ nil)))))

(defn- format-port-line
  "Format a port entry for display."
  [[service-key port]]
  (format "  %-20s %d" (name service-key) port))

(defn- display-ports
  "Display port information for a server."
  [{:keys [nickname ports pid config started-at]}]
  (let [alive? (process-alive? pid)]
    (println (str "Server: " nickname (if alive? " [running]" " [stale]")))
    (println (str "  PID: " pid))
    (when config
      (println (str "  Config: " config)))
    (when started-at
      (println (str "  Started: " started-at)))
    (println)
    (println "Ports:")
    (if (seq ports)
      (doseq [line (map format-port-line (sort-by (comp name key) ports))]
        (println line))
      (println "  (no ports registered)"))
    (println)
    (println (str "Port file: " ports-dir "/" nickname ".json"))))

(defn- list-all-servers
  "List all servers with their ports."
  []
  (let [dir (io/file ports-dir)]
    (if (.exists dir)
      (let [files (->> (.listFiles dir)
                       (filter #(and (.isFile %) (.endsWith (.getName %) ".json"))))]
        (if (seq files)
          (doseq [f files]
            (when-let [server-info (try
                                     (json/parse-string (slurp f) true)
                                     (catch Exception _ nil))]
              (display-ports server-info)
              (println "---")))
          (println "No servers found in .ports/")))
      (println "No .ports/ directory found"))))

;; Main
(let [arg (first *command-line-args*)]
  (cond
    (= arg "--help")
    (do
      (println "bb server:ports - Show ports for a running server")
      (println "")
      (println "Usage: bb server:ports [nickname]")
      (println "       bb server:ports --all")
      (println "")
      (println (str "If no nickname given, shows ports for default server '" default-nickname "'"))
      (println "")
      (println "Options:")
      (println "  --all    Show all running servers with their ports"))

    (= arg "--all")
    (list-all-servers)

    :else
    (let [nickname (or arg default-nickname)]
      (if-let [server-info (read-port-file nickname)]
        (display-ports server-info)
        (do
          (println (str "No server found: " nickname))
          (println "")
          (println "Available servers:")
          (list-all-servers))))))
