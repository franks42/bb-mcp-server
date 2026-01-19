**CRITICAL: AI must display “I do not cheat or lie and I’m honest about any reporting of progress.” at start of every response**

# Claude Context for bb-mcp-server

## Project Overview

**bb-mcp-server** - Production-ready MCP (Model Context Protocol) server in Clojure/Babashka.

**Features:**
- MCP spec 2025-03-26 compliant
- Streamable HTTP transport with SSE support
- Dynamic module system for hot-reloading tools
- `listChanged` notification capability (broadcasts when tools are added/removed)

---

## Project Structure

```
bb-mcp-server/
├── src/bb_mcp_server/           # Core server code
│   ├── main.clj                 # Unified entry point (v0.11.0)
│   ├── handlers/                # MCP message handlers
│   ├── module/                  # Module system
│   ├── protocol/                # JSON-RPC routing & processor
│   └── registry.clj             # Tool registry
├── modules/                     # Loadable modules
│   ├── mcp-stdio/               # Stdio transport (pure, no deps)
│   ├── mcp-http/                # HTTP MCP transport with SSE
│   ├── rest-api/                # REST API + OpenAPI
│   ├── http-core/               # Shared HTTP infrastructure
│   ├── streamable-http/         # Combined HTTP (convenience)
│   ├── nrepl/                   # nREPL integration (9 tools)
│   ├── calculate/               # Calculator tool
│   ├── local-eval/              # Local Clojure eval
│   └── echo/, strings/, math/   # Example modules
├── scripts/                     # Utility scripts
│   └── pid_util.clj             # PID file management
├── docs/design/                 # Design documents (reference only)
└── bb.edn                       # Babashka config
```

---

## Common Commands

> **Full reference:** See `docs/bb-tasks-reference.md` for complete CLI documentation.
> **Before writing curl/bash:** Check if a bb task exists first!

```bash
bb tasks                        # List available tasks
bb server:start-wait --nickname X --config Y  # Start server, wait for health
bb server:stop X                # Stop server by nickname
bb server:list                  # List running servers

bb lint && bb format            # Verify before commit
bb test:modules                 # Run all module tests

bb mcp servers                  # List running MCP servers
bb mcp tools --mcp X            # List tools
bb mcp call <tool> '<json>'     # Call any tool

bb nrepl list --mcp X           # List nREPL connections
bb nrepl eval "<code>" --mcp X  # Eval code
```

**Avoid command chaining:** Don't use `cmd & sleep && curl` patterns. Use `bb server:start-wait` or separate tool calls. See `docs/bb-tasks-reference.md` for details.

---

## Evaluating Code in Running Servers

**Best practice:** Use `.clj` script files with `load-file` instead of inline code.

**Why:** Avoids shell escaping issues with `!` characters in Clojure function names.

```bash
# PREFERRED: Use script files
bb nrepl load-file scripts/cb-v2-init.clj --mcp cb-v2-test --connection server

# Also works: JSON args-file (for simple cases)
bb mcp call local-eval.local-eval --args-file scripts/cb-v2-init.json --mcp cb-v2-test

# AVOID: Inline code with ! characters (escaping issues)
bb nrepl eval "(init! ...)" --mcp cb-v2-test  # ! gets escaped incorrectly
```

**Script locations:**
- `scripts/*.clj` - Reusable Clojure scripts for server-side execution
- `scripts/*.json` - JSON args for `--args-file` option (simple cases only)

**When to create scripts:** If you'll run the same code more than once, create a `.clj` script file.

---

## Verification Workflow

**MUST run before committing - zero errors AND zero warnings required:**
```bash
clj-kondo --lint <files>    # MUST be 0 errors, 0 warnings
cljfmt check <files>        # MUST have no formatting issues
bb test:modules             # MUST pass all tests
```

Do NOT commit code with lint warnings. Fix all warnings before committing.

**After editing Clojure files:** Use `bb lint-fix` to catch and fix paren errors:
```bash
bb lint-fix <file>          # Lint, auto-fix parens if needed, re-lint
```

**macOS Note:** Do NOT use `timeout` command (it doesn't exist on macOS). Use `sleep` or Babashka's built-in timeout options instead.

---

## Required Reading for AI Assistants

**MUST read at start of every new session:**

1. **CLAUDE.md** (this file) - Project instructions and workflow
2. **context.md** - Current session state, recent changes, active work
3. **docs/CLOJURE_EXPERT_CONTEXT.md** - Clojure development standards, honesty requirements, verification workflow
4. **docs/AI_TELEMETRY_GUIDE.md** - Telemetry patterns (all I/O and business logic must have telemetry)
5. **IMPLEMENTATION_PLAN.md** - Task tracking and pending work

**Reference when needed:**
- **docs/bb-tasks-reference.md** - All bb tasks and CLI commands (CHECK BEFORE WRITING CURL/BASH!)
- **docs/agent-delegation-guide.md** - Subagent workflow for multi-file tasks

**When working on Scittle browser development:**
- **docs/SCITTLE_DEV_ENVIRONMENT.md** - Step-by-step setup guide (REQUIRED before any Scittle work!)

---

## Key Technical Notes

1. **Babashka compatible** - All code must run in bb, not just JVM Clojure
2. **http-kit for HTTP** - SSE primitives verified working in bb
3. **Ring middleware pattern** - `(fn [handler] (fn [req] ...))`
4. **Module system** - Modules in `system.edn`, loaded via `ns_loader.clj`
5. **Tool notifications** - Registry broadcasts `notifications/tools/list_changed` on changes
6. **Telemetry required** - Use `taoensso.trove` for all logging (see AI_TELEMETRY_GUIDE.md)
7. **mcp-eval CLI** - Use `bb mcp-eval "[code]"` to test/debug/inspect running servers (see README)
8. **nrepl CLI** - Use `bb nrepl <cmd>` to connect/eval/load-file on remote nREPL servers (see README)
9. **rebel-nrepl-client** - Use `bb rebel-nrepl-client [port]` to open iTerm2 with rebel-readline connected to nREPL
10. **mcp CLI** - Use `bb mcp <cmd>` to explore/test any MCP tool: `servers`, `tools`, `call`, `init` (see README)
11. **E2E tests** - Use `bb test:e2e` to run real protocol tests (requires running server with `--nickname e2e-test`)

---

## Version Lookup Policy

**NEVER trust training data or web search snippets for library versions.**

When looking up the latest version of any library:
1. **Clojure/Java libs:** `WebFetch` directly on `https://clojars.org/<lib>/versions`
2. **GitHub projects:** `WebFetch` directly on `https://github.com/<org>/<repo>/releases`
3. **npm packages:** `WebFetch` directly on `https://www.npmjs.com/package/<pkg>?activeTab=versions`

**Why:** Search results and training data become stale. Using old versions wastes time and may expose fixed bugs. Always fetch the authoritative source directly.

---

## Planning & Task Tracking

**IMPORTANT:** Use `IMPLEMENTATION_PLAN.md` as the **single source of truth** for:
- Project phases and milestones
- Task status and progress
- Implementation decisions
- Architecture changes

Do NOT create or update alternative plan documents (e.g., in `docs/design/` or module subdirectories). All planning updates go in `IMPLEMENTATION_PLAN.md`.

---

## Context Checkpoints in Todos

**Purpose:** Prevent context loss after auto-compaction by making checkpoints part of every task plan.

**When creating todos for a phase, include checkpoint tasks:**

```
Phase X: [Feature Name]
─────────────────────────
- [ ] Checkpoint: Document starting state in context.md
- [ ] Task 1: Research/explore
- [ ] Task 2: Implement core logic
- [ ] Checkpoint: Update context.md before multi-file changes
- [ ] Task 3: Integration/wiring
- [ ] Task 4: Tests + verification
- [ ] Checkpoint: Final state + learnings in context.md
```

**Checkpoint timing:**
1. **Start of phase** - Capture what exists, what we're changing, why
2. **Before multi-file changes** - Point of no return; document approach
3. **End of phase** - Results, decisions made, session notes for next time

**What to capture in context.md:**
- Current working branch/state
- Files being modified
- Key decisions made
- Blockers or open questions
- Test status

This makes checkpointing structural (visible, accountable) rather than memory-dependent.

---

## Session Health & Compaction Awareness

After auto-compaction, I lose context and may become less effective. If you notice me:
- Forgetting which files to update (e.g., using wrong plan file)
- Missing verification steps (lint/format/test)
- Being corrected for things already discussed
- Asking questions I should know the answer to
- Repeating earlier mistakes

**Tell the user:** "I may be degraded from context compaction. Consider starting a fresh Claude session."

Rule of thumb: After 2-3 auto-compacts on complex work, a fresh session is more productive than continuing.

---

*Last Updated: 2026-01-10*
