(ns streamable-http.util
    "DEPRECATED: Use http-core.util directly.

   This namespace re-exports http-core.util for backwards compatibility.
   New code should require http-core.util instead."
    (:require [http-core.util :as core-util]))

;; Re-export all public vars from http-core.util
(def generate-json core-util/generate-json)
(def parse-json core-util/parse-json)
(def generate-uuid core-util/generate-uuid)
(def current-time-ms core-util/current-time-ms)
(def elapsed-ms core-util/elapsed-ms)
(def get-header core-util/get-header)
(def accepts? core-util/accepts?)
(def json-rpc-error core-util/json-rpc-error)
(def json-rpc-response core-util/json-rpc-response)
(def json-rpc-error-response core-util/json-rpc-error-response)
