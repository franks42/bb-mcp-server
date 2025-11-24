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
│   ├── handlers/                # MCP message handlers
│   ├── module/                  # Module system
│   │   └── ns_loader.clj        # Dynamic module loading
│   ├── protocol/                # JSON-RPC routing
│   └── registry.clj             # Tool registry
├── modules/                     # Loadable modules
│   ├── mcp-stdio/               # Stdio transport (stdin/stdout)
│   ├── mcp-http/                # HTTP MCP transport
│   ├── rest-api/                # REST API endpoints
│   ├── http-core/               # Shared HTTP infrastructure
│   ├── streamable-http/         # Combined HTTP (convenience)
│   ├── nrepl/                   # nREPL integration (9 tools)
│   ├── calculate/               # Calculator tool
│   ├── local-eval/              # Local Clojure eval
│   └── echo/, strings/, math/   # Example modules
├── scripts/                     # Server startup scripts
│   ├── streamable_http_server.clj
│   ├── stdio_server.clj
│   └── pid_util.clj             # PID file management
├── docs/design/                 # Design documents
└── bb.edn                       # Babashka config
```

---

## Common Commands

```bash
bb tasks                        # List available tasks
bb server:streamable [port]     # Run Streamable HTTP server (default 3000)
bb server:stop [port]           # Stop server on port
bb server:stdio                 # Run stdio server
bb test:modules                 # Run all module tests
bb lint                         # Lint with clj-kondo
bb format                       # Format with cljfmt
```

---

## Verification Workflow

Always run before committing:
```bash
clj-kondo --lint <files>
cljfmt check <files>
bb test:modules
```

---

## Key Technical Notes

1. **Babashka compatible** - All code must run in bb, not just JVM Clojure
2. **http-kit for HTTP** - SSE primitives verified working in bb
3. **Ring middleware pattern** - `(fn [handler] (fn [req] ...))`
4. **Module system** - Modules in `system.edn`, loaded via `ns_loader.clj`
5. **Tool notifications** - Registry broadcasts `notifications/tools/list_changed` on changes

---

## Planning & Task Tracking

**IMPORTANT:** Use `IMPLEMENTATION_PLAN.md` as the **single source of truth** for:
- Project phases and milestones
- Task status and progress
- Implementation decisions
- Architecture changes

Do NOT create or update alternative plan documents (e.g., in `docs/design/` or module subdirectories). All planning updates go in `IMPLEMENTATION_PLAN.md`.

---

*Last Updated: 2025-11-24*
