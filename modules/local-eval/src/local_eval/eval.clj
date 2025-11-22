(ns local-eval.eval
    "Local eval handler - execute code within MCP server runtime."
    (:require [cheshire.core :as json]))

;; =============================================================================
;; Base64 Utilities
;; =============================================================================

(defn- decode-base64
  "Decode base64 string to UTF-8 text."
  [b64-str]
  (String. (.decode (java.util.Base64/getDecoder) b64-str) "UTF-8"))

(defn- encode-base64
  "Encode UTF-8 text to base64 string."
  [text]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes text "UTF-8")))

;; =============================================================================
;; Result Serialization
;; =============================================================================

(defn- serialize-result
  "Convert result to serializable format."
  [result]
  (cond
    (nil? result) nil
    (string? result) result
    (or (number? result)
        (keyword? result)
        (boolean? result)) result
    (or (map? result)
        (vector? result)
        (list? result)
        (set? result)) (pr-str result)
    (instance? clojure.lang.Atom result)
    (str "#atom " (pr-str @result))
    :else (str "#" (.getName (class result)) " " result)))

;; =============================================================================
;; Handler
;; =============================================================================

(defn handle
  "Evaluate Clojure code within the MCP server runtime with base64 support.

   Parameters:
   - code: Clojure code to evaluate
   - input-base64: If true, decode code from base64 first
   - output-base64: If true, encode result fields as base64

   Returns MCP-formatted response with evaluation result."
  [{:keys [code input-base64 output-base64]}]
  (cond
    ;; Validation: code is required
    (empty? code)
    {:content [{:type "text"
                :text (json/generate-string
                       {:status "error"
                        :error "No code provided - specify 'code' parameter"}
                       {:pretty true})}]
     :isError true}

    ;; Process the code
    :else
    (let [;; Determine actual code to execute
          actual-code (if input-base64
                        (try
                         (decode-base64 code)
                         (catch Exception _e
                                ::base64-decode-failed))
                        code)]
      (if (= actual-code ::base64-decode-failed)
        {:content [{:type "text"
                    :text (json/generate-string
                           {:status "error"
                            :error "Failed to decode base64 code"}
                           {:pretty true})}]
         :isError true}
        (if (empty? actual-code)
          {:content [{:type "text"
                      :text (json/generate-string
                             {:status "error"
                              :error "Decoded code is empty"}
                             {:pretty true})}]
           :isError true}
          (try
            ;; Use with-out-str to capture stdout from println statements
           (let [result-atom (atom nil)
                 stdout-capture (with-out-str
                                 (let [result (eval (read-string actual-code))]
                                   (reset! result-atom result)))
                 result @result-atom
                 serializable-result (serialize-result result)
                  ;; Build base response
                 base-response {:status "success"
                                :code actual-code
                                :result serializable-result
                                :result-type (.getName (class result))
                                :stdout stdout-capture
                                :stderr ""}
                  ;; Add base64 encoding if requested
                 final-response (if output-base64
                                  (cond-> base-response
                                          serializable-result
                                          (assoc :result-base64 (encode-base64 (str serializable-result)))
                                          (not-empty stdout-capture)
                                          (assoc :stdout-base64 (encode-base64 stdout-capture)))
                                  base-response)]
             {:content [{:type "text"
                         :text (json/generate-string final-response {:pretty true})}]})
           (catch Exception e
                  (let [error-response {:status "error"
                                        :code actual-code
                                        :error (.getMessage e)
                                        :stdout ""
                                        :stderr ""
                                        :stacktrace (mapv str (.getStackTrace e))}
                        final-error (if output-base64
                                      (assoc error-response
                                             :error-base64 (encode-base64 (.getMessage e)))
                                      error-response)]
                    {:content [{:type "text"
                                :text (json/generate-string final-error {:pretty true})}]
                     :isError true}))))))))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-name "local-eval")

(def tool-definition
     {:name tool-name
      :description "Execute Clojure code within MCP server runtime for introspection and debugging.
Uses native Babashka/Clojure eval - ideal for server state inspection, debugging, and code execution.

**Parameters:**
- code: Clojure code to evaluate
- input-base64: Interpret code as base64-encoded (default: false)
- output-base64: Return results as base64 (default: false)

**Examples:**
  (+ 1 2 3)           => 6
  (System/getenv)     => environment variables
  (keys (ns-publics 'clojure.core)) => public vars

**Use Base64 for complex code with special characters.**"
      :inputSchema {:type "object"
                    :properties {:code {:type "string"
                                        :description "Clojure code to evaluate"}
                                 :input-base64 {:type "boolean"
                                                :description "Interpret code as base64-encoded (default: false)"}
                                 :output-base64 {:type "boolean"
                                                 :description "Return results as base64 (default: false)"}}
                    :required ["code"]}
      :handler handle})
