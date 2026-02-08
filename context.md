# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-07
**Version:** v1.17.0 (post `809c0fe`)
**Focus:** Telemetry infrastructure complete — queryable logs + noise reduction

---

## Current State (2026-02-07)

### Committed & Pushed

1. **telemetry-db module** (`671f5f1`):
   - In-memory queryable log store backed by plain atoms (Datascript incompatible with bb)
   - Wraps Trove `*log-fn*` to capture structured data before Timbre stringifies
   - Loads FIRST in system.edn to capture all module startup logs
   - Query API: filter by `:level`, `:ns` prefix, `:event-id` substring, `:since` timestamp
   - `bb logs -t <nickname>` CLI for terminal querying
   - `bb telemetry:catalog` — static analysis of all log points (831 found)
   - `TELEMETRY_LEVELS.md` policy document for log level standards
   - 16 tests, 35 assertions passing

2. **Telemetry catalog auto-generation** (`de81481`):
   - `bb dev:cb-v2 start/restart` generates timestamped catalog as step 1
   - Catalogs saved to `telemetry-catalogs/` (gitignored) for diffing between sessions
   - Generation takes ~167ms — negligible startup cost

3. **Log level noise reduction** (`671f5f1` + `809c0fe`):
   - Fixed 20+ noisy log events across codebase (debug/info → trace)
   - Moved atom-sync broadcast from debug → trace (was 53% of all entries)
   - Results across iterations:

   | Iteration | Total entries | Dominant noise |
   |-----------|---------------|----------------|
   | Original | 10,000+ (buffer full in minutes) | 97% wire noise |
   | After log level fixes | 828 | atom-sync 53% |
   | After atom-sync→trace | **482** | file-change-detected 10% (meaningful) |

   - Buffer now holds hours of meaningful events instead of minutes of wire noise
   - Breakdown: info 74%, debug 24%, warn 2% — signal-dominant

### Architecture

```
Trove log! call
    │
    ▼
telemetry-db wrapper log-fn (set-log-fn!)
    ├── Write structured entry to (atom []) with inline retention
    └── Forward to original log-fn (Timbre/stderr, backend-agnostic)

bb logs -t <nickname>  →  nrepl-direct  →  telemetry-db.core/query
```

- **Storage:** `(atom [])` newest-first, inline trim at max-entries (default 10000)
- **Key files:**
  - `modules/telemetry-db/src/telemetry_db/core.clj` — module core (207 LOC)
  - `scripts/logs_cli.clj` — `bb logs` CLI
  - `scripts/telemetry_catalog.clj` — static catalog generator
  - `docs/TELEMETRY_LEVELS.md` — log level policy

### Key Decisions

- **Datascript NOT compatible with Babashka** — `deftype` in `lru.cljc` uses `clojure.lang.ILookup` (not supported by SCI). Used plain atoms instead.
- **Wrap Trove `*log-fn*` not Timbre appender** — Trove→Timbre bridge stringifies structured data before appenders see it. Wrapping at Trove level preserves `:id`, `:msg`, `:data`.
- **telemetry-db loads first** — zero deps, captures all module startup logs.
- **Catalog auto-gen on dev:cb-v2 only** — not production server task.

### What's NOT done yet (future PRs)

1. **Browser telemetry ingestion** — sente `:telemetry/log` handler not wired
2. **Browser log viewer** — not implemented
3. **Further log level tuning** — top remaining events are meaningful but could be reviewed

---

## Quick Resume

```bash
# Run tests
bb test:module telemetry-db

# Start dev environment (generates catalog + starts server)
bb dev:cb-v2

# Query logs from running server
bb logs -t cb-v2-test
bb logs -t cb-v2-test --level error
bb logs -t cb-v2-test --ns code-browser --since 5m
bb logs -t cb-v2-test --dump /tmp/logs.edn

# Query via nrepl-direct
bb nrepl-direct eval '(count @(telemetry-db.core/get-store))' -t cb-v2-test
bb nrepl-direct eval '(telemetry-db.core/recent 10)' -t cb-v2-test
bb nrepl-direct eval '(telemetry-db.core/query {:level "error" :limit 20})' -t cb-v2-test

# Telemetry catalog
bb telemetry:catalog --report    # Summary to stdout
bb telemetry:catalog --save      # Save to telemetry-catalog.edn
```

---

## Recent Commits

```
809c0fe fix: Move atom-sync broadcast log to trace level
de81481 feat: Generate timestamped telemetry catalog on dev:cb-v2 start/restart
671f5f1 feat: Add telemetry-db module with queryable log store and catalog tools
041ea93 feat: Fix Scittle dev environment and add bb dev:cb-v2 task
cef8def feat: Replace all UUIDs with uuidv7 (com.github.franks42/uuidv7 v0.5.0)
```

---

*Last Updated: 2026-02-07*
