# Session Context for bb-mcp-server

**Last Updated:** 2025-11-26 (Phase 15 Planning Complete)
**Current Version:** v0.13.8

---

## Current State - Implementing Phase 15A (datalevin-pod)

**Planning complete.** Now implementing Phase 15A: datalevin-pod module.

### Planning Session Work (2025-11-26):
1. Researched Datalevin v0.9.27 (latest as of Nov 20, 2025)
2. Created `docs/design/datalevin-options.md` - comprehensive design doc
3. Reviewed Gemini's `docs/design/datalevin-options-review.md`
4. Defined two-module architecture: `datalevin-pod` + `datalevin-mcp`
5. Identified minimal tool interface: 3 tools (`schema`, `q`, `transact`)
6. Added Version Lookup Policy to CLAUDE.md
7. Updated IMPLEMENTATION_PLAN.md with detailed Phase 15A-D tasks

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
| `datalevin-mcp` | MCP tools interface | Via MCP tools (`schema`/`q`/`transact`) |

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
| 15A | datalevin-pod module | **In Progress** | Pod loading, connection lifecycle |
| 15B | datalevin-mcp module | Planned | MCP tools: `schema`, `q`, `transact` |
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

*Context updated 2025-11-26 - Implementing Phase 15A*
