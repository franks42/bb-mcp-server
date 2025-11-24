(ns streamable-http.util
    "DEPRECATED: Use http-core.util directly.

   This namespace re-exports http-core.util for backwards compatibility.
   New code should require http-core.util instead."
    (:require [http-core.util :as core-util]))

;; Re-export all public vars from http-core.util
(def generate-json "See http-core.util/generate-json" core-util/generate-json)
(def parse-json "See http-core.util/parse-json" core-util/parse-json)
(def generate-uuid "See http-core.util/generate-uuid" core-util/generate-uuid)
(def current-time-ms "See http-core.util/current-time-ms" core-util/current-time-ms)
(def elapsed-ms "See http-core.util/elapsed-ms" core-util/elapsed-ms)
(def get-header "See http-core.util/get-header" core-util/get-header)
(def accepts? "See http-core.util/accepts?" core-util/accepts?)
(def json-rpc-error "See http-core.util/json-rpc-error" core-util/json-rpc-error)
(def json-rpc-response "See http-core.util/json-rpc-response" core-util/json-rpc-response)
(def json-rpc-error-response "See http-core.util/json-rpc-error-response" core-util/json-rpc-error-response)
