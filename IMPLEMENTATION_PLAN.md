# bb-mcp-server Implementation Plan

**Status:** v1.20.0 — Live code refresh with file watching + automatic browser widget invalidation
**Last Updated:** 2026-02-08

---

## Current State

Production-ready MCP server with 33 modules, dynamic tool registry, dual transport (stdio + streamable HTTP), browser-based Code Browser v2 with WinBox floating windows, CM6 editor, and queryable telemetry infrastructure.

### Version History (recent milestones)

| Version | Tag | Description |
|---------|-----|-------------|
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
| R3.5 | Git status display | **Pending** |

**Tests:** 34 tests, 494 assertions passing (`bb test:module code-browser-v2`)

#### Browser UI Enhancements (v1.15–v1.16) ✅ COMPLETE

| Feature | Version | Description |
|---------|---------|-------------|
| Widget architecture | v1.15.0 | URI-parameterized widgets, `!widgets` atom, hash routing |
| WinBox windows | v1.15.0 | Floating draggable/resizable windows replacing flex layout |
| Collapsible breadcrumb | v1.15.1 | Vertical breadcrumb with navigable parent segments |
| CM6 editor | v1.16.0 | CodeMirror 6 replacing `<pre>` for source view |
| Zoom fix | v1.16.1 | Fixed zoom creep, Phase 1 UI enhancements |

#### Phase R4: Additional Sources — Pending

| Task | Description | Status |
|------|-------------|--------|
| R4.1 | JAR source adapter | Pending |
| R4.2 | GitHub source adapter | Pending |
| R4.3 | nREPL source adapter (Live Mode) | Pending |

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

### Live Mode (nREPL Introspection)
- Connect to running nREPL and introspect live system
- Loaded vs unloaded namespaces, live var values
- tools.trace integration

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

---

*Last Updated: 2026-02-08*
