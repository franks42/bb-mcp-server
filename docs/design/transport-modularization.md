# Transport Layer Modularization

Future consideration for cleaner separation of transports. Not urgent - defer until more experience with bb-module system.

## Current State

```
src/bb_mcp_server/
  protocol/router.clj      # JSON-RPC dispatch (method → handler)
  protocol/message.clj     # JSON-RPC message formatting
  transport/stdio.clj      # Stdin/stdout wire protocol
  handlers/                # MCP method handlers (initialize, tools/*)
  registry.clj             # Tool registry

modules/streamable-http/
  handlers/post.clj        # MCP JSON-RPC over HTTP
  handlers/rest.clj        # REST API (bypasses JSON-RPC)
  handlers/get.clj         # SSE stream
  handlers/delete.clj      # Session termination
  session.clj, sse.clj     # Shared HTTP infrastructure
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

## Potential Module Structure

```
modules/
  core/                    # Shared by all transports
    registry.clj           # Tool registry
    handlers/              # Business logic (pure functions)

  json-rpc/                # JSON-RPC protocol (no I/O)
    router.clj             # Method dispatch
    message.clj            # Message formatting
    errors.clj             # Error codes

  stdio/                   # Stdin/stdout transport
    transport.clj          # Line-based I/O loop
    (depends on: json-rpc)

  http-core/               # Shared HTTP infrastructure
    server.clj             # http-kit wrapper
    session.clj            # Session management
    sse.clj                # Server-Sent Events
    middleware.clj         # CORS, auth, rate limiting

  mcp-http/                # MCP over HTTP transport
    handlers.clj           # POST/GET/DELETE handlers
    (depends on: json-rpc, http-core)

  rest-api/                # REST API transport
    handlers.clj           # Module/tool routes
    openapi.clj            # OpenAPI spec generation
    docs.clj               # HTML documentation
    (depends on: http-core, NOT json-rpc)

  sente-lite/              # Future: bidirectional channels
    (depends on: http-core)
```

## Dependencies

```
              ┌─────────┐
              │  core   │ (registry, handlers)
              └────┬────┘
                   │
         ┌─────────┼─────────┐
         │         │         │
    ┌────┴────┐    │    ┌────┴────┐
    │ json-rpc│    │    │http-core│
    └────┬────┘    │    └────┬────┘
         │         │         │
    ┌────┴────┐    │    ┌────┴────┬────────────┐
    │  stdio  │    │    │ mcp-http│  rest-api  │
    └─────────┘    │    └─────────┴────────────┘
                   │
              (future: sente-lite)
```

## Why Defer?

1. **Works fine as-is** - Current structure is functional
2. **Module system maturity** - Need more experience with bb-module patterns
3. **Coupling is manageable** - Not deeply entangled, just co-located
4. **Refactoring cost** - Would touch many files, tests
5. **Future transports** - Adding sente-lite will clarify natural boundaries

## When to Revisit

- When adding sente-lite transport
- When the http-core infrastructure needs to be shared elsewhere
- When json-rpc needs to be used outside bb-mcp-server
- When the current structure becomes painful to maintain

## Questions to Answer Later

1. Should `registry.clj` move to a core module?
2. Should MCP handlers (initialize, tools/*) be separate from tool handlers?
3. Can json-rpc be a standalone bb library?
4. What's the right granularity for http-core vs mcp-http vs rest-api?

## Related

- [naming-conventions.md](naming-conventions.md) - Module-tool separator design
- [streamable-http-implementation-plan.md](streamable-http-implementation-plan.md) - Current HTTP transport phases
