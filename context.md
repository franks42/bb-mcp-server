# Streamable HTTP Transport - Implementation Context

**Current Task:** Implement MCP Streamable HTTP Transport (spec 2025-03-26)
**Status:** Planning complete, ready for Phase 1 implementation

---

## Quick Start for New Claude

1. Read the implementation plan: `docs/design/streamable-http-implementation-plan.md`
2. Read the design doc: `docs/design/streamable-http-transport-design.md`
3. Read the Gemini review: `docs/design/streamable-http-transport-review.md`
4. Start with Phase 1 tasks

---

## What We're Building

A **modular, self-contained** MCP Streamable HTTP transport that:
- Implements MCP spec 2025-03-26 (replaces older HTTP+SSE)
- Runs in Babashka (http-kit verified working)
- Uses Ring middleware pattern for extensibility
- Can be extracted to standalone repo later

### Key Features
- Single `/mcp` endpoint (POST for JSON-RPC, GET for SSE streams)
- Session management via `Mcp-Session-Id` header
- Server-to-client notifications via SSE
- Ring middleware compatible (CORS, auth, rate-limiting)

---

## Module Structure

```
modules/streamable-http/
├── module.edn                    # bb-mcp-server module manifest
├── src/streamable_http/
│   ├── core.clj                  # Public API
│   ├── server.clj                # HTTP server lifecycle
│   ├── router.clj                # Request routing
│   ├── middleware.clj            # Ring middleware (CORS, auth, rate-limit)
│   ├── handlers/
│   │   ├── post.clj              # POST /mcp handler
│   │   ├── get.clj               # GET /mcp handler (SSE stream)
│   │   └── delete.clj            # DELETE /mcp handler
│   ├── session.clj               # Session management
│   ├── sse.clj                   # SSE utilities
│   └── util.clj                  # Shared utilities
└── test/
```

---

## Implementation Phases

| Phase | Goal | Status |
|-------|------|--------|
| 1 | Foundation (util, sse, session) | Not started |
| 2 | HTTP Handlers (post, get, delete, router) | Not started |
| 2.5 | Ring Middleware Layer | Not started |
| 3 | Server Lifecycle (core.clj, server.clj) | Not started |
| 4 | bb-mcp-server Integration | Not started |
| 5 | Production Hardening | Not started |
| 6 | Documentation & Extraction Prep | Not started |

---

## Key Design Decisions

1. **Handler injection pattern** - module receives `(fn [json-rpc-req] -> json-rpc-resp)`, doesn't depend on bb-mcp-server internals
2. **Ring middleware compatible** - standard `(fn [handler] (fn [req] ...))` pattern
3. **Zero external deps** - only http-kit, cheshire (both bb-compatible)
4. **Built-in middleware** - wrap-cors, wrap-rate-limit, wrap-basic-auth, wrap-request-logging, wrap-origin-validation

---

## Verified Working in Babashka

http-kit SSE primitives tested in `/tmp/test-sse.clj`:
- `with-channel` ✅
- `send!` ✅
- `close` ✅
- `on-close` ✅

---

## File-by-File Implementation Order

```
Day 1: Foundation
  1. module.edn
  2. util.clj
  3. sse.clj + sse_test.clj
  4. session.clj + session_test.clj

Day 2: Handlers
  5. handlers/post.clj
  6. handlers/get.clj
  7. handlers/delete.clj
  8. router.clj

Day 3: Middleware
  9. middleware.clj
  10. middleware_test.clj
  11. Middleware stack assembly

Day 4: Server & API
  12. server.clj
  13. core.clj + core_test.clj
  14. integration_test.clj

Day 5: Integration
  15. bb-mcp-server adapter
  16. Update bb.edn paths
  17. End-to-end testing

Day 6: Hardening & Docs
  18. Security middleware
  19. External middleware testing
  20. Documentation
```

---

## References

- [MCP Spec - Transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- Design doc: `docs/design/streamable-http-transport-design.md`
- Implementation plan: `docs/design/streamable-http-implementation-plan.md`
- Module system: `docs/design/module-system-design.md`

---

*Last Updated: 2025-11-22*
