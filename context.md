# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13F Planning - Message Bus)
**Current Version:** v0.13.5

---

## Current State - Pre-Phase 13F

**Phase 13D Complete:** AI orchestrator functionality exposed via 4 MCP tools.

**Next Steps (Phase 13F):**
1. **Fix Concurrency Bug** - HTTP provider router race condition
2. **Implement Message Bus** - Atoms + Promises with Global Response Router

---

## CRITICAL: Concurrency Bug in HTTP Providers

**Location:** `modules/ai-orchestrator/src/ai_orchestrator/router.clj`

**Problem:** `current-request-id` atom is shared per instance. When two threads call `ask` concurrently, second overwrites first's request-id before HTTP response arrives.

```clojure
;; router.clj line 44-45 - THE BUG
(when-let [current-id (:current-request-id instance)]
  (reset! current-id request-id))  ;; Race condition!

;; openai-http/core.clj line 64 - reads stale value in future
(let [request-id @(:current-request-id instance) ...]
```

**Impact:** Concurrent HTTP requests to same instance fail or cross-talk.

**Fix:** Pass `request-id` directly to `send-message`:
1. Change protocol signature: `(send-message instance message request-id)`
2. Or assoc into instance: `(send-message (assoc instance :active-request-id request-id) message)`
3. Update both `openai-http.core` and `anthropic-http.core`

**Affected Files:**
- `modules/ai-orchestrator/src/ai_orchestrator/router.clj`
- `modules/ai-orchestrator/src/ai_orchestrator/protocol.clj`
- `modules/openai-http-provider/src/openai_http/core.clj`
- `modules/anthropic-http-provider/src/anthropic_http/core.clj`

---

## Message Bus Design (Phase 13F)

**Design Doc:** `docs/design/message-bus-design.md`
**Review:** `docs/design/message-bus-design-review-gemini.md`

**Decision:** Option 3 - Atoms + Promises (NOT core.async)

**Rationale:**
- Simpler to debug (fully inspectable state)
- No opaque channel magic
- Standard try/catch error handling
- Sufficient for 2-5 experts

**Key Optimization: Global Response Router**

Instead of creating new subscription per `ask`:
```clojure
;; BAD: Creates/removes subscription per request (atom churn)
(subscribe! reply-topic handler)
(finally (unsub))

;; GOOD: Single subscription, O(1) promise lookup
(def pending-requests (atom {}))  ;; request-id -> promise
;; Single subscription to :replies topic
;; Handler looks up promise and delivers
```

**API Surface:**
```clojure
;; Core
(subscribe! topic handler-fn)     ; -> unsubscribe-fn
(publish! topic msg)              ; -> nil
(ask topic msg :timeout-ms n)     ; -> {:success true :content ...}

;; Introspection
(list-topics)                     ; -> {topic count, ...}
(get-recent-messages n)           ; -> [msg, ...]

;; Teams
(create-team team-id members)
(team-publish! team topic msg)
(team-subscribe! team topic handler-fn)
(team-broadcast! team msg)
```

**Message Schema:**
```clojure
{:id "uuid"
 :topic :keyword
 :from "sender-id"
 :type :command|:event|:query|:response
 :payload {...}
 :ts 123456789}
```

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
modules/message-bus/                  # TODO: Phase 13F
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
- **Phase 13D: MCP Tool Integration (v0.13.5)**

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)

---

*Context prepared for Phase 13F - Concurrency Fix + Message Bus*
