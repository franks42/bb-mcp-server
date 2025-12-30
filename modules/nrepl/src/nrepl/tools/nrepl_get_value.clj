(ns nrepl.tools.nrepl-get-value
    "Get runtime value of a var in connected nREPL runtime.

    Phase 0 introspection tool - works with vanilla clojure.core (no cider-nrepl required).
    Returns the actual runtime value of a var (deref'd if needed)."
    (:require [nrepl.tools.tool-delegation :as delegate]
              [cheshire.core :as json]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful response with var value."
  [symbol-name value-data]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "nrepl-get-value"
                      :symbol symbol-name
                      :type (:type value-data)
                      :value (:value value-data)
                      :value-str (:value-str value-data)}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     (merge {:status "error"
                             :operation "nrepl-get-value"
                             :error error-message}
                            details)
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Get runtime value of a var in connected nREPL runtime.

   Returns:
   - :type - the type of the value (class name)
   - :value - the value itself (if serializable to JSON)
   - :value-str - string representation via pr-str

   For functions, returns metadata about the function rather than the function object.
   For large collections, truncates to max-length (default 100 items).

   WARNING: This evaluates @(resolve 'symbol) which dereferences vars.
   Be careful with vars that have expensive computations or side effects."
  [{:keys [symbol connection timeout max-length] :or {timeout 30000 max-length 100}}]

  (cond
    ;; Validation
    (empty? symbol)
    (format-error-response "Symbol required" {:hint "Specify 'symbol' parameter (e.g., 'my.app.config/settings', 'clojure.core/*ns*')"})

    :else
    (let [;; Code to get var value with type info and truncation
          ;; Handles functions specially (returns metadata, not the fn object)
          code (format "
(when-let [v (resolve '%s)]
  (let [val @v
        t (type val)
        type-name (if t (pr-str t) \"nil\")]
    (cond
      ;; Functions - return signature info instead of object
      (fn? val)
      {:type type-name
       :value-str \"<function>\"
       :value {:fn true
               :arglists (mapv str (:arglists (meta v)))
               :name (str (:name (meta v)))
               :ns (str (:ns (meta v)))}}

      ;; Large sequences - truncate
      (and (sequential? val) (> (count val) %d))
      {:type type-name
       :value-str (str \"<\" (count val) \" items, showing first %d>\")
       :value (vec (take %d val))
       :truncated true
       :total-count (count val)}

      ;; Large maps - truncate
      (and (map? val) (> (count val) %d))
      {:type type-name
       :value-str (str \"<\" (count val) \" entries, showing first %d>\")
       :value (into {} (take %d val))
       :truncated true
       :total-count (count val)}

      ;; Large sets - truncate
      (and (set? val) (> (count val) %d))
      {:type type-name
       :value-str (str \"<\" (count val) \" items, showing first %d>\")
       :value (vec (take %d val))
       :truncated true
       :total-count (count val)}

      ;; Regular values - return as-is with pr-str
      :else
      {:type type-name
       :value-str (pr-str val)
       :value val})))"
                       symbol
                       max-length max-length max-length  ; sequential
                       max-length max-length max-length  ; map
                       max-length max-length max-length) ; set

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
                        (format-error-response "Failed to parse var value"
                                               {:raw-value value-str
                                                :parse-error (.getMessage e)})))))))
        ;; Pass through error
        result))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "nrepl-get-value")

(def metadata
     "MCP tool metadata for nrepl-get-value."
     {:description "Get runtime value of a var. Returns the actual value (not just metadata) with type info. Large collections are truncated. Functions return their signature instead of the function object. WARNING: Dereferences the var which may trigger computation. Works with vanilla Clojure - no cider-nrepl required."
      :inputSchema {:type "object"
                    :properties {:symbol {:type "string"
                                          :description "Fully qualified symbol (e.g., 'my.app.config/settings') or simple symbol resolved in current ns"}
                                 :max-length {:type "integer"
                                              :description "Maximum items to return for large collections (default: 100)"
                                              :minimum 1
                                              :maximum 10000}
                                 :connection {:type "string"
                                              :description "Connection identifier (nickname, connection-id, or host:port). Optional - uses active connection if not specified."}
                                 :timeout {:type "integer"
                                           :description "Timeout in milliseconds (default: 30000)"
                                           :minimum 1000
                                           :maximum 300000}}
                    :required ["symbol"]}})
