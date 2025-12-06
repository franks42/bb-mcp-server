# Session Context for bb-mcp-server

**Last Updated:** 2025-11-28 (Phase 15.5 Webserver Complete, tagged v0.15.5)
**Current Version:** v0.15.5

---

## For Next Claude Session

**What just happened:**
1. Completed Phase 15.5 - Webserver module for human-facing dashboards
2. All code committed, tagged v0.15.5, pushed to main

**Next task:** Phase 15C - AI Knowledge Persistence (see table below)

**Start fresh session with:**
```
Read CLAUDE.md, docs/CLOJURE_EXPERT_CONTEXT.md, IMPLEMENTATION_PLAN.md, and this context.md
```

---

## Current State - Phase 15.5 Webserver Complete

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
e5c12f4 docs: Update plan and context for Phase 15.5 webserver
74b7770 feat(webserver): Add static file server module with live reload
f0d4680 docs: Expand Phase 15C scope to AI Knowledge Persistence
```

---

*Context updated 2025-11-28 - Phase 15.5 Webserver Complete (v0.15.5), Phase 15C next*
