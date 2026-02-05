(ns mcp-nrepl.tools.nrepl-introspect-ns
    "Introspect vars in a namespace in connected nREPL runtime.

    Phase 0 introspection tool - works with vanilla clojure.core (no cider-nrepl required).
    Provides immediate value for AI agents to verify what's loaded in a namespace."
    (:require [mcp-nrepl.tools.tool-delegation :as delegate]
              [cheshire.core :as json]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful response with namespace introspection data."
  [ns-name data]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "nrepl-introspect-ns"
                      :namespace ns-name
                      :publics (:publics data)
                      :public-count (count (:publics data))
                      :interns (:interns data)
                      :intern-count (count (:interns data))
                      :private-count (- (count (:interns data)) (count (:publics data)))}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     (merge {:status "error"
                             :operation "nrepl-introspect-ns"
                             :error error-message}
                            details)
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Introspect a namespace in connected nREPL runtime.

   Returns:
   - publics: list of public var names (exposed via ns-publics)
   - interns: list of all interned var names (includes private vars)
   - counts for each category

   Note: interns includes both public and private vars.
   Private vars = interns - publics."
  [{:keys [ns connection timeout include-meta] :or {timeout 30000}}]

  (cond
    ;; Validation
    (empty? ns)
    (format-error-response "Namespace required" {:hint "Specify 'ns' parameter (e.g., 'clojure.core', 'my.app.core')"})

    :else
    (let [;; Code to introspect namespace
          ;; Returns map with :publics and :interns as sorted vectors of symbol names
          code (if include-meta
                 ;; Include basic metadata for each var
                 (format "
(when-let [n (find-ns '%s)]
  {:publics (vec (sort (map (fn [[k v]] {:name (str k) :meta (select-keys (meta v) [:arglists :doc :macro :dynamic])}) (ns-publics n))))
   :interns (vec (sort (map (fn [[k v]] {:name (str k) :meta (select-keys (meta v) [:arglists :doc :macro :dynamic :private])}) (ns-interns n))))})"
                         ns)
                 ;; Simple list of names only
                 (format "
(when-let [n (find-ns '%s)]
  {:publics (vec (sort (map str (keys (ns-publics n)))))
   :interns (vec (sort (map str (keys (ns-interns n)))))})"
                         ns))

          ;; Delegate to nrepl-eval
          result (delegate/call-async-tool "mcp-nrepl.nrepl-eval"
                                           (cond-> {:code code :timeout timeout}
                                                   connection (assoc :connection connection)))]

      (if (delegate/is-success-result? result)
        ;; Extract and format
        (let [value-parsed (delegate/extract-result-data result :value-parsed)]
          (cond
            ;; nil means namespace not found
            (nil? value-parsed)
            (format-error-response "Namespace not found"
                                   {:namespace ns
                                    :hint "Use nrepl-loaded-namespaces to see available namespaces"})

            ;; Valid response
            (map? value-parsed)
            (format-success-response ns value-parsed)

            ;; Unexpected format - try parsing from string
            :else
            (let [value-str (delegate/extract-result-data result :value)]
              (if (= value-str "nil")
                (format-error-response "Namespace not found"
                                       {:namespace ns
                                        :hint "Use nrepl-loaded-namespaces to see available namespaces"})
                (try
                 (let [parsed (read-string value-str)]
                   (format-success-response ns parsed))
                 (catch Exception e
                        (format-error-response "Failed to parse introspection result"
                                               {:raw-value value-str
                                                :parse-error (.getMessage e)})))))))
        ;; Pass through error
        result))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "nrepl-introspect-ns")

(def metadata
     "MCP tool metadata for nrepl-introspect-ns."
     {:description "List all vars in a namespace. Returns public vars (via ns-publics) and all interned vars (via ns-interns, includes private). Use to verify what's actually loaded vs what static analysis sees. Works with vanilla Clojure - no cider-nrepl required."
      :inputSchema {:type "object"
                    :properties {:ns {:type "string"
                                      :description "Namespace to introspect (e.g., 'clojure.core', 'my.app.core')"}
                                 :include-meta {:type "boolean"
                                                :description "Include basic metadata (arglists, doc, macro, dynamic, private) for each var. Default: false"}
                                 :connection {:type "string"
                                              :description "Connection identifier (nickname, connection-id, or host:port). Optional - uses active connection if not specified."}
                                 :timeout {:type "integer"
                                           :description "Timeout in milliseconds (default: 30000)"
                                           :minimum 1000
                                           :maximum 300000}}
                    :required ["ns"]}})
