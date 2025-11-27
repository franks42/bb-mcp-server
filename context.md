# Session Context for bb-mcp-server

**Last Updated:** 2025-11-27 (Phase 15B+ Complete)
**Current Version:** v0.15.2

---

## Current State - Phase 15B+ Complete, Ready for 15C

**Phase 15B+ (datalevin-mcp enhanced) is complete.** Ready to implement Phase 15C: Conversation Persistence.

### Phase 15B+ Completed (2025-11-27):
- Added optional tools: `pull`, `find-by`
- Now 5 MCP tools total: schema, q, transact, pull, find-by
- Unit tests: 20 tests, 71 assertions - all passing

### Phase 15B Completed (2025-11-27):
- Created `modules/datalevin-mcp/` module structure
- Implemented 3 MCP tools: schema, q, transact
- Safe EDN parsing with helpful AI error messages
- Unit tests: 11 tests, 40 assertions - all passing
- HTTP integration tests: 6 tests - all passing

### Phase 15A Completed (2025-11-27):
- datalevin-pod module with Datalevin v0.9.27
- Unit tests: 5 tests, 27 assertions
- Integration via local-eval: 6 tests

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
| 15C | Conversation Persistence | Planned | Store AI conversation turns |
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

*Context updated 2025-11-27 - Phase 15B+ Complete (5 tools), ready for Phase 15C*
