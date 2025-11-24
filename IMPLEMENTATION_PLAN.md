# bb-mcp-server Implementation Plan

**Status:** Phase 10 Complete (v0.10.0)
**Last Updated:** 2025-11-24

---

## Completed: Phase 8 & 9 - Transport Module Extraction

Extracted the monolithic `streamable-http` module into focused, reusable modules with clear dependency boundaries.

**Prerequisite:** Unified Processor ✅ Complete

---

## Phase 8: Transport Module Extraction ✅

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

### 8.1 Extract `http-core` ✅

**Goal:** Create shared HTTP infrastructure module

**Risk:** Low - moving generic code with no logic changes

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.1.1 | Create `modules/http-core/` structure | ✅ | Directory and module.edn |
| 8.1.2 | Move `util.clj` → `http-core.util` | ✅ | Namespace renamed |
| 8.1.3 | Move `sse.clj` → `http-core.sse` | ✅ | Namespace renamed |
| 8.1.4 | Move `middleware.clj` → `http-core.middleware` | ✅ | Namespace renamed |
| 8.1.5 | Move `server.clj` → `http-core.server` | ⏳ | Deferred - kept in streamable-http |
| 8.1.6 | Move relevant tests | ✅ | 50 tests, 105 assertions |
| 8.1.7 | Update `streamable-http` requires | ✅ | Re-exports for backwards compat |
| 8.1.8 | Update `system.edn` and `bb.edn` | ✅ | Paths added, tasks work |
| 8.1.9 | Run all tests | ✅ | 149 tests pass |

**Note:** `server.clj` was kept in `streamable-http` as it contains MCP-specific lifecycle logic. May move to `http-core` in Phase 8.2 if needed.

**Module Manifest:**
```clojure
;; modules/http-core/module.edn
{:name "http-core"
 :version "0.1.0"
 :description "Shared HTTP infrastructure: SSE, middleware, utilities"
 :requires []
 :entry "http-core.core/module"}
```

**Success Criteria:**
- [x] `bb test:modules` passes
- [x] `bb server:streamable` works unchanged
- [x] No code duplication (re-exports in streamable-http)

---

### 8.2 Extract `mcp-http` ✅

**Goal:** MCP JSON-RPC transport as standalone module

**Risk:** Medium - session management is MCP-specific

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.2.1 | Create `modules/mcp-http/` structure | ✅ | Directory and module.edn |
| 8.2.2 | Move `session.clj` → `mcp-http.session` | ✅ | Namespace renamed |
| 8.2.3 | Move POST/GET/DELETE handlers | ✅ | Namespaces renamed |
| 8.2.4 | Create `mcp-http/router.clj` | ✅ | MCP-only routing |
| 8.2.5 | Move session tests | ✅ | Tests pass |
| 8.2.6 | Update requires throughout | ✅ | All imports correct |
| 8.2.7 | Update `system.edn` | ✅ | `:requires ["http-core"]` |
| 8.2.8 | Run all tests | ✅ | 31 tests, 62 assertions |

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
- [x] MCP endpoints work (`POST/GET/DELETE /mcp`)
- [x] Session management works
- [x] SSE notifications work

---

### 8.3 Extract `rest-api` ✅

**Goal:** REST API as standalone module (no JSON-RPC dependency)

**Risk:** Low - already somewhat isolated

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.3.1 | Create `modules/rest-api/` structure | ✅ | Directory and module.edn |
| 8.3.2 | Move `handlers/rest.clj` → `rest-api/handlers.clj` | ✅ | Namespace renamed |
| 8.3.3 | Move `openapi.clj` → `rest-api/openapi.clj` | ✅ | Namespace renamed |
| 8.3.4 | Move `docs.clj` → `rest-api/docs.clj` | ✅ | Namespace renamed |
| 8.3.5 | Create `rest-api/router.clj` | ✅ | REST routing |
| 8.3.6 | Move REST tests | ✅ | Tests pass |
| 8.3.7 | Update `system.edn` | ✅ | `:requires ["http-core"]` |
| 8.3.8 | Run all tests | ✅ | 9 tests, 56 assertions |

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
- [x] REST endpoints work (`/api/*`)
- [x] OpenAPI spec generates correctly
- [x] HTML docs render

---

### 8.4 Cleanup & Documentation ✅

**Goal:** Remove old module, update docs

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.4.1 | ~~Delete `modules/streamable-http/`~~ | ✅ | Kept as convenience wrapper |
| 8.4.2 | Update `CLAUDE.md` | ✅ | Reflects new structure |
| 8.4.3 | Update `README.md` | ✅ | Module table updated |
| 8.4.4 | Update server startup scripts | ✅ | Use new modules |
| 8.4.5 | Create README for each new module | ✅ | Documentation complete |

**Note:** `streamable-http` was kept as a convenience module that combines mcp-http + rest-api.

**Success Criteria:**
- [x] Core code moved to new modules
- [x] All documentation accurate

---

### 8.5 Extract `mcp-stdio` ✅

**Goal:** Make stdio transport a proper module for consistency

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 8.5.1 | Create `modules/mcp-stdio/` structure | ✅ | Directory and module.edn |
| 8.5.2 | Move `transport/stdio.clj` | ✅ | Namespace renamed to `mcp-stdio.core` |
| 8.5.3 | Update server startup | ✅ | Loads module |
| 8.5.4 | Test with Claude Code | ✅ | End-to-end works (10 tests, 42 assertions)

---

## Phase 9: Remove Legacy Wrappers ✅

**Goal:** Delete deprecated re-exports and legacy transport layer

Pre-1.0 with no external dependencies - full freedom to break backwards compatibility.

| # | Task | Status |
|---|------|--------|
| 9.1 | Delete `src/bb_mcp_server/transport/` directory | ✅ |
| 9.2 | Delete legacy tests (`test/bb_mcp_server/transport/`, `test/run_stdio_tests.clj`) | ✅ |
| 9.3 | Update scripts to use module namespaces directly | ✅ |
| 9.4 | Fix namespace declarations in scripts | ✅ |
| 9.5 | Clean up all lint warnings | ✅ |

**Rationale:** Re-exports add confusion and indirection with no benefit since nothing depends on them.

**Final Test Counts (v0.10.0):**
- Core: 40 tests, 161 assertions
- nrepl: 34 tests, 131 assertions
- http-core: 50 tests, 105 assertions
- mcp-http: 31 tests, 62 assertions
- mcp-stdio: 10 tests, 33 assertions
- **Total: 165 tests, 492 assertions**

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

### Phase 8: Transport Module Extraction ✅
- Extracted `http-core` - shared HTTP infrastructure (50 tests)
- Extracted `mcp-http` - MCP JSON-RPC transport (31 tests)
- Extracted `rest-api` - REST endpoints + OpenAPI (9 tests)
- Extracted `mcp-stdio` - stdio transport (10 tests)
- `streamable-http` kept as convenience wrapper

### Phase 9: Legacy Cleanup ✅ (v0.9.0)
- Deleted `src/bb_mcp_server/transport/` directory
- Deleted legacy tests
- Proper `ns` declarations in all scripts
- 0 lint warnings, 0 errors
- 125 tests, 340 assertions total

---

### Phase 10: Decouple mcp-stdio ✅

**Goal:** Make `mcp-stdio` a true peer to `mcp-http` by removing hardcoded dependencies on `bb-mcp-server.protocol.processor`.

**Result:** `mcp-stdio` is now a pure transport layer with zero bb-mcp-server dependencies.

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 10.1 | Refactor `run-stdio-loop!` signature | ✅ | Accepts `handler-fn` argument |
| 10.2 | Move context creation to caller | ✅ | `make-stdio-ctx` called in scripts |
| 10.3 | Update `server.clj` | ✅ | Creates handler, passes to transport |
| 10.4 | Update `scripts/stdio_server.clj` | ✅ | Creates handler, passes to transport |
| 10.5 | Update mcp-stdio tests | ✅ | Tests use mock handlers (10 tests, 33 assertions) |
| 10.6 | Remove processor require from mcp-stdio | ✅ | Only cheshire + trove imports |
| 10.7 | Standardize context structure | ✅ | Documented in processor.clj |
| 10.8 | Run all tests | ✅ | 165 tests, 492 assertions |

**Key Changes:**
```clojure
;; mcp-stdio/core.clj - now a pure transport
(ns mcp-stdio.core
  (:require [cheshire.core :as json]
            [taoensso.trove :as log]))
;; Zero bb-mcp-server imports!

(defn run-stdio-loop! [handler-fn]
  (doseq [line ...]
    (when-let [response (handler-fn line)]
      (println response)
      (flush))))

;; Entry points create handler
;; server.clj
(let [ctx (processor/make-stdio-ctx)
      handler (fn [line] (processor/process-request-str ctx line))]
  (stdio/run-stdio-loop! handler))
```

---

## Future Improvements

### Transport-Module Coupling (Revisit Later)

**Current State (v0.9.x):** Transport validation is purely a startup-time warning. The `registry/validate-transports` function checks if registered tools have compatible transports available and logs warnings, but does not prevent tools from being loaded.

**How it works today:**
- Tools can optionally specify `:transports #{:rest :mcp-http :mcp-stdio}`
- Default is all transports if not specified
- Server startup calls `validate-transports` with its available transports
- Warnings are logged for tools with no compatible transport

**Potential improvements to explore:**
1. **Declarative transport requirements in `module.edn`** - Let modules declare preferred transports at the module level, not just per-tool
2. **Transport as module dependencies** - Make transports loadable modules that handler modules can depend on
3. **Automatic transport loading** - When a tool requires a transport, auto-load it (requires careful design to avoid circular dependencies)
4. **Transport capability negotiation** - At runtime, transports advertise capabilities, tools query what's available

**Why defer:** Current approach is simple, non-breaking, and provides observability. More sophisticated approaches need more real-world usage patterns to guide design.

---

## References

- [Transport Modularization Design](docs/design/transport-modularization.md)
- [Module System Design](docs/design/module-system-design.md)
- [Streamable HTTP Implementation](modules/streamable-http/docs/streamable-http-implementation-plan.md)
- [Modularization Advice](docs/design/modularization-advice.md)

---

*Last Updated: 2025-11-24*
