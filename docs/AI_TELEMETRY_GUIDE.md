# AI Assistant Directive: Telemetry in bb-mcp-server

**For:** AI Coding Assistants (Claude, Copilot, Gemini, etc.)
**Project:** bb-mcp-server (Babashka MCP server)
**Goal:** Every function with I/O or business logic MUST have telemetry.

---

## 1. The Golden Rule

```clojure
;; DO: Use Trove for ALL logging
(require '[taoensso.trove :as log])

;; DO NOT: Use println, prn, timbre directly, or tools.logging
```

---

## 2. The API

```clojure
(log/log! {:level :info          ; REQUIRED: :trace :debug :info :warn :error :fatal
           :id    ::request-recv ; RECOMMENDED: namespaced keyword for searchability
           :msg   "Request received"  ; REQUIRED: human-readable message
           :data  {:method "GET"}     ; OPTIONAL: structured context
           :error exception})         ; OPTIONAL: exception object
```

---

## 3. Log Levels - Use Correctly

| Level | When to Use | Example |
|-------|-------------|---------|
| `:trace` | Internal flow, very verbose | Message parsing steps |
| `:debug` | Lifecycle events, dev useful | Connection added/removed |
| `:info` | Normal operations | Server started, request processed |
| `:warn` | Anomalies, recoverable | Rate limit approaching, retry |
| `:error` | Failures | Handler threw exception |
| `:fatal` | System cannot continue | DB connection lost |

**Default to `:info`** unless you have a reason for another level.

---

## 4. Event ID Convention

Use `:bb-mcp-server.{component}/{action}` pattern:

```clojure
:bb-mcp-server.router/request-received
:bb-mcp-server.handler/tool-executed
:bb-mcp-server.transport/connection-opened
:bb-mcp-server.telemetry/initialized
```

---

## 5. What to Log (Checklist)

Every significant function should log:

- [ ] **Entry** - Start of operation with input summary
- [ ] **Success** - Completion with result summary
- [ ] **Failure** - Exception with `:error` key and context
- [ ] **Duration** - For operations > 10ms

---

## 6. Copy-Paste Templates

### Standard Function Pattern
```clojure
(defn process-request [request]
  (log/log! {:level :info
             :id    ::process-request
             :msg   "Processing request"
             :data  {:request-id (:id request)}})
  (let [start (System/currentTimeMillis)]
    (try
      (let [result (do-work request)
            duration (- (System/currentTimeMillis) start)]
        (log/log! {:level :info
                   :id    ::process-request-complete
                   :msg   "Request processed"
                   :data  {:request-id (:id request)
                           :duration-ms duration}})
        result)
      (catch Exception e
        (log/log! {:level :error
                   :id    ::process-request-failed
                   :msg   "Request processing failed"
                   :error e
                   :data  {:request-id (:id request)}})
        (throw e)))))
```

### Simple Function (no timing needed)
```clojure
(defn validate-input [data]
  (log/log! {:level :debug
             :id    ::validate-input
             :msg   "Validating input"
             :data  {:keys (keys data)}})
  (if (valid? data)
    data
    (do
      (log/log! {:level :warn
                 :id    ::validation-failed
                 :msg   "Input validation failed"
                 :data  {:errors (get-errors data)}})
      nil)))
```

---

## 7. Project Setup (Already Done)

Telemetry bootstrap exists at `src/bb_mcp_server/telemetry.clj`:
- Routes logs to **stderr** (keeps stdout clean for JSON-RPC)
- Respects `LOG_LEVEL` env var
- Call `(telemetry/init!)` at entry points

**You don't need to create this** - just use `log/log!` in your code.

---

## 8. Quick Reference

```clojure
;; Require (every namespace that logs)
(ns bb-mcp-server.my-module
  (:require [taoensso.trove :as log]))

;; Info - normal operations
(log/log! {:level :info :id ::server-started :msg "Server started" :data {:port 8080}})

;; Debug - lifecycle/dev
(log/log! {:level :debug :id ::conn-added :msg "Connection added" :data {:conn-id id}})

;; Warn - anomalies
(log/log! {:level :warn :id ::rate-limited :msg "Rate limit hit" :data {:client-ip ip}})

;; Error - failures (include :error key!)
(log/log! {:level :error :id ::handler-failed :msg "Handler error" :error e :data {:tool name}})
```

---

## 9. DO NOT

- ❌ Use `println` or `prn` for logging
- ❌ Require `taoensso.timbre` directly (only in telemetry.clj)
- ❌ Log sensitive data (passwords, tokens, full request bodies)
- ❌ Use string concatenation in `:msg` - put variables in `:data`
- ❌ Skip logging in catch blocks

---

## 10. Why This Matters

- **Debugging**: Structured logs are searchable
- **Monitoring**: Can alert on `:error` level events
- **Performance**: Duration tracking finds bottlenecks
- **MCP Compliance**: stderr logging keeps stdio transport clean
