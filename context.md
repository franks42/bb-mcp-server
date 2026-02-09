# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-08
**Version:** v1.22.1 (post `70a759f`)
**Focus:** Statechart static analyzer ("statechart-kondo") — structural + convention checks

---

## Current State (2026-02-08)

### Committed & Pushed

1. **Statechart static analyzer "statechart-kondo"** (v1.22.0–v1.22.1):
   - `src/statecharts/validate.cljc` — 5 structural checks + 4 convention checks
   - Structural: unreachable, dead-end, non-deterministic, orphan, self-only
   - Conventions: missing `:id`, missing `:context`, error without recovery, no return to initial
   - `bb statechart:validate ns/var` CLI with colored graph output
   - `bb test:statecharts` — 19 tests, 69 assertions
   - State management best practices added to `docs/CLOJURE_EXPERT_CONTEXT.md`
   - Full reference at `docs/STATECHARTS_REFERENCE.md`

2. **clj-statecharts integration** (v1.21.0, `87a7156`):
   - `local_nrepl_server.clj` uses `fsm/machine` for lifecycle state management
   - Pure transition tests (no I/O) — 70 tests, 275 assertions

3. **Live code refresh** (v1.20.0):
   - File watcher, incremental rescan, widget auto-update via sente

4. **Telemetry infrastructure** (v1.18.0–v1.19.1):
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

- `src/statecharts/validate.cljc` — static analyzer (5 checks + graph extraction)
- `scripts/statechart_validate.clj` — CLI with colored output
- `test/statecharts/validate_test.clj` — 19 tests with synthetic + real machines
- `docs/CLOJURE_EXPERT_CONTEXT.md` — state management best practices section
- `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj` — statechart-managed lifecycle

### Key Decisions

- **`.cljc` for analyzer** — works in BB (tests, CLI) and Scittle (future browser viz).
- **Pure data output** — `validate` returns `{:errors :warnings :info :conventions :graph :summary}`, no I/O.
- **Nine checks in two categories** — 5 structural (graph analysis) + 4 convention (project standards).
- **Anonymous action detection infeasible** — BB/SCI all fns have identical `str` repr; documented as convention only.

### What's NOT done yet (future PRs)

1. **Browser statechart viz** — serve `validate.cljc` via `/cljc/`, render graphs with Mermaid.js
2. **Browser log viewer** — UI widget for browsing telemetry in browser
3. **Git status display** — show modified/staged files in code browser
4. **JAR/GitHub source adapters** — browse dependencies

---

## Quick Resume

```bash
# Run tests
bb test:statecharts              # 19 tests, 69 assertions (validate analyzer)
bb test:nrepl                    # 70 tests, 275 assertions (includes machine validation)
bb test:module code-browser-v2   # 34 tests, 494 assertions
bb test:module telemetry-db      # 16 tests, 35 assertions

# Statechart validation
bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-machine

# Start dev environment
bb dev:cb-v2

# nrepl-direct (ALWAYS use double quotes for !)
bb nrepl-direct eval "<code>" -t cb-v2-test
```

---

## Recent Commits

```
70a759f docs: Add state management best practices to Clojure expert context
e3cbd0b feat: Add convention checks to statechart analyzer
3d7822b feat: Add statechart static analyzer with CLI and tests
87a7156 feat: Integrate clj-statecharts with local nREPL server lifecycle
e61d4ee docs: Add Nexus functional action-dispatch pattern reference
```

---

*Last Updated: 2026-02-08*
