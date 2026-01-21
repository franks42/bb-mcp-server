#!/usr/bin/env bb
;; Start a bb-mcp-server and wait for it to be healthy
;; Usage: bb scripts/start_wait_server.clj [options]
;;
;; Options:
;;   --config PATH      Config file (default: system.edn)
;;   --nickname NAME    Server nickname (default: bb-server)
;;   --port PORT        HTTP port (default: 0 = ephemeral)
;;   --timeout SECONDS  Health check timeout (default: 30)
;;   --help             Show this help
;;
;; The script discovers the actual port from the port file after the server starts.
;; This works correctly even with ephemeral ports.
;;
;; Example:
;;   bb scripts/start_wait_server.clj --nickname test-server --timeout 30

(ns start-wait-server
    "Start a bb-mcp-server and wait for it to be healthy."
    (:require [babashka.process :as p]
              [babashka.http-client :as http]
              [cheshire.core :as json]
              [clojure.java.io :as io]
              [clojure.string :as str]))

(def ^:private default-nickname "bb-server")
(def ^:private default-port 0)  ; Ephemeral port by default
(def ^:private default-timeout 30)
(def ^:private ports-dir ".ports")

(defn- parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:nickname default-nickname
               :port default-port
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
  (println "Usage: bb server:start-wait [options]")
  (println "")
  (println "Options:")
  (println "  --config PATH      Config file (default: system.edn)")
  (println (str "  --nickname NAME    Server nickname (default: " default-nickname ")"))
  (println "  --port PORT        HTTP port (default: 0 = ephemeral)")
  (println "  --timeout SECONDS  Health check timeout (default: 30)")
  (println "  --help             Show this help")
  (println "")
  (println "Examples:")
  (println "  bb server:start-wait                       # Ephemeral port, nickname 'bb-server'")
  (println "  bb server:start-wait --nickname test-server")
  (println "  bb server:start-wait --nickname code-browser --config bb-code-browser-dev-system.edn")
  (println "  bb server:start-wait --port 3000           # Specific port"))

(defn- read-port-file
  "Read port file for given nickname. Returns nil if not found."
  [nickname]
  (let [file (io/file ports-dir (str nickname ".json"))]
    (when (.exists file)
      (try
       (json/parse-string (slurp file) true)
       (catch Exception _ nil)))))

(defn- wait-for-port-file
  "Wait for port file to appear and contain mcp-http port.
   Returns {:success true :port N :port-info map} or {:success false :reason keyword}."
  [nickname timeout-secs]
  (let [start (System/currentTimeMillis)
        timeout-ms (* timeout-secs 1000)]
    (loop [attempt 1]
          (let [elapsed (- (System/currentTimeMillis) start)]
            (if (> elapsed timeout-ms)
              {:success false :reason :timeout :attempts attempt}
              (if-let [port-info (read-port-file nickname)]
                      (if-let [mcp-port (get-in port-info [:ports :mcp-http])]
                              {:success true :port mcp-port :port-info port-info :attempts attempt}
              ;; Port file exists but no mcp-http yet - keep waiting
                              (do
                               (Thread/sleep 200)
                               (recur (inc attempt))))
            ;; No port file yet
                      (do
                       (Thread/sleep 200)
                       (recur (inc attempt)))))))))

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
  (println (str "Starting server '" nickname "'"
                (if (zero? port) " (ephemeral port)" (str " on port " port))
                "..."))

  (let [cmd (build-server-cmd opts)
        _ (println (str "  Command: " (str/join " " cmd)))
        ;; Start process in background, inherit stderr for logs
        _proc (p/process {:cmd cmd
                          :out :inherit
                          :err :inherit})]

    ;; First wait for port file to discover actual port
    (println "  Waiting for port file...")
    (let [port-result (wait-for-port-file nickname (min 10 timeout))]
      (if-not (:success port-result)
        (do
         (println (str "  ✗ Port file not created within " (min 10 timeout) "s"))
         (println "  Check server output above for errors")
         (try
          @(p/process ["bb" "server:stop" nickname])
          (catch Exception _))
         false)

        ;; Port file found - now wait for health
        (let [actual-port (:port port-result)
              port-info (:port-info port-result)]
          (println (str "  Port file found (attempt " (:attempts port-result) ")"))
          (println (str "  Discovered ports: " (pr-str (:ports port-info))))
          (println (str "  Waiting for health on port " actual-port " (timeout: " timeout "s)..."))

          (let [result (wait-for-health actual-port timeout)]
            (if (:success result)
              (do
               (println (str "  ✓ Server healthy after " (:attempts result) " attempts ("
                             (quot (:elapsed-ms result) 1000) "s)"))
               (println (str "  Health: http://localhost:" actual-port "/health"))
               (println (str "  Stop:   bb server:stop " nickname))
               true)
              (do
               (println (str "  ✗ Server failed to become healthy after " timeout "s"))
               (println "  Check server output above for errors")
               (try
                @(p/process ["bb" "server:stop" nickname])
                (catch Exception _))
               false))))))))

;; Main
(let [opts (parse-args *command-line-args*)]
  (cond
    (:help opts)
    (print-help)

    :else
    (let [success (start-server! opts)]
      (System/exit (if success 0 1)))))
