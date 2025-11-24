(ns streamable-http.sse
    "DEPRECATED: Use http-core.sse directly.

   This namespace re-exports http-core.sse for backwards compatibility.
   New code should require http-core.sse instead."
    (:require [http-core.sse :as core-sse]))

;; Re-export all public vars from http-core.sse
(def format-sse-event "See http-core.sse/format-sse-event" core-sse/format-sse-event)
(def format-json-rpc-event "See http-core.sse/format-json-rpc-event" core-sse/format-json-rpc-event)
(def sse-headers "See http-core.sse/sse-headers" core-sse/sse-headers)
(def send-event! "See http-core.sse/send-event!" core-sse/send-event!)
(def send-json-rpc! "See http-core.sse/send-json-rpc!" core-sse/send-json-rpc!)
(def open-stream! "See http-core.sse/open-stream!" core-sse/open-stream!)
(def close-stream! "See http-core.sse/close-stream!" core-sse/close-stream!)
(def send-notification! "See http-core.sse/send-notification!" core-sse/send-notification!)
(def broadcast! "See http-core.sse/broadcast!" core-sse/broadcast!)
