(ns streamable-http.sse
    "DEPRECATED: Use http-core.sse directly.

   This namespace re-exports http-core.sse for backwards compatibility.
   New code should require http-core.sse instead."
    (:require [http-core.sse :as core-sse]))

;; Re-export all public vars from http-core.sse
(def format-sse-event core-sse/format-sse-event)
(def format-json-rpc-event core-sse/format-json-rpc-event)
(def sse-headers core-sse/sse-headers)
(def send-event! core-sse/send-event!)
(def send-json-rpc! core-sse/send-json-rpc!)
(def open-stream! core-sse/open-stream!)
(def close-stream! core-sse/close-stream!)
(def send-notification! core-sse/send-notification!)
(def broadcast! core-sse/broadcast!)
