# Session Context for bb-mcp-server

**Last Updated:** 2025-11-25 (Phase 13G Complete - Multi-Agent Orchestration)
**Current Version:** v0.13.8

---

## Current State - Phase 13G Complete 🎉

**Phase 13G Complete:** Multi-agent orchestration working - 3 AI agents collaborating in 65 seconds!

### What Was Done:
1. **Multi-Agent Pipeline** - clojure-coder → code-reviewer → test-writer
2. **Mixed Providers** - subprocess (file access) + anthropic-http (isolated, fast)
3. **Message Bus Routing** - All agents communicate via ask/reply pattern
4. **Interaction Learnings** - Documented prompt patterns and anti-patterns

### Final Test Results:
```
clojure-coder (subprocess)    → 36s  (writes code to disk)
code-reviewer (anthropic-http) → 5s   (isolated review, APPROVED)
test-writer (anthropic-http)   → 12s  (generates comprehensive tests)
─────────────────────────────────────
Total:                          65 seconds
```

---

## Key Learnings (Phase 13G)

**Documented in:** `docs/design/multi-agent-interaction-learnings.md`

### 1. Prompt Patterns
- ❌ "If the code is PERFECT, respond APPROVED" → endless loops
- ✅ "If the code will WORK correctly, respond APPROVED" → approved in 5s
- ✅ Focus on BLOCKER/BUG/CRASH only, ignore style preferences

### 2. Provider Selection
| Use Case | Provider | Reason |
|----------|----------|--------|
| Write files to disk | subprocess | Has file access |
| Code review | anthropic-http | Isolated, fast, fresh perspective |
| Test generation | anthropic-http | Isolated, predictable timing |

### 3. Subprocess Isolation
When using subprocess for text generation (not file writing), add:
```
Base your response ONLY on the code provided.
Do NOT access the project's files or run any commands.
```

HTTP agents don't need this - isolation is enforced by transport.

---

## AI Orchestrator Architecture

### Providers (3 types):
- `anthropic-http` - Native Anthropic Messages API (recommended for Claude)
- `openai-http` - OpenAI-compatible API (OpenAI, Gemini, Anthropic compat)
- `claude-subprocess` - Claude CLI subprocess (~11s startup, has file access)

### Performance:
| Provider | Startup | Ongoing | File Access |
|----------|---------|---------|-------------|
| anthropic-http | <1s | 2-5s | No |
| openai-http | <1s | 0.6-1.7s | No |
| claude-subprocess | ~11-12s | 3-4s | **Yes** |

---

## Module Structure

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
| `docs/design/multi-agent-interaction-learnings.md` | **NEW** Prompt patterns & anti-patterns |
| `docs/design/multi-agent-orchestration-test.md` | Multi-agent test design |
| `docs/design/message-bus-design.md` | Message bus options analysis |
| `docs/design/ai-experts-framework.md` | Expert architecture |

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
- Phase 13F: Message Bus + Concurrency Fix (v0.13.6)
- **Phase 13G: Multi-Agent Orchestration (v0.13.7-v0.13.8)** ✅

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Use babashka.http-client** - NOT Java HttpURLConnection
3. **Telemetry required** - taoensso.trove for all I/O
4. **Zero lint warnings** - Not just errors
5. **Never commit API keys** - Use .cak.sh (gitignored)
6. **No `timeout` command** - Use `sleep` (macOS compatibility)

---

*Context updated for Phase 13G Complete - Multi-Agent Orchestration*
