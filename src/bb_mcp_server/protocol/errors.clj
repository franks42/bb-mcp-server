(ns bb-mcp-server.protocol.errors
    "Centralized error handling for MCP server.

  Provides:
  - Error type taxonomy with structured data
  - JSON Schema to Malli conversion
  - Argument validation with detailed errors
  - Exception formatting utilities
  - Stack trace simplification"
    (:require [clojure.string :as str]
              [malli.core :as m]
              [malli.error :as me]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Error Codes
;; -----------------------------------------------------------------------------

(def error-codes
     "JSON-RPC 2.0 and MCP error codes."
     {;; Standard JSON-RPC errors
      :parse-error -32700
      :invalid-request -32600
      :method-not-found -32601
      :invalid-params -32602
      :internal-error -32603
      ;; MCP custom errors
      :tool-not-found -32000
      :tool-execution-failed -32001
      :invalid-tool-params -32002
      :server-not-initialized -32003
      :schema-validation-failed -32004
      :configuration-error -32005})

;; -----------------------------------------------------------------------------
;; Error Types
;; -----------------------------------------------------------------------------

(def error-types
     "Error type keywords for categorization."
     #{:parse-error
       :protocol-error
       :validation-error
       :not-found
       :runtime-error
       :internal-error
       :config-error})

;; -----------------------------------------------------------------------------
;; JSON Schema to Malli Conversion
;; -----------------------------------------------------------------------------

(declare json-schema->malli)

(defn- json-type->malli
  "Convert a JSON Schema type definition to Malli schema."
  [json-schema]
  (let [{:keys [type items enum]} json-schema]
    (cond
      ;; Enum type
      enum
      (into [:enum] enum)

      ;; Array type
      (= type "array")
      (if items
        [:vector (json-type->malli items)]
        [:vector :any])

      ;; Object type
      (= type "object")
      (json-schema->malli json-schema)

      ;; Primitive types
      (= type "string") :string
      (= type "number") [:or :int :double]
      (= type "integer") :int
      (= type "boolean") :boolean
      (= type "null") :nil

      ;; Multiple types
      (vector? type)
      (into [:or] (map #(json-type->malli {:type %}) type))

      ;; Fallback
      :else :any)))

(defn json-schema->malli
  "Convert JSON Schema object to Malli schema.

  Supports:
  - type: object, string, number, integer, boolean, array, null
  - properties: object properties with nested schemas
  - required: list of required property names
  - items: array item schema
  - enum: enumerated values

  Args:
    json-schema - JSON Schema map with :type, :properties, :required, etc.

  Returns: Malli schema form"
  [json-schema]
  (let [{:keys [type properties required]} json-schema
        required-set (set required)]
    (if (= type "object")
      (let [prop-schemas
            (for [[k v] properties]
              (let [key-name (if (keyword? k) k (keyword k))
                    prop-schema (json-type->malli v)
                    optional? (not (contains? required-set (name k)))]
                (if optional?
                  [key-name {:optional true} prop-schema]
                  [key-name prop-schema])))]
        (into [:map] prop-schemas))
      ;; Non-object schema
      (json-type->malli json-schema))))

;; -----------------------------------------------------------------------------
;; Argument Validation
;; -----------------------------------------------------------------------------

(defn validate-arguments
  "Validate tool arguments against JSON Schema using Malli.

  Args:
    arguments   - Map of argument key-value pairs
    json-schema - JSON Schema for the tool's inputSchema
    tool-name   - Name of the tool (for error context)

  Returns: Map with either:
    {:valid true :arguments arguments}
    {:valid false :errors humanized-errors :tool tool-name}

  Does not throw - returns validation result for caller to handle."
  [arguments json-schema tool-name]
  (log/log! {:level :debug
             :id ::validate-arguments-start
             :msg "Validating arguments"
             :data {:tool tool-name :argument-keys (keys arguments)}})
  (try
    (let [malli-schema (json-schema->malli json-schema)
          ;; Convert string keys to keywords for validation
          kw-arguments (reduce-kv (fn [m k v]
                                    (assoc m (if (string? k) (keyword k) k) v))
                                  {}
                                  arguments)]
      (if (m/validate malli-schema kw-arguments)
        (do
          (log/log! {:level :debug
                     :id ::validation-success
                     :msg "Arguments valid"
                     :data {:tool tool-name}})
          {:valid true :arguments arguments})
        (let [explanation (m/explain malli-schema kw-arguments)
              humanized (me/humanize explanation)]
          (log/log! {:level :warn
                     :id ::validation-failed
                     :msg "Argument validation failed"
                     :data {:tool tool-name :errors humanized}})
          {:valid false
           :errors humanized
           :tool tool-name
           :schema malli-schema})))
    (catch Exception e
      (log/log! {:level :error
                 :id ::validation-exception
                 :msg "Exception during validation"
                 :error e
                 :data {:tool tool-name}})
      {:valid false
       :errors {:_validation "Schema conversion failed"}
       :tool tool-name
       :exception (ex-message e)})))

(defn validate-arguments!
  "Validate tool arguments, throwing on failure.

  Same as validate-arguments but throws ex-info on failure.

  Throws: ex-info with :type :validation-error on failure"
  [arguments json-schema tool-name]
  (let [result (validate-arguments arguments json-schema tool-name)]
    (when-not (:valid result)
      (throw (ex-info "Argument validation failed"
                      {:type :validation-error
                       :code (:invalid-tool-params error-codes)
                       :tool tool-name
                       :validation-errors (:errors result)
                       :arguments (keys arguments)})))
    arguments))

;; -----------------------------------------------------------------------------
;; Stack Trace Utilities
;; -----------------------------------------------------------------------------

(defn simplify-stack-trace
  "Extract simplified stack trace from exception.

  Filters to bb_mcp_server frames and takes top N.

  Args:
    e     - Exception
    limit - Max frames to return (default 5)

  Returns: Vector of strings like [\"ns.name:42\" ...]"
  ([^Throwable e] (simplify-stack-trace e 5))
  ([^Throwable e limit]
   (when e
     (->> (.getStackTrace e)
          (filter #(str/starts-with? (.getClassName ^StackTraceElement %) "bb_mcp_server"))
          (take limit)
          (mapv #(str (str/replace (.getClassName ^StackTraceElement %) "_" "-")
                      ":" (.getLineNumber ^StackTraceElement %)))))))

;; -----------------------------------------------------------------------------
;; Error Data Formatting
;; -----------------------------------------------------------------------------

(defn format-error-data
  "Format error data for JSON-RPC error response.

  Ensures consistent structure and includes available context.

  Args:
    error-info - Map with error details (:type, :tool, :cause, etc.)

  Returns: Map suitable for :data field of JSON-RPC error"
  [error-info]
  (let [{:keys [type tool cause validation-errors arguments
                expected actual exception]} error-info]
    (cond-> {}
            type (assoc :type type)
            tool (assoc :tool tool)
            cause (assoc :cause cause)
            validation-errors (assoc :validation-errors validation-errors)
            arguments (assoc :arguments arguments)
            expected (assoc :expected expected)
            actual (assoc :actual actual)
            exception (assoc :trace (simplify-stack-trace exception)))))

(defn ex-info->error-data
  "Extract error data from ex-info exception.

  Args:
    e - ExceptionInfo

  Returns: Formatted error data map"
  [^clojure.lang.ExceptionInfo e]
  (let [data (ex-data e)
        cause (ex-message e)]
    (format-error-data (assoc data
                              :cause cause
                              :exception e))))

(defn exception->error-data
  "Convert any exception to error data.

  Args:
    e         - Exception
    context   - Optional context map to merge

  Returns: Error data map"
  [^Throwable e context]
  (format-error-data (merge {:type :runtime-error
                             :cause (ex-message e)
                             :exception e}
                            context)))

;; -----------------------------------------------------------------------------
;; Error Logging
;; -----------------------------------------------------------------------------

(defn log-error!
  "Log error with structured context.

  Args:
    error-type - Keyword error type
    message    - Human-readable message
    data       - Additional context data"
  [error-type message data]
  (log/log! {:level :error
             :id (keyword "bb-mcp-server.errors" (name error-type))
             :msg message
             :data (dissoc data :exception)
             :error (:exception data)}))
