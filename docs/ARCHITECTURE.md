# bb-mcp-server Architecture

Overview of the modular architecture for bb-mcp-server.

## Module Hierarchy

```
                    ┌─────────────────────┐
                    │       CORE          │
                    │  registry, handlers │
                    │  protocol.processor │
                    └──────────┬──────────┘
                               │
           ┌───────────────────┼───────────────────┐
           │                   │                   │
    ┌──────┴──────┐            │            ┌──────┴──────┐
    │  mcp-stdio  │            │            │  http-core  │
    │   (10 tests)│            │            │  (50 tests) │
    └─────────────┘            │            └──────┬──────┘
                               │                   │
                               │      ┌────────────┴────────────┐
                               │      │                         │
                               │  ┌───┴────┐             ┌──────┴───┐
                               │  │mcp-http│             │ rest-api │
                               │  │(31 tests)            │ (9 tests)│
                               │  └────────┘             └──────────┘
                               │
                          (future: sente-lite)
```

## Module Descriptions

### Core (src/bb_mcp_server/)

The core provides:
- **registry.clj** - Tool registration with `listChanged` notification support
- **protocol/processor.clj** - Unified JSON-RPC processor with transport context
- **protocol/router.clj** - Method dispatch (initialize, tools/list, tools/call)
- **protocol/message.clj** - JSON-RPC message formatting
- **handlers/** - MCP method handlers

### mcp-stdio (modules/mcp-stdio/)

Stdio transport for stdin/stdout JSON-RPC communication.

**Use when:**
- Integrating with Claude Desktop
- Spawning MCP server as subprocess
- Single-client scenarios

**Entry point:** `mcp-stdio.core/run-stdio-loop!`

```clojure
(require '[mcp-stdio.core :as stdio])
(stdio/run-stdio-loop!)  ; Blocks, reads stdin, writes stdout
```

### http-core (modules/http-core/)

Shared HTTP infrastructure used by mcp-http and rest-api.

**Provides:**
- `http-core.util` - JSON helpers, UUID generation, HTTP headers
- `http-core.sse` - Server-Sent Events formatting and channel ops
- `http-core.middleware` - Ring middleware (CORS, rate-limit, auth, logging)

### mcp-http (modules/mcp-http/)

MCP JSON-RPC over HTTP with SSE for server-to-client notifications.

**Endpoints:**
- `POST /mcp` - JSON-RPC requests (initialize, tools/list, tools/call)
- `GET /mcp` - Open SSE stream for notifications
- `DELETE /mcp` - Terminate session
- `GET /health` - Health check

**Use when:**
- Multiple clients need to connect
- You need server-to-client notifications (progress, tool list changes)
- Debugging with curl/Postman

### rest-api (modules/rest-api/)

REST endpoints for tool discovery and invocation.

**Endpoints:**
- `GET /api/server` - Server info (name, version, moduleToolSeparator)
- `GET /api/modules` - List all modules
- `GET /api/modules/:module/tools` - List tools in module
- `GET /api/modules/:module/tools/:name` - Get tool metadata
- `POST /api/modules/:module/tools/:name` - Call tool
- `GET /api/openapi.json` - OpenAPI 3.0 specification
- `GET /api/docs` - HTML documentation

**Use when:**
- Integrating with non-MCP clients
- Building web UIs
- Need OpenAPI/Swagger compatibility

### streamable-http (modules/streamable-http/)

Convenience module that combines mcp-http + rest-api.

**Use when:**
- You want "batteries included" HTTP server
- You need both MCP and REST endpoints
- Quick prototyping

```clojure
(require '[streamable-http.core :as shttp])

(def server (shttp/start-server! my-handler {:port 3000}))
;; Now serving:
;;   /mcp     - MCP JSON-RPC
;;   /health  - Health check
;;   /api/*   - REST endpoints (if configured)

(shttp/stop-server! server)
```

## Request Flows

### Stdio Transport

```
stdin (JSON line)
    │
    ▼
┌─────────────────────────┐
│   protocol/processor    │
│   (parse, validate)     │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│   protocol/router       │
│   (method → handler)    │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│      handlers/*         │
│  (initialize, tools/*)  │
└───────────┬─────────────┘
            │
            ▼
stdout (JSON line)
```

### HTTP MCP Transport

```
HTTP POST /mcp
    │
    ▼
┌─────────────────────────┐
│   mcp-http/router       │
│   (Ring handler)        │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│   mcp-http/handlers     │
│   (post, get, delete)   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│   protocol/processor    │
│   (with HTTP context)   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│   protocol/router       │
│   (method → handler)    │
└───────────┬─────────────┘
            │
            ▼
HTTP JSON response
(+ SSE for notifications)
```

### REST Transport

```
HTTP POST /api/modules/:m/tools/:t
    │
    ▼
┌─────────────────────────┐
│   rest-api/handlers     │
│   (bypasses JSON-RPC)   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│      registry           │
│   (get tool, handler)   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│    Tool handler         │
│   (direct invocation)   │
└───────────┬─────────────┘
            │
            ▼
HTTP JSON response
```

## Transport Comparison

| Feature              | mcp-stdio | mcp-http | rest-api |
|---------------------|-----------|----------|----------|
| Multiple clients    | No        | Yes      | Yes      |
| Server notifications| Via stdout| Via SSE  | No       |
| Session management  | N/A       | Yes      | No       |
| Claude Desktop      | Yes       | No*      | No       |
| curl/Postman debug  | No        | Yes      | Yes      |
| OpenAPI spec        | No        | No       | Yes      |
| Protocol overhead   | Minimal   | HTTP     | HTTP     |

*Claude Desktop doesn't support HTTP MCP transport yet.

## Tool Modules

Tool modules provide the actual functionality:

- **nrepl** - Remote REPL integration (9 tools)
- **calculate** - Math expression evaluator
- **local-eval** - Server-side code execution
- **echo, strings, math, hello** - Example tools

Tools register with the core registry and are accessible via all transports.

## Configuration

### Server Startup

```clojure
;; Stdio (blocking)
(require '[mcp-stdio.core :as stdio])
(stdio/run-stdio-loop!)

;; HTTP (non-blocking)
(require '[streamable-http.core :as shttp])
(shttp/start-server! handler {:port 3000})
```

### Tool Registration

```clojure
(require '[bb-mcp-server.registry :as reg])

(reg/register-tool!
  {:name "my-tool"
   :module "my-module"
   :description "Does something"
   :inputSchema {:type "object" :properties {...}}
   :handler (fn [args] ...)})
```

## File Structure

```
bb-mcp-server/
├── src/bb_mcp_server/           # Core
│   ├── protocol/                # JSON-RPC processing
│   ├── handlers/                # MCP method handlers
│   └── registry.clj             # Tool registry
├── modules/
│   ├── mcp-stdio/               # Stdio transport
│   ├── http-core/               # Shared HTTP utils
│   ├── mcp-http/                # HTTP MCP transport
│   ├── rest-api/                # REST endpoints
│   ├── streamable-http/         # Combined HTTP (convenience)
│   ├── nrepl/                   # nREPL tools
│   ├── calculate/               # Calculator tool
│   └── ...                      # Other tool modules
├── test/bb_mcp_server/          # Core tests
│   ├── protocol/                # Protocol tests
│   ├── handlers/                # Handler tests
│   ├── cli/                     # CLI parsing tests
│   ├── bootstrap/               # Bootstrap config tests
│   └── pid_file_test.clj        # PID file tests
└── test/run_bootstrap_tests.clj # Bootstrap test runner
```

---

## Testing Architecture

The project uses a layered testing approach:

### Module Tests
Each module has its own test runner:
```bash
bb test:mcp-stdio      # Test stdio transport
bb test:http-core      # Test HTTP utilities
bb test:mcp-http       # Test HTTP MCP transport
bb test:rest-api       # Test REST endpoints
```

### Bootstrap Tests
Tests for core server functionality:
- **CLI Argument Parsing**: Verifies `--http`, `--port`, `--nickname`, `--config` flags
- **PID File Management**: Tests nickname-based port files and JSON format
- **Bootstrap Configuration**: Validates `bb-bootstrap-system.edn` loading

```bash
bb test:bootstrap     # Run bootstrap tests
bb test:all          # Run all tests (modules + bootstrap)
```

### Test Quality
- **0 linting errors** (clj-kondo)
- **0 linting warnings** 
- All tests use Babashka-compatible `clojure.test`
- Test fixtures for cleanup and isolation

---

*Last updated: 2025-12-27 (Bootstrap testing suite added)*
