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
   Returns parsed JSON map or nil on EOF."
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
                            buf (char-array len)]
                        (.read in buf 0 len)
                        (json/parse-string (String. buf) true)))

        ;; Parse header line
            :else
            (let [[k v] (str/split line #": " 2)]
              (recur (assoc headers k v)))))))
