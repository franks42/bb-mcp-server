# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13F Complete - Message Bus)
**Current Version:** v0.13.6

---

## Current State - Phase 13F Complete

**Phase 13F Complete:** Message bus implemented with full test coverage.

### What Was Done:
1. **Fixed Concurrency Bug** - Pass `request-id` via `:active-request-id` in instance map
2. **Implemented Message Bus** - Atoms + Promises with Global Response Router
3. **25 tests, 68 assertions** - All passing

### Concurrency Fix Applied:
- `router.clj`: Uses `(assoc instance :active-request-id request-id)` before `send-message`
- All providers read from `:active-request-id` instead of shared atom
- Each request gets its own request-id scope - no race conditions

---

## Message Bus Implementation (Phase 13F)

**Module:** `modules/message-bus/`
**Design Doc:** `docs/design/message-bus-design.md`

**Implementation:** Atoms + Promises with Global Response Router (NOT core.async)

**Key Features:**
- **Global Response Router** - Single subscription, O(1) promise lookup
- **Team Isolation** - Namespaced topics prevent cross-talk
- **Ring Buffer Log** - Last 100 messages retained for debugging
- **Error Isolation** - Handler exceptions don't crash bus

**API Surface:**
```clojure
;; Core (message_bus.core)
(subscribe! topic handler-fn)     ; -> unsubscribe-fn
(publish! topic msg)              ; -> nil
(ask topic msg :timeout-ms n)     ; -> {:success true :content ...}
(reply! request-id response)      ; -> boolean

;; Introspection
(list-topics)                     ; -> {topic count, ...}
(get-recent-messages n)           ; -> [msg, ...]
(get-pending-requests)            ; -> count

;; Teams (message_bus.teams)
(create-team! team-id members)
(team-publish! team topic msg)
(team-subscribe! team topic handler-fn)
(team-broadcast! team msg)
```

**Tests:** 25 tests, 68 assertions

**Future:** Datalevin persistence (see `docs/design/datalevin-message-bus-review.md`)

---

## AI Orchestrator MCP Tools (v0.13.5)

**Module:** `modules/ai-orchestrator-tools/`

4 MCP tools for AI instance management:

| Tool | Description |
|------|-------------|
| `ai_start_instance` | Start AI instance (any provider) |
| `ai_ask` | Send message to running instance |
| `ai_stop_instance` | Stop instance and release resources |
| `ai_list_instances` | List all running instances |

**Provider Types:**
- `anthropic-http` - Native Anthropic Messages API (recommended for Claude)
- `openai-http` - OpenAI-compatible API (OpenAI, Gemini, Anthropic compat)
- `claude-subprocess` - Claude CLI subprocess (~11s startup)

**OpenAI-compatible Endpoints (via openai-http):**
- OpenAI: `https://api.openai.com/v1` (default)
- Gemini: `https://generativelanguage.googleapis.com/v1beta/openai`
- Anthropic: `https://api.anthropic.com/v1` (Bearer auth)

---

## Performance Summary

| Provider | Model | Startup | Ongoing Requests |
|----------|-------|---------|------------------|
| anthropic-http | Sonnet 4.5 | ~500ms | 2-3s |
| openai-http | gpt-4o-mini | ~1ms | 0.9-1.7s |
| openai-http | gemini-2.0-flash | ~1ms | 0.6-0.8s |
| claude-subprocess | Sonnet | **~11-12s** | 3-4s |

---

## Project Structure

### AI Modules (Phase 13)
```
modules/ai-orchestrator/              # Core orchestration (5 tests)
modules/ai-orchestrator-tools/        # MCP tools (13 tests, 44 assertions)
modules/anthropic-http-provider/      # Anthropic API (4 tests)
modules/openai-http-provider/         # OpenAI API (5 tests)
modules/claude-subprocess-provider/   # Claude CLI subprocess
modules/port-registry/                # Port allocation (12 tests)
modules/expert-registry/              # Expert definitions (9 tests)
modules/message-bus/                  # Pub/sub + ask/reply (25 tests, 68 assertions)
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
bb test:ai-orchestrator        # AI orchestrator tests

# Verification (REQUIRED before commit)
clj-kondo --lint <files>       # 0 errors, 0 warnings
cljfmt check <files>           # All files formatted
```

---

## Key Design Docs

| Document | Purpose |
|----------|---------|
| `IMPLEMENTATION_PLAN.md` | Single source of truth for planning |
| `docs/design/message-bus-design.md` | Message bus options analysis |
| `docs/design/ai-experts-framework.md` | Expert architecture |
| `docs/design/ai-experts-framework-review-gemini-3.md` | Concurrency bug identified |

---

## Completed Phases

- Phase 13A: Core scaffolding
- Phase 13.5: Stdio safety
- Phase 13-Design: Architecture docs
- Phase 13-Port: Port registry (v0.13.1)
- Phase 13E: Expert registry MVP (v0.13.2)
- Phase 13B: Multi-provider refactor (v0.13.3-v0.13.4.3)
- Phase 13C: HTTP providers (v0.13.4.1)
- Phase 13D: MCP Tool Integration (v0.13.5)
- **Phase 13F: Message Bus + Concurrency Fix (v0.13.6)**

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)

---

*Context updated for Phase 13F Complete - Message Bus Implementation*
