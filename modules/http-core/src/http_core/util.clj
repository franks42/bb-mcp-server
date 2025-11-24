(ns http-core.util
    "Utility functions for HTTP transport.

   JSON helpers, UUID generation, and common utilities."
    (:require [cheshire.core :as json]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; =============================================================================
;; JSON Helpers
;; =============================================================================

(defn parse-json
  "Parse JSON string to Clojure data with keyword keys.
   Arrays are converted to vectors for (vector? ...) checks.
   Returns nil on parse failure."
  [s]
  (when s
    (try
     (let [parsed (json/parse-string s true)]
        ;; Convert lazy seqs to vectors for array detection
       (if (and (sequential? parsed) (not (vector? parsed)))
         (vec parsed)
         parsed))
     (catch Exception e
            (log/log! {:level :warn
                       :id    ::parse-json-failed
                       :msg   "Failed to parse JSON"
                       :error e
                       :data  {:input-length (count s)}})
            nil))))

(defn generate-json
  "Generate JSON string from Clojure data.
   Returns nil on generation failure."
  [data]
  (try
   (json/generate-string data)
   (catch Exception e
          (log/log! {:level :warn
                     :id    ::generate-json-failed
                     :msg   "Failed to generate JSON"
                     :error e
                     :data  {:data-type (type data)}})
          nil)))

;; =============================================================================
;; UUID Generation
;; =============================================================================

(defn generate-uuid
  "Generate a random UUID string."
  []
  (str (java.util.UUID/randomUUID)))

;; =============================================================================
;; Time Utilities
;; =============================================================================

(defn current-time-ms
  "Current time in milliseconds since epoch."
  []
  (System/currentTimeMillis))

(defn elapsed-ms
  "Calculate elapsed time in ms since start-time."
  [start-time]
  (- (current-time-ms) start-time))

;; =============================================================================
;; Header Utilities
;; =============================================================================

(defn get-header
  "Get header value from request, case-insensitive."
  [request header-name]
  (get-in request [:headers (str/lower-case header-name)]))

(defn accepts?
  "Check if request accepts a given content type.
   Also returns true if Accept header is */*."
  [request content-type]
  (when-let [accept (get-header request "accept")]
            (or (str/includes? accept "*/*")
                (str/includes? accept content-type))))

;; =============================================================================
;; JSON-RPC Helpers
;; =============================================================================

(defn json-rpc-error
  "Create a JSON-RPC 2.0 error response map."
  [{:keys [id code message data]}]
  (cond-> {:jsonrpc "2.0"
           :error {:code code
                   :message message}
           :id id}
          data (assoc-in [:error :data] data)))

(defn json-rpc-response
  "Create a JSON-RPC 2.0 success response map."
  [{:keys [id result]}]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn json-rpc-error-response
  "Create an HTTP response with JSON-RPC error."
  [{:keys [status id code message data]}]
  {:status (or status 400)
   :headers {"Content-Type" "application/json"}
   :body (generate-json (json-rpc-error {:id id
                                         :code code
                                         :message message
                                         :data data}))})
