# bb-mcp-server - Session Context

**Status:** v0.11.0 - Phase 11 Complete (Unified Entry Point)
**Updated:** 2025-11-23

---

## Critical Reminders for Claude

### 1. Plan Before Code
**ALWAYS update `IMPLEMENTATION_PLAN.md` BEFORE implementing.** The user will remind you if you forget. This is the single source of truth for planning - NOT docs/design/*.md files.

### 2. Verification Workflow
Run before every commit:
```bash
clj-kondo --lint <files>
cljfmt check <files>
bb test:modules
```

### 3. Key Files
- **CLAUDE.md** - Project instructions (READ THIS)
- **IMPLEMENTATION_PLAN.md** - Single source of truth for planning
- **system.edn** - Module configuration
- **src/bb_mcp_server/main.clj** - Unified entry point

---

## Current State (v0.11.0)

### Unified Entry Point
```bash
bb server              # stdio (default, Claude Desktop)
bb server --http       # HTTP only on port 3000
bb server --http 8080  # HTTP on custom port
bb server --stdio --http       # both transports simultaneously
bb server --help       # show usage
```

### Test Counts
- Core: 40 tests, 161 assertions
- nrepl: 34 tests, 131 assertions
- http-core: 50 tests, 105 assertions
- mcp-http: 31 tests, 62 assertions
- mcp-stdio: 10 tests, 33 assertions
- rest-api: 9 tests, 56 assertions
- **Total: ~175 tests**

### Transports
1. **Stdio** (`bb server --stdio`) - JSON-RPC over stdin/stdout (Claude Desktop)
2. **HTTP MCP** (`bb server --http`) - Streamable HTTP with SSE
3. **REST API** (`/api/*`) - Direct HTTP calls

### REST API Endpoints
```
GET  /api/server                       - Server info
GET  /api/modules                      - List all modules
GET  /api/modules/:module/tools        - List tools in module
GET  /api/modules/:module/tools/:name  - Tool metadata
POST /api/modules/:module/tools/:name  - Call tool
GET  /api/openapi.json                 - OpenAPI 3.0 spec
GET  /api/docs                         - HTML documentation
GET  /health                           - Health check
```

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
├── http-core/     # Shared HTTP infrastructure (SSE, middleware)
├── mcp-http/      # MCP JSON-RPC over HTTP with sessions
├── mcp-stdio/     # Stdio transport (pure, no bb-mcp-server deps)
├── rest-api/      # REST endpoints + OpenAPI
├── streamable-http/  # Convenience wrapper (mcp-http + rest-api)
├── nrepl/         # nREPL integration (9 tools)
├── calculate/     # Calculator tool
├── local-eval/    # Local Clojure eval
├── echo/, strings/, math/, hello/  # Example modules
```

---

## Completed Phases

- **Phase 1-7**: Foundation, transports, module system, REST API
- **Phase 8**: Transport module extraction (http-core, mcp-http, rest-api)
- **Phase 9**: Legacy cleanup (deleted transport/ directory)
- **Phase 10**: Decoupled mcp-stdio (pure transport layer)
- **Phase 11**: Unified entry point (`bb server` with flags)

---

## bb.edn Tasks

```bash
bb server [flags]        # Run server (see --help)
bb server:stop <port>    # Stop server via PID file
bb test:modules          # All module tests
bb lint                  # clj-kondo
bb format                # cljfmt
```

---

## References
- IMPLEMENTATION_PLAN.md - The plan (single source of truth)
- docs/design/ - Design documents (reference only)
- MCP Spec: https://modelcontextprotocol.io/specification/2025-03-26/
