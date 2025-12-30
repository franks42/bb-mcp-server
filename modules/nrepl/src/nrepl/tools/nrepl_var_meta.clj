(ns nrepl.tools.nrepl-var-meta
    "Get metadata for a var in connected nREPL runtime.

    Phase 0 introspection tool - works with vanilla clojure.core (no cider-nrepl required).
    Returns arglists, doc, file, line, macro, dynamic, private, etc."
    (:require [nrepl.tools.tool-delegation :as delegate]
              [cheshire.core :as json]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful response with var metadata."
  [symbol-name metadata-map]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "nrepl-var-meta"
                      :symbol symbol-name
                      :metadata metadata-map}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     (merge {:status "error"
                             :operation "nrepl-var-meta"
                             :error error-message}
                            details)
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Get metadata for a var in connected nREPL runtime.

   Returns var metadata including:
   - :name, :ns - identity
   - :arglists - function signatures
   - :doc - docstring
   - :file, :line, :column - source location
   - :macro, :dynamic, :private - var properties
   - :added, :deprecated - version info

   Symbol can be fully qualified (my.ns/foo) or simple (foo, resolved in current ns)."
  [{:keys [symbol connection timeout] :or {timeout 30000}}]

  (cond
    ;; Validation
    (empty? symbol)
    (format-error-response "Symbol required" {:hint "Specify 'symbol' parameter (e.g., 'clojure.core/map', 'my.app/handler')"})

    :else
    (let [;; Code to get var metadata
          ;; Converts metadata to JSON-friendly format (stringify keys, handle non-serializable values)
          code (format "
(when-let [v (resolve '%s)]
  (let [m (meta v)]
    (-> m
        ;; Convert arglists to strings (they contain symbols)
        (update :arglists (fn [al] (when al (mapv str al))))
        ;; Convert ns to string
        (update :ns (fn [n] (when n (str n))))
        ;; Convert name to string
        (update :name (fn [n] (when n (str n))))
        ;; Remove non-serializable keys
        (dissoc :test))))"
                       symbol)

          ;; Delegate to nrepl-eval
          result (delegate/call-async-tool "nrepl.nrepl-eval"
                                           (cond-> {:code code :timeout timeout}
                                                   connection (assoc :connection connection)))]

      (if (delegate/is-success-result? result)
        ;; Extract and format
        (let [value-parsed (delegate/extract-result-data result :value-parsed)]
          (cond
            ;; nil means var not found
            (nil? value-parsed)
            (format-error-response "Var not found"
                                   {:symbol symbol
                                    :hint "Check spelling and namespace. Use nrepl-introspect-ns to see available vars."})

            ;; Valid response
            (map? value-parsed)
            (format-success-response symbol value-parsed)

            ;; Unexpected format - try parsing from string
            :else
            (let [value-str (delegate/extract-result-data result :value)]
              (if (= value-str "nil")
                (format-error-response "Var not found"
                                       {:symbol symbol
                                        :hint "Check spelling and namespace. Use nrepl-introspect-ns to see available vars."})
                (try
                 (let [parsed (read-string value-str)]
                   (format-success-response symbol parsed))
                 (catch Exception e
                        (format-error-response "Failed to parse var metadata"
                                               {:raw-value value-str
                                                :parse-error (.getMessage e)})))))))
        ;; Pass through error
        result))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "nrepl-var-meta")

(def metadata
     "MCP tool metadata for nrepl-var-meta."
     {:description "Get metadata for a var (function, macro, def). Returns arglists, docstring, file/line location, and properties (macro, dynamic, private). Use to inspect runtime state of a specific symbol. Works with vanilla Clojure - no cider-nrepl required."
      :inputSchema {:type "object"
                    :properties {:symbol {:type "string"
                                          :description "Fully qualified symbol (e.g., 'clojure.core/map') or simple symbol resolved in current ns"}
                                 :connection {:type "string"
                                              :description "Connection identifier (nickname, connection-id, or host:port). Optional - uses active connection if not specified."}
                                 :timeout {:type "integer"
                                           :description "Timeout in milliseconds (default: 30000)"
                                           :minimum 1000
                                           :maximum 300000}}
                    :required ["symbol"]}})
