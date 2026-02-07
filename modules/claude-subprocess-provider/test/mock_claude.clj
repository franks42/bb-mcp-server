#!/usr/bin/env bb
;; Mock Claude process for testing claude-manager module.
;;
;; Simulates Claude's stream-json protocol:
;; - Reads JSONL from stdin
;; - Emits system/init, assistant, and result messages
;;
;; Usage:
;;   echo '{"type":"user","message":{"role":"user","content":"Hello"}}' | bb mock_claude.clj
;;
;; Or spawn as subprocess from tests.

(ns mock-claude
    (:require [cheshire.core :as json]
              [com.github.franks42.uuidv7.core :as uuidv7]))

(defn generate-session-id
  "Generate a mock session ID."
  []
  (str "mock-session-" (subs (str (uuidv7/uuidv7)) 0 8)))

(defn handle-message
  "Process incoming message and emit Claude-like response."
  [msg session-id]
  (let [content (get-in msg [:message :content] "")]
    ;; Emit system init (only on first message, but for simplicity emit each time)
    (println (json/generate-string {:type "system"
                                    :subtype "init"
                                    :session_id session-id
                                    :tools []}))
    ;; Emit assistant response
    (println (json/generate-string {:type "assistant"
                                    :message {:content (str "Mock response to: " content)}}))
    ;; Emit result
    (println (json/generate-string {:type "result"
                                    :subtype "success"
                                    :cost_usd 0.0
                                    :duration_ms 1
                                    :duration_api_ms 1}))
    (flush)))

(defn run-mock-loop
  "Main loop - read stdin, emit responses."
  []
  (let [session-id (generate-session-id)]
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
           (try
            (let [msg (json/parse-string line true)]
              (when (= "user" (:type msg))
                (handle-message msg session-id)))
            (catch Exception e
          ;; Emit error for malformed input
                   (println (json/generate-string {:type "error"
                                                   :error {:message (str "Parse error: " (.getMessage e))}}))
                   (flush))))))

;; Run when executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (run-mock-loop))
