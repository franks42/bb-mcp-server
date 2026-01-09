(ns bb-mcp-server.modules.clojure-lsp.jsonrpc
    "JSON-RPC framing for LSP communication.
   LSP uses Content-Length headers for message framing."
    (:require [cheshire.core :as json]
              [clojure.string :as str]
              [taoensso.trove :as log])
    (:import [java.time Instant]
             [java.io FileWriter]))

;; Debug file for raw LSP data - set via enable-debug-dump!
(defonce ^:private debug-state
         (atom {:enabled? false
                :file-path nil
                :writer nil}))

(defn enable-debug-dump!
  "Enable dumping all raw LSP data to a file for debugging.
   Call with file path to enable, or nil to disable."
  [file-path]
  (when-let [w (:writer @debug-state)]
            (.close ^FileWriter w))
  (if file-path
    (let [writer (FileWriter. ^String file-path true)]
      (reset! debug-state {:enabled? true
                           :file-path file-path
                           :writer writer})
      (log/log! {:level :info
                 :id ::debug-dump-enabled
                 :msg "LSP debug dump enabled"
                 :data {:file-path file-path}}))
    (do
     (reset! debug-state {:enabled? false :file-path nil :writer nil})
     (log/log! {:level :info
                :id ::debug-dump-disabled
                :msg "LSP debug dump disabled"}))))

(defn- dump-raw-data!
  "Dump raw LSP data to debug file if enabled."
  [content-length body]
  (when (:enabled? @debug-state)
    (try
     (let [writer ^FileWriter (:writer @debug-state)
           timestamp (str (Instant/now))
           has-nul? (str/includes? body "\u0000")
           nul-count (count (filter #(= % \u0000) body))]
       (.write writer (str "\n=== " timestamp " ===\n"))
       (.write writer (str "Content-Length: " content-length "\n"))
       (.write writer (str "Actual-Length: " (count body) "\n"))
       (.write writer (str "Has-NUL: " has-nul? " (count: " nul-count ")\n"))
       (.write writer "--- RAW DATA (hex dump of first 500 bytes if NUL present) ---\n")
       (if has-nul?
         (let [preview (take 500 body)
               hex-str (apply str (map #(format "%02x " (int %)) preview))]
           (.write writer hex-str)
           (.write writer "\n--- RAW DATA (escaped) ---\n")
           (.write writer (pr-str (subs body 0 (min 1000 (count body))))))
         (.write writer body))
       (.write writer "\n=== END ===\n\n")
       (.flush writer))
     (catch Exception e
            (log/log! {:level :warn
                       :id ::debug-dump-error
                       :msg "Failed to write debug dump"
                       :data {:error (.getMessage e)}})))))

(defn- strip-nul-bytes
  "Remove NUL bytes from string."
  [^String s]
  (str/replace s "\u0000" ""))

(defn write-message!
  "Write a JSON-RPC message with Content-Length header.
   Message format:
   Content-Length: <length>\\r\\n
   \\r\\n
   <json-body>"
  [^java.io.BufferedWriter out msg]
  (let [body (json/generate-string msg)
        len (count (.getBytes body "UTF-8"))]
    (.write out (str "Content-Length: " len "\r\n\r\n" body))
    (.flush out)))

(defn read-message!
  "Read a JSON-RPC message from input stream.
   Parses Content-Length header and reads exact byte count.
   Returns parsed JSON map, nil on EOF, or {:parse-error ...} on JSON error.

   If NUL bytes are detected, attempts to strip them and re-parse.
   All raw data is dumped to debug file if debug-dump is enabled."
  [^java.io.BufferedReader in]
  (loop [headers {}]
        (let [line (.readLine in)]
          (cond
        ;; EOF
            (nil? line)
            nil

        ;; Empty line = end of headers, read body
            (str/blank? line)
            (when-let [len-str (get headers "Content-Length")]
                      (let [len (parse-long len-str)
                            buf (char-array len)
                            _ (.read in buf 0 len)
                            body (String. buf)
                            has-nul? (str/includes? body "\u0000")
                            nul-count (when has-nul? (count (filter #(= % \u0000) body)))]
                        ;; Dump raw data for debugging
                        (dump-raw-data! len body)

                        ;; Strip NUL bytes BEFORE parsing (proactive fix)
                        ;; Cheshire may return partial results instead of throwing
                        (let [clean-body (if has-nul?
                                           (do
                                            (log/log! {:level :warn
                                                       :id ::nul-bytes-detected
                                                       :msg "NUL bytes detected in LSP response, stripping"
                                                       :data {:content-length len
                                                              :nul-count nul-count
                                                              :stripping true}})
                                            (strip-nul-bytes body))
                                           body)]
                          (try
                           (let [result (json/parse-string clean-body true)]
                             (when has-nul?
                               (log/log! {:level :info
                                          :id ::nul-recovery-success
                                          :msg "Successfully parsed after stripping NUL bytes"
                                          :data {:original-length len
                                                 :cleaned-length (count clean-body)}}))
                             result)
                           (catch Exception e
                                  (log/log! {:level :error
                                             :id ::parse-error
                                             :msg "JSON parse failed"
                                             :data {:error (.getMessage e)
                                                    :length (count clean-body)
                                                    :had-nul-bytes has-nul?}})
                                  {:parse-error {:message (.getMessage e)
                                                 :length len
                                                 :nul-stripped has-nul?
                                                 :preview (subs clean-body 0 (min 200 (count clean-body)))}})))))

        ;; Parse header line
            :else
            (let [[k v] (str/split line #": " 2)]
              (recur (assoc headers k v)))))))