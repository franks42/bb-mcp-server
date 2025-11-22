# Claude Context for bb-mcp-server

## Project Overview

**bb-mcp-server** - Production-ready MCP (Model Context Protocol) server in Clojure/Babashka.

**Current State:**
- Core MCP server working (stdio transport, JSON-RPC, tool registry)
- 7 modules loaded via dynamic module system (`ns_loader.clj`)
- HTTP transport working for basic requests
- **Next:** Implementing Streamable HTTP transport (MCP spec 2025-03-26)

---

## Current Task: Streamable HTTP Transport

See `context.md` for detailed implementation context.

**Key docs to read:**
1. `context.md` - Quick implementation overview
2. `docs/design/streamable-http-implementation-plan.md` - Full plan with phases
3. `docs/design/streamable-http-transport-design.md` - Technical design

**Status:** Planning complete, ready for Phase 1 implementation

---

## Project Structure

```
bb-mcp-server/
├── src/bb_mcp_server/           # Core server code
│   ├── core.clj                 # Entry point
│   ├── transport/               # Transport implementations
│   │   ├── stdio.clj            # stdio transport (working)
│   │   └── http.clj             # Basic HTTP (working)
│   ├── handlers/                # MCP message handlers
│   ├── module/                  # Module system
│   │   └── ns_loader.clj        # Dynamic module loading
│   └── registry.clj             # Tool registry
├── modules/                     # Loadable modules
│   ├── hello/                   # Example module
│   ├── math/
│   ├── http/
│   └── nrepl/
├── docs/design/                 # Design documents
│   ├── streamable-http-*.md     # Current focus
│   └── module-system-design.md
├── context.md                   # Current task context
└── bb.edn                       # Babashka config
```

---

## Verification Workflow

Always run before committing:
```bash
clj-kondo --lint <file>
cljfmt check <file>
bb test  # when tests exist
```

---

## Key Technical Notes

1. **Babashka compatible** - All code must run in bb, not just JVM Clojure
2. **http-kit for HTTP** - SSE primitives verified working in bb
3. **Ring middleware pattern** - `(fn [handler] (fn [req] ...))`
4. **Module system** - Use `ns_loader.clj` for dynamic loading

---

## Common Commands

```bash
bb tasks                    # List available tasks
bb server:stdio             # Run stdio server
bb server:http [port]       # Run HTTP server
bb check                    # Lint + format
```

---

*Last Updated: 2025-11-22*
