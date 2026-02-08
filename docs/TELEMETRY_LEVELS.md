# Telemetry Log-Level Policy

## Level Definitions

| Level | When to use | Captured by telemetry-db? |
|-------|-------------|---------------------------|
| **error** | Something broke and needs attention. Failed connections, exceptions, corrupt state. | Always |
| **warn** | Unexpected condition, but recovered. Stale connection detected, retry succeeded, fallback used. | Always |
| **info** | State transitions and business events. Module started, browser connected, query served, tool registered. | Always |
| **debug** | Useful during active investigation. LSP request/response, kondo analysis results, sync operations. | With `:min-level :debug` (default) |
| **trace** | Wire-level detail. Every WebSocket message received, every file-watcher event, every debounce timer scheduled. | Only with `:min-level :trace` |

## Core Principles

### 1. Log absence, not presence

For routine periodic operations (heartbeats, health checks, sync pings), log when they **fail or are missing**, not when they succeed. A heartbeat pong every 10 seconds is noise; a missing heartbeat after 30 seconds is signal.

**Good:**
```clojure
;; Only logs when something is wrong
(log/log! {:level :info
           :id ::stale-connections-detected
           :msg "Disconnecting stale browsers"
           :data {:count (count stale-conns)}})
```

**Bad:**
```clojure
;; Logs every 10 seconds per connected browser
(log/log! {:level :trace
           :id ::heartbeat-pong
           :msg "Heartbeat pong received"
           :data {:sente-conn-id sente-conn-id}})
```

### 2. Volume determines level

If an event fires more than once per second during normal operation, it belongs at **trace**. The telemetry-db buffer (10k entries) should hold hours of meaningful events, not minutes of wire noise.

| Frequency | Level |
|-----------|-------|
| Once (startup/shutdown) | info |
| Per user action (click, query) | info or debug |
| Per message/request | trace |
| Per timer tick | trace |

### 3. Results over attempts

Log the result, not the attempt. "Analysis complete with 42 vars" (debug) is useful. "About to run analysis" (trace) is not — you only need it when the analysis hangs.

### 4. Structured data in `:data`, not `:msg`

The `:msg` field is for human reading in table output. Put queryable details in `:data`.

```clojure
;; Good: msg is readable, data is queryable
(log/log! {:level :info
           :id ::module-started
           :msg "Module started"
           :data {:module "telemetry-db" :duration-ms 3}})

;; Bad: details buried in msg string
(log/log! {:level :info
           :id ::module-started
           :msg (str "Module telemetry-db started in 3ms")})
```

### 5. Event ID naming

Use `::keyword` (namespace-qualified) for the `:id` field. This auto-generates IDs like `:sente-browser.server/browser-message` that are greppable and unique.

## Level Assignment by Category

### Module lifecycle → info
```
module-starting, module-started, module-stopped
system-starting, system-started, system-stopped
```

### Connection lifecycle → info
```
browser-connected, browser-validated, browser-disconnected
nrepl-connection-opened, nrepl-connection-closed
stale-connections-detected
```

### User-facing operations → info
```
handle-fetch, query-served, tool-registered
```

### Internal operations with useful results → debug
```
kondo-analysis-complete (has var count)
lsp-request (infrequent, shows method name)
sync-broadcast (shows what changed)
```

### Routine per-message/per-event processing → trace
```
browser-message, routing-response (every WebSocket message)
file-watcher-event (every file change)
debounce-scheduled (every timer creation)
lsp-notification-sent, lsp-notification-received (every notification)
```

### Failures → warn or error
```
warn: recovered failures (retry succeeded, stale connection cleaned up)
error: unrecovered failures (exception, crash, data loss)
```

## Reviewing Levels

Use the telemetry catalog to audit levels:

```bash
bb telemetry:catalog --report          # summary by level/namespace
bb telemetry:catalog --level trace     # list all trace points
```

When adding new `log!` calls, ask: "If this fires 1000 times, do I want it in my 10k-entry buffer?" If no, it's trace.
