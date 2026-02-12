# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-12
**Version:** v1.30.0 (stable)
**Focus:** Statechart Service/ManyStore adoption + FSM runtime introspection

---

## Current State (2026-02-11)

### Committed & Pushed

1. **Statechart Service/ManyStore adoption + FSM runtime introspection** (v1.30.0):
   - **Service pattern** for `local_nrepl_server.clj` — `!service` defonce wraps compiled statechart
   - **ManyStore pattern** for `sente_browser/server.clj` — `!connection-store` tracks per-browser-connection FSM state
   - **`:reinit` transition** on `:stopped` state for clean test resets (preserves `:_state`)
   - **Service/Store detection** in `babashka.clj` — protocol-based detection (`IService`, `IStore`)
   - **Browser rendering** for Service (FSM state + context) and ManyStore (store type, instances, states)
   - **SCI deftype fix** — use `(resolve 'statecharts.service/state)` protocol fn instead of `.state` method
   - 72 mcp-nrepl tests (303 assertions), all modules pass

2. **Live var value display — Phase L2** (v1.29.0):
   - **"+ Value" button** in toolbar for nREPL-sourced symbols — fetches live runtime value
   - **Type-aware rendering**: maps, vectors, atoms, statecharts, nil, functions, etc.
   - **Atom auto-deref**: detects `IAtom`, double-derefs, shows "atom →" badge + inner type
   - **Statechart detection**: `statecharts.types/statechart?` check, shows machine id, initial state, compiled status, state tags
   - **Truncation**: `*print-length* 20`, `*print-level* 5`, 4096 char hard cap
   - **Var & value metadata** display
   - **Predicate badges**: `counted?`, `sorted?`, `fn?`, `var?`, `sequential?`, `associative?`
   - New source abstraction layer: `sources/protocol.clj`, `sources/runtime.clj`, `sources/runtime/babashka.clj`, `sources/nrepl.clj`
   - Tests: 80 tests, 625 assertions (code-browser-v2)

2. **Runtime project addition** (v1.26.0):
   - `bb add-project` CLI and browser input for adding projects at runtime
   - Multi-project browsing + CM6 zoom fix for short content

3. **File watcher robustness + telemetry** (v1.25.0):
   - 3 race condition fixes: thread-safe debounce, per-file serialization, phantom deletion correction
   - Enhanced telemetry for debugging

4. **Statecharts infrastructure** (v1.21.0–v1.24.0):
   - `clj-statecharts` integration, static analyzer, browser + server connection statecharts
   - Per-connection server statechart (4 states, 5 transitions)

### Architecture — Phase L2 Var Value

```
Browser: "+ Value" button clicked
    │
    ▼
send-event! :code-browser-v2/fetch {:query-type :var-value, :uri "..."}
    │
    ▼ (sente WebSocket)
Server: handlers.clj handle-fetch :var-value case
    │
    ├── Find NreplSource in registered sources
    ├── nrepl.clj fetch-var-value → runtime/fetch-var-value multimethod
    └── babashka.clj :babashka defmethod → remote eval on target server
              │
              ▼
    Target BB server: single eval string
    ├── resolve var, deref (double-deref if atom)
    ├── classify type, detect statechart/service/store
    ├── pprint with truncation
    ├── collect var & value metadata
    └── return EDN map
              │
              ▼
Browser: var-value-content renderer
    ├── Header badges (container, type, statechart/service/store, count, predicates)
    ├── Statechart/Service/Store info box (when detected)
    ├── Pprinted value display
    └── Metadata sections
```

### Key Files

- `modules/code-browser-v2/src/code_browser/sources/runtime.clj` — `fetch-var-value` multimethod
- `modules/code-browser-v2/src/code_browser/sources/runtime/babashka.clj` — babashka implementation
- `modules/code-browser-v2/src/code_browser/sources/nrepl.clj` — nREPL wrapper
- `modules/code-browser-v2/src/code_browser/handlers.clj` — `:var-value` case in `handle-fetch`
- `modules/sente-browser/src/browser/code_browser_v2.cljs` — `var-value-content` renderer, `"+ Value"` button
- `modules/sente-browser/src/sente_browser/bootstrap.clj` — CSS for var-value widgets
- `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj` — Service-wrapped nREPL server statechart
- `modules/sente-browser/src/sente_browser/server.clj` — ManyStore for per-connection browser FSM states

### Key Decisions

- **Source abstraction layer** — `IProjectSource` protocol extended with `sources/protocol.clj`; `runtime.clj` provides multimethods dispatched on runtime type (`:babashka`, `:default`)
- **Remote eval for introspection** — single eval string sent to target BB server, returns EDN map
- **Statechart detection is optional** — `try/catch` around `resolve` of `statecharts.types/statechart?`; gracefully returns false if not loaded
- **"+ Value" button visibility** — only shown for nREPL-sourced symbols (`:uri/source :nrepl` check)
- **Atom auto-deref** — detects `IAtom`, double-derefs, reports both container and inner type
- **FSM state introspection done** — Service pattern (single-instance) and ManyStore pattern (multi-instance) adopted; var-value widgets detect and render live FSM state

### What's NOT done yet (future PRs)

1. ~~**FSM runtime state introspection**~~ ✅ Done in v1.30.0
2. **Statechart write gate** — wire `widget_lifecycle.cljc` as write gate for `!widgets` r/atom
3. **Browser statechart viz** — serve `validate.cljc` via `/cljc/`, render graphs with Mermaid.js
4. **Browser log viewer** — UI widget for browsing telemetry in browser
5. **Git status display** — show modified/staged files in code browser
6. **JAR/GitHub source adapters** — browse dependencies

### Browser Testing Policy

**ALWAYS use Playwright MCP tools** (`mcp__playwright__browser_*`) for browser/E2E testing.
**NEVER** install npx packages, create TypeScript test files, or use `npx playwright` CLI.
The MCP tools provide interactive, real-time browser automation directly from the conversation.

---

## Quick Resume

```bash
# Run tests
bb test:module code-browser-v2   # 80 tests, 625 assertions
bb test:module sente-browser     # 24 tests, 62 assertions
bb test:statecharts              # 19 tests, 69 assertions
bb test:nrepl                    # 72 tests, 303 assertions
bb test:module telemetry-db      # 16 tests, 35 assertions

# Statechart validation
bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-statechart-compiled

# Start dev environment
bb dev:cb-v2

# nrepl-direct (ALWAYS use double quotes for !)
bb nrepl-direct eval "<code>" -t cb-v2-test
```

---

## Recent Commits

```
PENDING feat: Statechart Service/ManyStore adoption with FSM runtime introspection
1307d59 docs: Update context.md with commit hash for v1.29.0
b6b2f8c feat: Live var value display with type-aware rendering and statechart detection (Phase L2)
e2e4288 feat: Runtime project addition with bb add-project CLI and browser input
c6e3894 feat: Multi-project browsing + fix CM6 zoom for short content
5784d5b fix: Fix WinBox zoom/fit-to-content for CM6 source views
43f08fa feat: Replace inline JS loader with Scittle CLJS ui_loader.cljs
c49f014 feat: Add load-local-js-file command for importing JS as ES modules in browser
85424fb feat: Upgrade Scittle from 0.7.30 to 0.8.31
```

---

*Last Updated: 2026-02-12*
