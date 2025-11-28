# Session Context for bb-mcp-server

**Last Updated:** 2025-11-28 (Phase 15.5 Webserver Complete)
**Current Version:** v0.15.5

---

## Current State - Phase 15.5 Webserver Complete

**Phase 15.5 (webserver module) is complete.** Next: Phase 15C "AI Knowledge Persistence".

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
| 15C | AI Knowledge Persistence | Planned | Experts, prompts, conversations (6 sub-phases) |
| 15D | Message Bus Migration | Planned | Evaluate replacing atoms with Datalevin |

---

## Phase 15A Testing Strategy

Minimal server config for isolated testing:
```clojure
;; system.edn for Phase 15A testing
{:modules ["local-eval" "datalevin-pod"]}
```

Test via local-eval:
```clojure
(require '[datalevin-pod.core :as dl])
(dl/get-conn)
(dl/transact! [{:person/name "Test User"}])
(dl/q '[:find ?name :where [?e :person/name ?name]])
```

---

## Key Design Docs

| Document | Purpose |
|----------|---------|
| `IMPLEMENTATION_PLAN.md` | Single source of truth for planning |
| `docs/design/datalevin-options.md` | Datalevin integration design |
| `docs/design/datalevin-options-review.md` | Gemini's review |

---

## Key Commands

```bash
# Run server (minimal for testing)
bb server --http               # HTTP on port 3000

# Testing
bb test:modules                # All module tests

# Verification (REQUIRED before commit)
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

---

*Context updated 2025-11-28 - Phase 15.5 Webserver Complete, Phase 15C next*
