# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-08
**Version:** v1.20.0 (post `d75cb5a`)
**Focus:** Live code refresh — file watching + automatic browser widget invalidation

---

## Current State (2026-02-08)

### Committed & Pushed

1. **Live code refresh** (v1.20.0, `36c82c8`–`d75cb5a`):
   - File watcher monitors source directories for `.clj`/`.cljs`/`.cljc` changes
   - Changed files trigger incremental single-file rescan (not full project rescan)
   - Cache invalidation clears stale source entries on file change
   - Widget invalidation broadcasts to all connected browsers via sente
   - Browser widgets auto-refresh their data — no manual reload needed
   - **Demo:** Edit any `.clj` file → source view in browser updates within seconds

2. **Cyclic dependency fix** (`d75cb5a`):
   - `sente-browser.server` required `code-browser.core` for `dispatch-event`
   - `code-browser.core` required `sente-browser.server` for `broadcast-to-browsers!`
   - Fix: redirect require to `code-browser.handlers` (same function, no back-dependency)
   - One-line change, broke the cycle, enabled live refresh to work

3. **Telemetry infrastructure** (v1.18.0–v1.19.1):
   - In-memory queryable log store, Trove `*log-fn*` wrapper
   - Browser telemetry ingestion via sente `:telemetry/log` events
   - `bb logs -t <nickname>` CLI, `bb telemetry:catalog` static analysis (849 log points)
   - Full lint compliance: 0 errors, 0 warnings across all file types

### Architecture — Live Code Refresh

```
File saved on disk (e.g., echo/core.clj)
    │
    ▼
code-browser.core file watcher (java.nio WatchService)
    │
    ├── Incremental rescan (clj-kondo single-file analysis)
    ├── Cache invalidation (clear stale source entries)
    └── broadcast-to-browsers! :code-browser-v2/invalidate
              │
              ▼ (sente WebSocket)
Browser: code_browser_v2.cljs receives invalidation
    │
    ├── All open widgets re-fetch their data
    └── Source view, symbol list, namespace list auto-refresh
```

### Key Files

- `modules/code-browser-v2/src/code_browser/core.clj` — file watcher, `broadcast-to-browsers!`
- `modules/code-browser-v2/src/code_browser/handlers.clj` — event dispatch, `handle-fetch`
- `modules/sente-browser/src/sente_browser/server.clj` — `broadcast-to-browsers!`, dispatch routing
- `modules/sente-browser/src/browser/code_browser_v2.cljs` — widget invalidation handler

### Key Decisions

- **Incremental rescan** — single-file clj-kondo analysis, not full project rescan.
- **Redirect require to handlers** — breaks cyclic dependency with one-line change.
- **Broadcast invalidation** — server pushes to all browsers, browsers decide what to refresh.
- **Datascript NOT compatible with Babashka** — used plain atoms for telemetry-db.

### What's NOT done yet (future PRs)

1. **Browser log viewer** — UI widget for browsing telemetry in browser
2. **Git status display** — show modified/staged files in code browser
3. **JAR/GitHub source adapters** — browse dependencies

---

## Quick Resume

```bash
# Run tests
bb test:module code-browser-v2    # 34 tests, 494 assertions
bb test:module telemetry-db       # 16 tests, 35 assertions

# Start dev environment (generates catalog + starts server)
bb dev:cb-v2

# Query logs from running server
bb logs -t cb-v2-test
bb logs -t cb-v2-test --source browser

# nrepl-direct (ALWAYS use double quotes for !)
bb nrepl-direct eval "<code>" -t cb-v2-test
bb nrepl-direct list -t cb-v2-test
```

---

## Recent Commits

```
d75cb5a fix: Break cyclic dependency between sente-browser.server and code-browser.core
a10eb05 fix: Handle both absolute and relative file paths in rescan
1c37d4d fix: Normalize clj-kondo file paths to relative in scan-file
ab3b61f feat: Replace full project rescan with incremental single-file rescanning
3b4acf2 feat: Add widget invalidation broadcast for live code refresh
36c82c8 feat: Add file watching & cache invalidation for Code Browser v2
```

---

*Last Updated: 2026-02-08*
