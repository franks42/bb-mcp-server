# Error Handling Design

## Overview

Robust error handling for bb-mcp-server MCP implementation. Provides:
- Categorized error types with structured data
- Full Malli validation for tool arguments
- Exception middleware for consistent error formatting
- Detailed error context for debugging

---

## Error Taxonomy

### JSON-RPC Standard Errors (-32700 to -32600)

| Code | Name | Description |
|------|------|-------------|
| -32700 | Parse Error | Invalid JSON received |
| -32600 | Invalid Request | JSON not a valid JSON-RPC request |
| -32601 | Method Not Found | Method does not exist |
| -32602 | Invalid Params | Invalid method parameters |
| -32603 | Internal Error | Internal JSON-RPC error |

### MCP Custom Errors (-32000 to -32099)

| Code | Name | Description |
|------|------|-------------|
| -32000 | Tool Not Found | Requested tool not in registry |
| -32001 | Tool Execution Failed | Tool handler threw exception |
| -32002 | Invalid Tool Params | Tool arguments failed validation |
| -32003 | Server Not Initialized | Called method before initialize |
| -32004 | Schema Validation Failed | Malli schema validation error |
| -32005 | Configuration Error | Server configuration invalid |

---

## Error Response Structure

```clojure
;; JSON-RPC 2.0 error response
{:jsonrpc "2.0"
 :id 1
 :error {:code -32001
         :message "Tool execution failed"
         :data {:tool "hello"
                :type :runtime-error
                :cause "Invalid input"
                :trace ["hello.core:42"]}}}
```

### Error Data Fields

| Field | Type | Description |
|-------|------|-------------|
| `:tool` | string | Tool name (if applicable) |
| `:type` | keyword | Error category (see below) |
| `:cause` | string | Root cause message |
| `:trace` | vector | Simplified stack trace (top 5) |
| `:validation-errors` | map | Malli humanized errors |
| `:expected` | any | What was expected |
| `:actual` | any | What was received |

### Error Types (`:type` field)

| Type | Description |
|------|-------------|
| `:parse-error` | JSON parsing failed |
| `:protocol-error` | JSON-RPC structure invalid |
| `:validation-error` | Schema validation failed |
| `:not-found` | Resource (tool, method) not found |
| `:runtime-error` | Exception during execution |
| `:internal-error` | Unexpected server error |
| `:config-error` | Configuration issue |

---

## Malli Argument Validation

### Current State (Phase 2.1)

Basic required-field checking only:

```clojure
;; tools_call.clj - validate-arguments
(defn- validate-arguments [arguments input-schema]
  (let [required-fields (get input-schema :required [])]
    (every? #(contains? arguments %) required-fields)))
```

### Enhanced State (Phase 2.2)

Full Malli schema validation:

```clojure
;; Convert JSON Schema to Malli at registration time
(defn- json-schema->malli [json-schema]
  (let [{:keys [type properties required]} json-schema]
    (cond
      (= type "object")
      (into [:map]
            (for [[k v] properties]
              (let [prop-schema (json-type->malli v)
                    optional? (not (contains? (set required) (name k)))]
                (if optional?
                  [k {:optional true} prop-schema]
                  [k prop-schema]))))

      (= type "string") :string
      (= type "number") :number
      (= type "integer") :int
      (= type "boolean") :boolean
      (= type "array") [:vector :any]
      :else :any)))

;; Validate at call time with helpful errors
(defn validate-arguments! [arguments malli-schema tool-name]
  (when-not (m/validate malli-schema arguments)
    (let [explanation (m/explain malli-schema arguments)
          humanized (me/humanize explanation)]
      (throw (ex-info "Argument validation failed"
                      {:type :validation-error
                       :tool tool-name
                       :validation-errors humanized
                       :arguments (keys arguments)})))))
```

---

## Exception Middleware

Wrap all handlers with exception catching:

```clojure
(defn wrap-exception-handler
  "Middleware that catches exceptions and formats them as error responses."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        ;; Structured exception - extract data
        (let [{:keys [type] :as data} (ex-data e)]
          (format-error-response request data)))
      (catch Exception e
        ;; Unexpected exception - wrap with context
        (format-internal-error-response request e)))))
```

---

## Error Context Enhancement

### Telemetry Integration

All errors logged with structured context:

```clojure
(defn log-error! [error-data]
  (log/log! {:level :error
             :id (keyword "bb-mcp-server" (name (:type error-data)))
             :msg (:message error-data)
             :data (dissoc error-data :trace)
             :error (when-let [cause (:cause error-data)]
                     (ex-info cause error-data))}))
```

### Stack Trace Simplification

Filter internal frames, keep only relevant ones:

```clojure
(defn simplify-stack-trace [^Throwable e]
  (->> (.getStackTrace e)
       (filter #(str/starts-with? (.getClassName %) "bb_mcp_server"))
       (take 5)
       (mapv #(str (.getClassName %) ":" (.getLineNumber %)))))
```

---

## Implementation Files

| File | Purpose |
|------|---------|
| `protocol/errors.clj` | Error codes, types, formatting |
| `protocol/message.clj` | JSON-RPC response creation (existing) |
| `registry.clj` | Enhanced validation (existing) |
| `handlers/tools_call.clj` | Updated error handling (existing) |

---

## Error Response Examples

### Tool Not Found

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32000,
    "message": "Tool not found",
    "data": {
      "tool": "unknown-tool",
      "type": "not-found",
      "available": ["hello", "echo", "add", "concat"]
    }
  }
}
```

### Validation Failed

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": {
    "code": -32002,
    "message": "Invalid tool params",
    "data": {
      "tool": "add",
      "type": "validation-error",
      "validation-errors": {
        "a": ["should be a number"],
        "b": ["missing required key"]
      },
      "expected": {"a": "number", "b": "number"},
      "actual": {"a": "not-a-number"}
    }
  }
}
```

### Execution Failed

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "error": {
    "code": -32001,
    "message": "Tool execution failed",
    "data": {
      "tool": "hello",
      "type": "runtime-error",
      "cause": "Division by zero",
      "trace": ["bb_mcp_server.tools.hello:42"]
    }
  }
}
```

---

## Migration Notes

### Backward Compatibility

- Error codes unchanged
- Existing `:data` fields preserved
- New fields are additive

### Testing Strategy

1. Unit test each error type
2. Test validation with invalid schemas
3. Test exception middleware isolation
4. Integration test full error flow

---

*Status: Design complete, ready for implementation*
