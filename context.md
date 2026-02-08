# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-08
**Version:** v1.19.1 (post `e947e77`)
**Focus:** Telemetry infrastructure complete — comprehensive browser+server observability with full lint compliance

---

## Current State (2026-02-08)

### Committed & Pushed

1. **Comprehensive browser telemetry** (`c4d5552`, `e947e77`):
   - 15 telemetry points in `code_browser_v2.cljs`: widget lifecycle, fetch, navigation, clicks
   - Events: `::fetch-requested`, `::fetch-success`, `::fetch-error`, `::widget-refreshed`,
     `::winbox-created`, `::hash-navigation`, `::restoring-widget-chain`,
     `::project-clicked`, `::namespace-clicked`, `::symbol-clicked`, `::mounted`, `::unmounted`,
     `::widget-opened`, `::widget-focused`, `::widget-closed`
   - Browser Trove `log/log!` calls route to server via sente `:telemetry/log` events
   - `browser_telemetry.cljs` wraps browser `*log-fn*` — captures `:info+` entries
   - Feedback loop prevention: excludes `sente-lite.*` namespaces + `volatile!` re-entrancy guard
   - Source tagging: entries tagged `"browser:<mcp-conn-id>"` for `--source browser` filtering

2. **Telemetry catalog improvements** (`8d8b04f`, `dbe97d7`, `022741c`):
   - Scans all Clojure file types: `.clj`, `.cljs`, `.cljc`, `.bb` (849 log points found)
   - Shows `:msg` descriptions in filtered output instead of function names
   - Extracts `:msg` from escaped strings in inline ClojureScript (bootstrap.clj)

3. **clj-kondo lint compliance** (`e947e77`):
   - Added `:skip-args [taoensso.trove/log!]` to suppress false positive arity errors in `.cljs`
   - Added proper `:require` for uuidv7 in `scittle_cm6.cljs` (was using fully-qualified call)
   - All browser `.cljs` files now lint clean: 0 errors, 0 warnings

4. **`!` character escaping fix** (bb.edn + CLAUDE.md):
   - Root cause: Claude Code's Bash tool escapes `!` to `\!` in single-quoted strings
   - `nrepl-direct` bb task now uses `load-file` directly (no bash wrapper)
   - Full write-up: `docs/exclamation-escaping.md`

5. **telemetry-db module** (v1.18.0, `671f5f1`):
   - In-memory queryable log store backed by plain atoms
   - Wraps Trove `*log-fn*` to capture structured data before Timbre stringifies
   - Loads FIRST in system.edn to capture all module startup logs
   - Query API: filter by `:level`, `:ns`, `:event-id`, `:since`, `:source`
   - `bb logs -t <nickname>` CLI for terminal querying
   - `bb telemetry:catalog` — static analysis of all 849 log points across .clj/.cljs/.cljc/.bb
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
  - `modules/sente-browser/src/browser/code_browser_v2.cljs` — 15 telemetry points for UI lifecycle
  - `modules/sente-browser/src/sente_browser/server.clj` — `:telemetry/log` handler
  - `scripts/logs_cli.clj` — `bb logs` CLI
  - `scripts/telemetry_catalog.clj` — static log point catalog (849 points, all file types)
  - `.clj-kondo/config.edn` — `:skip-args` for Trove `log!` false positive suppression
  - `docs/exclamation-escaping.md` — `!` escaping root cause and solutions

### Key Decisions

- **Datascript NOT compatible with Babashka** — used plain atoms instead.
- **Wrap Trove `*log-fn*` not Timbre appender** — preserves structured fields.
- **Exclude `sente-lite.*` from forwarding** — `client/send!` calls Trove `log!` internally.
- **Fire-and-forget** — browser sends `:telemetry/log`, server ingests with no response.
- **ALWAYS double quotes for `!`** — Claude Code Bash tool escapes `!` in single quotes.
- **`:skip-args` for clj-kondo** — Trove `log!` macro arity is unresolvable in `.cljs`, must clear `.cache/` after config change.

### What's NOT done yet (future PRs)

1. **Browser log viewer** — UI widget for browsing telemetry in browser

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
e947e77 fix: Suppress false positive clj-kondo arity warnings for Trove log! in .cljs
022741c fix: Extract :msg from escaped strings in inline ClojureScript
dbe97d7 feat: Show :msg description in telemetry catalog output
8d8b04f fix: Include .cljs, .cljc, and .bb files in telemetry catalog scanner
c4d5552 feat: Add comprehensive browser telemetry for widget lifecycle and navigation
47d2fb7 docs: Browser telemetry milestone + ! escaping fix (v1.19.0)
2692dc8 feat: Add Trove telemetry alongside console.log in browser UI code
d4c22c5 fix: Prevent browser telemetry feedback loop via namespace exclusion
ac3cadb feat: Route browser Trove logs to server telemetry-db via sente
671f5f1 feat: Add telemetry-db module with queryable log store and catalog tools
```

---

*Last Updated: 2026-02-08*
