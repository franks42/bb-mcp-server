# telemetry-db Module

## What It Is

A module that captures every structured log call in the running server and stores it in memory so you can query, filter, and dump logs from the terminal or (eventually) the browser.

It is **not** a custom database. It is an `(atom [])` holding a capped vector of maps. The module's value is the **Trove log-fn interception** and the **query/CLI layer** on top — not the storage mechanism.

## Why It Exists

The server has 792 `trove/log!` calls across 96 files emitting structured telemetry with `:id`, `:msg`, `:data`, and `:level` fields. All of this is routed to stderr via Timbre and lost once it scrolls past the terminal. During the `init!` hanging bug, we ignored logs entirely and poked at runtime state via nrepl-direct because the logs were not queryable.

The Trove-to-Timbre bridge (`taoensso.trove.timbre`) **stringifies** the structured `:id`/`:msg`/`:data` fields into Timbre's `:vargs` vector before any appender sees them. A Timbre appender cannot recover the structure. The only way to preserve it is to intercept at the Trove `*log-fn*` level, before Timbre ever sees the data.

## How It Works

### Interception Point

```
trove/log! call
    |
    v
telemetry-db wrapper log-fn
    |-- forwards to original log-fn (Timbre/stderr, unchanged)
    |-- extracts structured fields from the Trove signature:
    |     (fn [ns-str coords level id lazy_])
    |     where lazy_ = (delay {:keys [msg data error kvs]})
    |-- builds a map and prepends it to the store atom
    v
(atom [{newest...} {older...} ...])   ;; capped at max-entries
```

The wrapper is installed via `trove/set-log-fn!` at module start and restored at module stop. stderr output is completely unchanged — the wrapper always calls the original log-fn first.

### Storage

A plain `(atom [])` holding maps, newest-first. Each entry:

```clojure
{:log/id        "019..."          ;; UUIDv7 string
 :log/level     "info"            ;; string
 :log/ns        "code-browser.core"
 :log/event-id  ":code-browser.core/init-complete"
 :log/msg       "Initialization complete"
 :log/data      "{:ns-count 208}" ;; pr-str of original data map
 :log/error     "Connection refused" ;; ex-message, if error
 :log/timestamp 1738972800000     ;; epoch millis
 :log/line      42                ;; source line, if available
 :log/source    "server"}         ;; "server" or "browser"
```

Retention: on every write, if `(count entries) > max-entries`, the vector is trimmed to `max-entries` (dropping oldest from the tail). Default `max-entries` is 10,000. No periodic timer, no background thread — just an inline check in the single `swap!`.

### Why Not Datascript

The original plan specified Datascript for in-memory Datalog queries. **Datascript does not work on Babashka.** Versions 1.3.10, 1.5.0, and 1.7.8 all fail with the same error:

```
defrecord/deftype currently only support protocol implementations,
found: clojure.lang.ILookup
Location: datascript/lru.cljc:5:1
```

Babashka's SCI interpreter reads `.cljc` files using the `:clj` reader conditional branch. Datascript's `lru.cljc` uses `deftype` with `clojure.lang.ILookup` (a Java interface), which SCI's `deftype` does not support — it only handles Clojure protocols. This is a fundamental SCI limitation, not a version-specific bug.

A plain atom with `filter`/`take` is sufficient for querying 10k maps and has zero dependencies.

## Files

| File | Lines | Purpose |
|------|-------|---------|
| `modules/telemetry-db/module.edn` | 13 | Module manifest |
| `modules/telemetry-db/src/telemetry_db/core.clj` | 207 | Module core: wrapper, query, ingest, dump, lifecycle |
| `modules/telemetry-db/test/run_tests.clj` | 16 | Test runner |
| `modules/telemetry-db/test/telemetry_db/core_test.clj` | 223 | 16 tests, 35 assertions |
| `scripts/logs_cli.clj` | 266 | `bb logs` CLI |

Modified files:

| File | Change |
|------|--------|
| `bb.edn` | Added `logs` and `test:telemetry-db` tasks |
| `system.edn` | `"telemetry-db"` first in `:modules` list + config |
| `system-cb-v2-test.edn` | Same |

## Public API

```clojure
(require '[telemetry-db.core :as tdb])

;; Query with filters (all optional, returns newest-first)
(tdb/query {:level :error})                    ;; by level
(tdb/query {:ns "code-browser"})               ;; by namespace prefix
(tdb/query {:event-id "module"})               ;; by event-id substring
(tdb/query {:since (- (System/currentTimeMillis) 300000)}) ;; last 5 min
(tdb/query {:level :warn :ns "sente" :limit 20})           ;; combined

;; Convenience
(tdb/recent 10)                                ;; last 10 entries

;; External ingestion (for browser logs via sente, future)
(tdb/ingest! {:level :warn :ns "browser.ui" :msg "click failed"
              :source "browser"})

;; Persistence
(tdb/dump! "/tmp/logs.edn")                    ;; write all entries to file
(tdb/load-dump "/tmp/logs.edn")                ;; read back as vector of maps

;; Raw access (for nrepl-direct ad-hoc queries)
(tdb/get-store)                                ;; returns the atom
(count @(tdb/get-store))                       ;; entry count
```

## CLI Usage

```bash
bb logs -t cb-v2-test                           # last 50 entries, table format
bb logs -t cb-v2-test --level error             # errors only
bb logs -t cb-v2-test --ns code-browser         # filter by namespace prefix
bb logs -t cb-v2-test --event module-loaded     # filter by event ID
bb logs -t cb-v2-test --since 5m                # last 5 minutes
bb logs -t cb-v2-test --limit 200               # more entries
bb logs -t cb-v2-test --dump /tmp/logs.edn      # persist to file
bb logs -t cb-v2-test --pprint                  # pretty-print instead of table
```

The CLI connects via nrepl-direct to the server's nREPL port (discovered from `.ports/<nickname>.json`), evals `telemetry-db.core/query` remotely, and formats the result as a table.

## Module Configuration

In `system.edn` or `system-cb-v2-test.edn`:

```edn
:modules ["telemetry-db" ...]   ;; FIRST — captures all subsequent module startup logs

:config {"telemetry-db" {:max-entries 10000
                         :min-level :debug
                         :exclude-ns #{"sente-lite."}}}
```

Config keys:

| Key | Default | Description |
|-----|---------|-------------|
| `:max-entries` | 10000 | Maximum log entries retained in memory |
| `:min-level` | nil (all) | Minimum severity to capture. One of `:trace :debug :info :warn :error :fatal`. Entries below this level are forwarded to stderr but not stored. |
| `:exclude-ns` | nil | Set of namespace prefixes to exclude from capture. Entries from matching namespaces are forwarded to stderr but not stored. |

**Why filter at capture?** Without filters, a 10k-entry buffer fills in minutes with sente-lite wire-format trace logs (~8,400 of 10k entries). Filtering `:trace` and `sente-lite.*` at capture time preserves module startup events, business logic logs, and error conditions that would otherwise be pushed out.

## Future Work

- **Browser telemetry ingestion:** Wire a `:telemetry/log` sente event handler in `server.clj` that calls `ingest!` to unify server and browser logs in one store.
- **Browser log viewer:** A sente request/response query UI (same pattern as code-browser-v2 `handle-fetch`).
- **Datalevin persistence:** Optionally persist logs to Datalevin for cross-session querying (using the existing datalevin-pod infrastructure).
