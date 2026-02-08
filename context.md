# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-07
**Version:** v1.17.0
**Focus:** telemetry-db module — queryable in-memory log store

---

## Current State (2026-02-07)

### What's been done this session

1. **telemetry-db module implemented (uncommitted):**

   | File | Status |
   |------|--------|
   | `modules/telemetry-db/module.edn` | NEW: module manifest |
   | `modules/telemetry-db/src/telemetry_db/core.clj` | NEW: core module (207 LOC) |
   | `modules/telemetry-db/test/run_tests.clj` | NEW: test runner |
   | `modules/telemetry-db/test/telemetry_db/core_test.clj` | NEW: 16 tests, 35 assertions |
   | `scripts/logs_cli.clj` | NEW: `bb logs` CLI |
   | `bb.edn` | Added `logs` task, `test:telemetry-db` task |
   | `system.edn` | Added `telemetry-db` first in modules list |
   | `system-cb-v2-test.edn` | Added `telemetry-db` first in modules list |

2. **Key architectural decision:**
   - **Original plan:** Use Datascript for in-memory Datalog queries
   - **Problem:** Datascript 1.7.8 (and 1.3.10) are NOT compatible with Babashka — `deftype` in `lru.cljc` uses `clojure.lang.ILookup` which SCI doesn't support
   - **Solution:** Plain atoms with vectors of maps. Same API, simpler, fully bb-compatible.
   - Retention uses inline trim in `add-entry!` (on every write, checks count > max-entries)

3. **Architecture:**
   - Wraps Trove `*log-fn*` (not Timbre appender) to capture structured data
   - Stores entries newest-first in `(atom [])`
   - Query API: filter by `:level`, `:ns` prefix, `:event-id` substring, `:since` timestamp
   - `ingest!` for external entries (browser telemetry via sente)
   - `dump!` / `load-dump` for EDN persistence
   - `bb logs -t <nickname>` CLI for querying from terminal

4. **Verification PASSED:**
   - `clj-kondo` — 0 errors, 0 warnings
   - `cljfmt` — all files formatted correctly
   - `bb test:module telemetry-db` — 16 tests, 35 assertions, 0 failures

### What's NOT done yet

1. **Git commit** — All changes uncommitted
2. **Live server test** — Not tested with running server yet
3. **Browser telemetry ingestion** — sente `:telemetry/log` handler not wired (future PR)
4. **Browser log viewer** — Not implemented (future PR)

---

## Quick Resume

```bash
# Run tests
bb test:module telemetry-db

# Start server with telemetry-db
bb dev:cb-v2

# Query logs from running server
bb logs -t cb-v2-test
bb logs -t cb-v2-test --level error
bb logs -t cb-v2-test --ns code-browser --since 5m
bb logs -t cb-v2-test --dump /tmp/logs.edn

# Query via nrepl-direct
bb nrepl-direct eval '(count @(telemetry-db.core/get-store))' -t cb-v2-test
bb nrepl-direct eval '(telemetry-db.core/recent 10)' -t cb-v2-test
```

---

*Last Updated: 2026-02-07*
