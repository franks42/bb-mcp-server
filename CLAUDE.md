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
│   ├── mcp-nrepl/               # nREPL integration (9 tools)
│   ├── calculate/               # Calculator tool
│   ├── mcp-local-eval/          # Local Clojure eval
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

# nrepl-direct (preferred — direct TCP, no MCP overhead)
bb nrepl-direct list -t X                     # List connections
bb nrepl-direct eval "<code>" -t X            # Eval on server (use double quotes!)
bb nrepl-direct eval "<code>" -t X/browser-1  # Eval in browser
bb nrepl-direct load-local-file <path> -t X/browser-1  # Load ClojureScript file
bb nrepl-direct load-local-js-file <path> -t X/browser-1  # Import JS as ES module

# MCP-based CLIs (fallback)
bb mcp servers                  # List running MCP servers
bb mcp tools --mcp X            # List tools
bb mcp call <tool> '<json>'     # Call any tool
bb nrepl list --mcp X           # List nREPL connections
bb nrepl eval "<code>" --mcp X  # Eval code
```

**Avoid command chaining:** Don't use `cmd & sleep && curl` patterns. Use `bb server:start-wait` or separate tool calls. See `docs/bb-tasks-reference.md` for details.

---

## Evaluating Code in Running Servers

**Best practice:** Use `bb nrepl-direct` with the `-t` (target) shorthand.

**CRITICAL — `!` character escaping:**
AI tool environments (including Claude Code's Bash tool) escape `!` to `\!` inside **single-quoted** strings, silently breaking Clojure code containing `swap!`, `reset!`, `mount!`, `!atom-name`, etc.

**Rules:**
1. **ALWAYS use double quotes** for eval strings containing `!`: `bb nrepl-direct eval "(swap! x inc)" -t X`
2. **NEVER use single quotes** for eval strings containing `!`: ~~`bb nrepl-direct eval '(swap! x inc)' -t X`~~ — BROKEN
3. **For complex code, use `load-local-file`** instead of inline eval — avoids all escaping issues

```bash
# PREFERRED: Double quotes for eval (REQUIRED when code contains !)
bb nrepl-direct eval "(+ 1 2 3)" -t myserver               # Server eval
bb nrepl-direct eval "(mount!)" -t myserver/browser-1       # Browser eval
bb nrepl-direct load-local-file scripts/init.clj -t myserver/browser-1  # Load CLJS file
bb nrepl-direct load-local-js-file lib/utils.js -t myserver/browser-1  # Import JS module
bb nrepl-direct list -t myserver                            # List connections

# ALSO WORKS: Script files for complex multi-line code
bb nrepl-direct load-local-file scripts/cb-v2-init.clj -t cb-v2-test

# FALLBACK: MCP-based CLIs (when you need MCP-specific tools)
bb nrepl eval "(+ 1 2 3)" --mcp myserver                   # Via MCP nrepl module
bb mcp call mcp-local-eval.local-eval '{"code":"..."}' --mcp myserver  # Server-side eval
```

**Script locations:**
- `scripts/*.clj` - Reusable Clojure scripts for server-side execution

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

## Session Startup — Memory-First Workflow

**CLAUDE.md** (this file) is always loaded into the system prompt. Everything else comes from **memory queries first, files as fallback.**

**MUST do at start of every new session (in this order):**

1. **Query memory for current project state:**
   ```
   mcp__memory__retrieve_memory query="bb-mcp-server current project state and recent work" n_results=5
   mcp__memory__search_by_tag tags=["current-state", "bb-mcp-server"]
   ```
2. **Query memory for critical rules and constraints:**
   ```
   mcp__memory__search_by_tag tags=["critical", "rule", "architecture"]
   ```
3. **Query memory for task-relevant context** (based on what user asks):
   ```
   mcp__memory__retrieve_memory query="<topic user is asking about>" n_results=5
   ```
4. **Only read files if memory is insufficient** — fall back to:
   - `context.md` — if memory has no recent project state
   - Specific docs — if memory has no relevant patterns
   - Note: `IMPLEMENTATION_PLAN.md` is archived — status lives in memory

**Reference docs (query memory first, read file as fallback):**
- Clojure standards → `mcp__memory__search_by_tag tags=["clojure", "standards"]` → fallback: `docs/CLOJURE_EXPERT_CONTEXT.md`
- Telemetry patterns → `mcp__memory__search_by_tag tags=["telemetry", "logging"]` → fallback: `docs/AI_TELEMETRY_GUIDE.md`
- BB tasks/CLI → `mcp__memory__search_by_tag tags=["bb-tasks", "cli"]` → fallback: `docs/bb-tasks-reference.md`
- Subagent guide → `mcp__memory__search_by_tag tags=["subagent", "delegation"]` → fallback: `docs/agent-delegation-guide.md`
- Scittle dev → `mcp__memory__search_by_tag tags=["scittle", "setup"]` → fallback: `docs/SCITTLE_DEV_ENVIRONMENT.md`

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
12. **Action-dispatch pattern** - For new event handlers, prefer pure functions returning action data over direct I/O. See `docs/NEXUS_PATTERN_REFERENCE.md` for the Nexus-inspired pattern guide
13. **Statecharts for lifecycles** - For modules with explicit state/status atoms, consider formalizing with clj-statecharts (BB + Scittle compatible fork at `../clj-statecharts-bb-scittle`). See `docs/STATECHARTS_REFERENCE.md`
14. **Browser testing: Playwright MCP tools ONLY** - For ALL browser/E2E testing, use the Playwright MCP tools (`mcp__playwright__browser_navigate`, `browser_snapshot`, `browser_click`, `browser_run_code`, `browser_evaluate`, etc.). NEVER install npx packages, create TypeScript test files, or use `npx playwright` CLI. The MCP tools provide interactive, real-time browser automation directly from your conversation.

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

**MCP memory is the single source of truth** for project status, phases, and decisions.

- **Phase status:** `mcp__memory__search_by_tag tags=["phase-status", "bb-mcp-server"]`
- **Architecture:** `mcp__memory__search_by_tag tags=["architecture", "bb-mcp-server"]`
- **Decisions:** `mcp__memory__search_by_tag tags=["decision", "bb-mcp-server"]`
- **Priorities:** `mcp__memory__search_by_tag tags=["priorities", "bb-mcp-server"]`

When phases change status or decisions are made, **store a memory** — don't update files.

When the user wants a project overview, **query memory and render** a formatted table/summary.

`IMPLEMENTATION_PLAN.md` is archived (frozen 2026-02-18). Do NOT update it.

---

## Context Checkpoints — Memory-Native

**Purpose:** Prevent context loss after auto-compaction by storing checkpoints in the memory DB.

**When creating todos for a phase, include checkpoint tasks:**

```
Phase X: [Feature Name]
─────────────────────────
- [ ] Checkpoint: Store starting state in memory
- [ ] Task 1: Research/explore
- [ ] Task 2: Implement core logic
- [ ] Checkpoint: Store approach and progress in memory
- [ ] Task 3: Integration/wiring
- [ ] Task 4: Tests + verification
- [ ] Checkpoint: Store final state + learnings in memory
```

**How to store checkpoints:**
```
mcp__memory__store_memory
  content: "<what's happening, what files are changing, key decisions>"
  metadata: {tags: ["current-state", "bb-mcp-server", "<feature-tag>"], type: "checkpoint"}
```

**What to capture in each checkpoint:**
- Current working branch/state
- Files being modified and why
- Key decisions made
- Blockers or open questions
- Test status

**Advantages over context.md:**
- Survives compaction (query memory to recover context)
- History preserved (old checkpoints stay, new ones added)
- Selective retrieval (get only checkpoints relevant to current work)
- No file to forget to update (store_memory is the update)

---

## Session Health & Compaction Recovery

After auto-compaction, context is lost. **First recovery step: query memory.**

```
mcp__memory__retrieve_memory query="bb-mcp-server current state and recent work" n_results=5
mcp__memory__search_by_tag tags=["current-state", "bb-mcp-server"]
```

If you notice yourself:
- Forgetting which files to update (e.g., using wrong plan file)
- Missing verification steps (lint/format/test)
- Being corrected for things already discussed
- Asking questions I should know the answer to
- Repeating earlier mistakes

**First:** Query memory to recover context. **If still degraded after memory recovery:**
**Tell the user:** "I may be degraded from context compaction. Consider starting a fresh Claude session."

Rule of thumb: After 2-3 auto-compacts on complex work, a fresh session is more productive than continuing.

---

## Memory Storage Habits

**After completing a task or phase:**
```
mcp__memory__store_memory
  content: "Completed <what>. Key changes: <files>. Decisions: <why>. Gotchas: <surprises>."
  metadata: {tags: ["current-state", "bb-mcp-server", "<topic>"], type: "checkpoint"}
```

**After hitting a non-obvious bug:**
```
mcp__memory__store_memory
  content: "Bug: <symptom>. Root cause: <cause>. Fix: <solution>."
  metadata: {tags: ["bug-fix", "bb-mcp-server", "<area>"], type: "fix"}
```

**After a design decision:**
```
mcp__memory__store_memory
  content: "Decision: <what was decided>. Alternatives considered: <options>. Rationale: <why>."
  metadata: {tags: ["decision", "bb-mcp-server", "<topic>"], type: "decision"}
```

**After learning a new pattern or technique:**
```
mcp__memory__store_memory
  content: "Pattern: <description>. Usage: <when/how>. Example: <code snippet>."
  metadata: {tags: ["pattern", "bb-mcp-server", "<topic>"], type: "pattern"}
```

**Before ending a session (or when user says goodbye):**
```
mcp__memory__store_memory
  content: "Session end state: <what we worked on>. Status: <done/in-progress>. Next steps: <what's pending>."
  metadata: {tags: ["current-state", "bb-mcp-server", "session-end"], type: "checkpoint"}
```

---

## Refactoring

After any module/namespace rename, grep the entire codebase for remaining references to the old name before considering the task complete. Check: import statements, require calls, configuration files (JSON, HTML), build files, and test fixtures.

---

## Testing / Verification

After refactoring changes, attempt to start/load the server or run the build to verify nothing is broken before committing.

---

## Language-Specific Notes

When working with Clojure/ClojureScript namespaces, remember that namespace references can appear in HTML files, EDN configs, and JavaScript interop — not just .clj/.cljs files.

---

*Last Updated: 2026-02-18*
