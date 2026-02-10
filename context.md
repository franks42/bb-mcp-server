# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-09
**Version:** v1.25.0 (post `7f2c6df`)
**Focus:** File watcher robustness fixes — race conditions, phantom deletions

---

## Current State (2026-02-09)

### Committed & Pushed

1. **File watcher race condition fixes** (v1.25.0):
   - Thread-safe debounce: `locking` around cancel+reset in `debounced-callback` (directory.clj)
   - Per-file serialization: `!rescan-locks` atom prevents concurrent retract/transact (core.clj)
   - Phantom deletion correction: `fs/exists?` check before treating `:remove` as deleted (core.clj)
   - Root cause found via telemetry: 4 concurrent rescans for same file, interleaved retract/transact → 0 symbols
   - "." project name fix: `fs/normalize` in `get-project-name` and `create-directory-source` (directory.clj)
   - Project deduplication in `query-projects` (handlers.clj)
   - URI decode from browser hash: `js/decodeURIComponent` for spaces in dir names (code_browser_v2.cljs)
   - Tests: 47 tests, 542 assertions

2. **Per-connection server statechart** (v1.24.0, `bd16e44`):
   - `sente_browser/server.clj` — 4 states, 5 transitions per browser WebSocket connection
     (pending-validation→validated→disconnected, with validation-failed branch)
   - Replaces implicit `:status` keyword FSM with formal `clj-statecharts` machine instances
   - Per-connection instances in `!browser-connections` atom (`:_state` replaces `:status`)
   - `conn-transition!` helper with telemetry on every state change
   - `validated?` and `get-connection-state` query helpers replace 7 repeated guards
   - Heartbeat pong self-transition updates `:last-heartbeat` via `fsm/assign`
   - Heartbeat timeout fires `:heartbeat-timeout` FSM event (not direct disconnect)
   - 24 tests, 62 assertions (`bb test:module sente-browser`)
   - Integration verified: Playwright browser connect/browse/disconnect, telemetry logs

2. **Browser connection statechart** (v1.23.0, `e1fba2a`):
   - `bootstrap_client.cljs` — 6 states, 9 transitions
     (idle→connecting→ws-open→connected, with disconnected/reconnecting)
   - Server `local_nrepl_server.clj` — added telemetry + config/machine split
   - CDN-served `statecharts-bundle.cljc` via `<script>` tag in `bootstrap.clj`
   - Coding conventions documented in `docs/STATECHARTS_REFERENCE.md`
   - Verified via Playwright: full connection flow, code browser nav, 0 errors

3. **Statechart static analyzer "statechart-kondo"** (v1.22.0–v1.22.1):
   - `src/statecharts/validate.cljc` — 5 structural checks + 4 convention checks
   - Structural: unreachable, dead-end, non-deterministic, orphan, self-only
   - Conventions: missing `:id`, missing `:context`, error without recovery, no return to initial
   - `bb statechart:validate ns/var` CLI with colored graph output
   - `bb test:statecharts` — 19 tests, 69 assertions
   - State management best practices added to `docs/CLOJURE_EXPERT_CONTEXT.md`
   - Full reference at `docs/STATECHARTS_REFERENCE.md`

4. **clj-statecharts integration** (v1.21.0, `87a7156`):
   - `local_nrepl_server.clj` uses `fsm/machine` for lifecycle state management
   - Pure transition tests (no I/O) — 70 tests, 275 assertions

5. **Live code refresh** (v1.20.0):
   - File watcher, incremental rescan, widget auto-update via sente

6. **Telemetry infrastructure** (v1.18.0–v1.19.1):
   - In-memory queryable log store, `bb logs`, `bb telemetry:catalog` (849 log points)

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

- `modules/sente-browser/src/sente_browser/server.clj` — server per-connection statechart (4 states)
- `modules/sente-browser/src/browser/bootstrap_client.cljs` — browser connection statechart (6 states)
- `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj` — server nREPL statechart (5 states)
- `modules/sente-browser/test/sente_browser/server_test.clj` — 24 tests, 62 assertions
- `modules/sente-browser/src/sente_browser/bootstrap.clj` — serves statecharts CDN script
- `src/statecharts/validate.cljc` — static analyzer (5 checks + graph extraction)
- `scripts/statechart_validate.clj` — CLI with colored output
- `test/statecharts/validate_test.clj` — 19 tests with synthetic + real machines
- `docs/STATECHARTS_REFERENCE.md` — full reference + coding conventions
- `docs/CLOJURE_EXPERT_CONTEXT.md` — state management best practices section

### Key Decisions

- **Per-connection FSM instances** — each browser connection gets its own state machine in `!browser-connections`.
- **Config/machine split** — always `def` the config map separately from `fsm/machine` for REPL inspectability.
- **Telemetry on every transition** — `conn-transition!` / `transition!` logs from-state, to-state, event for full observability.
- **Entry actions for side effects** — browser statechart uses entry actions for `set-status!`, logging, adapter calls.
- **Thin callback dispatchers** — WebSocket callbacks just call `(transition! {:type ...})`, no inline logic.
- **Statechart for lifecycle, not routing** — event routing stays as `case` dispatch, statechart only gates via `validated?`.
- **`.cljc` for analyzer** — works in BB (tests, CLI) and Scittle (future browser viz).
- **Pure data output** — `validate` returns `{:errors :warnings :info :conventions :graph :summary}`, no I/O.

### What's NOT done yet (future PRs)

1. **Statechart write gate** — Wire `widget_lifecycle.cljc` as write gate for `!widgets` r/atom (Step 2 of plan)
2. **Browser statechart viz** — serve `validate.cljc` via `/cljc/`, render graphs with Mermaid.js
3. **Browser log viewer** — UI widget for browsing telemetry in browser
4. **Git status display** — show modified/staged files in code browser
5. **JAR/GitHub source adapters** — browse dependencies

### Browser Testing Policy

**ALWAYS use Playwright MCP tools** (`mcp__playwright__browser_*`) for browser/E2E testing.
**NEVER** install npx packages, create TypeScript test files, or use `npx playwright` CLI.
The MCP tools provide interactive, real-time browser automation directly from the conversation.

---

## Quick Resume

```bash
# Run tests
bb test:module sente-browser     # 24 tests, 62 assertions (server statechart)
bb test:statecharts              # 19 tests, 69 assertions (validate analyzer)
bb test:nrepl                    # 70 tests, 275 assertions (includes machine validation)
bb test:module code-browser-v2   # 47 tests, 542 assertions
bb test:module telemetry-db      # 16 tests, 35 assertions

# Statechart validation
bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-machine-config

# Start dev environment
bb dev:cb-v2

# nrepl-direct (ALWAYS use double quotes for !)
bb nrepl-direct eval "<code>" -t cb-v2-test
```

---

## Recent Commits

```
7f2c6df fix: "." project name bug + spaces in dir names + project deduplication
2db3240 fix: Make Datalevin queries version-agnostic for stale hash resilience
95f9a1c docs: Add statechart-as-write-gate pattern for Reagent apps
b92b8d8 feat: Add widget lifecycle statechart for documentation and validation
8ca7d3a refactor: Extract explicit widget :status field + centralized mutation helpers
bd16e44 feat: Add per-connection statechart to server-side browser management
```

---

*Last Updated: 2026-02-09*
