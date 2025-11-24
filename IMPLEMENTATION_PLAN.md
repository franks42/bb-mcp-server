# bb-mcp-server Implementation Plan

**Status:** Phase 8 - Transport Module Extraction
**Last Updated:** 2025-11-23

---

## Current Focus: Phase 8 - Transport Module Extraction

Extract the monolithic `streamable-http` module into focused, reusable modules with clear dependency boundaries.

**Prerequisite:** Unified Processor ✅ Complete

---

## Phase 8: Transport Module Extraction

### Overview

**Goal:** Break up `streamable-http` into `http-core`, `mcp-http`, and `rest-api` modules.

**Current State:**
```
modules/streamable-http/     # 13 files, mixed concerns
├── util.clj                 # Generic utilities
├── sse.clj                  # SSE formatting
├── session.clj              # Session management (MCP-specific)
├── middleware.clj           # Ring middleware
├── server.clj               # http-kit lifecycle
├── router.clj               # Combined MCP + REST routing
├── handlers/
│   ├── post.clj             # MCP POST handler
│   ├── get.clj              # MCP GET (SSE stream)
│   ├── delete.clj           # MCP DELETE
│   └── rest.clj             # REST API handlers
├── openapi.clj              # OpenAPI generation
├── docs.clj                 # HTML docs
└── core.clj                 # Entry point
```

**Target State:**
```
modules/
├── http-core/               # Shared HTTP infrastructure
│   ├── util.clj
│   ├── sse.clj
│   ├── middleware.clj
│   └── server.clj
│
├── mcp-http/                # MCP JSON-RPC over HTTP
│   ├── session.clj
│   ├── handlers/
│   │   ├── post.clj
│   │   ├── get.clj
│   │   └── delete.clj
│   └── router.clj
│
├── rest-api/                # REST API (no JSON-RPC)
│   ├── handlers.clj
│   ├── router.clj
│   ├── openapi.clj
│   └── docs.clj
│
└── mcp-stdio/               # (Optional) Extract from src/
    └── transport.clj
```

**Dependency Graph:**
```
                 ┌─────────────┐
                 │   (core)    │  registry, processor, handlers
                 └──────┬──────┘
                        │
         ┌──────────────┼──────────────┐
         │              │              │
    ┌────┴────┐         │       ┌──────┴──────┐
    │mcp-stdio│         │       │  http-core  │
    └─────────┘         │       └──────┬──────┘
                        │              │
                        │    ┌─────────┼─────────┐
                        │    │                   │
                        │ ┌──┴───┐          ┌────┴────┐
                        │ │mcp-  │          │ rest-   │
                        │ │http  │          │ api     │
                        │ └──────┘          └─────────┘
```

---

### 8.1 Extract `http-core`

**Goal:** Create shared HTTP infrastructure module

**Risk:** Low - moving generic code with no logic changes

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.1.1 | Create `modules/http-core/` structure | ⏳ | Directory and module.edn |
| 8.1.2 | Move `util.clj` → `http-core.util` | ⏳ | Namespace renamed |
| 8.1.3 | Move `sse.clj` → `http-core.sse` | ⏳ | Namespace renamed |
| 8.1.4 | Move `middleware.clj` → `http-core.middleware` | ⏳ | Namespace renamed |
| 8.1.5 | Move `server.clj` → `http-core.server` | ⏳ | Namespace renamed |
| 8.1.6 | Move relevant tests | ⏳ | Tests pass in new location |
| 8.1.7 | Update `streamable-http` requires | ⏳ | Uses `http-core.*` |
| 8.1.8 | Update `system.edn` and `bb.edn` | ⏳ | Module loads correctly |
| 8.1.9 | Run all tests | ⏳ | `bb test:modules` passes |

**Module Manifest:**
```clojure
;; modules/http-core/module.edn
{:name "http-core"
 :version "0.1.0"
 :description "Shared HTTP infrastructure: SSE, middleware, server lifecycle"
 :requires []
 :entry "http-core.core/module"}
```

**Success Criteria:**
- [ ] `bb test:modules` passes
- [ ] `bb server:streamable` works unchanged
- [ ] No code duplication

---

### 8.2 Extract `mcp-http`

**Goal:** MCP JSON-RPC transport as standalone module

**Risk:** Medium - session management is MCP-specific

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.2.1 | Create `modules/mcp-http/` structure | ⏳ | Directory and module.edn |
| 8.2.2 | Move `session.clj` → `mcp-http.session` | ⏳ | Namespace renamed |
| 8.2.3 | Move POST/GET/DELETE handlers | ⏳ | Namespaces renamed |
| 8.2.4 | Create `mcp-http/router.clj` | ⏳ | MCP-only routing |
| 8.2.5 | Move session tests | ⏳ | Tests pass |
| 8.2.6 | Update requires throughout | ⏳ | All imports correct |
| 8.2.7 | Update `system.edn` | ⏳ | `:requires ["http-core"]` |
| 8.2.8 | Run all tests | ⏳ | `bb test:modules` passes |

**Module Manifest:**
```clojure
;; modules/mcp-http/module.edn
{:name "mcp-http"
 :version "0.1.0"
 :description "MCP JSON-RPC over HTTP with SSE notifications"
 :requires ["http-core"]
 :entry "mcp-http.core/module"}
```

**Success Criteria:**
- [ ] MCP endpoints work (`POST/GET/DELETE /mcp`)
- [ ] Session management works
- [ ] SSE notifications work

---

### 8.3 Extract `rest-api`

**Goal:** REST API as standalone module (no JSON-RPC dependency)

**Risk:** Low - already somewhat isolated

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.3.1 | Create `modules/rest-api/` structure | ⏳ | Directory and module.edn |
| 8.3.2 | Move `handlers/rest.clj` → `rest-api/handlers.clj` | ⏳ | Namespace renamed |
| 8.3.3 | Move `openapi.clj` → `rest-api/openapi.clj` | ⏳ | Namespace renamed |
| 8.3.4 | Move `docs.clj` → `rest-api/docs.clj` | ⏳ | Namespace renamed |
| 8.3.5 | Create `rest-api/router.clj` | ⏳ | REST routing |
| 8.3.6 | Move REST tests | ⏳ | Tests pass |
| 8.3.7 | Update `system.edn` | ⏳ | `:requires ["http-core"]` |
| 8.3.8 | Run all tests | ⏳ | `bb test:modules` passes |

**Module Manifest:**
```clojure
;; modules/rest-api/module.edn
{:name "rest-api"
 :version "0.1.0"
 :description "RESTful API with OpenAPI spec generation"
 :requires ["http-core"]
 :entry "rest-api.core/module"}
```

**Success Criteria:**
- [ ] REST endpoints work (`/api/*`)
- [ ] OpenAPI spec generates correctly
- [ ] HTML docs render

---

### 8.4 Cleanup & Documentation

**Goal:** Remove old module, update docs

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.4.1 | Delete `modules/streamable-http/` | ⏳ | Directory removed |
| 8.4.2 | Update `CLAUDE.md` | ⏳ | Reflects new structure |
| 8.4.3 | Update `README.md` | ⏳ | Module table updated |
| 8.4.4 | Update server startup scripts | ⏳ | Use new modules |
| 8.4.5 | Create README for each new module | ⏳ | Documentation complete |

**Success Criteria:**
- [ ] No references to `streamable-http` namespace
- [ ] All documentation accurate

---

### 8.5 (Optional) Extract `mcp-stdio`

**Goal:** Make stdio transport a proper module for consistency

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.5.1 | Create `modules/mcp-stdio/` structure | ⏳ | Directory and module.edn |
| 8.5.2 | Move `transport/stdio.clj` | ⏳ | Namespace renamed |
| 8.5.3 | Update server startup | ⏳ | Loads module |
| 8.5.4 | Test with Claude Code | ⏳ | End-to-end works |

---

### Migration Strategy

**Approach:** Incremental with namespace aliases for compatibility

```clojure
;; Temporary compatibility layer in streamable-http
(ns streamable-http.util
  (:require [http-core.util :as util]))

;; Re-export all public vars
(def parse-json util/parse-json)
(def generate-json util/generate-json)
```

**Testing at Each Phase:**
```bash
bb lint
bb format
bb test:modules
bb server:streamable 19878  # Manual smoke test
```

**Rollback Plan:**
- Each phase creates new modules without deleting old code until 8.4
- Revert `system.edn` to load `streamable-http` if issues arise

---

## Completed Phases (Summary)

### Phase 1-2: Foundation ✅
- Project initialization, bb.edn, tooling
- Minimal MCP server (stdio)
- Tool registry with Malli validation
- Error handling with JSON Schema → Malli conversion

### Phase 3: Multi-Transport ✅
- HTTP transport with http-kit
- CORS, content negotiation
- Transport protocol abstraction

### Phase 4-5: Module System ✅
- Dynamic module loading (`ns_loader.clj`)
- Component-style lifecycle (`system.clj`)
- Dependency resolution

### Phase 6: Streamable HTTP Transport ✅
- MCP spec 2025-03-26 compliant
- Session management with SSE
- Ring middleware (CORS, rate-limit, auth)
- `listChanged` capability with broadcast notifications
- PID file management, graceful shutdown

### Phase 7: REST API & Unified Processor ✅
- REST endpoints (`/api/modules/:module/tools/:name`)
- OpenAPI 3.0 spec generation
- HTML documentation
- Module-tool separator (`moduleToolSeparator`)
- **Unified Processor** - transport-agnostic JSON-RPC processing
- Context objects for transport-specific capabilities

---

## References

- [Transport Modularization Design](docs/design/transport-modularization.md)
- [Module System Design](docs/design/module-system-design.md)
- [Streamable HTTP Implementation](modules/streamable-http/docs/streamable-http-implementation-plan.md)
- [Modularization Advice](docs/design/modularization-advice.md)

---

*Last Updated: 2025-11-23*
