# Transport Layer Modularization

**Status: Phase 8 Complete (v0.8.6)**

Clean separation of transport layer into independent modules with clear dependencies.

## Current State

```
src/bb_mcp_server/
  protocol/router.clj      # JSON-RPC dispatch (method → handler)
  protocol/processor.clj   # Unified JSON-RPC processor with context
  protocol/message.clj     # JSON-RPC message formatting
  transport/stdio.clj      # Re-export → mcp-stdio.core (DEPRECATED)
  transport/http.clj       # Re-export → mcp-http.core (DEPRECATED)
  handlers/                # MCP method handlers (initialize, tools/*)
  registry.clj             # Tool registry

modules/
  mcp-stdio/               # Stdio transport module
  http-core/               # Shared HTTP infrastructure
  mcp-http/                # MCP JSON-RPC over HTTP
  rest-api/                # REST API endpoints
  streamable-http/         # Combined MCP+REST (convenience)
```

## Request Flow Analysis

**Stdio transport** (no HTTP):
```
stdin (line) → JSON parse → router/route-request → handler → JSON string → stdout
```

**HTTP MCP transport**:
```
HTTP POST /mcp → JSON parse → router/route-request → handler → JSON response
```

**REST transport**:
```
HTTP POST /api/modules/:m/tools/:t → JSON parse → handler directly → JSON response
```

Key insight: Stdio and HTTP-MCP share JSON-RPC dispatch. REST bypasses it entirely.

## Layered Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         HANDLERS                                 │
│  (initialize, tools/list, tools/call, tool-specific handlers)   │
│  Pure functions: request map → response map                      │
└─────────────────────────────────────────────────────────────────┘
                              ↑
┌─────────────────────────────────────────────────────────────────┐
│                       DISPATCH LAYER                             │
│  - MCP: protocol/router.clj (JSON-RPC method → handler)         │
│  - REST: handlers/rest.clj (URL path → handler)                 │
└─────────────────────────────────────────────────────────────────┘
                              ↑
┌─────────────────────────────────────────────────────────────────┐
│                      TRANSPORT LAYER                             │
│  - stdio: line-seq stdin → router → println stdout              │
│  - HTTP:  http-kit → router/rest → HTTP response                │
└─────────────────────────────────────────────────────────────────┘
```

## Module Structure

**Phase 8: Transport Module Extraction** ✅ Complete

### Completed Modules

✅ **Phase 8.1: http-core** (50 tests, 105 assertions)
```
modules/http-core/
  src/http_core/
    util.clj              # JSON helpers, UUID, headers
    sse.clj               # SSE formatting and channel ops
  test/http_core/
    util_test.clj
    sse_test.clj
```

✅ **Phase 8.2: mcp-http** (31 tests, 62 assertions)
```
modules/mcp-http/
  src/mcp_http/
    session.clj           # Session CRUD, cleanup task
    handlers/
      post.clj            # JSON-RPC via POST
      get.clj             # SSE stream opening
      delete.clj          # Session termination
    router.clj            # MCP-only routing (/mcp, /health)
    server.clj            # http-kit lifecycle
    core.clj              # Module entry point
  test/mcp_http/
    session_test.clj
    handlers_test.clj
  (depends on: http-core)
```

✅ **Phase 8.3: rest-api** (9 tests, 56 assertions)
```
modules/rest-api/
  src/rest_api/
    handlers.clj          # Module/tool routes, router
    openapi.clj           # OpenAPI 3.0 spec generation
    docs.clj              # HTML documentation generator
    core.clj              # Module entry point
  test/rest_api/
    handlers_test.clj
  (depends on: http-core, NOT mcp-http)
```

✅ **Phase 8.4: mcp-stdio** (10 tests, 42 assertions)
```
modules/mcp-stdio/
  src/mcp_stdio/
    core.clj              # Stdio transport (stdin/stdout JSON-RPC)
  test/mcp_stdio/
    core_test.clj
  (no module dependencies - uses core protocol.processor)
```

✅ **Phase 8.5: Legacy Cleanup**

Cleaned up legacy transport code:
- Deleted `transport/protocol.clj` (unused)
- Deleted `scripts/http_server.clj` (redundant)
- Converted `transport/http.clj` to re-export from `mcp-http.core`
- Fixed `server.clj` broken function reference
- `bb server:http` now alias for `bb server:streamable`

### Current State: streamable-http

With http-core, mcp-http, and rest-api extracted, streamable-http now provides:
- Re-exports from mcp-http and rest-api (backwards compatibility)
- Combined router (MCP + REST)
- Middleware (CORS, rate-limit, auth, logging)

### Future Modules

⏳ **sente-lite** (future)
```
modules/sente-lite/       # Bidirectional channels
  (depends on: http-core)
```

## Dependencies

```
              ┌─────────┐
              │  core   │ (registry, handlers, protocol.processor)
              └────┬────┘
                   │
    ┌──────────────┼──────────────┐
    │              │              │
┌───┴────┐         │         ┌────┴────┐
│mcp-stdio│        │         │http-core│
└─────────┘        │         └────┬────┘
                   │              │
                   │    ┌─────────┴─────────┐
                   │    │                   │
                   │ ┌──┴─────┐      ┌──────┴──┐
                   │ │mcp-http│      │ rest-api│
                   │ └────────┘      └─────────┘
                   │
              (future: sente-lite)
```

## Future Considerations

### When to Revisit

- When adding sente-lite transport
- When the http-core infrastructure needs to be shared elsewhere
- When json-rpc needs to be used outside bb-mcp-server
- **When implementing progress notifications** (requires transport-aware delivery)

### Open Questions

1. Should `registry.clj` move to a core module?
2. Should MCP handlers (initialize, tools/*) be separate from tool handlers?
3. Can json-rpc be a standalone bb library?

---

## Unified Processor Architecture

*Implemented in Phase 8 (v0.8.0+)*

### Solution: `protocol.processor` with Context

The unified processor accepts a **context object** (`ctx`) carrying transport-specific capabilities.

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
│                                    │
│  process-request [ctx request]     │
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

### The Context Object

The `ctx` map carries transport-specific capabilities to handlers:

```clojure
;; Stdio context
{:transport :stdio
 :send-notification! (fn [msg] (println (json/generate-string msg)))}

;; HTTP context
{:transport :http
 :session-id "abc-123"
 :send-notification! (fn [msg] (sse/send-json-rpc! channel msg))}
```

### Handler Signature Change

**Before:** `(fn [request] ...)`
**After:** `(fn [ctx request] ...)`

Example with progress notifications:

```clojure
(defn handle-tools-call [{:keys [send-notification!]} request]
  ;; ... execute tool ...
  ;; Send progress (works on both stdio and HTTP!)
  (send-notification! {:method "notifications/progress"
                       :params {:progress 50}})
  ;; Return result
  {:result ...})
```

### Why This is Low-Risk

1. **Handlers stay pure** - Just add `ctx` as first arg
2. **No module changes** - Tool modules don't know about transports
3. **Incremental** - Can migrate one handler at a time
4. **Backward compatible** - Old handlers work, just can't send notifications

### Implementation Steps

1. **Create `bb-mcp-server.protocol.processor`**
   - Move parsing/dispatch logic from `test-harness.clj`
   - Accept `[ctx request-or-str]`
   - Call router with `[ctx request]`

2. **Update Router**
   - `route-request [ctx request]` instead of `[request]`
   - Pass `ctx` to handlers

3. **Update Handlers (incremental)**
   - Change signature to `[ctx request]`
   - Use `(:send-notification! ctx)` for progress

4. **Update Transports**
   - **Stdio**: Build stdio-ctx, call processor
   - **HTTP**: Build http-ctx with session/SSE, call processor

### REST API: No Changes Needed

REST bypasses JSON-RPC entirely - calls registry/handlers directly.
This is correct and should stay separate.

---

## Future Improvements

### REST API: GET vs POST Revisit

The current REST API uses:
- `GET /api/modules/:module/tools/:name` - Get tool metadata
- `POST /api/modules/:module/tools/:name` - Call tool

**Questions to revisit:**
1. Should idempotent tools (read-only) support GET for invocation?
2. Could we add `?call` query param to GET for simple tools?
3. Should there be explicit `/:name/metadata` vs `/:name/call` endpoints?
4. How do OpenAPI/Swagger UIs expect tool invocation patterns?

**Design considerations:**
- REST purists prefer GET for idempotent operations
- POST for tool calls matches RPC semantics
- MCP tools may have side effects, so POST is safer default
- OpenAPI generation currently assumes POST-only invocation

This should be revisited when:
- User feedback indicates GET would be more convenient
- Adding support for cURL-friendly quick calls
- Integrating with OpenAPI tooling that expects specific patterns

---

## Related

- [naming-conventions.md](naming-conventions.md) - Module-tool separator design
- [streamable-http-implementation-plan.md](../streamable-http/docs/streamable-http-implementation-plan.md) - HTTP transport phases
- [modularization-advice.md](modularization-advice.md) - Original architecture review
