# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13D Complete - MCP Tool Integration)
**Current Version:** v0.13.5

---

## Current State - Phase 13D Complete

**Phase 13D Complete:** AI orchestrator functionality exposed via 4 MCP tools. Full end-to-end testing with real Anthropic API verified.

**Next Phase:** 13F - Message Bus (core.async bus, team-based communication)

---

## AI Orchestrator MCP Tools (NEW - v0.13.5)

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

**Example MCP Usage:**
```bash
# Initialize session
curl -X POST http://localhost:3000/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"clientInfo":{"name":"test"}},"id":1}'

# Start AI instance
curl -X POST http://localhost:3000/mcp -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"ai-orchestrator-tools.ai_start_instance","arguments":{"name":"test-ai","provider_type":"anthropic-http","model":"claude-sonnet-4-5-20250929","api_key":"..."}},"id":2}'

# Ask question
curl -X POST http://localhost:3000/mcp -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"ai-orchestrator-tools.ai_ask","arguments":{"name":"test-ai","message":"What is 2+2?"}},"id":3}'
# Response: {"content":"4","duration_ms":696}
```

---

## Performance Summary

| Provider | Model | Startup | Ongoing Requests |
|----------|-------|---------|------------------|
| anthropic-http | Sonnet 4.5 | ~500ms | 2-3s |
| openai-http | gpt-4o-mini | ~1ms | 0.9-1.7s |
| openai-http | gemini-2.0-flash | ~1ms | 0.6-0.8s |
| claude-subprocess | Sonnet | **~11-12s** | 3-4s |

**Design Guidance:**
- **Quick tasks:** Use HTTP providers (fast startup)
- **Long-running experts:** Subprocess acceptable (startup amortized)
- **Ephemeral experts:** HTTP preferred (fast spawn/destroy)

---

## AI Orchestrator Architecture

**Core Module:** `modules/ai-orchestrator/`

**Provider Modules:**
- `modules/anthropic-http-provider/` - Native Anthropic API
- `modules/openai-http-provider/` - OpenAI/Anthropic compat
- `modules/claude-subprocess-provider/` - Claude CLI subprocess

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

---

## Project Structure

### AI Modules (Phase 13)
```
modules/ai-orchestrator/              # Core orchestration (5 tests)
modules/ai-orchestrator-tools/        # MCP tools (13 tests, 44 assertions) NEW
modules/anthropic-http-provider/      # Anthropic API (4 tests)
modules/openai-http-provider/         # OpenAI API (5 tests)
modules/claude-subprocess-provider/   # Claude CLI subprocess
modules/port-registry/                # Port allocation (12 tests)
modules/expert-registry/              # Expert definitions (9 tests)
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
bb server --stdio --http       # Both transports

# Testing
bb test:modules                # All module tests
bb test:ai-orchestrator        # AI orchestrator tests
bb modules/ai-orchestrator-tools/test/run_tests.clj  # MCP tools tests

# Verification
clj-kondo --lint <files>       # 0 errors, 0 warnings required
cljfmt check <files>           # All files formatted
```

---

## Model Configuration

### Claude 4.5 Models (Current)
- **Sonnet 4.5**: `claude-sonnet-4-5-20250929` (recommended, balanced)
- **Haiku 4.5**: `claude-haiku-4-5-20251001` (fast, low cost)
- **Opus 4.5**: `claude-opus-4-5-20251101` (most capable)

---

## Completed Phases

- Phase 13A: Core scaffolding (claude-manager)
- Phase 13.5: Stdio safety (lint rules)
- Phase 13-Design: Architecture docs
- Phase 13-Port: Port registry (v0.13.1)
- Phase 13E: Expert registry MVP (v0.13.2)
- Phase 13B: Multi-provider refactor (v0.13.3-v0.13.4.3)
- Phase 13C: HTTP providers (v0.13.4.1)
- **Phase 13D: MCP Tool Integration (v0.13.5)** NEW

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)
6. **IMPLEMENTATION_PLAN.md** - Single source of truth

---

## Session Health Note

If the Claude session shows signs of degradation:
- Forgetting which files to update
- Missing verification steps
- Repeating earlier mistakes

**Recommendation:** Start a fresh Claude session rather than continuing.

---

*Context prepared for Phase 13F - Message Bus*
