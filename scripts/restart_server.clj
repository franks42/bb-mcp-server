#!/usr/bin/env bb
;;; Restart a bb-mcp-server instance (stop + start)
;;;
;;; Usage:
;;;   bb scripts/restart_server.clj [nickname] [--config PATH] [--port PORT]
;;;
;;; Restarts the server with same nickname, preserving config if not specified.

(ns restart-server
    (:require [babashka.process :as p]
              [cheshire.core :as json]
              [clojure.java.io :as io]
              [clojure.string :as str]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private default-nickname "bb-server")
(def ^:private ports-dir ".ports")

;; =============================================================================
;; Port File Operations
;; =============================================================================

(defn- read-port-file
  "Read port file for a nickname."
  [nickname]
  (let [path (str ports-dir "/" nickname ".json")]
    (when (.exists (io/file path))
      (try
       (json/parse-string (slurp path) true)
       (catch Exception _
              nil)))))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:nickname nil :config nil :port nil}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (= arg "--config")
              (recur (rest rest-args) (assoc opts :config (first rest-args)))

              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (first rest-args)))

              (or (= arg "-h") (= arg "--help"))
              (assoc opts :help true)

              (str/starts-with? arg "-")
              (do (println (str "Unknown option: " arg))
                  (assoc opts :help true))

              :else
              (recur rest-args (assoc opts :nickname arg)))))))

(defn- print-help
  "Print usage help."
  []
  (println "
Usage: bb server:restart [nickname] [OPTIONS]

Restart a bb-mcp-server instance (stop + start).

Arguments:
  nickname    Server nickname (default: bb-server)

Options:
  --config PATH   Config file (default: preserve from running server)
  --port PORT     HTTP port (default: ephemeral)
  -h, --help      Show this help

Examples:
  bb server:restart              # Restart default server
  bb server:restart my-server    # Restart my-server
  bb server:restart --config custom.edn  # Restart with new config
"))

;; =============================================================================
;; Main Logic
;; =============================================================================

(defn- stop-server!
  "Stop server and wait for it to terminate."
  [nickname]
  (println (str "Stopping server '" nickname "'..."))
  (let [result (p/shell {:out :string :err :string :continue true}
                        "bb" "server:stop" nickname)]
    (when (not= 0 (:exit result))
      (println (:out result))
      (println (:err result)))
    (= 0 (:exit result))))

(defn- start-server!
  "Start server with given options."
  [{:keys [nickname config port]}]
  (println (str "\nStarting server '" nickname "'..."))
  (let [args (cond-> ["bb" "server:start-wait" "--nickname" nickname]
                     config (conj "--config" config)
                     port (conj "--port" port))
        result (apply p/shell {:out :inherit :err :inherit :continue true} args)]
    (= 0 (:exit result))))

(defn -main
  "Restart server entry point."
  [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (do (print-help)
          (System/exit 0))

      :else
      (let [nickname (or (:nickname opts) default-nickname)
            ;; Read existing config from port file if not overridden
            existing (read-port-file nickname)
            config (or (:config opts)
                       (:config existing)
                       "system.edn")
            port (:port opts)]

        (if existing
          ;; Server is running - stop then start
          (if (stop-server! nickname)
            (do
             (Thread/sleep 500) ; Brief pause for cleanup
             (if (start-server! {:nickname nickname
                                 :config config
                                 :port port})
               (do
                (println (str "\n✓ Server '" nickname "' restarted successfully"))
                (System/exit 0))
               (do
                (println (str "\n✗ Failed to start server '" nickname "'"))
                (System/exit 1))))
            (do
             (println (str "\n✗ Failed to stop server '" nickname "'"))
             (System/exit 1)))

          ;; Server not running - just start
          (do
           (println (str "Server '" nickname "' is not running. Starting..."))
           (if (start-server! {:nickname nickname
                               :config config
                               :port port})
             (do
              (println (str "\n✓ Server '" nickname "' started successfully"))
              (System/exit 0))
             (do
              (println (str "\n✗ Failed to start server '" nickname "'"))
              (System/exit 1)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
