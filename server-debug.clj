#!/usr/bin/env bb

;; Debug wrapper for server.clj that logs all I/O

(require '[clojure.java.io :as io])

(def log-file "/tmp/bb-mcp-server-debug.log")

(defn log-msg [prefix msg]
  (spit log-file
        (str (java.time.Instant/now) " " prefix ": " msg "\n")
        :append true))

(log-msg "START" "Debug wrapper started")

;; Start the actual server as a subprocess
(let [pb (ProcessBuilder. ["/opt/homebrew/bin/bb" "server.clj"])
      _ (.directory pb (io/file "/Users/franksiebenlist/Development/bb-mcp-server"))
      process (.start pb)
      stdin (.getOutputStream process)
      stdout (.getInputStream process)
      stderr (.getErrorStream process)]

  ;; Thread to read from our stdin and write to server stdin
  (future
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (log-msg "STDIN" line)
        (.write stdin (.getBytes (str line "\n")))
        (.flush stdin))
      (catch Exception e
        (log-msg "ERROR-IN" (str "Input error: " (.getMessage e))))))

  ;; Thread to read from server stdout and write to our stdout
  (future
    (try
      (doseq [line (line-seq (java.io.BufferedReader. (io/reader stdout)))]
        (log-msg "STDOUT" line)
        (println line)
        (flush))
      (catch Exception e
        (log-msg "ERROR-OUT" (str "Output error: " (.getMessage e))))))

  ;; Thread to read from server stderr and log it
  (future
    (try
      (doseq [line (line-seq (java.io.BufferedReader. (io/reader stderr)))]
        (log-msg "STDERR" line))
      (catch Exception e
        (log-msg "ERROR-ERR" (str "Stderr error: " (.getMessage e))))))

  ;; Wait for process to exit
  (.waitFor process)
  (log-msg "EXIT" (str "Process exited with code: " (.exitValue process))))
