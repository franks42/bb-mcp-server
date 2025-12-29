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

```bash
bb tasks                        # List available tasks
bb server                       # Run stdio (default, Claude Desktop)
bb server --http                # Run HTTP only on port 3000
bb server --http 8080           # Run HTTP on custom port
bb server --stdio --http        # Run both transports
bb server --help                # Show usage
bb server:stop [port]           # Stop server on port
bb test:modules                 # Run all module tests
bb test:e2e                     # E2E tests (needs running server)
bb test:bootstrap               # Bootstrap config tests
bb lint                         # Lint with clj-kondo
bb format                       # Format with cljfmt
```

---

## Verification Workflow

**MUST run before committing - zero errors AND zero warnings required:**
```bash
clj-kondo --lint <files>    # MUST be 0 errors, 0 warnings
cljfmt check <files>        # MUST have no formatting issues
bb test:modules             # MUST pass all tests
```

Do NOT commit code with lint warnings. Fix all warnings before committing.

**macOS Note:** Do NOT use `timeout` command (it doesn't exist on macOS). Use `sleep` or Babashka's built-in timeout options instead.

---

## Required Reading for AI Assistants

**MUST read at start of every new session:**

1. **CLAUDE.md** (this file) - Project instructions and workflow
2. **context.md** - Current session state, recent changes, active work
3. **docs/CLOJURE_EXPERT_CONTEXT.md** - Clojure development standards, honesty requirements, verification workflow
4. **docs/AI_TELEMETRY_GUIDE.md** - Telemetry patterns (all I/O and business logic must have telemetry)
5. **IMPLEMENTATION_PLAN.md** - Task tracking and pending work

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

*Last Updated: 2025-12-28*
