# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13C - HTTP Providers COMPLETE)
**Current Version:** v0.13.4 (fixed and working)

---

## Current State - Phase 13C Complete ✅

**Phase 13C HTTP Providers** - Implementation complete, babashka.http-client fix committed, tested successfully.

### Summary

Implemented two HTTP provider modules for ai-orchestrator:

1. **anthropic-http-provider** - Native Anthropic Messages API
2. **openai-http-provider** - OpenAI Chat Completions API (compatible with both OpenAI and Anthropic)

Both providers initially used Java HttpURLConnection which is not compatible with Babashka's security model. Fixed by replacing with babashka.http-client.

**Status:**
- ✅ Both providers implemented and fixed
- ✅ All lint/format/tests pass (9 tests, 43 assertions)
- ✅ Real API connection tested successfully
- ✅ Authentication working (x-api-key and Bearer token headers)
- ✅ Error handling working correctly
- ✅ Committed and pushed to main

**Note:** Real API test showed credit balance issue on test account, but this confirms HTTP requests are reaching Anthropic API successfully and authentication is working (no 401/403 errors).

---

## Module Structure

### anthropic-http-provider
- Native Anthropic Messages API (`https://api.anthropic.com/v1/messages`)
- Authentication: `x-api-key` header
- Protocol implementation for `:anthropic-http`

**Files:**
```
modules/anthropic-http-provider/
├── module.edn
├── src/anthropic_http/
│   ├── core.clj         # Protocol implementation
│   └── http_client.clj  # HTTP client using babashka.http-client
└── test/
    ├── anthropic_http/core_test.clj
    └── run_tests.clj
```

### openai-http-provider
- OpenAI Chat Completions API (`https://api.openai.com/v1/chat/completions`)
- Authentication: `Authorization: Bearer` header
- Works with both OpenAI and Anthropic compatibility endpoint (configurable base-url)
- Protocol implementation for `:openai-http`

**Files:**
```
modules/openai-http-provider/
├── module.edn
├── src/openai_http/
│   ├── core.clj         # Protocol implementation
│   └── http_client.clj  # HTTP client using babashka.http-client
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

# Test anthropic-http-provider
bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :anthropic-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-3-5-sonnet-20241022\"})]
  (println (orch/ask \"test\" \"Say hello\"))
  (orch/stop-instance! \"test\"))"

# Test openai-http-provider with Anthropic compatibility
bb -e "(require '[ai-orchestrator.core :as orch] '[openai-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :openai-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-3-5-sonnet-20241022\"
                                        :base-url \"https://api.anthropic.com/v1\"})]
  (println (orch/ask \"test\" \"Say hello\"))
  (orch/stop-instance! \"test\"))"
```

---

## Project State

**Current Phase:** 13C (v0.13.4) - COMPLETE ✅

**Completed Phases:**
- 13A: Core Scaffolding (claude-manager with mock)
- 13.5: Stdio Safety (lint rules)
- 13-Design: Architecture docs
- 13-Port: Port Registry (v0.13.1)
- 13E: Expert Registry MVP (v0.13.2)
- 13B: Multi-Provider Refactor (v0.13.3)
- 13C: HTTP Providers (v0.13.4) ← CURRENT

**Next Phase:** 13D - MCP Integration

**Module Count:** 14 modules (including new HTTP providers)

---

## Key Technical Details

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

*This context captures the completed Phase 13C implementation.*
