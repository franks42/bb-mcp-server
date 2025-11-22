;; PID file utilities for bb-mcp-server
;;
;; Provides functions to write, read, and manage PID files for server processes.
;; PID files are stored in .bb-mcp-server-{port}.pid in the project root.

(ns pid-util
    (:require [clojure.java.io :as io]))

(defn pid-file-path
  "Get the PID file path for a given port."
  [port]
  (str ".bb-mcp-server-" port ".pid"))

(defn current-pid
  "Get the current process ID."
  []
  (.pid (java.lang.ProcessHandle/current)))

(defn write-pid-file!
  "Write current process PID to file for given port."
  [port]
  (let [path (pid-file-path port)
        pid (current-pid)]
    (spit path (str pid))
    (println (str "  PID " pid " written to " path))
    pid))

(defn read-pid-file
  "Read PID from file for given port. Returns nil if file doesn't exist."
  [port]
  (let [path (pid-file-path port)]
    (when (.exists (io/file path))
      (try
       (parse-long (slurp path))
       (catch Exception _
              nil)))))

(defn delete-pid-file!
  "Delete the PID file for a given port."
  [port]
  (let [path (pid-file-path port)
        file (io/file path)]
    (when (.exists file)
      (.delete file)
      true)))

(defn process-alive?
  "Check if a process with given PID is still alive."
  [pid]
  (try
   (-> (java.lang.ProcessHandle/of pid)
       (.isPresent))
   (catch Exception _
          false)))

(defn stop-server!
  "Stop server running on given port by reading PID file and sending SIGTERM.
   Returns:
   - {:stopped pid} on success
   - {:error :no-pid-file} if no PID file exists
   - {:error :process-not-found} if process doesn't exist
   - {:error :kill-failed} if kill fails"
  [port]
  (if-let [pid (read-pid-file port)]
          (if (process-alive? pid)
            (do
             (println (str "Stopping server (PID " pid ") on port " port "..."))
             (try
          ;; Send SIGTERM for graceful shutdown
              (let [exit-code (.waitFor (-> (Runtime/getRuntime)
                                            (.exec (str "kill -TERM " pid))))]
                (if (zero? exit-code)
                  (do
                   (println "  SIGTERM sent, waiting for shutdown...")
                ;; Wait up to 5 seconds for process to exit
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
                              {:stopped pid :forced true}))
                           (do
                            (delete-pid-file! port)
                            (println "  Server stopped gracefully")
                            {:stopped pid}))))
                  {:error :kill-failed}))
              (catch Exception e
                     {:error :kill-failed :exception (.getMessage e)})))
            (do
             (println (str "Process " pid " not running, cleaning up stale PID file"))
             (delete-pid-file! port)
             {:error :process-not-found :stale-pid pid}))
          (do
           (println (str "No server running on port " port " (no PID file found)"))
           {:error :no-pid-file})))

(defn register-shutdown-hook!
  "Register JVM shutdown hook to clean up PID file."
  [port]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    (fn []
      (delete-pid-file! port)))))
