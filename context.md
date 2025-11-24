# bb-mcp-server - Session Context

**Status:** v0.12.0 - Phase 12 Complete (Telemetry Audit), Phase 13A Complete (Claude Manager Scaffolding)
**Updated:** 2025-11-24

---

## Critical Reminders for Claude

### 1. Plan Before Code
**ALWAYS update `IMPLEMENTATION_PLAN.md` BEFORE implementing.** The user will remind you if you forget. This is the single source of truth for planning - NOT docs/design/*.md files.

### 2. Verification Workflow
Run before every commit:
```bash
clj-kondo --lint <files>    # MUST be 0 errors, 0 warnings
cljfmt check <files>        # MUST have no formatting issues
bb test:modules             # MUST pass all tests
```

### 3. Key Files
- **CLAUDE.md** - Project instructions (READ THIS)
- **IMPLEMENTATION_PLAN.md** - Single source of truth for planning
- **system.edn** - Module configuration
- **src/bb_mcp_server/main.clj** - Unified entry point

---

## Current Session Summary

### What Was Accomplished

**Phase 13A Complete ✅** - Claude Manager Scaffolding:
- Implemented basic claude-manager module with mock process testing
- Created core.clj (spawn!, ask, kill!, list-instances)
- Created process.clj (spawn-process!, start-reader-loop!)
- Created registry.clj (instance tracking & state management)
- Created mock_claude.clj (JSONL echo mock for testing)
- 12 tests, 23 assertions - all passing ✅
- Verification: 0 lint errors, 0 warnings, all tests passing ✅

**Key Architecture Decision:**
- Implemented **Dedicated Reader Loop pattern** from Gemini review
- One background future per instance reads stdout continuously
- Solves concurrency issues from clay-noj-ai prototype
- Thread-safe: multiple callers can ask same instance

**Files Modified This Session:**
- `IMPLEMENTATION_PLAN.md` - Added Phase 13 section
- `bb.edn` - Added claude-manager paths and test:claude-manager task
- `context.md` - This file

**New Files Created:**
- `modules/claude-manager/` - Complete module structure
- `docs/design/claude-subprocess-spawning-architecture.md` - Phase 13 architecture doc
- `gemini-claude-subprocess-spawning-review.md` - Gemini's review with reader loop recommendation

### What's Pending

**Next Steps for Phase 13:**
- Phase 13B: Real Claude CLI integration (replace mock with real `claude` command)
- Phase 13C: MCP tool exposure (claude_spawn, claude_message, etc.)

**Uncommitted Changes:**
```
M IMPLEMENTATION_PLAN.md
M bb.edn
M calculator-usage.edn
M context.md
?? docs/design/claude-subprocess-spawning-architecture.md
?? gemini-claude-subprocess-spawning-review.md
?? gemini-recommendations-3.md
?? gemini-recommendations-4.md
?? gemini-recommendations-final.md
?? modules/claude-manager/
```

---

## Recent Phases

### Phase 12 Complete ✅ (v0.12.0)
**Telemetry Audit** - Added logging to Phase 9-11 code:
- Added 10 telemetry events to `main.clj`
- Verified all Phase 9-11 files had proper telemetry
- Files modified: `src/bb_mcp_server/main.clj`

### Phase 11 Complete ✅ (v0.11.0)
**Unified Entry Point** - Single `bb server` command:
```bash
bb server              # stdio (default, Claude Desktop)
bb server --http       # HTTP only on port 3000
bb server --http 8080  # HTTP on custom port
bb server --stdio --http       # both transports simultaneously
bb server --help       # show usage
```

---

## Test Counts (as of Phase 13A)

- Core: 40 tests, 161 assertions
- nrepl: 34 tests, 131 assertions
- http-core: 50 tests, 105 assertions
- mcp-http: 31 tests, 62 assertions
- mcp-stdio: 10 tests, 33 assertions
- rest-api: 9 tests, 56 assertions
- **claude-manager: 12 tests, 23 assertions** ⭐ NEW
- **Total: ~187 tests**

---

## Architecture

```
┌──────────────┐      ┌──────────────┐
│  Stdio       │      │  HTTP (SSE)  │
│  Transport   │      │  Transport   │
└──────┬───────┘      └──────┬───────┘
       │                     │
       ▼                     ▼
┌────────────────────────────────────┐
│         Unified Processor          │
│ (bb-mcp-server.protocol.processor) │
└────────────────┬───────────────────┘
                 │
                 ▼
┌────────────────────────────────────┐
│              Router                │
│ (bb-mcp-server.protocol.router)    │
└────────────────┬───────────────────┘
                 │
                 ▼
┌──────────────┐      ┌──────────────┐
│  Handlers    │      │  Registry    │
└──────────────┘      └──────────────┘
```

REST API bypasses JSON-RPC - calls registry/handlers directly.

---

## Module Structure

```
modules/
├── http-core/         # Shared HTTP infrastructure (SSE, middleware)
├── mcp-http/          # MCP JSON-RPC over HTTP with sessions
├── mcp-stdio/         # Stdio transport (pure, no bb-mcp-server deps)
├── rest-api/          # REST endpoints + OpenAPI
├── streamable-http/   # Convenience wrapper (mcp-http + rest-api)
├── nrepl/             # nREPL integration (9 tools)
├── calculate/         # Calculator tool
├── local-eval/        # Local Clojure eval
├── claude-manager/    # Claude subprocess spawning ⭐ NEW
├── echo/, strings/, math/, hello/  # Example modules
```

---

## Completed Phases

- **Phase 1-7**: Foundation, transports, module system, REST API
- **Phase 8**: Transport module extraction (http-core, mcp-http, rest-api)
- **Phase 9**: Legacy cleanup (deleted transport/ directory)
- **Phase 10**: Decoupled mcp-stdio (pure transport layer)
- **Phase 11**: Unified entry point (`bb server` with flags) - v0.11.0
- **Phase 12**: Telemetry audit (added logging to Phases 9-11) - v0.12.0
- **Phase 13A**: Claude manager scaffolding with mock testing ✅

---

## bb.edn Tasks

```bash
bb server [flags]           # Run server (see --help)
bb server:stop <port>       # Stop server via PID file
bb test:modules             # All module tests
bb test:claude-manager      # Claude manager tests ⭐ NEW
bb lint                     # clj-kondo
bb format                   # cljfmt
```

---

## Key Design Documents

- **IMPLEMENTATION_PLAN.md** - The plan (single source of truth)
- **docs/design/claude-subprocess-spawning-architecture.md** - Phase 13 architecture
- **gemini-claude-subprocess-spawning-review.md** - Gemini's review (reader loop pattern)
- **docs/AI_TELEMETRY_GUIDE.md** - Telemetry patterns
- MCP Spec: https://modelcontextprotocol.io/specification/2025-03-26/

---

## Session Health Note

After 2-3 auto-compactions on complex work, consider starting a fresh Claude session for better productivity. Signs of degradation:
- Forgetting which files to update
- Missing verification steps
- Repeating earlier mistakes
- Asking questions already answered
