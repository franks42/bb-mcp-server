# bb-mcp-server Implementation Plan

**Status:** Phase 13-Port Complete (v0.13.1) - Port Registry Infrastructure
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

### Phase 10: Decouple mcp-stdio ✅ (v0.10.0)
- `mcp-stdio` now a pure transport with zero bb-mcp-server dependencies
- `run-stdio-loop!` accepts `handler-fn` argument
- 165 tests, 492 assertions total

### Phase 11: Unified Entry Point ✅ (v0.11.0)
- Single `bb server` command with composable flags
- `--stdio`, `--http [port]`, `--port`, `--help`
- Supports running both transports simultaneously
- Deleted deprecated `scripts/stdio_server.clj`, `scripts/streamable_http_server.clj`

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

## Phase 11: Unified Entry Point ✅ (v0.11.0)

**Goal:** Single `bb server` command that can run any combination of transports.

**Before:**
- `bb server:stdio` - Stdio only
- `bb server:streamable` - HTTP only
- No way to run both in one process

**After:**
```bash
bb server              # stdio (default, Claude Desktop)
bb server --http       # HTTP only on port 3000
bb server --http 8080  # HTTP only on port 8080
bb server --stdio --http       # both transports simultaneously
bb server --stdio --http 8080  # both, HTTP on 8080
bb server --help       # show usage
```

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 11.1 | Create `src/bb_mcp_server/main.clj` | ✅ | CLI parsing, unified entry point |
| 11.2 | Implement `parse-args` | ✅ | Returns `{:stdio :http :port :help}` |
| 11.3 | Implement `initialize-system!` | ✅ | Shared module/handler initialization |
| 11.4 | Implement `start-http!` | ✅ | HTTP transport with PID file |
| 11.5 | Implement `start-stdio!` | ✅ | Stdio transport blocking |
| 11.6 | Support dual transport mode | ✅ | HTTP async + stdio blocking |
| 11.7 | Update `bb.edn` with unified `server` task | ✅ | Deprecate old tasks |
| 11.8 | Delete deprecated scripts | ✅ | `scripts/stdio_server.clj`, `scripts/streamable_http_server.clj` |
| 11.9 | Test all combinations | ✅ | --stdio, --http, both |

**Key Files:**
- NEW: `src/bb_mcp_server/main.clj` (258 lines)
- MODIFIED: `bb.edn` - unified `server` task
- DELETED: `scripts/stdio_server.clj`, `scripts/streamable_http_server.clj`

**Benefits:**
- Single mental model - one command, flags for behavior
- Resource efficient - no duplicate module loading
- Shared state - single registry, single notification dispatch
- Operational simplicity - one PID, one process, one log stream

---

## Phase 12: Telemetry Audit ✅

**Goal:** Ensure all key operations in Phases 9-11 code have proper telemetry per `docs/AI_TELEMETRY_GUIDE.md`.

**Files audited:**
- `src/bb_mcp_server/main.clj` - Unified entry point
- `modules/mcp-stdio/src/mcp_stdio/core.clj` - Stdio transport
- `modules/mcp-http/src/mcp_http/server.clj` - HTTP server lifecycle
- `modules/mcp-http/src/mcp_http/session.clj` - Session management
- `modules/mcp-http/src/mcp_http/handlers/*.clj` - Request handlers

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 12.1 | Audit `main.clj` telemetry | ✅ | Added: system-initializing, system-initialized, system-create-failed, system-start-failed, http-starting, http-started, stdio-starting, shutdown-initiated, shutdown-complete, dual-transport-mode |
| 12.2 | Audit `mcp-stdio/core.clj` telemetry | ✅ | Already has good coverage (entry, success, failure, debug) |
| 12.3 | Audit `mcp-http/server.clj` telemetry | ✅ | Already has: server-started, server-stopping, server-stopped, notifying-sse-clients |
| 12.4 | Audit `mcp-http/session.clj` telemetry | ✅ | Already has: session-created, session-destroyed, cleanup events, SSE channel add/remove |
| 12.5 | Audit `mcp-http/handlers/*.clj` telemetry | ✅ | Already has: handle-initialize, initialize-complete, request-complete, parse-error, session-terminated |
| 12.6 | Add missing telemetry calls | ✅ | Added 10 telemetry calls to main.clj |
| 12.7 | Run lint/format/test verification | ✅ | 0 errors, 0 warnings, 125 tests pass |

**Telemetry added to main.clj:**
- `::system-initializing` - Entry point for initialization
- `::system-initialized` - Success with duration-ms
- `::system-create-failed` - Failure creating module system
- `::system-start-failed` - Failure starting modules
- `::http-starting` - HTTP transport entry
- `::http-started` - HTTP transport success with endpoints
- `::stdio-starting` - Stdio transport entry
- `::shutdown-initiated` - Shutdown hook triggered
- `::shutdown-complete` - Clean shutdown
- `::dual-transport-mode` - Both transports starting

---

## Phase 13: Claude Subprocess Spawning

**Goal:** Create a module for spawning Claude CLI instances as subprocesses with stdio connected to bb-mcp-server.

**Design Document:** [claude-subprocess-spawning-architecture.md](docs/design/claude-subprocess-spawning-architecture.md)

### Phase 13A: Core Scaffolding ✅ Complete

**Status:** Implementation complete - all tests passing

**What was implemented:**
- Created `modules/claude-manager/` with complete module structure
- Implemented Dedicated Reader Loop pattern from Gemini review
- Core API: `spawn!`, `ask`, `kill!`, `list-instances`
- Process management: `spawn-process!`, `start-reader-loop!`, `write-message!`
- Registry: instance tracking, request-ID generation, promise-based async correlation
- Mock testing: `mock_claude.clj` JSONL echo for CI-friendly tests
- Tests: 12 tests, 23 assertions - all passing ✅
- Verification: 0 lint errors, 0 warnings ✅

**Files created:**
```
modules/claude-manager/
├── module.edn
├── src/claude_manager/
│   ├── core.clj          # Public API (spawn!, ask, kill!, list-instances)
│   ├── process.clj       # Process spawning & dedicated reader loop
│   └── registry.clj      # Instance tracking & state management
└── test/
    ├── mock_claude.clj   # JSONL echo mock (Option B from discussion)
    ├── claude_manager/core_test.clj  # 12 tests, 23 assertions
    └── run_tests.clj
```

**Configuration:**
- Added to `bb.edn`: paths and `test:claude-manager` task

**Next:** Phase 13B - Replace mock with real Claude CLI integration

### Phase 13.5: Stdio Transport Safety ✅ Complete

**Status:** Safety improvements complete - all verification passing

**Problem identified:** Any `println` statement in production code corrupts JSON-RPC stream when stdio transport is active (e.g., Claude Desktop spawning bb-mcp-server).

**What was implemented:**
1. **Scanned codebase** - Found violation in `main.clj` lines 299-301 (dual-transport mode startup banner)
2. **Fixed main.clj** - Replaced all problematic `println` with telemetry calls
3. **Added lint rule** - Added `:discouraged-var` to `.clj-kondo/config.edn` to forbid `println/prn/print`
4. **Created checklist** - New `docs/CODE_REVIEW_CHECKLIST.md` with stdio safety as top priority
5. **Improved tooling** - Created `bb fix-parens` task with user-friendly parmezan wrapper
6. **Updated docs** - Fixed parmezan documentation in `docs/CLOJURE_EXPERT_CONTEXT.md`

**Files modified:**
- `src/bb_mcp_server/main.clj` - Replaced 3 println calls with telemetry (lines 279, 291, 299-301)
- `.clj-kondo/config.edn` - Added `:discouraged-var` linter with error level
- `bb.edn` - Added `fix-parens` task
- `docs/CLOJURE_EXPERT_CONTEXT.md` - Corrected parmezan usage (was using wrong `--in-place` flag)
- `docs/CODE_REVIEW_CHECKLIST.md` - Created comprehensive code review checklist
- `docs/design/claude-subprocess-spawning-architecture.md` - Updated status to reflect Phase 13A completion

**Verification:**
```bash
$ clj-kondo --lint src/bb_mcp_server/main.clj
linting took 82ms, 0 errors, 0 warnings  ✅

$ cljfmt check src/bb_mcp_server/main.clj
All files formatted correctly.  ✅

$ bb test:modules
125 tests, 293 assertions, 0 failures  ✅
```

**Critical Note:** Existing `println` in `modules/mcp-stdio/src/mcp_stdio/core.clj` and `src/bb_mcp_server/protocol/processor.clj` are VALID - they ARE the stdio transport implementation.

**Lint rule added:**
```clojure
:discouraged-var {:level :error
                  :symbols {clojure.core/println {:message "Use taoensso.trove/log! instead. println breaks stdio transport."}
                            clojure.core/prn {:message "Use taoensso.trove/log! instead. prn breaks stdio transport."}
                            clojure.core/print {:message "Use taoensso.trove/log! instead. print breaks stdio transport."}}}
```

### Phase 13: Architecture Design Phase 🎨 In Progress

**Status:** Comprehensive architecture documents created - ready for implementation

**What was designed:**
1. **Multi-Provider AI Orchestration** (`docs/design/ai-orchestrator-architecture.md`)
   - Unified registry for Claude subprocess, OpenAI HTTP, Anthropic, Ollama
   - Protocol abstraction using Clojure multimethods
   - Provider-agnostic API: `start-instance!`, `ask`, `stop-instance!`
   - Module layering: `ai-orchestrator` (core) + provider plugins

2. **Domain Experts Framework** (`docs/design/ai-experts-framework.md`)
   - Expert registry with curriculum, capabilities, domains
   - File-based MVP (Phase 13E) → Datalevin migration (Phase 14)
   - Message bus architecture (centralized, P2P, team-based patterns)
   - Dynamic expert orchestration (on-demand team creation)
   - Dedicated MCP servers per expert domain
   - HTTP-based recommended (remote, shared, dynamic, cloud-ready)

3. **Critical Design Considerations** (Gemini 3 Pro Review Integration)
   - **Expert Driver Pattern**: Clojure wrapper for reactive Claude instances
   - **State Management**: BB Server manages history explicitly (not inside Claude)
   - **Security**: Localhost-only binding, PID tracking, port allocation
   - **Terminology**: Profile/Manifest, Context/Knowledge, Persona, Curriculum
   - **Implementation Details**: Expert record, MCP integration, tool registry

**Documents created:**
- `docs/design/ai-orchestrator-architecture.md` - Multi-provider orchestration
- `docs/design/ai-experts-framework.md` - Domain experts with curriculum (1601 lines)
- `ai-experts-framework-review-gemini.md` - External review recommendations

**Key architectural decisions:**
- Lifecycle naming: Use `start-instance!` / `stop-instance!` (not `spawn!` / `kill!`)
- Provider registry schema: `:provider-type`, `:protocol`, `:transport`, `:capabilities`
- Expert Driver pattern: BB wrapper for bus subscription, history, lifecycle
- State management: Explicit history in BB (enables pause/resume, debugging, checkpointing)
- MCP servers: HTTP preferred over stdio (remote, shared, dynamic tools)
- Port allocation: Domain-specific ranges (19880-19889 for clojure-tools, etc.)

**Architecture patterns validated:**
- Module layering (core + plugins)
- Protocol dispatch via multimethods
- Two-registry architecture (definitions vs running instances)
- Team-based message bus (coordinator + members)
- Dedicated MCP servers per domain (80% context reduction)

### Phase 13-Port: Port Registry Infrastructure ✅ Complete

**Goal:** Implement file-based port allocation and discovery system.

**Status:** Complete - v0.13.1

**Why this comes first:** Experts need dedicated MCP servers, which need unique ports. Must have port management before implementing expert framework.

**Design Document:** [port-management-architecture.md](docs/design/port-management-architecture.md)

**Completed Features:**
1. ✅ Created `modules/port-registry/` module
2. ✅ Implemented file-based registry (`.ports/registry.edn`)
3. ✅ Core functions:
   - `allocate-port!` - Assign port from domain-specific range
   - `release-port!` - Free port for reuse
   - `discover-by-domain` - Find existing server
   - `discover-by-expert` - Find port by expert ID
   - `validate-registry!` - Cleanup zombie ports on startup
   - `update-port-info!` - Update allocation metadata
   - `get-port-info` - Get allocation details
   - `list-allocations` - List all port allocations
4. ✅ Port ranges configuration (10 domains):
   - clojure-tools: 19880-19889
   - aws-tools: 19890-19899
   - python-tools: 19900-19909
   - js-tools: 19910-19919
   - data-tools: 19920-19929
   - ml-tools: 19930-19939
   - web-tools: 19940-19949
   - db-tools: 19950-19959
   - test-tools: 19960-19969
   - misc-tools: 19970-19979
5. ✅ Health monitoring:
   - `check-port-health!` - Socket connection test
   - `cleanup-stale-allocations!` - Remove dead processes
6. ✅ Comprehensive tests: 12 tests, 36 assertions, all passing

**Files created:**
```
modules/port-registry/
├── module.edn
├── src/port_registry/
│   ├── core.clj      # Public API (300+ lines)
│   └── storage.clj   # EDN persistence (77 lines)
└── test/
    ├── port_registry/core_test.clj (205 lines)
    └── run_tests.clj
```

**Critical Feature (Gemini Review):** Zombie port cleanup ✅
- On startup, validate all ports in registry
- Check if PID is still alive using `process-alive?` (kill -0)
- Release ports from crashed processes
- Prevents registry filling up with stale allocations

**Verification:**
```bash
$ bb test:port-registry
12 tests, 36 assertions, 0 failures, 0 errors ✅

$ clj-kondo --lint modules/port-registry/
0 errors, 0 warnings ✅

$ cljfmt check modules/port-registry/
All source files formatted correctly ✅
```

**Next:** Phase 13E - Expert Registry MVP

### Key Features

1. **Claude-as-a-tool** - Invoke Claude instances from MCP tools
2. **Multi-agent orchestration** - Multiple Claude instances communicating
3. **Session management** - Persistent processes with conversation state
4. **Request/Response correlation** - Match async replies to original requests

### Architecture Decision

**Option A:** Two modules (generic process spawning + Claude-specific)
**Option B:** Single `claude-spawner` module

*Recommendation:* Start with Option B to avoid premature abstraction.

### Sub-phases

| # | Sub-phase | Status | Description |
|---|-----------|--------|-------------|
| 13A | Core Scaffolding | ✅ Complete | Mock process testing, dedicated reader loop, basic API |
| 13.5 | Stdio Safety | ✅ Complete | Lint rules, telemetry migration, code review checklist |
| 13-Design | Architecture Docs | ✅ Complete | Multi-provider orchestration, domain experts framework, port management |
| 13-Port | Port Registry | ✅ Complete | File-based port allocation/discovery, zombie cleanup (12 tests, 36 assertions) |
| 13E | Expert Registry MVP | Next | File-based expert definitions, curriculum loading (uses port-registry) |
| 13B | Multi-Provider Refactor | Planned | Refactor claude-manager to use ai-orchestrator + providers |
| 13C | OpenAI HTTP Provider | Planned | Validate multi-provider design with second provider |
| 13D | MCP Integration | Planned | Expose orchestrator as MCP tools |
| 13F | Message Bus | Planned | core.async bus, team-based communication |
| 13G | Dynamic Orchestration | Planned | On-demand expert creation, task delegation |

### Proposed Tools

```clojure
claude_spawn   - Start a new Claude subprocess
claude_message - Send message and get response
claude_list    - List running instances
claude_stop    - Stop a Claude instance
claude_fork    - Fork from existing session
```

### Reference Implementation

Based on patterns from `clay-noj-ai` project:
- `babashka.process/process` for spawning
- `--input-format stream-json --output-format stream-json` for JSONL protocol
- `--resume session-id` for session forking
- Request-ID format: `{name}-{counter}-{uuid-prefix}`

### Key Architecture Decision (from Gemini Review)

**Dedicated Reader Loop Pattern** - Critical fix for concurrency issues in prototype:
- Background `future` per instance reads stdout continuously
- Dispatches messages by type (result, assistant, system, error)
- Delivers results to waiting promises by request-id
- Thread-safe: multiple callers can `ask` same instance

See `gemini-claude-subprocess-spawning-review.md` for full analysis.

### Module Structure

```
modules/claude-manager/
├── src/claude_manager/
│   ├── core.clj      # Public API (spawn, ask, list)
│   ├── process.clj   # Low-level process & I/O handling
│   └── registry.clj  # State management (atoms)
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
