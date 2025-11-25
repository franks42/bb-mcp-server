# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13C - HTTP Providers + Async Routing COMPLETE)
**Current Version:** v0.13.4.1 (async routing working)

---

## Current State - Phase 13C Complete ✅

**Phase 13C HTTP Providers** - Implementation complete with async routing integration.

### Summary

Implemented two HTTP provider modules for ai-orchestrator:

1. **anthropic-http-provider** - Native Anthropic Messages API
2. **openai-http-provider** - OpenAI Chat Completions API (compatible with both OpenAI and Anthropic)

Both providers initially used Java HttpURLConnection (not Babashka compatible), then had timeout issues with sync routing. Both issues fixed.

**Implementation Fixes:**
1. Replaced Java HttpURLConnection with babashka.http-client
2. Implemented async routing with promise delivery (Path 1)
   - HTTP calls made in futures (non-blocking)
   - Responses delivered to router promises via request correlation
   - Added :current-request-id atom to instance structure

**Status:**
- ✅ Both providers implemented and fixed
- ✅ Async routing working correctly (no timeouts)
- ✅ All lint/format/tests pass (9 tests, 43 assertions)
- ✅ Real API connection tested successfully with claude-sonnet-4-5-20250929
- ✅ Authentication working (x-api-key and Bearer token headers)
- ✅ Error handling working correctly
- ✅ Model config files added (anthropic-models.edn, openai-models.edn)
- ✅ Committed to main (commit ea78acf)

**Real API Test Result:** Response received in 2.8s: `{:content "Hello", :duration_ms 2837}`

---

## Module Structure

### anthropic-http-provider
- Native Anthropic Messages API (`https://api.anthropic.com/v1/messages`)
- Authentication: `x-api-key` header
- Protocol implementation for `:anthropic-http`
- Async HTTP with promise delivery

**Files:**
```
modules/anthropic-http-provider/
├── module.edn
├── anthropic-models.edn        # Claude 4.5 model identifiers
├── src/anthropic_http/
│   ├── core.clj                # Protocol implementation with async routing
│   └── http_client.clj         # HTTP client using babashka.http-client
└── test/
    ├── anthropic_http/core_test.clj
    └── run_tests.clj
```

### openai-http-provider
- OpenAI Chat Completions API (`https://api.openai.com/v1/chat/completions`)
- Authentication: `Authorization: Bearer` header
- Works with both OpenAI and Anthropic compatibility endpoint (configurable base-url)
- Protocol implementation for `:openai-http`
- Async HTTP with promise delivery

**Files:**
```
modules/openai-http-provider/
├── module.edn
├── openai-models.edn           # OpenAI + Anthropic compat model identifiers
├── src/openai_http/
│   ├── core.clj                # Protocol implementation with async routing
│   └── http_client.clj         # HTTP client using babashka.http-client
└── test/
    └── openai_http/core_test.clj
```

---

## Testing

### Unit Tests
```bash
bb modules/anthropic-http-provider/test/run_tests.clj  # 4 tests, 18 assertions
bb modules/openai-http-provider/test/run_tests.clj     # 5 tests, 25 assertions
```

### Real API Tests

User has API key in `.cak.sh` (gitignored - NEVER commit):

```bash
. ./.cak.sh  # Sets CLAUDE_API_KEY environment variable

# Test anthropic-http-provider (use Claude 4.5 models from anthropic-models.edn)
bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :anthropic-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-sonnet-4-5-20250929\"
                                        :max-tokens 100})]
  (println (orch/ask \"test\" \"Say hello in exactly one word\"))
  (orch/stop-instance! \"test\"))"

# Test openai-http-provider with Anthropic compatibility
bb -e "(require '[ai-orchestrator.core :as orch] '[openai-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :openai-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-sonnet-4-5-20250929\"
                                        :base-url \"https://api.anthropic.com/v1\"
                                        :max-tokens 100})]
  (println (orch/ask \"test\" \"Say hello in exactly one word\"))
  (orch/stop-instance! \"test\"))"
```

**Expected Result:** `{:content "Hello", :duration_ms ~2800}`

---

## Project State

**Current Phase:** 13C (v0.13.4.1) - COMPLETE ✅

**Completed Phases:**
- 13A: Core Scaffolding (claude-manager with mock)
- 13.5: Stdio Safety (lint rules)
- 13-Design: Architecture docs
- 13-Port: Port Registry (v0.13.1)
- 13E: Expert Registry MVP (v0.13.2)
- 13B: Multi-Provider Refactor (v0.13.3)
- 13C: HTTP Providers + Async Routing (v0.13.4.1) ← CURRENT

**Next Phase:** 13D - MCP Integration

**Module Count:** 14 modules

**Key Achievement:** HTTP providers now work correctly with async router using promise delivery pattern. Real API test successful with claude-sonnet-4-5-20250929.

---

## Key Technical Details

### Async HTTP Provider Pattern

HTTP providers must integrate with router's promise system:

```clojure
;; Instance structure (required fields)
{:name name
 :provider-type :anthropic-http
 :model model
 :transport {...}
 :pending-requests (atom {})         ; Router manages this
 :current-request-id (atom nil)      ; Router sets this before send-message
 :session-id (atom nil)}

;; send-message implementation (async pattern)
(defmethod proto/send-message :anthropic-http
  [instance message]
  (let [request-id @(:current-request-id instance)
        pending-requests (:pending-requests instance)]
    ;; Make HTTP call async in a future
    (future
      (let [result (http/ask-message ...)]
        ;; Deliver response to promise
        (when-let [p (get @pending-requests request-id)]
          (deliver p {:content result :duration_ms duration})
          (swap! pending-requests dissoc request-id))))
    ;; Return true immediately (don't block)
    true))
```

### babashka.http-client API

```clojure
(require '[babashka.http-client :as http])

;; POST request
(http/post url {:headers {"Content-Type" "application/json"
                          "Authorization" "Bearer xyz"}
                :body (json/generate-string data)
                :throw false                ; Don't throw on error status
                :connect-timeout 30000      ; ms
                :read-timeout 30000})       ; ms

;; Response format:
{:status 200
 :headers {"content-type" "application/json"}
 :body "..."}  ; String, needs json/parse-string
```

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb (not JVM Clojure)
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use environment variables, .cak.sh is gitignored
6. **IMPLEMENTATION_PLAN.md** - Single source of truth for planning

---

## Model Configuration Files

### anthropic-models.edn
Located in `modules/anthropic-http-provider/anthropic-models.edn`

Current Claude 4.5 models:
- **Sonnet 4.5**: `claude-sonnet-4-5-20250929` (recommended for testing)
- **Haiku 4.5**: `claude-haiku-4-5-20251001` (fast, lower cost)
- **Opus 4.5**: `claude-opus-4-5-20251101` (most capable)

Legacy models also documented. Use specific versioned IDs for production.

### openai-models.edn
Located in `modules/openai-http-provider/openai-models.edn`

- OpenAI models (gpt-4-turbo, gpt-4, gpt-3.5-turbo)
- Anthropic compatibility section (same models as anthropic-models.edn)
- Set `base-url` to `https://api.anthropic.com/v1` for Anthropic compatibility

---

*This context captures the completed Phase 13C implementation with async routing integration.*
