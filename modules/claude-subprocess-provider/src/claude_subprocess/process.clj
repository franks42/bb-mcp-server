(ns claude-subprocess.process
    "Low-level process spawning and I/O handling.

   Implements the Dedicated Reader Loop pattern:
   - One background future per instance reads stdout continuously
   - Dispatches messages by type to appropriate handlers
   - Thread-safe: multiple callers can write to same instance"
    (:require [babashka.process :as p]
              [cheshire.core :as json]
              [taoensso.trove :as log])
    (:import [java.io BufferedReader BufferedWriter]))

;; =============================================================================
;; Process Spawning
;; =============================================================================

(defn spawn-process!
  "Spawn a subprocess with given command and args.

   Arguments:
     cmd  - Command vector, e.g. [\"bb\" \"script.clj\"]
     opts - Options map (currently unused, for future config)

   Returns:
     Process map with :process, :reader, :writer keys."
  [cmd & [{:keys [_env] :as _opts}]]
  (log/log! {:level :info
             :id    ::spawning-process
             :msg   "Spawning subprocess"
             :data  {:cmd cmd}})
  (let [proc (p/process cmd {:shutdown p/destroy-tree})
        reader (BufferedReader. (java.io.InputStreamReader. (:out proc)))
        writer (BufferedWriter. (java.io.OutputStreamWriter. (:in proc)))]
    {:process proc
     :reader reader
     :writer writer
     :alive? (atom true)}))

(defn process-alive?
  "Check if process is still running."
  [proc-map]
  (and @(:alive? proc-map)
       (.isAlive (:proc (:process proc-map)))))

(defn kill-process!
  "Kill the subprocess."
  [proc-map]
  (log/log! {:level :info
             :id    ::killing-process
             :msg   "Killing subprocess"})
  (reset! (:alive? proc-map) false)
  (p/destroy-tree (:process proc-map)))

;; =============================================================================
;; I/O Operations
;; =============================================================================

(defn write-message!
  "Write a JSONL message to process stdin.

   Arguments:
     proc-map - Process map from spawn-process!
     msg      - Clojure map to serialize as JSON

   Returns:
     true on success, false on error."
  [proc-map msg]
  (try
   (let [writer ^BufferedWriter (:writer proc-map)
         json-str (json/generate-string msg)]
     (log/log! {:level :debug
                :id    ::writing-message
                :msg   "Writing message to stdin"
                :data  {:type (:type msg)}})
     (.write writer json-str)
     (.newLine writer)
     (.flush writer)
     true)
   (catch Exception e
          (log/log! {:level :error
                     :id    ::write-error
                     :msg   "Failed to write message"
                     :error e})
          false)))

(defn read-line!
  "Read a single line from process stdout.

   Returns:
     String line or nil on EOF/error."
  [proc-map]
  (try
   (.readLine ^BufferedReader (:reader proc-map))
   (catch Exception e
          (log/log! {:level :debug
                     :id    ::read-error
                     :msg   "Read error (likely EOF)"
                     :error e})
          nil)))

(defn parse-jsonl
  "Parse a JSONL line into Clojure map.

   Returns:
     Parsed map or {:parse-error true :line line :error msg} on failure."
  [line]
  (try
   (json/parse-string line true)
   (catch Exception e
          {:parse-error true
           :line line
           :error (.getMessage e)})))

;; =============================================================================
;; Reader Loop
;; =============================================================================

(defn start-reader-loop!
  "Start dedicated background reader for a process.

   Arguments:
     proc-map   - Process map from spawn-process!
     dispatch!  - Function (fn [msg-type msg]) called for each message

   Returns:
     Future that runs the reader loop.

   The reader loop:
   - Continuously reads lines from stdout
   - Parses JSONL
   - Calls dispatch! with message type and parsed message
   - Stops when process exits or alive? is false"
  [proc-map dispatch!]
  (log/log! {:level :info
             :id    ::starting-reader-loop
             :msg   "Starting reader loop"})
  (future
   (try
    (loop []
          (when @(:alive? proc-map)
            (when-let [line (read-line! proc-map)]
                      (let [msg (parse-jsonl line)]
                        (if (:parse-error msg)
                          (do
                           (log/log! {:level :warn
                                      :id    ::parse-error
                                      :msg   "Failed to parse JSONL"
                                      :data  msg})
                           (dispatch! :parse-error msg))
                          (let [msg-type (keyword (:type msg))]
                            (log/log! {:level :debug
                                       :id    ::message-received
                                       :msg   "Received message"
                                       :data  {:type msg-type}})
                            (dispatch! msg-type msg))))
                      (recur))))
    (log/log! {:level :info
               :id    ::reader-loop-ended
               :msg   "Reader loop ended normally"})
    (catch Exception e
           (log/log! {:level :error
                      :id    ::reader-loop-error
                      :msg   "Reader loop error"
                      :error e}))
    (finally
     (reset! (:alive? proc-map) false)))))
