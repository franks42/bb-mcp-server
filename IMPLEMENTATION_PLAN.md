# bb-mcp-server Implementation Plan

**Status:** v1.32.0 (active)
**Last Updated:** 2026-02-15

---

## Current State

Production-ready MCP server with 33 modules, dynamic tool registry, dual transport (stdio + streamable HTTP), browser-based Code Browser v2 with WinBox floating windows, CM6 editor, and queryable telemetry infrastructure.

### Version History (recent milestones)

| Version | Tag | Description |
|---------|-----|-------------|
| v1.32.0 | `5b72686` | Stale ns retraction fix, Playwright E2E demo, all CLI `!` escaping |
| v1.31.1 | — | Two-process dev env (external nREPL target), self-introspection fix |
| v1.31.0 | `6332a9d` | Fix nrepl-direct silent error swallowing |
| v1.30.0 | `f5e47e3` | Statechart Service/ManyStore adoption, FSM runtime introspection |
| v1.29.0 | `1307d59` | Live var value display with type-aware rendering + statechart detection |
| v1.26.0 | — | Runtime project addition, `bb add-project` CLI, sente warning fixes |
| v1.25.0 | `a55d8e5` | File watcher robustness, enhanced telemetry, heartbeat noise elimination |
| v1.24.0 | `bd16e44` | Statecharts on both sides of sente-lite WebSocket (server + browser) |
| v1.23.0 | `e1fba2a` | Browser connection statechart (6 states, 9 transitions) + telemetry |
| v1.22.1 | `e3cbd0b` | Convention checks (`:id`, `:context`, error recovery, return path) + docs |
| v1.22.0 | `3d7822b` | Statechart static analyzer: 5 structural checks, CLI, .cljc for BB + Scittle |
| v1.21.0 | `87a7156` | clj-statecharts integration for local nREPL server lifecycle |
| v1.20.0 | `d75cb5a` | Live code refresh: file watching, incremental rescan, widget auto-update |
| v1.19.1 | `e947e77` | Comprehensive telemetry, catalog .cljs/.cljc/.bb, clj-kondo lint fix |
| v1.19.0 | `47d2fb7` | Browser telemetry ingestion, unified logs, `!` escaping fix |
| v1.18.0 | `086db91` | Telemetry infrastructure: queryable log store, catalog tools, noise reduction |
| v1.17.0 | `041ea93` | UUIDv7 migration, Scittle dev environment (`bb dev:cb-v2`) |
| v1.16.x | `d66754d` | CM6 editor, zoom fixes, Phase 1 UI enhancements |
| v1.15.x | `9d9e5bc` | Collapsible breadcrumb, WinBox floating windows, widget architecture |
| v1.14.19 | `0362d72` | nrepl-direct `--target` shorthand, browser routing |
| v1.14.17 | `52847dc` | Code Browser v2 browser loading fixed, full navigation verified |

---

## Dev Environment Quick Reference

```bash
# Verification (ALWAYS before committing — 0 errors, 0 warnings)
bb lint && bb format && bb test:modules

# Dev environment
bb dev:cb-v2                    # Start Code Browser v2 dev env (catalog + server + browser)
bb dev:cb-v2 stop               # Stop dev env
bb dev:cb-v2 status             # Check status

# Server management
bb server:start-wait --nickname X --config Y  # Start server, wait for health
bb server:stop X                # Stop server by nickname
bb server:list                  # List running servers

# nrepl-direct (preferred — direct TCP, no MCP overhead)
bb nrepl-direct eval "<code>" -t X              # Server eval (double quotes for !)
bb nrepl-direct eval "<code>" -t X/browser-1    # Browser eval
bb nrepl-direct load-local-file <path> -t X     # Load file
bb nrepl-direct list -t X                       # List connections

# Statechart validation
bb statechart:validate ns/var                   # Validate a machine definition
bb test:statecharts                             # Run statechart validate tests

# Project management
bb add-project /path/to/project [-t target]     # Add project dir to running Code Browser v2

# Telemetry
bb logs -t cb-v2-test                           # Query logs from running server
bb logs -t cb-v2-test --level error             # Filter by level
bb logs -t cb-v2-test --ns code-browser         # Filter by namespace
bb logs -t cb-v2-test --source browser          # Browser-only telemetry
bb telemetry:catalog --report                   # Static log point analysis (849 points)

# MCP CLI (fallback)
bb mcp servers                  # List running MCP servers
bb mcp tools --mcp X            # List tools
bb mcp call <tool> '<json>'     # Call any tool
```

---

## Unified Port Registry (v1.14.9)

All services use **ephemeral ports by default** (port 0 = OS assigns). Ports discovered via `.ports/<nickname>.json`.

```json
{
  "pid": 12345,
  "nickname": "cb-v2-test",
  "config": "system-cb-v2-test.edn",
  "timestamp": "2026-02-08T01:49:16.123127Z",
  "ports": {
    "nrepl-server": 7888,
    "sente-websocket": 8090,
    "http-server": 8091,
    "nrepl-proxy": 1667,
    "mcp-http": 54283
  }
}
```

---

## Telemetry Infrastructure (v1.18.0) ✅ COMPLETE

Queryable in-memory log store that intercepts Trove `*log-fn*` before Timbre stringification.

| Component | Status | Description |
|-----------|--------|-------------|
| `telemetry-db` module | ✅ Done | Atom-backed store, wraps Trove `*log-fn*`, 10k retention |
| `bb logs` CLI | ✅ Done | Query by level/ns/event/time via nrepl-direct |
| `bb telemetry:catalog` | ✅ Done | Static analysis of 849 log points (.clj/.cljs/.cljc/.bb) |
| Catalog auto-gen | ✅ Done | Timestamped catalogs on `bb dev:cb-v2` start |
| Log level tuning | ✅ Done | 10k+ noise entries → 482 meaningful (97% reduction) |
| `TELEMETRY_LEVELS.md` | ✅ Done | Log level policy document |
| Browser telemetry ingestion | ✅ Done | sente `:telemetry/log` events, `--source browser` filter |
| Browser telemetry coverage | ✅ Done | 15 events in code_browser_v2.cljs (lifecycle, fetch, navigation) |
| `!` escaping fix | ✅ Done | Double quotes mandate, `docs/exclamation-escaping.md` |
| clj-kondo lint compliance | ✅ Done | `:skip-args` for Trove `log!`, uuidv7 require in scittle_cm6 |
| Browser log viewer | Pending | UI for browsing telemetry in browser |

### Key Decisions

- **Datascript NOT bb-compatible** — uses `clojure.lang.ILookup` in `lru.cljc`. Used plain atoms instead.
- **Wrap Trove `*log-fn*`** not Timbre appender — preserves structured `:id`, `:msg`, `:data` fields.
- **telemetry-db loads first** — zero deps, captures all module startup logs.

---

## Code Browser v2 Redesign

**Design docs:** `docs/design/code_browser-review-redesign.md`

### Architecture

URI-centric design: `<source>://<project>@<version>/<ns>/<symbol>`

| Layer | Files | Description |
|-------|-------|-------------|
| URI | `uri.cljc` | Parse/generate/validate URIs |
| DB | `db/protocol.clj`, `db/datalevin.clj` | IDatalogDB interface + Datalevin backend |
| Sources | `sources/directory.clj` | Project source adapters (clj-kondo analysis) |
| Handlers | `handlers.clj` | Event dispatch, `handle-fetch` query API |
| Core | `core.clj` | Public API, init, lifecycle |
| Browser | `code_browser_v2.cljs` | Widget manager, WinBox windows, CM6 editor |

### Implementation Phases

#### Phase R0–R2: Foundation + End-to-End ✅ COMPLETE

- URI parsing/generation/validation
- IDatalogDB protocol + Datalevin backend
- Directory source adapter (clj-kondo)
- Content cache (LRU)
- atom-sync exports from Datalevin views
- Browser state + events
- Project → namespace → symbol → source flow verified

#### Phase R3: Feature Parity (Partial)

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done |
| R3.3 | Multi-file namespace support | ✅ Done |
| R3.x | Fix v2 browser loading (`nrepl-eval-local-file`) | ✅ Done |
| R3.4 | File watching / cache invalidation / live refresh | ✅ Done |
| R3.6 | Runtime project addition (browser input + CLI `bb add-project`) | ✅ Done |
| R3.5 | Git status display | **Pending** |

**Tests:** 80 tests, 629 assertions passing (`bb test:module code-browser-v2`)

#### Browser UI Enhancements (v1.15–v1.16) ✅ COMPLETE

| Feature | Version | Description |
|---------|---------|-------------|
| Widget architecture | v1.15.0 | URI-parameterized widgets, `!widgets` atom, hash routing |
| WinBox windows | v1.15.0 | Floating draggable/resizable windows replacing flex layout |
| Collapsible breadcrumb | v1.15.1 | Vertical breadcrumb with navigable parent segments |
| CM6 editor | v1.16.0 | CodeMirror 6 replacing `<pre>` for source view |
| Zoom fix | v1.16.1 | Fixed zoom creep, Phase 1 UI enhancements |

#### Phase R4: Additional Sources — In Progress

| Task | Description | Status |
|------|-------------|--------|
| R4.1 | JAR source adapter | Pending |
| R4.2 | GitHub source adapter | Pending |
| R4.3 | nREPL source adapter (Live Mode) | ✅ Done |

**R4.3 Status Detail:**
- ✅ nREPL adapter implementation complete (`sources/nrepl.clj`, `sources/runtime/*.clj`)
- ✅ Runtime introspection working (Babashka, Clojure)
- ✅ Fingerprint-based polling implemented (3s interval)
- ✅ Source registration fixed (nREPL sources now in `:sources` map)
- ✅ dispatch-event paren bug fixed
- ✅ Datalevin deadlock from rescan storm fixed (fingerprint storage + concurrency guard)
- ✅ Self-introspection deadlock fixed — two-process architecture (external nREPL target on port 9876)
- ✅ `bb dev:cb-v2` manages both processes (start/stop/status)
- ✅ Graceful shutdown restored (no more SIGKILL)
- ✅ Widgets load correctly: projects (3), namespaces verified in browser
- ✅ Stale namespace retraction fix — `rescan-project!` uses `(:project-name source)` for DB retraction
- ✅ Playwright E2E demo — create/modify/remove namespaces verified visually in browser
- ✅ All CLI `!` escaping fixed (nrepl-direct, nrepl, mcp-eval, mcp call)

**Remaining work:** Incremental rescans (single-ns instead of all-ns), runtime-data-sync statechart integration.

#### Phase R5: Polish & Switchover — Pending

| Task | Description | Status |
|------|-------------|--------|
| R5.1 | Full test coverage (unit + integration + browser) | Pending |
| R5.2 | Performance testing with large codebases | Pending |
| R5.3 | Update documentation | Pending |
| R5.4 | Switch config to use v2 as default | Pending |
| R5.5 | Remove old code browser | Pending |

---

## Code Browser v1 (Complete — Reference Only)

**Files (DO NOT MODIFY during v2 development):**
- `modules/sente-browser/src/sente_browser/code_browser.clj` (2,458 lines)
- `modules/sente-browser/src/browser/code_browser.cljs` (1,101 lines)

**Features:** Synced atoms, clj-kondo var classification, symbol inspector with tabs, multi-file namespaces, lazy JAR exploration, git repo cloning, live file watching.

---

## Future Work

### Browser Telemetry Viewer
- ~~Ingest browser logs via sente `:telemetry/log` events~~ ✅ Done (v1.19.0)
- Build log viewer widget in Code Browser v2 UI
- ~~Unified query across server + browser telemetry~~ ✅ Done (`--source browser`)

### Code Browser v2 UI Polish
- [ ] Add remove/delete button per project in the project list widget
- [ ] Fix zoom/fit-to-content inconsistency in project list widget
- [ ] Fix WinBox z-index overlap preventing toolbar button clicks — buttons become unclickable when another widget overlaps (workaround: use `dispatchEvent('click')` via browser console or Playwright)

### Live Mode (nREPL Introspection)
- ~~Connect to running nREPL and introspect live system~~ ✅ Phase L0-L1 (nREPL source browsing)
- ~~Live var values~~ ✅ Phase L2 (var-value widget with type-aware rendering, statechart detection)
- Loaded vs unloaded namespaces
- tools.trace integration
- ~~FSM runtime state introspection~~ ✅ v1.30.0 — Service pattern (`local_nrepl_server.clj`), ManyStore pattern (`sente_browser/server.clj`), protocol-based detection in var-value widgets, browser rendering for Service/Store types

### Terminal Code Browser (charm.clj TUI)
- Multi-panel terminal UI for code browsing using charm.clj (Elm architecture, bb-compatible since 1.12.215)
- Namespace list, symbol list, docstrings/source in bordered panels — like lazygit but for Clojure introspection
- Same Datalevin queries and nREPL introspection as browser, different renderer (ANSI instead of DOM)
- Advantages: ~50ms startup, works over SSH, no browser needed
- Depends on charm.clj layout maturity for multi-panel resizable dashboards
- **Status:** Idea — no implementation planned yet

### Durable Atoms (sqlatom / editscript / Datalevin)
- Explore persistent atoms backed by SQLite or Datalevin for server state (telemetry events, config, session data)
- **sqlatom** (`github.com/filipesilva/sqlatom`): Durable atoms via SQLite with cross-process CAS safety. Simple but writes entire value as EDN blob on every `swap!` — prohibitive for large collections (e.g. 10k telemetry events)
- **editscript** (`github.com/juji-io/editscript`): Diff/patch Clojure data structures. Could optimize durable atoms by persisting only deltas instead of full values. Essentially event sourcing with Clojure data.
- **Datalevin-backed atoms**: Map operations (`assoc`/`dissoc`/`update`) translate naturally to Datalevin transactions (`:db/add`, retractions). Deltas are free. But requires schema — flat keyword maps with consistent value types. Nested/dynamic structures don't map cleanly.
- **Key insight**: The schema is the optimization boundary. With schema → efficient column-per-key deltas, queryable. Without schema → opaque blob, sqlatom-style. Same atom API either way.
- **Immediate use case**: Telemetry event persistence — currently in-memory atom (10k entries, lost on restart). Events have a stable schema (`:log/id`, `:log/level`, `:log/ns`, `:log/msg`, `:log/timestamp`), so either SQLite rows or Datalevin entities would work well.
- **Status:** Idea — exploring trade-offs, no implementation planned yet

### Type & Purity Introspection for Code Browser
- Surface type information and purity analysis alongside functions in the code browser
- **Goal**: For any function, show input/output types and whether it's pure, has side effects, or performs I/O — without requiring the developer to annotate anything

#### Layered Type Inference (by priority and confidence)

| Priority | Source | Confidence | How |
|----------|--------|------------|-----|
| 1 | Declared malli/spec schemas | High | Runtime introspection via nREPL — `:malli/schema` metadata, `fdef`s |
| 2 | clj-kondo analysis | Medium | Already in our analysis pipeline — return types, type-mismatch data |
| 3 | Type hints in source | Medium | Already in var metadata — `^String`, `^long`, etc. |
| 4 | LLM-inferred | Variable | Feed fn source + docstring + arglists to LLM, get back schema. Batch process, cache in Datalevin with `:inferred` confidence tag |

- Sources 1-3 are essentially free — the data already flows through the system, just not stored/displayed
- Source 4 could run as an offline batch job over the codebase, results cached in Datalevin
- **Existing tools**: Spectrum (spec-based type propagation through AST, early/stalled), type-infer (exposes JVM compiler's own inference, JVM-only), Typed Clojure (semi-automated annotation generation)

#### Purity Analysis

Equally important to type inference — knowing whether a function is **pure** (no side effects, deterministic) enables:
- **Memoization safety** — browser can suggest/auto-apply memoization for pure fns
- **Parallelization** — pure fns are safe to run concurrently
- **Testing confidence** — pure fns are trivially testable (input → output, no setup)
- **Refactoring safety** — pure fns can be moved, inlined, reordered without risk
- **Visual indication** — color-code or badge pure vs. effectful fns in the browser

**Deduction signals for purity:**
- No calls to `swap!`, `reset!`, `alter`, `send`, `println`, `spit`, I/O fns → likely pure
- No use of `def`, `atom`, `ref`, `agent`, `volatile!` → no state mutation
- All called functions are themselves pure → transitively pure
- clj-kondo analysis already tracks var usage — could flag side-effectful calls
- Type hints and return types can corroborate (fns returning collections from collection inputs are often pure)
- LLM analysis can assess purity from source + docstring with high accuracy

**Purity levels:**
- **Pure** — no side effects, deterministic, safe to memoize/parallelize
- **Read-only** — reads external state (deref atoms, env vars) but doesn't mutate
- **Effectful** — performs I/O, mutations, or non-deterministic operations

#### Storage and Display

- Store as Datalevin attributes on var entities: `:var/type-signature`, `:var/type-confidence`, `:var/purity`, `:var/purity-confidence`
- Display optionally in the browser — badges, tooltips, or a dedicated "Type & Purity" tab in the symbol inspector
- Queryable via Datalog: "show me all pure functions that take a map and return a vector"

#### Pros/Cons

- **Pros**: Richer browsing, type-aware filtering, purity-aware refactoring suggestions, bridges dynamic Clojure to typed understanding, incremental (start with what's available), Datalevin makes it queryable
- **Cons**: Multiple type formats to normalize, schema design for polymorphic/union types, LLM inference adds cost/latency (mitigated by batch+cache), purity analysis can be conservative (false negatives for safe code that looks effectful), optional display adds UI complexity
- **Babashka note**: Typed Clojure and type-infer are JVM-only; need to extract/cache annotations rather than depend at runtime
- **Status:** Idea — no implementation planned yet

### Symbol-at-Point
- Click any symbol in CM6 source viewer → navigate to definition
- LSP hover integration
- Cross-project navigation

---

## Module Inventory (33 modules)

| Module | Description |
|--------|-------------|
| `atom-sync` | Reactive atom synchronization (server↔browser) |
| `ai-orchestrator` | Multi-provider AI orchestration |
| `ai-orchestrator-tools` | MCP tools for AI orchestrator |
| `anthropic-http-provider` | Anthropic API provider |
| `calculate` | Calculator tool |
| `claude-manager` | Claude subprocess management |
| `claude-subprocess-provider` | Claude subprocess AI provider |
| `clojure-lsp` | Clojure LSP integration |
| `code-browser-v2` | URI-centric code browser with Datalevin backend |
| `datalevin` | Datalevin database integration |
| `datalevin-mcp` | Datalevin MCP tools |
| `datalevin-pod` | Datalevin pod lifecycle |
| `directory-browser` | File system browsing |
| `echo` | Echo tool (example) |
| `expert-registry` | AI expert role registry |
| `hello` | Hello world (example) |
| `http-core` | Shared HTTP infrastructure |
| `math` | Math tools (example) |
| `mcp-http` | HTTP MCP transport with SSE |
| `mcp-local-eval` | Local Clojure eval |
| `mcp-nrepl` | nREPL integration (9 tools) |
| `mcp-stdio` | Stdio transport |
| `message-bus` | Internal message bus |
| `nrepl-proxy-server` | nREPL proxy for browser routing |
| `nrepl-server` | Embedded nREPL server |
| `openai-http-provider` | OpenAI API provider |
| `port-registry` | Ephemeral port management |
| `rest-api` | REST API + OpenAPI |
| `sente-browser` | Sente WebSocket browser transport |
| `streamable-http` | Combined HTTP convenience module |
| `strings` | String tools (example) |
| `telemetry-db` | Queryable in-memory log store |
| `webserver` | HTTP server (http-kit) |

---

## Completed Infrastructure (Reference)

| Phase | Description |
|-------|-------------|
| 1–7 | Foundation, MCP server, tool registry, module system |
| 8–10 | Transport modularization (stdio, http, rest-api) |
| 11–12 | Unified entry point, telemetry |
| 13 | AI orchestration (multi-provider, experts) |
| 14–15 | Dynamic module loading, Datalevin integration |
| 16–20 | nREPL proxy, Scittle browser, MCP CLI, E2E tests |
| 21 | UUIDv7 migration (v1.17.0) |
| 22 | Telemetry infrastructure (v1.18.0) |
| 23 | Browser telemetry ingestion, `!` escaping fix (v1.19.0) |
| 24 | Comprehensive telemetry coverage, catalog improvements, lint compliance (v1.19.1) |
| 25 | Live code refresh: file watching, incremental rescan, browser auto-update (v1.20.0) |
| 26 | clj-statecharts integration for local nREPL server lifecycle (v1.21.0) |
| 27 | Statechart static analyzer: 5 structural checks, CLI (v1.22.0) |
| 28 | Convention checks + state management best practices in CLOJURE_EXPERT_CONTEXT.md (v1.22.1) |
| 29 | Browser connection statechart: 6 states, 9 transitions, CDN-served bundle (v1.23.0) |
| 30 | Server per-connection statechart: 4 states, 5 transitions, both sides of WebSocket (v1.24.0) |
| 31 | File watcher robustness: thread-safe debounce, per-file locks, atomic write handling (v1.25.0) |
| 32 | Runtime project addition: browser input, `bb add-project` CLI, sente warning fixes (v1.26.0) |
| 33 | Live var value display: type-aware rendering, atom auto-deref, statechart detection (v1.29.0) |
| 34 | Stale ns retraction fix, Playwright E2E demo, CLI `!` escaping (v1.32.0) |

---

## References

- [Code Browser Redesign](docs/design/code_browser-review-redesign.md)
- [Atom Sync Design](docs/design/atom-sync-design.md)
- [Static Code Analysis](docs/design/static-code-analysis.md)
- [Module System Design](docs/design/module-system-design.md)
- [Telemetry Levels Policy](docs/TELEMETRY_LEVELS.md)
- [Scittle Dev Environment](docs/SCITTLE_DEV_ENVIRONMENT.md)
- [BB Tasks Reference](docs/bb-tasks-reference.md)
- [nREPL Direct Guide](docs/bb-nrepl-direct-user-guide.md)
- [Exclamation Escaping](docs/exclamation-escaping.md)
- [Nexus Pattern Reference](docs/NEXUS_PATTERN_REFERENCE.md)
- [Statecharts Reference](docs/STATECHARTS_REFERENCE.md)

---

*Last Updated: 2026-02-15*
