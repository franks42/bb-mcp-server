# bb-mcp-server Implementation Plan

**Status:** Phase 14B Complete (v0.14.0) - Dynamic Runtime Module Loading
**Last Updated:** 2025-11-26

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

### Phase 13E: Expert Registry MVP ✅ Complete

**Goal:** File-based expert definitions with curriculum loading.
**Status:** Complete - v0.13.2

**Completed Features:**
1. ✅ Created `modules/expert-registry/` module
2. ✅ Expert definition loading from `.experts/` directory
3. ✅ Curriculum management (essential + reference files)
4. ✅ Expert spawn/kill lifecycle with port allocation
5. ✅ Integration with port-registry and claude-manager
6. ✅ Example expert: clojure-coder with curriculum
7. ✅ Comprehensive tests: 9 tests, 29 assertions, all passing

**Module Structure:**
```
modules/expert-registry/
├── src/expert_registry/
│   ├── core.clj         # Expert lifecycle & registry
│   └── curriculum.clj   # Curriculum loading
└── test/
    └── expert_registry/
        └── core_test.clj  # 9 tests, 29 assertions
```

**Expert Definition Format:**
```clojure
.experts/clojure-coder/
├── manifest.edn          # Expert metadata & capabilities
├── essential/            # Must-read curriculum
│   └── standards.md
└── reference/            # Optional reference docs
    └── babashka.md
```

**Core Functions:**
- `init!` - Load expert definitions from disk
- `list-experts` - Query all experts
- `get-expert` - Get expert by ID
- `find-experts-by-capability` - Search by capability
- `spawn-expert!` - Create instance with curriculum loaded
- `kill-expert!` - Terminate instance and release port

**Verification:**
```bash
$ bb test:expert-registry
9 tests, 29 assertions, 0 failures, 0 errors ✅

$ clj-kondo --lint modules/expert-registry/
0 errors, 0 warnings ✅

$ cljfmt check modules/expert-registry/
All source files formatted correctly ✅
```

**Next:** Phase 13C - OpenAI HTTP Provider (Validation)

---

### Phase 13B: Multi-Provider Refactor ✅ Complete

**Goal:** Extract orchestration infrastructure and make Claude subprocess a provider plugin.
**Status:** Complete - v0.13.4.2 (CLI args fixed)

**Completed Features:**
1. ✅ Created `modules/ai-orchestrator/` - Provider-agnostic infrastructure
2. ✅ Created `modules/claude-subprocess-provider/` - Claude CLI subprocess provider
3. ✅ Implemented multimethod-based protocol for extensibility
4. ✅ Provider-agnostic registry and request correlation
5. ✅ Updated expert-registry to use orchestrator API
6. ✅ Comprehensive tests: 5 tests, 17 assertions, all passing
7. ✅ **Fixed subprocess CLI args - tested with real Claude CLI (3.85s response)**

**Module Structure:**
```
modules/ai-orchestrator/
├── src/ai_orchestrator/
│   ├── core.clj         # Public API (start-instance!, ask, stop-instance!)
│   ├── registry.clj     # Provider-agnostic registry
│   ├── protocol.clj     # Multimethods for extensibility
│   └── router.clj       # Request correlation
└── test/
    └── ai_orchestrator/
        └── core_test.clj  # 5 tests, 17 assertions

modules/claude-subprocess-provider/
├── src/claude_subprocess/
│   ├── core.clj         # Protocol implementation
│   └── process.clj      # JSONL subprocess handling
└── test/
    ├── mock_claude.clj  # Test helper
    └── (tests via orchestrator)
```

**Protocol Design (Multimethods):**
```clojure
;; ai-orchestrator.protocol
(defmulti create-instance :provider-type)
(defmulti send-message (fn [instance _] (:provider-type instance)))
(defmulti stop-instance :provider-type)
(defmulti get-capabilities identity)

;; claude-subprocess.core implements for :claude-subprocess
(defmethod create-instance :claude-subprocess [...])
(defmethod send-message :claude-subprocess [...])
(defmethod stop-instance :claude-subprocess [...])
```

**Public API:**
```clojure
;; Provider-agnostic API
(orch/start-instance! "my-claude"
  {:provider-type :claude-subprocess
   :cmd ["claude" "--stream-json"]})

(orch/ask "my-claude" "What is 2+2?")
(orch/stop-instance! "my-claude")
```

**Breaking Changes:**
- `claude-manager.core/spawn!` → `ai-orchestrator.core/start-instance!`
- `claude-manager.core/kill!` → `ai-orchestrator.core/stop-instance!`
- `claude-manager.core/ask` → `ai-orchestrator.core/ask` (same name, different namespace)
- expert-registry updated to use new API

**Verification:**
```bash
$ bb modules/ai-orchestrator/test/run_tests.clj
5 tests, 17 assertions, 0 failures, 0 errors ✅

$ clj-kondo --lint modules/ai-orchestrator/src modules/claude-subprocess-provider/src
0 errors, 0 warnings ✅

$ cljfmt check modules/ai-orchestrator/src modules/claude-subprocess-provider/src
All source files formatted correctly ✅
```

**Subprocess CLI Args Fix (v0.13.4.2):**

Issue discovered: Subprocess provider was passing just the command path to `spawn-process!`, but it expected a full command vector with CLI arguments.

Root cause:
- Tests only used mock_claude.clj (never tested with real CLI)
- Missing args: `-p`, `--verbose`, `--input-format stream-json`, `--output-format stream-json`, `--permission-mode bypassPermissions`

Solution:
- Build command vector in `create-instance` before spawning process
- Reference working pattern from clay-noj-ai prototype
- Added `:args` parameter for optional custom arguments

Real API test:
```bash
# Response received successfully in 3.85s
Response: {:content "Hello!", :cost_usd 0, :duration_ms 3850}
```

**Performance Test Results (2025-11-25):**

Tested 5 concurrent instances (3 HTTP + 2 subprocess) with two consecutive requests to isolate startup overhead:

| Provider | First Request (with startup) | Second Request (ongoing) | Startup Overhead |
|----------|------------------------------|--------------------------|------------------|
| subprocess-1 (Sonnet) | 14,642ms (API: 3,844ms) | 3,364ms (API: 3,359ms) | **~11,300ms** |
| subprocess-2 (Haiku) | 15,366ms (API: 4,787ms) | 3,560ms (API: 3,559ms) | **~11,800ms** |
| sonnet-http | 3,301ms | 2,776ms | ~525ms |
| haiku-http | 680ms | 611ms | ~69ms |
| sonnet-compat | 3,325ms | 2,298ms | ~1,027ms |

**Key Findings:**
1. **Subprocess startup overhead is ~11-12 seconds** (process spawning, Claude CLI initialization)
2. **After initialization, subprocess performs comparably to HTTP** (3.3-3.6s vs 2.3-2.8s)
3. **API time is nearly identical** (~3.3s subprocess vs ~2.7s HTTP)
4. **Difference (~600ms) likely HTTP connection overhead vs JSONL stdio**

**Implications for Expert Framework:**
- **Quick tasks (< 1 minute):** Use HTTP providers (lower startup overhead)
- **Long-running experts:** Subprocess is fine (startup cost amortized)
- **Ephemeral experts:** HTTP preferred (faster spawn/destroy cycles)
- **Persistent experts:** Subprocess acceptable (startup happens once)

**Next:** Phase 13C - HTTP Providers

**OpenAI & Gemini API Test Results (2025-11-25):**

Verified OpenAI-compatible endpoint works with multiple providers:

| Provider | Model | Base URL | First Request | Second Request |
|----------|-------|----------|---------------|----------------|
| OpenAI | gpt-4o-mini | api.openai.com | 1,702ms → `4` | 902ms → `Greetings!` |
| Gemini | gemini-2.0-flash | generativelanguage.googleapis.com | 847ms → `4` | 653ms → `Hello.` |

**Key Finding:** Gemini supports OpenAI-compatible Chat Completions API via:
- Base URL: `https://generativelanguage.googleapis.com/v1beta/openai`
- Auth: `Authorization: Bearer <GEMINI_API_KEY>`
- Models: gemini-2.0-flash, gemini-2.5-flash, gemini-2.5-pro, etc.

```clojure
;; Gemini via OpenAI-compatible endpoint
(orch/start-instance! "gemini"
  {:provider-type :openai-http
   :api-key (System/getenv "GEMINI_API_KEY")
   :base-url "https://generativelanguage.googleapis.com/v1beta/openai"
   :model "gemini-2.0-flash"})
```

---

### Phase 13C: HTTP Providers ✅ Complete

**Goal:** Validate multi-provider design with HTTP-based providers.
**Status:** Complete - v0.13.4.1 (with async routing)

**Completed Features:**
1. ✅ Created `modules/anthropic-http-provider/` - Native Anthropic Messages API
2. ✅ Created `modules/openai-http-provider/` - OpenAI Chat Completions API (+ Anthropic compat)
3. ✅ HTTP client implementations with babashka.http-client (Babashka compatible)
4. ✅ Provider protocol implementations with async routing integration
5. ✅ Model configuration files (anthropic-models.edn, openai-models.edn)
6. ✅ Comprehensive tests: 9 tests, 43 assertions, all passing
7. ✅ **Real API testing: Both providers verified with Anthropic API (claude-sonnet-4-5-20250929)**

**Module Structure:**
```
modules/anthropic-http-provider/
├── anthropic-models.edn       # Claude 4.5 model identifiers
├── src/anthropic_http/
│   ├── core.clj               # Protocol implementation with async routing
│   └── http_client.clj        # Anthropic Messages API client (babashka.http-client)
└── test/
    └── anthropic_http/
        ├── core_test.clj      # 4 tests, 18 assertions
        └── run_tests.clj

modules/openai-http-provider/
├── openai-models.edn          # OpenAI + Anthropic compat model identifiers
├── src/openai_http/
│   ├── core.clj               # Protocol implementation with async routing
│   └── http_client.clj        # OpenAI Chat Completions API client (babashka.http-client)
└── test/
    └── openai_http/
        └── core_test.clj      # 5 tests, 25 assertions
```

**Anthropic HTTP Provider:**
- Native Messages API (`https://api.anthropic.com/v1/messages`)
- Authentication: `x-api-key` header
- Request format: `{model, messages, max_tokens, system, temperature, stream}`
- Response: Message object with content blocks

**OpenAI HTTP Provider:**
- Chat Completions API (`https://api.openai.com/v1/chat/completions`)
- Authentication: `Authorization: Bearer <key>` header
- Request format: `{model, messages, max_tokens, temperature, stream}`
- Works with both OpenAI and Anthropic compatibility endpoint

**Usage Examples:**
```clojure
;; Anthropic native API (Claude 4.5 models from anthropic-models.edn)
(orch/start-instance! "claude-api"
  {:provider-type :anthropic-http
   :api-key (System/getenv "CLAUDE_API_KEY")
   :model "claude-sonnet-4-5-20250929"  ; Sonnet 4.5 (recommended)
   :max-tokens 1024})

;; OpenAI API
(orch/start-instance! "gpt"
  {:provider-type :openai-http
   :api-key (System/getenv "OPENAI_API_KEY")
   :model "gpt-4-turbo-preview"})

;; Anthropic via OpenAI compatibility endpoint (uses Bearer auth)
(orch/start-instance! "claude-compat"
  {:provider-type :openai-http
   :api-key (System/getenv "CLAUDE_API_KEY")
   :model "claude-sonnet-4-5-20250929"
   :base-url "https://api.anthropic.com/v1"
   :max-tokens 1024})
```

**Verification:**

**Unit Tests:**
```bash
$ bb modules/anthropic-http-provider/test/run_tests.clj
Ran 4 tests containing 18 assertions.
0 failures, 0 errors. ✅

$ bb modules/openai-http-provider/test/run_tests.clj
Ran 5 tests containing 25 assertions.
0 failures, 0 errors. ✅
```

**Real API Tests (both providers verified):**
```bash
# Test anthropic-http-provider
$ . ./.cak.sh && bb -e "(require '[ai-orchestrator.core :as orch] '[anthropic-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :anthropic-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-sonnet-4-5-20250929\"
                                        :max-tokens 100})]
  (println (orch/ask \"test\" \"Say hello in exactly one word\"))
  (orch/stop-instance! \"test\"))"

Response: {:content "Hello", :duration_ms 2837} ✅

# Test openai-http-provider (Anthropic compatibility mode)
$ . ./.cak.sh && bb -e "(require '[ai-orchestrator.core :as orch] '[openai-http.core])
(let [i (orch/start-instance! \"test\" {:provider-type :openai-http
                                        :api-key (System/getenv \"CLAUDE_API_KEY\")
                                        :model \"claude-sonnet-4-5-20250929\"
                                        :base-url \"https://api.anthropic.com/v1\"
                                        :max-tokens 100})]
  (println (orch/ask \"test\" \"Say hello in exactly one word\"))
  (orch/stop-instance! \"test\"))"

Response: {:content "Hello", :duration_ms 2839} ✅
```

**Lint & Format:**
```bash
$ clj-kondo --lint modules/anthropic-http-provider/src modules/openai-http-provider/src
linting took 28ms, 0 errors, 0 warnings ✅

$ cljfmt check modules/anthropic-http-provider/src modules/openai-http-provider/src
All source files formatted correctly ✅
```

**Key Validations:**
- ✅ Multi-provider protocol works for HTTP-based providers
- ✅ Both native APIs and compatibility endpoints supported
- ✅ Provider-specific configuration (api-key, base-url, timeouts)
- ✅ Transport metadata properly structured
- ✅ All telemetry requirements met
- ✅ **Async routing integration: HTTP calls non-blocking, promise delivery working**
- ✅ **Real API connectivity: Both providers successfully tested with Anthropic API**
- ✅ **No timeout errors: Async promise pattern correctly implemented**

---

### Phase 13G: Multi-Agent Orchestration ✅ Complete

**Goal:** Demonstrate multiple AI agents collaborating on a real task via message bus
**Status:** Complete - v0.13.8

**Test Scenario:** Code Review Pipeline
- 3 agents: clojure-coder, code-reviewer, test-writer
- Task: Implement `retry-with-backoff` function with tests

**Final Architecture:**
```
User Task
    │
    ▼
┌─────────────────┐
│  clojure-coder  │  subprocess (file access for writing code)
│  Sonnet 4.5     │  36s
└────────┬────────┘
         │ code (read from disk)
         ▼
┌─────────────────┐
│  code-reviewer  │  anthropic-http (isolated, fast)
│  Sonnet 4.5     │  5s
└────────┬────────┘
         │ APPROVED
         ▼
┌─────────────────┐
│   test-writer   │  anthropic-http (isolated, fast)
│  Haiku          │  12s
└────────┬────────┘
         │ tests
         ▼
    Complete (65s total)
```

**Key Learnings Documented in `docs/design/multi-agent-interaction-learnings.md`:**

1. **"Good enough" prompts beat "perfect" prompts**
   - "If PERFECT, respond APPROVED" → endless review loops
   - "If it WORKS correctly, respond APPROVED" → approved in 5s
   - Focus on BLOCKER/BUG/CRASH only, ignore style preferences

2. **Subprocess vs HTTP performance**
   - Subprocess: file access but unpredictable timing (may do extra work)
   - HTTP API: fast, predictable, isolated (physically cannot access files)
   - Use subprocess ONLY when file access is REQUIRED

3. **Subprocess isolation prompts**
   - When using subprocess for text generation, add:
     "Base your response ONLY on the code provided. Do NOT access files."
   - HTTP agents don't need this - isolation enforced by transport

**Iterations:**
| # | Issue | Fix | Result |
|---|-------|-----|--------|
| 1 | Reviewer never approved | - | 3 iterations, max reached |
| 2 | Changed to "good enough" prompt | Focus BLOCKER/BUG/CRASH | **APPROVED in 5s** |
| 3 | Test-writer timed out (120s) | - | Subprocess doing file ops |
| 4 | Switched test-writer to HTTP | `anthropic-http` Haiku | **Complete in 65s** |

**Files Created:**
- `docs/design/multi-agent-orchestration-test.md` - Design document
- `docs/design/multi-agent-interaction-learnings.md` - Prompt patterns & anti-patterns
- `docs/design/multi-agent-test-log.md` - Execution log
- `scripts/multi_agent_test.clj` - Test script

**Verification:**
```bash
$ source .cak.sh && bb scripts/multi_agent_test.clj
✅ 3 agents started (coder subprocess, reviewer HTTP, tester HTTP)
✅ Code generated (36s)
✅ Code reviewed and APPROVED (5s)
✅ Tests generated (12s)
✅ All agents stopped cleanly
Total: 65 seconds
```

---

### Phase 13F: Message Bus ✅ Complete

**Goal:** Lightweight message bus for AI expert communication
**Status:** Complete - v0.13.6

**Design Decision:** Used **Atoms + Promises** instead of core.async for:
- Better debuggability (no opaque channels)
- Easier introspection (atoms are inspectable)
- Simpler error handling
- User preference against "opaque magic"

**Completed Features:**
1. ✅ Created `modules/message-bus/` module
2. ✅ Core API: `subscribe!`, `publish!`, `ask`, `reply!`
3. ✅ Global Response Router for O(1) request correlation
4. ✅ Teams API for namespaced topic isolation
5. ✅ Introspection: `list-topics`, `get-recent-messages`, `get-pending-requests`
6. ✅ Handler error isolation (one crash doesn't affect others)
7. ✅ Comprehensive tests: 25 tests, 68 assertions, all passing

**Module Structure:**
```
modules/message-bus/
├── module.edn
├── src/message_bus/
│   ├── core.clj    # Pub/sub, ask/reply, Global Response Router
│   └── teams.clj   # Team-based isolation (namespaced topics)
└── test/
    ├── message_bus/core_test.clj   # 15 tests
    └── message_bus/teams_test.clj  # 10 tests
```

**Core API:**
```clojure
;; Pub/Sub
(def unsub (bus/subscribe! :topic handler-fn))
(bus/publish! :topic {:content "hello"})
(unsub)

;; Request/Response with O(1) correlation
(bus/subscribe! :responder
  (fn [{:keys [request-id content]}]
    (bus/reply! request-id (process content))))

(bus/ask :responder "question" :timeout-ms 5000)
;; => {:success true :content "answer" :duration-ms 123}
```

**Teams API:**
```clojure
;; Create isolated team
(def team (teams/create-team :deploy-app #{:clojure :aws :docs}))

;; Team-scoped communication (topics are namespaced)
(teams/team-subscribe! team :clojure handler-fn)
(teams/team-publish! team :clojure {:content "ready"})
(teams/team-broadcast! team {:content "starting"})
```

**Future Consideration: Datalevin Backend**

After Phase 14 adds a Datalevin service, revisit message-bus implementation.
See `docs/design/datalevin-message-bus-review.md` for analysis.

**Potential benefits of Datalevin backend:**
- Free persistence (conversation history as audit log)
- Queryable history via Datalog
- Unified state (bus + database in one component)
- "Blackboard Architecture" pattern

**Trade-offs:**
- Millisecond latency vs microsecond (acceptable for AI workloads)
- Single writer bottleneck (unlikely to hit before API rate limits)

**Decision:** Keep current atoms+promises implementation until Datalevin is integrated.
The API surface (`subscribe!`, `publish!`, `ask`, `reply!`) can remain unchanged -
only the backing implementation would change.

---

### Phase 13D: MCP Tool Integration ✅ Complete

**Goal:** Expose AI orchestrator functionality via MCP tools
**Status:** Complete - v0.13.5

**Completed Features:**
1. ✅ Created `modules/ai-orchestrator-tools/` module
2. ✅ Implemented 4 MCP tools exposing orchestrator API
3. ✅ Module loading order fix (providers load before tools)
4. ✅ Real API testing via MCP protocol (Anthropic HTTP verified)
5. ✅ Comprehensive tests: 13 tests, 44 assertions, all passing

**Module Structure:**
```
modules/ai-orchestrator-tools/
├── module.edn                    # Requires ai-orchestrator, all providers
├── src/ai_orchestrator_tools/
│   └── core.clj                  # 4 MCP tools
└── test/
    ├── ai_orchestrator_tools/
    │   └── core_test.clj         # 13 tests, 44 assertions
    └── run_tests.clj
```

**MCP Tools Implemented:**

| Tool | Description | Parameters |
|------|-------------|------------|
| `ai_start_instance` | Start AI instance | name, provider_type, model, api_key, base_url, max_tokens, cmd |
| `ai_ask` | Send message to instance | name, message |
| `ai_stop_instance` | Stop running instance | name |
| `ai_list_instances` | List all instances | provider_type (optional filter) |

**Provider Support:**
- `anthropic-http` - Native Anthropic Messages API
- `openai-http` - OpenAI API (or Anthropic compat via base_url)
- `claude-subprocess` - Claude CLI subprocess

**Example Usage via MCP:**
```json
// Start instance
{"jsonrpc":"2.0","method":"tools/call","id":1,
 "params":{"name":"ai-orchestrator-tools.ai_start_instance",
           "arguments":{"name":"test-ai",
                        "provider_type":"anthropic-http",
                        "model":"claude-sonnet-4-5-20250929",
                        "api_key":"sk-..."}}}

// Ask question
{"jsonrpc":"2.0","method":"tools/call","id":2,
 "params":{"name":"ai-orchestrator-tools.ai_ask",
           "arguments":{"name":"test-ai",
                        "message":"What is 2+2?"}}}
// Response: {"content":"4","duration_ms":696}

// Stop instance
{"jsonrpc":"2.0","method":"tools/call","id":3,
 "params":{"name":"ai-orchestrator-tools.ai_stop_instance",
           "arguments":{"name":"test-ai"}}}
```

**Module Loading Order Fix:**
The `ai-orchestrator-tools` module requires all provider modules be loaded first.
Fixed in `system.edn` and `bb-mcp-server.module.system`:

```clojure
;; system.edn - providers before tools
:modules ["echo" "strings" "math" "calculate" "local-eval" "nrepl"
          "ai-orchestrator"
          "anthropic-http-provider"
          "openai-http-provider"
          "claude-subprocess-provider"
          "ai-orchestrator-tools"]
```

**Verification:**
```bash
$ bb modules/ai-orchestrator-tools/test/run_tests.clj
Ran 13 tests containing 44 assertions.
0 failures, 0 errors. ✅

$ clj-kondo --lint modules/ai-orchestrator-tools/
linting took 14ms, 0 errors, 0 warnings ✅

$ cljfmt check modules/ai-orchestrator-tools/
All source files formatted correctly ✅
```

**Real API Test Results:**
- Started bb-mcp-server with 11 modules, 19 tools
- Established MCP session via HTTP POST
- `ai_list_instances` → 0 instances (correct)
- `ai_start_instance` (anthropic-http, Sonnet 4.5) → success
- `ai_ask "What is 2+2?"` → `{"content":"4","duration_ms":696}`
- `ai_stop_instance` → success
- `ai_list_instances` → 0 instances (correct)

---

## Phase 13: Claude Subprocess Spawning

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
| 13E | Expert Registry MVP | ✅ Complete | File-based expert definitions, curriculum loading (9 tests, 29 assertions) |
| 13B | Multi-Provider Refactor | ✅ Complete | Provider-agnostic orchestration with claude-subprocess provider (5 tests, 17 assertions) |
| 13C | HTTP Providers | ✅ Complete | Anthropic & OpenAI HTTP providers with async routing (9 tests, 43 assertions, real API verified) |
| 13D | MCP Integration | ✅ Complete | AI orchestrator exposed as MCP tools (4 tools, 13 tests, 44 assertions) |
| 13F | Message Bus | ✅ Complete | Atoms+promises bus with Global Response Router (25 tests, 68 assertions) |
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

## Phase 14: Dynamic Classpath-Based Module Discovery

**Status:** Phase 14A & 14B Complete (v0.14.0)
**Last Updated:** 2025-11-26

### Overview

**Goal:** Enable loading modules from outside the project directory and dynamically at runtime.

**Key Features:**
1. **External Modules** - Load from `BB_MCP_EXTERNAL_MODULES` env var
2. **Dynamic Runtime Loading** - Hot-load modules via `load-new-module!`
3. **Minimal Bootstrap Pattern** - Start with just nrepl, load everything else dynamically

---

### Phase 14A: External Module Loading ✅ Complete

**Goal:** Load modules from arbitrary paths outside the project directory.

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 14A.1 | Implement `BB_MCP_EXTERNAL_MODULES` env var support | ✅ | Colon-separated paths |
| 14A.2 | Auto-detect single module vs collection | ✅ | Check for module.edn |
| 14A.3 | Add paths to classpath at startup | ✅ | Via `babashka.classpath/add-classpath` |
| 14A.4 | Update README with external modules docs | ✅ | Usage examples |
| 14A.5 | Test with external modules | ✅ | echo/hello from /tmp/external-modules/ |

**Usage:**
```bash
# Single module or collection of modules
BB_MCP_EXTERNAL_MODULES=/path/to/my-module bb server --http

# Multiple paths (colon-separated)
BB_MCP_EXTERNAL_MODULES=/path/to/module1:/path/to/modules-collection bb server --http
```

---

### Phase 14B: Runtime Dynamic Loading ✅ Complete

**Goal:** Load modules at runtime after the server is already running.

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 14B.1 | Implement `load-new-module!` function | ✅ | In `system.clj` |
| 14B.2 | Add classpath manipulation via bootstrap | ✅ | `add-external!` helper |
| 14B.3 | Support loading + starting + registering | ✅ | Full module lifecycle |
| 14B.4 | Test via test script | ✅ | `scripts/test_dynamic_load.clj` |
| 14B.5 | Document minimal bootstrap pattern | ✅ | README updated |

**Core Function:**
```clojure
(require '[bb-mcp-server.module.system :as system])
(system/load-new-module! "/path/to/external-module")
;; => {:success {:module-name "hello" :tools ["hello.hello"]}}
```

**Minimal Bootstrap Pattern:**
```clojure
;; system.edn - start with just nrepl
{:modules ["nrepl"]}

;; Then dynamically load everything else via nrepl-eval:
(system/load-new-module! "/path/to/module-a")
(system/load-new-module! "/path/to/module-b")
```

**Key Implementation Details:**
- `system/load-new-module!` (lines 647-717 in system.clj)
- Adds module src to classpath via `bootstrap/add-external!`
- Loads module via `ns-loader/load-module`
- Starts module with dependencies
- Registers tools immediately
- Triggers `listChanged` notification

**Test Verification:**
```bash
$ bb scripts/test_dynamic_load.clj
=== Testing Dynamic Module Loading ===
Initial state: 2 tools [strings.concat math.add]
Dynamically loaded echo: 3 tools
Dynamically loaded hello: 4 tools
Echo tool result: {:echo "Dynamic loading works!"}
Hello tool result: {:greeting "Hi, External Module!"}
=== Dynamic Loading Test Complete ===
```

---

### Phase 14C: Documentation & Cleanup (Planned)

| # | Task | Status | Acceptance Criteria |
|---|------|--------|---------------------|
| 14C.1 | Add "Selective Namespace Loading" section | Planned | docs/dynamic-module-loading.md |
| 14C.2 | Clarify two-level dependency model | Planned | docs/design/module-system-design.md |
| 14C.3 | Make `add-classpath` conditional in ns_loader.clj | Planned | Skip if path already on classpath |

**Key Insight (from 2025-11-26 discussion):**

When all module paths are on the classpath (via bb.edn `:paths`), cross-module namespace `require` works automatically:

1. **Namespace dependencies** → Automatic via classpath (Clojure's `require`)
2. **Module dependencies** → Only for lifecycle ordering (start/stop)

**Example (target documentation):**
```clojure
;; Scenario: Module A wants to use utilities from Module B
;; WITHOUT loading Module B (no tool registration, no lifecycle)

(ns module-a.core
  (:require [module-b.utils :as utils]))  ; Just works!

;; module-b.utils is available via classpath (bb.edn :paths)
;; Module B's tools are NOT registered
;; Module B's lifecycle is NOT started

;; This is pure Clojure - classpath IS the dependency mechanism
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
