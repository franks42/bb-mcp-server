#!/usr/bin/env bb
;; Start a bb-mcp-server and wait for it to be healthy
;; Usage: bb scripts/start_wait_server.clj [options]
;;
;; Options:
;;   --config PATH      Config file (default: system.edn)
;;   --nickname NAME    Server nickname (required)
;;   --port PORT        HTTP port (default: 3000)
;;   --timeout SECONDS  Health check timeout (default: 30)
;;   --help             Show this help
;;
;; Example:
;;   bb scripts/start_wait_server.clj --nickname test-server --port 3000 --timeout 30

(ns start-wait-server
  "Start a bb-mcp-server and wait for it to be healthy."
  (:require [babashka.process :as p]
            [babashka.http-client :as http]
            [clojure.string :as str]))

(def ^:private default-port "Default HTTP port" 3000)
(def ^:private default-timeout "Default health check timeout in seconds" 30)

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:port default-port
               :timeout default-timeout}]
    (if (empty? args)
      opts
      (let [[arg & more] args]
        (case arg
          "--config" (recur (rest more) (assoc opts :config (first more)))
          "--nickname" (recur (rest more) (assoc opts :nickname (first more)))
          "--port" (recur (rest more) (assoc opts :port (parse-long (first more))))
          "--timeout" (recur (rest more) (assoc opts :timeout (parse-long (first more))))
          "--help" (assoc opts :help true)
          (recur more opts))))))

(defn- print-help []
  (println "bb server:start-wait - Start server and wait for health")
  (println "")
  (println "Usage: bb server:start-wait --nickname NAME [options]")
  (println "")
  (println "Options:")
  (println "  --config PATH      Config file (default: system.edn)")
  (println "  --nickname NAME    Server nickname (required)")
  (println "  --port PORT        HTTP port (default: 3000)")
  (println "  --timeout SECONDS  Health check timeout (default: 30)")
  (println "  --help             Show this help")
  (println "")
  (println "Examples:")
  (println "  bb server:start-wait --nickname test-server")
  (println "  bb server:start-wait --nickname code-browser --config bb-code-browser-dev-system.edn")
  (println "  bb server:start-wait --nickname e2e --port 3001 --timeout 60"))

(defn- health-check
  "Check if server is healthy. Returns true/false."
  [port]
  (try
    (let [resp (http/get (str "http://localhost:" port "/health")
                         {:timeout 2000
                          :throw false})]
      (= 200 (:status resp)))
    (catch Exception _
      false)))

(defn- wait-for-health
  "Poll health endpoint until healthy or timeout."
  [port timeout-secs]
  (let [start (System/currentTimeMillis)
        timeout-ms (* timeout-secs 1000)]
    (loop [attempt 1]
      (let [elapsed (- (System/currentTimeMillis) start)]
        (cond
          (health-check port)
          {:success true :attempts attempt :elapsed-ms elapsed}

          (> elapsed timeout-ms)
          {:success false :reason :timeout :attempts attempt :elapsed-ms elapsed}

          :else
          (do
            (Thread/sleep 500)
            (recur (inc attempt))))))))

(defn- build-server-cmd
  "Build the command to start the server."
  [{:keys [config nickname port]}]
  (cond-> ["bb" "server" "--http" "--port" (str port) "--nickname" nickname]
    config (into ["--config" config])))

(defn- start-server!
  "Start server in background and wait for health."
  [{:keys [nickname port timeout] :as opts}]
  (println (str "Starting server '" nickname "' on port " port "..."))

  (let [cmd (build-server-cmd opts)
        _ (println (str "  Command: " (str/join " " cmd)))
        ;; Start process in background, inherit stderr for logs
        _proc (p/process {:cmd cmd
                         :out :inherit
                         :err :inherit})]

    (println (str "  Waiting for health (timeout: " timeout "s)..."))

    (let [result (wait-for-health port timeout)]
      (if (:success result)
        (do
          (println (str "  ✓ Server healthy after " (:attempts result) " attempts ("
                        (quot (:elapsed-ms result) 1000) "s)"))
          (println (str "  Health: http://localhost:" port "/health"))
          (println (str "  Stop:   bb server:stop " nickname))
          true)
        (do
          (println (str "  ✗ Server failed to become healthy after " timeout "s"))
          (println "  Check server output above for errors")
          ;; Try to stop the server we started
          (try
            @(p/process ["bb" "server:stop" nickname])
            (catch Exception _))
          false)))))

;; Main
(let [opts (parse-args *command-line-args*)]
  (cond
    (:help opts)
    (print-help)

    (nil? (:nickname opts))
    (do
      (println "Error: --nickname is required")
      (println "")
      (print-help)
      (System/exit 1))

    :else
    (let [success (start-server! opts)]
      (System/exit (if success 0 1)))))
