# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-07
**Version:** v1.19.0 (post `2692dc8`)
**Focus:** Browser telemetry ingestion complete — unified server + browser logs queryable via `bb logs`

---

## Current State (2026-02-07)

### Committed & Pushed

1. **Browser telemetry ingestion** (`ac3cadb`, `d4c22c5`, `2692dc8`):
   - Browser Trove `log/log!` calls route to server telemetry-db via sente `:telemetry/log` events
   - `browser_telemetry.cljs` wraps browser `*log-fn*` — captures `:info+` entries, sends via sente
   - Feedback loop prevention: excludes `sente-lite.*` namespaces + `volatile!` re-entrancy guard
   - Source tagging: entries tagged `"browser:<mcp-conn-id>"` for filtering
   - `--source browser` filter in `bb logs` CLI and telemetry-db query API
   - Dual logging in `code_browser_v2.cljs` — both `js/console.log` and Trove `log/log!`
   - Verified: mount, widget-opened, widget-closed, atom-sync events all visible in `bb logs`

2. **`!` character escaping fix** (bb.edn + CLAUDE.md):
   - Root cause: Claude Code's Bash tool escapes `!` to `\!` in single-quoted strings
   - `nrepl-direct` bb task now uses `load-file` directly (no bash wrapper)
   - CLAUDE.md updated: ALWAYS use double quotes for eval containing `!`
   - Full write-up: `docs/exclamation-escaping.md`

3. **telemetry-db module** (v1.18.0, `671f5f1`):
   - In-memory queryable log store backed by plain atoms
   - Wraps Trove `*log-fn*` to capture structured data before Timbre stringifies
   - Loads FIRST in system.edn to capture all module startup logs
   - Query API: filter by `:level`, `:ns`, `:event-id`, `:since`, `:source`
   - `bb logs -t <nickname>` CLI for terminal querying
   - `bb telemetry:catalog` — static analysis of all log points (831 found)
   - 16 tests, 35 assertions passing

### Architecture

```
Browser: Trove log! call
    │
    ▼
browser_telemetry.cljs wrapper
    ├── Forward to original log-fn (console output unchanged)
    └── send-event! :telemetry/log {structured entry}
              │
              ▼ (sente WebSocket)
Server: on-browser-message → telemetry-db/ingest!
    │
    ▼
telemetry-db atom store (newest-first, 10k retention)
    │
    ▼
bb logs -t cb-v2-test --source browser  ← queryable
```

- **Storage:** `(atom [])` newest-first, inline trim at max-entries (default 10000)
- **Key files:**
  - `modules/telemetry-db/src/telemetry_db/core.clj` — module core
  - `modules/sente-browser/src/browser/browser_telemetry.cljs` — browser Trove wrapper
  - `modules/sente-browser/src/sente_browser/server.clj` — `:telemetry/log` handler
  - `scripts/logs_cli.clj` — `bb logs` CLI
  - `docs/exclamation-escaping.md` — `!` escaping root cause and solutions

### Key Decisions

- **Datascript NOT compatible with Babashka** — used plain atoms instead.
- **Wrap Trove `*log-fn*` not Timbre appender** — preserves structured fields.
- **Exclude `sente-lite.*` from forwarding** — `client/send!` calls Trove `log!` internally.
- **Fire-and-forget** — browser sends `:telemetry/log`, server ingests with no response.
- **ALWAYS double quotes for `!`** — Claude Code Bash tool escapes `!` in single quotes.

### What's NOT done yet (future PRs)

1. **Browser log viewer** — UI widget for browsing telemetry in browser
2. **Further log level tuning** — top remaining events are meaningful but could be reviewed

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
bb logs -t cb-v2-test --source browser
bb logs -t cb-v2-test --source browser --ns code-browser-v2
bb logs -t cb-v2-test --dump /tmp/logs.edn

# Query via nrepl-direct (ALWAYS use double quotes for !)
bb nrepl-direct eval "(count @(telemetry-db.core/get-store))" -t cb-v2-test
bb nrepl-direct eval "(telemetry-db.core/recent 10)" -t cb-v2-test
bb nrepl-direct eval "(telemetry-db.core/query {:source \"browser\" :limit 20})" -t cb-v2-test

# Telemetry catalog
bb telemetry:catalog --report    # Summary to stdout
bb telemetry:catalog --save      # Save to telemetry-catalog.edn
```

---

## Recent Commits

```
2692dc8 feat: Add Trove telemetry alongside console.log in browser UI code
d4c22c5 fix: Prevent browser telemetry feedback loop via namespace exclusion
ac3cadb feat: Route browser Trove logs to server telemetry-db via sente
086db91 docs: Update context.md for telemetry infrastructure milestone
809c0fe fix: Move atom-sync broadcast log to trace level
de81481 feat: Generate timestamped telemetry catalog on dev:cb-v2 start/restart
671f5f1 feat: Add telemetry-db module with queryable log store and catalog tools
```

---

*Last Updated: 2026-02-07*
