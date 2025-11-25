# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13B & 13C Complete + Performance Testing)
**Current Version:** v0.13.4.3 (All providers tested, performance validated)

---

## Current State - Phase 13 Infrastructure Complete ✅

**Phase 13B & 13C Complete:** All three AI providers (anthropic-http, openai-http, claude-subprocess) are implemented, tested with real APIs, and performance-validated.

**Next Phase:** 13D - MCP Tool Integration (Expose orchestrator via MCP tools)

---

## Performance Test Results (NEW - v0.13.4.3)

**Test Date:** 2025-11-25
**Test:** 5 concurrent instances (3 HTTP + 2 subprocess) with two consecutive requests

### Key Performance Findings

| Provider | First Request (with startup) | Second Request (ongoing) | Startup Overhead |
|----------|------------------------------|--------------------------|------------------|
| subprocess-1 (Sonnet) | 14,642ms (API: 3,844ms) | 3,364ms (API: 3,359ms) | **~11,300ms** |
| subprocess-2 (Haiku) | 15,366ms (API: 4,787ms) | 3,560ms (API: 3,559ms) | **~11,800ms** |
| sonnet-http | 3,301ms | 2,776ms | ~525ms |
| haiku-http | 680ms | 611ms | ~69ms |
| sonnet-compat (OpenAI) | 3,325ms | 2,298ms | ~1,027ms |

**Critical Insights:**
1. **Subprocess startup overhead: ~11-12 seconds** (process spawn + CLI init)
2. **After init, subprocess comparable to HTTP**: 3.3-3.6s vs 2.3-2.8s
3. **API time nearly identical**: ~3.3s subprocess vs ~2.7s HTTP
4. **HTTP providers have minimal startup**: ~500-1000ms

**Design Implications for Phase 13D+:**
- **Quick tasks (< 1 minute):** Use HTTP providers (lower startup)
- **Long-running experts:** Subprocess acceptable (startup amortized)
- **Ephemeral experts:** HTTP preferred (fast spawn/destroy)
- **Persistent experts:** Subprocess fine (startup happens once)

**Documentation:**
- Full results in `docs/design/ai-experts-framework.md` (performance section)
- Test methodology in `IMPLEMENTATION_PLAN.md` (Phase 13B section)

---

## AI Orchestrator Architecture

**Module:** `modules/ai-orchestrator/`

Three providers implemented and tested:

### 1. anthropic-http-provider ✅
- Native Anthropic Messages API
- Auth: `x-api-key` header
- Models: Claude 4.5 (Sonnet, Haiku, Opus)
- Performance: Fast (600ms-3s depending on model)

### 2. openai-http-provider ✅
- OpenAI Chat Completions API
- Auth: `Authorization: Bearer` header
- Compatible with both OpenAI AND Anthropic (via base-url override)
- Performance: Similar to anthropic-http

### 3. claude-subprocess-provider ✅
- Claude CLI subprocess with JSONL stdio
- Command vector: `[cmd "-p" "--verbose" "--input-format" "stream-json" ...]`
- Performance: ~11s startup, then 3-4s per request
- **Fixed in v0.13.4.2:** CLI args properly configured

**Core API:**
```clojure
(require '[ai-orchestrator.core :as orch])
(require '[anthropic-http.core])  ; Load provider

;; Start instance
(orch/start-instance! "my-ai"
  {:provider-type :anthropic-http
   :api-key (System/getenv "CLAUDE_API_KEY")
   :model "claude-sonnet-4-5-20250929"})

;; Ask question
(orch/ask "my-ai" "Say hello")
;=> {:content "Hello!", :duration_ms 2800}

;; Stop instance
(orch/stop-instance! "my-ai")
```

**Testing:**
```bash
# Unit tests
bb test:ai-orchestrator              # 5 tests
bb modules/anthropic-http-provider/test/run_tests.clj  # 4 tests
bb modules/openai-http-provider/test/run_tests.clj     # 5 tests

# Real API test
. ./.cak.sh  # Load CLAUDE_API_KEY
bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(orch/start-instance! \"test\" {:provider-type :anthropic-http
                                :api-key (System/getenv \"CLAUDE_API_KEY\")
                                :model \"claude-sonnet-4-5-20250929\"})
(println (orch/ask \"test\" \"Say hello\"))
(orch/stop-instance! \"test\")"
```

---

## Next Phase: 13D - MCP Tool Integration

**Goal:** Expose AI orchestrator functionality via MCP tools

**What needs to happen:**
1. Create MCP tools module (`modules/ai-orchestrator-tools/`)
2. Implement tools:
   - `ai_start_instance` - Start AI instance with config
   - `ai_ask` - Send message to instance
   - `ai_stop_instance` - Stop instance
   - `ai_list_instances` - List active instances
3. Register tools in main MCP server
4. Test via MCP protocol (initialize → tools/list → tools/call)
5. Integration tests with all three providers

**Design considerations:**
- Tool input schema for provider config (model, api-key, etc.)
- Error handling for invalid providers/models
- Instance naming/ID management
- Telemetry for all tool calls

**Reference:**
- See `IMPLEMENTATION_PLAN.md` Phase 13D section for full spec
- Review `modules/nrepl/` for MCP tool pattern examples

---

## Project Structure

### AI Provider Modules
```
modules/ai-orchestrator/              # Core orchestration
modules/anthropic-http-provider/      # Anthropic API
modules/openai-http-provider/         # OpenAI/Anthropic compat
modules/claude-subprocess-provider/   # Claude CLI subprocess
```

### Supporting Infrastructure
```
modules/port-registry/                # Port allocation for experts
modules/expert-registry/              # Expert definitions & curriculum
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

## Key Technical Details

### Async HTTP Provider Pattern

HTTP providers integrate with router's promise system:

```clojure
;; Instance structure
{:name name
 :provider-type :anthropic-http
 :model model
 :transport {...}
 :pending-requests (atom {})       ; Router manages
 :current-request-id (atom nil)    ; Router sets before send
 :session-id (atom nil)}

;; send-message implementation
(defmethod proto/send-message :anthropic-http
  [instance message]
  (let [request-id @(:current-request-id instance)
        pending-requests (:pending-requests instance)]
    ;; Async HTTP call in future
    (future
      (let [result (http/post ...)]
        ;; Deliver to promise
        (when-let [p (get @pending-requests request-id)]
          (deliver p {:content result :duration_ms ...})
          (swap! pending-requests dissoc request-id))))
    true))  ; Return immediately
```

### Subprocess Provider Pattern

```clojure
;; Build command vector with CLI args
(let [base-args ["-p" "--verbose"
                 "--input-format" "stream-json"
                 "--output-format" "stream-json"
                 "--permission-mode" "bypassPermissions"]
      cmd-vec (vec (concat [cmd] base-args))]
  (spawn-process! cmd-vec))

;; JSONL stdio communication
;; Write: {"type":"ask","data":{"message":"..."}}
;; Read:  {"content":"...","cost_usd":0,"duration_ms":3850}
```

### babashka.http-client API

```clojure
(require '[babashka.http-client :as http])

(http/post url
  {:headers {"Content-Type" "application/json"
             "x-api-key" api-key}
   :body (json/generate-string data)
   :throw false
   :connect-timeout 30000
   :read-timeout 30000})
;=> {:status 200 :body "..." :headers {...}}
```

---

## Model Configuration

### Claude 4.5 Models (Current)
- **Sonnet 4.5**: `claude-sonnet-4-5-20250929` (recommended, balanced)
- **Haiku 4.5**: `claude-haiku-4-5-20251001` (fast, low cost)
- **Opus 4.5**: `claude-opus-4-5-20251101` (most capable)

**Files:**
- `modules/anthropic-http-provider/anthropic-models.edn`
- `modules/openai-http-provider/openai-models.edn`

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)
6. **IMPLEMENTATION_PLAN.md** - Single source of truth

---

## Completed Phases

- ✅ **Phase 13A**: Core scaffolding (claude-manager)
- ✅ **Phase 13.5**: Stdio safety (lint rules)
- ✅ **Phase 13-Design**: Architecture docs
- ✅ **Phase 13-Port**: Port registry (v0.13.1)
- ✅ **Phase 13E**: Expert registry MVP (v0.13.2)
- ✅ **Phase 13B**: Multi-provider refactor (v0.13.3 → v0.13.4.3)
  - Claude subprocess provider with CLI args fix
  - Performance testing complete
- ✅ **Phase 13C**: HTTP providers (v0.13.4.1)
  - Anthropic HTTP provider
  - OpenAI HTTP provider
  - Async routing integration

---

## Session Health Note

**This context document was rewritten after multiple auto-compaction cycles in the previous session.**

If the next Claude session shows signs of degradation:
- Forgetting which files to update
- Missing verification steps
- Repeating earlier mistakes
- Asking questions already answered

**Recommendation:** Start a fresh Claude session rather than continuing a degraded one.

---

*Context prepared for Phase 13D - MCP Tool Integration*
