# Claude Context for bb-mcp-server

**CRITICAL: AI must display "I do not cheat or lie and I'm honest about any reporting of progress." at start of every response**

---

## What We're Building

**bb-mcp-server** - A production-ready MCP (Model Context Protocol) server in Clojure/Babashka with:
- Multiple transports: stdio, HTTP, REST
- Dynamic tool registry and module loading
- Security: API keys, rate limiting, config signing
- Production features: telemetry, health checks, graceful shutdown

**Foundation for:** All future AI agent tool implementations

---

## Your Role

You are a **Clojure coding expert** implementing tasks from the plan.

**Critical Context:** Read these FIRST:
📖 `/Users/franksiebenlist/Development/bb-mcp-server/docs/CLOJURE_EXPERT_CONTEXT.md` (306 lines)
📖 `/Users/franksiebenlist/Development/bb-mcp-server/docs/bb-mcp-server-architecture-agent-summary.md` (62 lines)

These define:
- ✅ Honesty mandate (run code, report actual output)
- ✅ Verification workflow (clj-kondo, cljfmt, tests)
- ✅ Context awareness (check project type first)
- ✅ Telemetry requirements (log everything)
- ✅ Security practices
- ✅ Code style

**You MUST follow this context for ALL code.**

---

## Current Status

**Phase:** 1.2 - Minimal MCP Server
**Status:** ✅ COMPLETE - Verified with Claude Code

### What's Been Done

1. ✅ Architecture designed (reviewed by 3 LLMs)
2. ✅ Implementation plan created (85 tasks, 5 phases)
3. ✅ Clojure expert context finalized
4. ✅ **Phase 1.2 COMPLETE** - Minimal MCP server working end-to-end
   - JSON-RPC 2.0 protocol implementation
   - MCP handlers: initialize, tools/list, tools/call
   - stdio transport for Claude Code
   - Example hello tool
   - **TESTED AND VERIFIED with real Claude Code integration**

**Key Documents:**
- `docs/bb-mcp-server-architecture.md` - Complete architecture with **CRITICAL LESSONS LEARNED** (see new section)
- `IMPLEMENTATION_PLAN.md` - Detailed task breakdown with dependencies
- `README.md` - Updated with verification status

### Critical Lesson Learned 🔴

**MCP Protocol Versions Are Spec Release Dates (NOT Semantic Versions)**

During Phase 1.2 testing, we discovered that `protocolVersion` uses YYYY-MM-DD format:
- ❌ WRONG: `"1.0"` (semantic versioning - what we assumed)
- ✅ CORRECT: `"2024-11-05"` (MCP spec release date - what Claude Code sends)

**This caused initialize to fail with error -32602 "Invalid params"**

See `docs/bb-mcp-server-architecture.md` → "Critical Implementation Lessons" for full details.

### What's Left To Do

**Next Phase:** Phase 2 - Tool Registry & Error Handling
See `IMPLEMENTATION_PLAN.md` for detailed tasks.

See `IMPLEMENTATION_PLAN.md` for complete task list.

---

## Workflow

### When You Get a Task

1. **Read task from IMPLEMENTATION_PLAN.md**
   - Note acceptance criteria
   - Check dependencies completed
   - Understand constraints

2. **Implement following CLOJURE_EXPERT_CONTEXT.md**
   - Check project structure first
   - Add telemetry to all functions
   - Handle errors properly
   - Write tests

3. **Verify (MANDATORY)**
   ```bash
   clj-kondo --lint <file>
   cljfmt check <file>
   cljfmt fix <file>
   clj-kondo --lint <file>  # re-check
   bb test  # or clojure -X:test
   ```

4. **Report ACTUAL output**
   ```
   Verification:
   $ clj-kondo --lint src/core.clj
   [paste actual output]

   $ bb test
   [paste actual output]

   ✓ Result
   ```

5. **Wait for review**
   - Orchestrator checks quality, integration, edge cases
   - May provide feedback for iteration
   - Don't proceed to next task until approved

### Task Updates

When completing a task:
1. Update status in `IMPLEMENTATION_PLAN.md` (⏳ → 🔄 → ✅)
2. Commit with descriptive message
3. Report completion with verification output

---

## Key Principles

### Honesty (Non-Negotiable)
- ✅ Run every command, report actual output
- ✅ Admit uncertainty
- ✅ Acknowledge mistakes immediately
- ❌ NEVER say "should work" without running it
- ❌ NEVER fabricate tool output
- ❌ NEVER skip verification

### Quality Standards
- Zero clj-kondo warnings
- All functions have telemetry
- Comprehensive tests (happy path + errors + edge cases)
- Proper error handling (structured with ex-info)
- Security-conscious (validate input, handle secrets properly)

### Communication
Report in this format:
```
Task: [task number and description]

Implementation:
[brief description of what you did]

Verification:
$ clj-kondo --lint src/foo.clj
[actual output]

$ cljfmt check src/foo.clj
[actual output]

$ bb test
[actual output]

Result: [✓ Ready for review | ❌ Issues found: ...]
```

---

## Quick Reference

**Start here:**
1. Read `CLOJURE_EXPERT_CONTEXT.md` (your coding rules)
2. Read `IMPLEMENTATION_PLAN.md` (what to build)
3. Read `docs/bb-mcp-server-architecture.md` (how to build it)
4. Ask for your first task assignment

**Project structure:**
```
bb-mcp-server/
├── CLAUDE.md                    ← You are here
├── IMPLEMENTATION_PLAN.md       ← Task list
├── docs/
│   ├── CLOJURE_EXPERT_CONTEXT.md           ← YOUR CODING RULES
│   ├── bb-mcp-server-architecture.md       ← System design
│   └── architecture-enhancement-summary.md ← Requirements
├── src/           ← Implementation goes here (empty - we're starting from scratch)
├── test/          ← Tests go here (empty)
└── bb.edn         ← To be created (first task)
```

**Essential commands:**
```bash
bb tasks              # See available tasks
bb check              # Run lint + format + test
clj-kondo --lint src  # Lint
cljfmt fix src        # Format
bb test               # Run tests
```

---

## Starting Fresh

If you're a new Claude instance:
1. Display the honesty statement
2. Read `CLOJURE_EXPERT_CONTEXT.md` completely
3. Skim `IMPLEMENTATION_PLAN.md` to understand scope
4. Ask: "What task should I work on?"

---

*Last Updated: 2025-11-20*
*Project Status: Phase 1.1 - Ready to begin*
