(ns bb-mcp-server.modules.clojure-lsp.jsonrpc
    "JSON-RPC framing for LSP communication.
   LSP uses Content-Length headers for message framing."
    (:require [cheshire.core :as json]
              [clojure.string :as str]))

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
   Returns parsed JSON map, nil on EOF, or {:parse-error ...} on JSON error."
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
                            body (String. buf)]
                        (try
                         (json/parse-string body true)
                         (catch Exception e
                                ;; Return error info instead of crashing
                                {:parse-error {:message (.getMessage e)
                                               :length len
                                               :preview (subs body 0 (min 200 (count body)))}}))))

        ;; Parse header line
            :else
            (let [[k v] (str/split line #": " 2)]
              (recur (assoc headers k v)))))))
