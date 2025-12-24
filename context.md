# Session Context for bb-mcp-server

**Last Updated:** 2025-12-24 (nrepl-proxy-server Complete, tagged v1.3.0)
**Current Version:** v1.3.0

---

## For Next Claude Session

**What just happened:**
1. Completed nrepl-proxy-server module - shadow-cljs style browser REPL proxy
2. Integration tested with 3 concurrent Playwright browser tabs
3. All code committed, tagged v1.3.0, pushed to main

**Key achievement:** Standard nREPL clients (Calva, terminal) can now connect to port 1667 and switch between bb and browser REPLs using shadow-cljs style API.

**Start fresh session with:**
```
Read CLAUDE.md, docs/CLOJURE_EXPERT_CONTEXT.md, IMPLEMENTATION_PLAN.md, and this context.md
```

---

## Current State - nrepl-proxy-server Complete (v1.3.0)

### nrepl-proxy-server Module ✅ (2025-12-24)

Shadow-cljs style browser REPL proxy - connect Calva/terminal to browser REPLs:

**API:**
```clojure
(browser/list)           ; List available browsers
(browser/repl :browser-127)  ; Switch to browser context
:cljs/quit               ; Return to bb context
```

**Features:**
- Multi-browser support (tested with 3 concurrent Playwright tabs)
- Seamless switching between browsers
- ClojureScript eval in browser (`js/navigator`, `js/document`, etc.)
- Automatic session cleanup on disconnect
- Module dependencies: `sente-browser` for WebSocket browser connections

**Architecture:**
- `session.clj` - Per-nREPL-session target routing (:bb or browser-id)
- `api.clj` - Browser API namespace with list/repl/quit commands
- `server.clj` - TCP nREPL server with bencode protocol
- `core.clj` - Module entry point with `:start`/`:stop`/`:status`

**Key files:**
- `modules/nrepl-proxy-server/src/nrepl_proxy_server/*.clj`
- `system.edn` - config with `:enabled true :port 1667`

**Tests:** 15 tests, 44 assertions - all passing

---

## Previous State - Phase 15.5 Webserver Complete

**Phase 15.5 (webserver module) is complete and tagged v0.15.5.** Next: Phase 15C "AI Knowledge Persistence".

### Phase 15.5: Webserver Module ✅ (2025-11-28)

Simple static file server for human-facing dashboards/UIs:

- Static file serving (HTML/CSS/JS) with 25+ MIME types
- Live reload via WebSocket + file watcher (toggle at runtime)
- Hiccup template rendering (.hiccup → HTML)
- Multiple concurrent servers on different ports
- API: `start!`, `stop!`, `list-servers`, `set-reload!`
- Default port 9876 (avoids 8080 conflicts)
- 20 unit tests, 54 assertions - all passing
- Playwright browser tests ready

```clojure
(require '[webserver.core :as ws])
(ws/start! {:port 9876 :root "./webroot" :reload true :hiccup true})
```

**Key files:**
- `modules/webserver/src/webserver/core.clj` - Public API
- `modules/webserver/src/webserver/handler.clj` - HTTP handler, MIME types
- `modules/webserver/src/webserver/reload.clj` - WebSocket live reload
- `modules/webserver/src/webserver/hiccup.clj` - Template rendering
- `modules/webserver/README.md` - User guide

### Phase 15C: AI Knowledge Persistence (Next)

Scope expanded beyond just conversations to include full AI knowledge management:

| # | Task | Description |
|---|------|-------------|
| 15C.1 | Conversation persistence | Store turns with timestamps, roles |
| 15C.2 | Expert definitions → DB | Migrate manifest.edn to Datalevin |
| 15C.3 | Dynamic Prompt Store | Curriculum as versioned DB entities |
| 15C.4 | Tool requirements | `:expert/tools` refs |
| 15C.5 | Query by capabilities | Datalog queries for expert discovery |
| 15C.6 | Hot-swap instructions | Update prompts without restart |

**Design Principle:** File-based `.experts/` remains source of truth for version control. Datalevin is runtime cache for dynamic updates.

### Phase 15B+ Completed (2025-11-27):
- Added optional tools: `pull`, `find-by`
- Now 5 MCP tools total: schema, q, transact, pull, find-by
- Unit tests: 20 tests, 71 assertions - all passing

### Phase 15A-B Completed (2025-11-27):
- datalevin-pod: Datalevin v0.9.27, connection management
- datalevin-mcp: 3 core tools (schema, q, transact)
- Safe EDN parsing with helpful AI error messages

---

## Datalevin Integration Architecture

### Two Modules:
```
┌─────────────────────────────────────┐
│         MCP Client (AI)             │
└──────────────┬──────────────────────┘
               │
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
┌──────────┐    ┌──────────────┐
│ local-eval│    │ datalevin-mcp│
│ (raw clj) │    │ (schema/q/tx)│
└─────┬─────┘    └──────┬───────┘
      │                 │
      └────────┬────────┘
               │
               ▼
        ┌─────────────┐
        │datalevin-pod│
        │ (pod + conn)│
        └──────┬──────┘
               │
               ▼
        ┌─────────────┐
        │  Datalevin  │
        │   (LMDB)    │
        └─────────────┘
```

| Module | Purpose | AI Access |
|--------|---------|-----------|
| `datalevin-pod` | Pod lifecycle, connection management | Via `local-eval` (raw Clojure) |
| `datalevin-mcp` | MCP tools interface | Via MCP tools (`schema`/`q`/`transact`/`pull`/`find-by`) |

### Configuration:
- **Default path:** `/var/db/datalevin/bb-mcp-server`
- **Override:** `BB_MCP_DATALEVIN_PATH` environment variable

### Version:
- **Datalevin:** v0.9.27 (November 2025)
- **Pod:** `(pods/load-pod 'huahaiy/datalevin "0.9.27")`

---

## Phase 15 Sub-phases

| # | Sub-phase | Status | Description |
|---|-----------|--------|-------------|
| 15A | datalevin-pod module | ✅ Complete | Pod loading, connection lifecycle |
| 15B | datalevin-mcp module | ✅ Complete | MCP tools: `schema`, `q`, `transact` |
| 15B+ | Optional tools | ✅ Complete | Added `pull`, `find-by` (20 tests, 71 assertions) |
| 15.5 | Webserver module | ✅ Complete | Static file server, live reload, hiccup (20 tests, 54 assertions) |
| 15C | AI Knowledge Persistence | **Next** | Experts, prompts, conversations (6 sub-phases) |
| 15D | Message Bus Migration | Planned | Evaluate replacing atoms with Datalevin |

---

## Key Commands

```bash
# Run server
bb server --http               # HTTP on port 3000
bb server --stdio              # stdio transport (Claude Desktop)

# Testing
bb test:modules                # All module tests
bb test:webserver              # Webserver module tests

# Verification (REQUIRED before commit - ZERO warnings)
clj-kondo --lint <files>       # 0 errors, 0 warnings
cljfmt check <files>           # All files formatted
```

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Datalevin v0.9.27** - Always verify version at clojars.org/datalevin/versions
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)
6. **defonce no docstrings** - Babashka SCI doesn't support docstrings in defonce

---

## Recent Commits

```
c58cbaa chore: Add design docs, utils, and update gitignore
7daf3a6 feat(nrepl-proxy-server): Add shadow-cljs style browser REPL proxy
7da2ea3 feat(sente-browser): Show connection nickname in browser UI
```

---

*Context updated 2025-12-24 - nrepl-proxy-server Complete (v1.3.0)*
