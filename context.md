# Session Context for bb-mcp-server

**Last Updated:** 2025-11-26 (Phase 15 Added - Datalevin Integration)
**Current Version:** v0.13.8

---

## Current State - Ready for Phase 15 (Datalevin)

**All previous phases complete through Phase 13G.** Next major work: Datalevin database integration.

### Recent Session Work (2025-11-26):
1. Fixed IMPLEMENTATION_PLAN.md - Phase 13G was showing "Planned" but was actually Complete
2. Added Phase 15: Datalevin Database Integration to IMPLEMENTATION_PLAN.md
3. Researched Datalevin Pod integration from `../scittle-nrepl-bb-dl-db` project

---

## Datalevin Integration Research (Ready to Implement)

**Reference project:** `../scittle-nrepl-bb-dl-db` has working Datalevin integration

### Key Technical Details:
- **Pod:** `huahaiy/datalevin` version `0.9.22`
- **Namespace:** `pod.huahaiy.datalevin` (alias as `d`)
- **Backend:** LMDB (C library) - requires pod approach

### Working Pattern from Reference:
```clojure
;; Load pod once
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.9.22")
(require '[pod.huahaiy.datalevin :as d])

;; Connect to database
(def conn (d/get-conn "/path/to/db" schema))

;; Transact data
(d/transact! conn [{:person/name "Alice" :person/age 30}])

;; Query
(d/q '[:find ?name :where [?e :person/name ?name]] (d/db conn))

;; Listen for changes (push notifications - no polling!)
(d/listen! conn :my-listener (fn [tx-report] ...))
```

### Why Datalevin for bb-mcp-server:
1. **Blackboard Architecture** - Agents write to shared DB, others react via `d/listen!`
2. **Push notifications** - No polling needed
3. **Persistence** - Survives restarts, enables conversation history
4. **Query power** - Datalog queries, aggregations, pull API

### Design Doc:
- `docs/design/datalevin-message-bus-review.md` - Gemini's analysis

---

## AI Orchestrator Architecture (Phase 13 Complete)

### Providers (3 types):
- `anthropic-http` - Native Anthropic Messages API (recommended)
- `openai-http` - OpenAI-compatible API
- `claude-subprocess` - Claude CLI subprocess (has file access)

### Performance:
| Provider | Startup | Ongoing | File Access |
|----------|---------|---------|-------------|
| anthropic-http | <1s | 2-5s | No |
| openai-http | <1s | 0.6-1.7s | No |
| claude-subprocess | ~11-12s | 3-4s | **Yes** |

### Multi-Agent Test Results (Phase 13G):
```
clojure-coder (subprocess)    → 36s  (writes code to disk)
code-reviewer (anthropic-http) → 5s   (isolated review)
test-writer (anthropic-http)   → 12s  (generates tests)
─────────────────────────────────────
Total:                          65 seconds
```

---

## Module Structure

### AI Modules (Phase 13)
```
modules/ai-orchestrator/              # Core orchestration
modules/ai-orchestrator-tools/        # MCP tools
modules/anthropic-http-provider/      # Anthropic API
modules/openai-http-provider/         # OpenAI API
modules/claude-subprocess-provider/   # Claude CLI subprocess
modules/port-registry/                # Port allocation
modules/expert-registry/              # Expert definitions
modules/message-bus/                  # Pub/sub + ask/reply
```

### MCP Server Core
```
src/bb_mcp_server/
├── main.clj                          # Unified entry point
├── handlers/                         # MCP message handlers
├── module/                           # Module system
├── protocol/                         # JSON-RPC routing
└── registry.clj                      # Tool registry
```

---

## Key Commands

```bash
# Run server
bb server                      # stdio (Claude Desktop)
bb server --http               # HTTP on port 3000
bb server --http 8080          # HTTP on custom port

# Testing
bb test:modules                # All module tests

# Multi-agent test
source .cak.sh && bb scripts/multi_agent_test.clj

# Verification (REQUIRED before commit)
clj-kondo --lint <files>       # 0 errors, 0 warnings
cljfmt check <files>           # All files formatted
```

---

## Key Design Docs

| Document | Purpose |
|----------|---------|
| `IMPLEMENTATION_PLAN.md` | Single source of truth for planning |
| `docs/design/datalevin-message-bus-review.md` | Datalevin as message bus analysis |
| `docs/design/multi-agent-interaction-learnings.md` | Prompt patterns & anti-patterns |
| `docs/design/ai-experts-framework.md` | Expert architecture |

---

## Completed Phases

- Phase 13A-G: AI Orchestrator complete (v0.13.8)
- Phase 14: Module System (dynamic loading verified)

## Next Phase

**Phase 15: Datalevin Database Integration** (IMPLEMENTATION_PLAN.md)
- 15A: Core Integration (pod loading, connection management)
- 15B: Conversation Persistence
- 15C: Message Bus Migration (replace in-memory with Datalevin)
- 15D: Expert Registry Persistence

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)

---

## Immediate Next Steps (for Fresh Session)

1. Read `CLAUDE.md` and `docs/CLOJURE_EXPERT_CONTEXT.md`
2. Review `IMPLEMENTATION_PLAN.md` Phase 15
3. Start Phase 15A: Create `modules/datalevin/` module
   - `module.edn` - module descriptor
   - `core.clj` - pod lifecycle + connection management
   - MCP tools for query/transact

---

*Context updated 2025-11-26 - Ready for Datalevin Integration*
