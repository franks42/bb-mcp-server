(ns mcp-nrepl.tools.nrepl-loaded-namespaces
    "List all loaded namespaces in connected nREPL runtime.

    Phase 0 introspection tool - works with vanilla clojure.core (no cider-nrepl required).
    Provides immediate value for AI agents to verify runtime state."
    (:require [mcp-nrepl.tools.tool-delegation :as delegate]
              [cheshire.core :as json]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful response with list of namespaces."
  [namespaces]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "nrepl-loaded-namespaces"
                      :count (count namespaces)
                      :namespaces namespaces}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     (merge {:status "error"
                             :operation "nrepl-loaded-namespaces"
                             :error error-message}
                            details)
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "List all loaded namespaces in connected nREPL runtime.

   Returns sorted list of namespace names as strings.
   Optionally filters by prefix (e.g., 'my.app' to see only your app's namespaces)."
  [{:keys [connection prefix timeout] :or {timeout 30000}}]

  (let [;; Code to evaluate - gets all namespaces, converts to sorted strings
        code (if prefix
               (format "(vec (sort (filter #(clojure.string/starts-with? %% \"%s\") (map str (all-ns)))))"
                       prefix)
               "(vec (sort (map str (all-ns))))")

        ;; Delegate to nrepl-eval
        result (delegate/call-async-tool "mcp-nrepl.nrepl-eval"
                                         (cond-> {:code code :timeout timeout}
                                                 connection (assoc :connection connection)))]

    (if (delegate/is-success-result? result)
      ;; Extract the value-parsed field which contains the parsed EDN
      (let [value-parsed (delegate/extract-result-data result :value-parsed)]
        (if (vector? value-parsed)
          (format-success-response value-parsed)
          ;; Fallback: parse from :value string
          (let [value-str (delegate/extract-result-data result :value)]
            (try
             (format-success-response (read-string value-str))
             (catch Exception e
                    (format-error-response "Failed to parse namespace list"
                                           {:raw-value value-str
                                            :parse-error (.getMessage e)}))))))
      ;; Pass through error
      result)))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "nrepl-loaded-namespaces")

(def metadata
     "MCP tool metadata for nrepl-loaded-namespaces."
     {:description "List all loaded namespaces in the connected nREPL runtime. Returns sorted namespace names. Use prefix filter to see only relevant namespaces (e.g., prefix='my.app'). Works with vanilla Clojure - no cider-nrepl required."
      :inputSchema {:type "object"
                    :properties {:connection {:type "string"
                                              :description "Connection identifier (nickname, connection-id, or host:port). Optional - uses active connection if not specified."}
                                 :prefix {:type "string"
                                          :description "Optional prefix to filter namespaces (e.g., 'my.app' returns only namespaces starting with 'my.app')"}
                                 :timeout {:type "integer"
                                           :description "Timeout in milliseconds (default: 30000)"
                                           :minimum 1000
                                           :maximum 300000}}}})
