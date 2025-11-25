# Session Context for bb-mcp-server (Post Auto-Compact)

**Last Updated:** 2025-11-24 (Phase 13C - HTTP Providers PARTIAL)
**Current Version:** v0.13.4 (committed), but BROKEN - needs fix

---

## CRITICAL: Current State - TESTING BLOCKED

**Phase 13C HTTP Providers** - Implementation committed (v0.13.4) but **needs immediate fix** before testing.

### What's Broken

The HTTP client in both providers uses Java `HttpURLConnection` which is **NOT compatible with Babashka's security model**:

```
Error: Method setRequestMethod on class sun.net.www.protocol.https.HttpsURLConnectionImpl not allowed!
```

### What Needs to Be Done IMMEDIATELY

**Fix both HTTP client implementations to use `babashka.http-client` instead of Java HttpURLConnection:**

1. **anthropic-http-provider/src/anthropic_http/http_client.clj** - IN PROGRESS (partially fixed)
2. **openai-http-provider/src/openai_http/http_client.clj** - NOT YET FIXED

**Progress:**
- ✅ Changed requires in anthropic-http-provider to use babashka.http-client
- ✅ Removed Java HttpURLConnection imports
- ✅ Simplified build-headers (no longer need connection setup)
- ✅ Rewrote create-message to use http/post
- ❌ Still need to apply same fix to openai-http-provider
- ❌ Need to re-run tests after fixes
- ❌ Need to re-commit the fix

### Testing Instructions (AFTER FIX)

User has API key available via `.cak.sh`:
```bash
# Source the key file (NEVER hardcode, NEVER commit this file)
. ./.cak.sh

# Test command that WILL work after fix:
bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(let [instance (orch/start-instance! \"test\"
                 {:provider-type :anthropic-http
                  :api-key (System/getenv \"CLAUDE_API_KEY\")
                  :model \"claude-3-5-sonnet-20241022\"})]
  (println (orch/ask \"test\" \"Say hello in one word\"))
  (orch/stop-instance! \"test\"))"
```

---

## What Was Built (Phase 13C - v0.13.4)

Created two HTTP provider modules for ai-orchestrator:

### anthropic-http-provider
- Native Anthropic Messages API (`https://api.anthropic.com/v1/messages`)
- Authentication: `x-api-key` header
- Protocol implementation for `:anthropic-http`

### openai-http-provider
- OpenAI Chat Completions API (`https://api.openai.com/v1/chat/completions`)
- Authentication: `Authorization: Bearer` header
- Works with both OpenAI and Anthropic compatibility endpoint (configurable base-url)
- Protocol implementation for `:openai-http`

**Files Created:**
```
modules/anthropic-http-provider/
├── module.edn
├── src/anthropic_http/
│   ├── core.clj         # Protocol implementation
│   └── http_client.clj  # BROKEN - needs babashka.http-client fix
└── test/
    ├── anthropic_http/core_test.clj
    └── run_tests.clj

modules/openai-http-provider/
├── module.edn
├── src/openai_http/
│   ├── core.clj         # Protocol implementation
│   └── http_client.clj  # BROKEN - needs babashka.http-client fix
└── test/
    └── openai_http/core_test.clj
```

**Test Results (before real API testing):**
- Unit tests pass: 9 tests, 43 assertions ✅
- Real API testing blocked by HttpURLConnection issue ❌

---

## How to Fix (Step-by-Step)

### 1. Fix openai-http-provider (same pattern as anthropic-http-provider)

Replace Java HttpURLConnection with babashka.http-client:

```clojure
;; Change requires
(:require [babashka.http-client :as http]
          [cheshire.core :as json]
          [taoensso.trove :as log])

;; Remove imports - no more (:import [java.net HttpURLConnection URL])

;; Replace HTTP call with:
(let [response (http/post url
                         {:headers (build-headers api-key)
                          :body (json/generate-string body)
                          :throw false
                          :connect-timeout timeout-ms
                          :read-timeout timeout-ms})]
  ;; Parse response
  {:status (:status response)
   :body (json/parse-string (:body response) true)})
```

### 2. Re-run verification

```bash
# Lint
clj-kondo --lint modules/anthropic-http-provider/src modules/openai-http-provider/src

# Format
cljfmt check modules/anthropic-http-provider/src modules/openai-http-provider/src
cljfmt fix modules/anthropic-http-provider/src modules/openai-http-provider/src  # if needed

# Tests
bb modules/anthropic-http-provider/test/run_tests.clj
bb modules/openai-http-provider/test/run_tests.clj
```

### 3. Test with real API

```bash
. ./.cak.sh  # Load CLAUDE_API_KEY

# Test anthropic-http-provider
bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :anthropic-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-3-5-sonnet-20241022\"})]
  (println (orch/ask \"test\" \"Say hello\"))
  (orch/stop-instance! \"test\"))"

# Test openai-http-provider with Anthropic compat endpoint
bb -e "(require '[ai-orchestrator.core :as orch] '[openai-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :openai-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-3-5-sonnet-20241022\"
                                        :base-url \"https://api.anthropic.com/v1\"})]
  (println (orch/ask \"test\" \"Say hello\"))
  (orch/stop-instance! \"test\"))"
```

### 4. Commit the fix

```bash
git add modules/anthropic-http-provider/ modules/openai-http-provider/
git commit -m "fix: Use babashka.http-client instead of HttpURLConnection

Replace Java HttpURLConnection with babashka.http-client in both
HTTP providers to fix compatibility with Babashka's security model.

Error was: Method setRequestMethod not allowed on HttpsURLConnectionImpl

Changes:
- anthropic-http-provider: Use http/post from babashka.http-client
- openai-http-provider: Use http/post from babashka.http-client
- Remove Java imports, simplify request handling

Tests: (run counts after fix)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"

# No new tag needed - this is a fix for v0.13.4
git push origin main
```

---

## Project State

**Current Phase:** 13C (v0.13.4) - BROKEN, needs fix
**Completed Phases:**
- 13A: Core Scaffolding (claude-manager with mock)
- 13.5: Stdio Safety (lint rules)
- 13-Design: Architecture docs
- 13-Port: Port Registry (v0.13.1)
- 13E: Expert Registry MVP (v0.13.2)
- 13B: Multi-Provider Refactor (v0.13.3)
- 13C: HTTP Providers (v0.13.4) ← CURRENT, BROKEN

**Next Phase:** 13D - MCP Integration (after fixing 13C)

**Module Count:** 14 modules (including new HTTP providers)

---

## Key Technical Constraints

1. **Babashka compatible** - All code must run in bb (not JVM Clojure)
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use environment variables, .cak.sh is gitignored
6. **IMPLEMENTATION_PLAN.md** - Single source of truth for planning

---

## babashka.http-client API Reference

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

## Ready to Continue?

**Immediate priority:** Fix openai-http-provider to match anthropic-http-provider fix, then test both with real API.

**No other blockers.** All previous phases are solid, just this one compatibility issue to resolve.

---

*This context captures the critical broken state that needs immediate attention before continuing.*
