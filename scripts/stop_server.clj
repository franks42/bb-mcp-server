#!/usr/bin/env bb
;; Stop a running bb-mcp-server by port
;; Usage: bb scripts/stop_server.clj [port]

(require '[clojure.java.io :as io])

(def port
     "Server port from CLI args or default 3000."
     (or (some-> (first *command-line-args*) parse-long) 3000))

(defn pid-file-path [port]
  (str ".bb-mcp-server-" port ".pid"))

(defn read-pid-file [port]
  (let [path (pid-file-path port)]
    (when (.exists (io/file path))
      (try
       (parse-long (slurp path))
       (catch Exception _ nil)))))

(defn delete-pid-file! [port]
  (let [file (io/file (pid-file-path port))]
    (when (.exists file)
      (.delete file))))

(defn process-alive? [pid]
  (try
   (.isPresent (java.lang.ProcessHandle/of pid))
   (catch Exception _ false)))

(defn stop-server! [port]
  (if-let [pid (read-pid-file port)]
          (if (process-alive? pid)
            (do
             (println (str "Stopping server (PID " pid ") on port " port "..."))
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
                              (delete-pid-file! port)
                              (println "  Server killed")))
                           (do
                            (delete-pid-file! port)
                            (println "  Server stopped gracefully")))))
                  (println "  Failed to send signal")))
              (catch Exception e
                     (println "  Error:" (.getMessage e)))))
            (do
             (println (str "Process " pid " not running, cleaning up stale PID file"))
             (delete-pid-file! port)))
          (println (str "No server running on port " port " (no PID file found)"))))

(stop-server! port)
