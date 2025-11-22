# bb-mcp-server Implementation Plan

**Status:** From Scratch → Production
**Started:** 2025-11-20
**Approach:** Orchestrator (Claude) + Clojure Expert Agent (delegated tasks)

---

## Mandatory Requirements for All Tasks

**📖 All code MUST follow these guides:**
- `docs/CLOJURE_EXPERT_CONTEXT.md` - Coding rules, verification workflow
- `docs/AI_TELEMETRY_GUIDE.md` - **Telemetry patterns for all functions**
- `docs/bb-mcp-server-architecture.md` - System design

**Telemetry is NOT optional** - every function with I/O or business logic must have logging per `AI_TELEMETRY_GUIDE.md`.

---

## Legend

- 🎯 **Orchestrator Task** - Architecture, design, review (Claude does this)
- 🤖 **Agent Task** - Implementation (delegate to Clojure expert agent)
- ✅ **Completed**
- 🔄 **In Progress**
- ⏳ **Pending**
- ⏸️ **Blocked** (waiting on dependency)

---

## Phase 1: Project Foundation (Week 1)

### 1.1 Project Initialization
**Goal:** Working project structure with tooling

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 1.1.1 | Design project structure | 🎯 | ✅ | Orchestrator | Directory layout, dependency choices documented |
| 1.1.2 | Create bb.edn with tasks | 🤖 | ✅ | Agent | Tasks: lint, format, test, check. Zero clj-kondo warnings |
| 1.1.3 | Create deps.edn with minimal deps | 🤖 | ✅ | Agent | Babashka-compatible deps only. Builds successfully |
| 1.1.4 | Set up directory structure | 🤖 | ✅ | Agent | src/, test/, .clj-kondo/, docs/ created |
| 1.1.5 | Configure clj-kondo | 🤖 | ✅ | Agent | .clj-kondo/config.edn with strict rules |
| 1.1.6 | Configure cljfmt | 🤖 | ✅ | Agent | .cljfmt.edn with project style |
| 1.1.7 | Add .gitignore | 🤖 | ✅ | Agent | Standard Clojure + bb ignores |
| 1.1.8 | Review and verify setup | 🎯 | ✅ | Orchestrator | All tools work, bb check passes |
| 1.1.9 | Document agent workflow assessment | 🎯 | ✅ | Orchestrator | Agent performance evaluated, recommendations documented |

**Dependencies:** None
**Estimated LOC:** ~100 (config files)
**Deliverable:** Project builds, lints, formats successfully

---

### 1.2 Minimal MCP Server (stdio only) ✅ COMPLETE
**Goal:** Prove MCP protocol works end-to-end

**Strategy:** Build core protocol first (testable without I/O), then add stdio transport

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 1.2.1 | Design MCP message protocol | 🎯 | ✅ | Orchestrator | JSON-RPC 2.0 request/response spec |
| 1.2.2 | Implement message parsing | 🤖 | ✅ | Agent | Parse/validate JSON-RPC. Handle malformed input. Unit tests |
| 1.2.3 | Implement core handler router | 🤖 | ✅ | Agent | Dispatch to method handlers. Error handling. Unit tests |
| 1.2.4 | Implement "initialize" handler | 🤖 | ✅ | Agent | Returns server capabilities. Unit tests |
| 1.2.5 | Implement "tools/list" handler | 🤖 | ✅ | Agent | Returns list of available tools. Unit tests |
| 1.2.6 | Implement "tools/call" dispatcher | 🤖 | ✅ | Agent | Routes to registered tool handlers. Unit tests |
| 1.2.7 | Implement test tool: "hello" | 🤖 | ✅ | Agent | Takes name, returns greeting. Full tests |
| 1.2.8 | Add telemetry to all handlers | 🤖 | ✅ | Agent | Trove logging per AI_TELEMETRY_GUIDE.md (completed during implementation) |
| 1.2.9 | Test RPC handlers with Claude Code | 🎯 | ✅ | Orchestrator | Configure bb-mcp-server, verify all methods work in real Claude session |
| 1.2.10 | Implement stdio transport | 🤖 | ✅ | Agent | Read/write JSON-RPC over stdio. Wraps tested handlers |
| 1.2.11 | Test stdio with Claude Code | 🎯 | ✅ | Orchestrator | End-to-end test via stdio in real Claude session |
| 1.2.12 | Write additional integration tests | 🤖 | ⏳ | Agent | Automated test suite for CI/CD (deferred to Phase 1.2 cleanup) |
| 1.2.13 | Review protocol implementation | 🎯 | ✅ | Orchestrator | MCP spec compliant, error handling correct |

**Dependencies:** 1.1 (Project Initialization)
**Actual LOC:** ~500 (including comprehensive telemetry)
**Deliverable:** ✅ Working MCP server responding to stdio, verified with Claude Code
**Tagged:** v0.1.0-phase1.2

**Phase 1.2 Achievements:**
- MCP protocol version 2025-03-26 (forward-compatible)
- JSON-RPC notification handling (per spec)
- Capabilities negotiation
- Comprehensive telemetry with structured logging
- Critical bug fixes: stderr logging, notification handling

**Cleanup Needed:**
- Update test suite to expect protocol version 2025-03-26
- Fix test failures due to protocol version mismatch
- Add integration tests for notification handling
**Testing Strategy:**
1. Unit test core protocol (no I/O)
2. Test RPC handlers with real Claude Code (validate protocol works)
3. Add stdio transport
4. Test stdio with real Claude Code (validate transport works)
5. Automated integration tests for CI/CD

---

## Phase 2: Core Functionality (Week 2)

### 2.1 Tool Registry ✅ COMPLETE
**Goal:** Dynamic tool registration and lookup

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 2.1.1 | Design tool registry interface | 🎯 | ✅ | Orchestrator | API design for register/unregister/lookup |
| 2.1.2 | Implement tool registry | 🤖 | ✅ | Agent | Thread-safe registry. Full tests |
| 2.1.3 | Add schema validation (Malli) | 🤖 | ✅ | Agent | Validate tool definitions. Clear errors |
| 2.1.4 | Update tools/list to use registry | 🤖 | ✅ | Agent | Dynamic tool listing works |
| 2.1.5 | Update tools/call to use registry | 🤖 | ✅ | Agent | Dynamic dispatch works |
| 2.1.6 | Add 3 example tools | 🤖 | ✅ | Agent | echo, add, concat with tests |
| 2.1.7 | Review registry design | 🎯 | ✅ | Orchestrator | Clean API, good error messages |

**Dependencies:** 1.2 (Minimal MCP Server) ✅ COMPLETE
**Actual LOC:** ~350 (registry.clj + examples.clj + migrations)
**Deliverable:** ✅ Unified tool registry with Malli validation

**Phase 2.1 Achievements:**
- Unified registry (definition + handler in single record)
- Thread-safe with O(1) lookup
- Malli schema validation
- 4 tools: hello, echo, add, concat
- Old APIs deprecated with clear migration path

---

### 2.2 Error Handling ✅ COMPLETE
**Goal:** Robust error handling and reporting

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 2.2.1 | Design error taxonomy | 🎯 | ✅ | Orchestrator | Error types and codes defined |
| 2.2.2 | Implement error response format | 🤖 | ✅ | Agent | JSON-RPC error responses |
| 2.2.3 | Add input validation | 🤖 | ✅ | Agent | Validate all tool params with Malli |
| 2.2.4 | Add exception middleware | 🤖 | ✅ | Agent | Catch and format all exceptions |
| 2.2.5 | Add telemetry for errors | 🤖 | ✅ | Agent | Log all errors with context |
| 2.2.6 | Write error handling tests | 🤖 | ✅ | Agent | Test all error paths |
| 2.2.7 | Review error handling | 🎯 | ✅ | Orchestrator | Clear messages, good debugging info |

**Dependencies:** 2.1 (Tool Registry) ✅ COMPLETE
**Actual LOC:** ~280 (errors.clj + tools_call.clj updates)
**Deliverable:** ✅ Robust error handling with Malli validation

**Phase 2.2 Achievements:**
- Error taxonomy with 11 error codes (JSON-RPC + MCP custom)
- JSON Schema to Malli conversion for full type validation
- Detailed validation errors (e.g., "should be an integer")
- Centralized error logging with structured context
- Stack trace simplification for debugging
- Design doc: `docs/design/error-handling-design.md`

---

## Phase 3: Multi-Transport (Week 3)

### 3.1 HTTP Transport ✅ COMPLETE
**Goal:** Add HTTP alongside stdio

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 3.1.1 | Design transport abstraction | 🎯 | ✅ | Orchestrator | Common interface for stdio/HTTP/REST |
| 3.1.2 | Implement transport protocol | 🤖 | ✅ | Agent | Protocol for transport detection |
| 3.1.3 | Refactor stdio as transport impl | 🤖 | ⏳ | Agent | Stdio implements transport interface (deferred) |
| 3.1.4 | Implement HTTP transport | 🤖 | ✅ | Agent | HTTP server using http-kit |
| 3.1.5 | Add HTTP middleware stack | 🤖 | ✅ | Agent | CORS, content negotiation |
| 3.1.6 | Add transport selection logic | 🤖 | ✅ | Agent | bb server:stdio / bb server:http |
| 3.1.7 | Write HTTP integration tests | 🤖 | ✅ | Agent | Test full HTTP request cycle |
| 3.1.8 | Review transport architecture | 🎯 | ✅ | Orchestrator | Clean abstraction, no duplication |

**Dependencies:** 2.2 (Error Handling) ✅ COMPLETE
**Actual LOC:** ~550 (http.clj + protocol.clj + scripts/*.clj + bb.edn)
**Deliverable:** ✅ Server runs on stdio OR HTTP with comprehensive bb task interface

**Phase 3.1 Achievements:**
- Transport protocol abstraction (map-based for Babashka)
- HTTP transport with http-kit server
- CORS support for browser clients
- JSON-RPC error → HTTP status mapping
- Health endpoint at GET /health
- Design doc: `docs/design/transport-design.md`

**bb Task Interface (v0.2.0):**
- `bb server:stdio` - Start stdio server
- `bb server:http [port]` - Start HTTP server (default 3000)
- `bb http:test` - Run all HTTP tests (6 tests)
- `bb http:health` - Check server health
- `bb http:init` - Test MCP initialize
- `bb http:tools` - List available tools
- `bb http:hello <name>` - Test hello tool
- `bb http:add <a> <b>` - Test add tool
- `bb http:concat <str>...` - Test concat tool
- `bb http:call <tool> <json>` - Generic tool caller

**Scripts:**
- `scripts/http_server.clj` - HTTP server startup
- `scripts/http_test.clj` - Comprehensive HTTP test suite
- `scripts/stdio_server.clj` - Stdio server startup

**Claude Code Integration:**
- `bb-mcp-stdio` - Stdio transport (spawned by Claude)
- `bb-mcp-http` - HTTP transport (requires running server)

**Tagged:** v0.2.0-http-transport

---

### 3.2 REST Transport
**Goal:** RESTful API alongside MCP

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 3.2.1 | Design REST API routes | 🎯 | ⏸️ | Orchestrator | RESTful resource mapping |
| 3.2.2 | Implement REST routing | 🤖 | ⏸️ | Agent | GET /tools, POST /tools/:name |
| 3.2.3 | Implement REST→MCP adapter | 🤖 | ⏸️ | Agent | Convert REST to internal MCP calls |
| 3.2.4 | Add REST-specific middleware | 🤖 | ⏸️ | Agent | Content negotiation, rate limiting |
| 3.2.5 | Write REST integration tests | 🤖 | ⏸️ | Agent | Test all REST endpoints |
| 3.2.6 | Review REST implementation | 🎯 | ⏸️ | Orchestrator | RESTful design, good DX |

**Dependencies:** 3.1 (HTTP Transport)
**Estimated LOC:** ~200-300
**Deliverable:** RESTful API works alongside MCP

---

## Phase 4: Security & Production Features (Week 4)

### 4.1 API Key Authentication
**Goal:** Secure the server

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 4.1.1 | Design auth architecture | 🎯 | ⏸️ | Orchestrator | Key storage, validation flow |
| 4.1.2 | Implement key hashing (SHA-256) | 🤖 | ⏸️ | Agent | Constant-time comparison |
| 4.1.3 | Implement auth middleware | 🤖 | ⏸️ | Agent | Check API key on all requests |
| 4.1.4 | Add key generation task (bb.edn) | 🤖 | ⏸️ | Agent | bb security:generate-key |
| 4.1.5 | Add key management tasks | 🤖 | ⏸️ | Agent | add-key, remove-key, list-keys |
| 4.1.6 | Exempt stdio from auth | 🤖 | ⏸️ | Agent | Stdio always allowed (local) |
| 4.1.7 | Write auth tests | 🤖 | ⏸️ | Agent | Test valid/invalid keys |
| 4.1.8 | Review security implementation | 🎯 | ⏸️ | Orchestrator | No security holes, good practices |

**Dependencies:** 3.2 (REST Transport)
**Estimated LOC:** ~200-300
**Deliverable:** API key authentication works

---

### 4.2 Rate Limiting
**Goal:** Prevent abuse

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 4.2.1 | Design rate limiting strategy | 🎯 | ⏸️ | Orchestrator | Token bucket algorithm |
| 4.2.2 | Implement token bucket | 🤖 | ⏸️ | Agent | Per-IP rate limiting |
| 4.2.3 | Add rate limiting middleware | 🤖 | ⏸️ | Agent | 429 responses when exceeded |
| 4.2.4 | Add rate limit configuration | 🤖 | ⏸️ | Agent | Configurable limits |
| 4.2.5 | Write rate limit tests | 🤖 | ⏸️ | Agent | Test limit enforcement |
| 4.2.6 | Review rate limiting | 🎯 | ⏸️ | Orchestrator | Fair, effective, configurable |

**Dependencies:** 4.1 (API Key Authentication)
**Estimated LOC:** ~150-200
**Deliverable:** Rate limiting prevents abuse

---

### 4.3 Module Loading (Component-Style)
**Goal:** Load external tool modules with Component-style lifecycle management

**📖 Design Document:** `docs/design/module-system-design.md`

**Architecture (Directory-per-Module):**
```
modules/
├── hello/
│   ├── module.edn        ; Manifest: name, version, depends-on, entry-ns
│   ├── README.md         ; Documentation
│   └── src/hello/
│       └── core.clj      ; Implements IModule (start/stop/status)
```

**Design Principles:**
- Each module is self-contained directory with manifest
- Modules declare dependencies via `:depends-on` in module.edn
- Topological sort determines load order
- Dependencies injected at start time
- Start in dependency order, stop in reverse

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 4.3.1 | Design module system | 🎯 | ✅ | Orchestrator | See `docs/design/module-system-design.md` |
| 4.3.2 | Implement IModule protocol | 🤖 | ⏸️ | Agent | `start`, `stop`, `status` with dependency injection |
| 4.3.3 | Implement module loader | 🤖 | ⏸️ | Agent | Discover, validate, load modules from modules/ |
| 4.3.4 | Add dependency resolution | 🤖 | ⏸️ | Agent | Topological sort, cycle detection, optional deps |
| 4.3.5 | Implement system map | 🤖 | ⏸️ | Agent | `start-system!`/`stop-system!`/`status` |
| 4.3.6 | Add module.edn validation | 🤖 | ⏸️ | Agent | Malli schema for manifest, version constraints |
| 4.3.7 | Add configuration hierarchy | 🤖 | ⏸️ | Agent | defaults → modules.edn → env → runtime |
| 4.3.8 | Add reload support | 🤖 | ⏸️ | Agent | Hot reload changed modules |
| 4.3.9 | Migrate hello to module | 🤖 | ⏸️ | Agent | Reference implementation in modules/hello/ |
| 4.3.10 | Write module system tests | 🤖 | ⏸️ | Agent | Test lifecycle, deps, errors, reload |
| 4.3.11 | Review module system | 🎯 | ⏸️ | Orchestrator | Secure, third-party ready, well-documented |

**Dependencies:** 4.2 (Rate Limiting)
**Estimated LOC:** ~600-800
**Deliverable:** Self-contained modules with dependency injection and lifecycle management

---

## Phase 5: Production Readiness (Week 5)

### 5.1 Configuration Management
**Goal:** Production-ready configuration

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 5.1.1 | Design config system | 🎯 | ⏸️ | Orchestrator | EDN + env vars + CLI args |
| 5.1.2 | Implement config loading | 🤖 | ⏸️ | Agent | Layered config with defaults |
| 5.1.3 | Add config validation | 🤖 | ⏸️ | Agent | Malli schemas for all config |
| 5.1.4 | Add config signing (HMAC) | 🤖 | ⏸️ | Agent | Tamper detection |
| 5.1.5 | Write config tests | 🤖 | ⏸️ | Agent | Test all config scenarios |
| 5.1.6 | Review config system | 🎯 | ⏸️ | Orchestrator | Secure, flexible, documented |

**Dependencies:** 4.3 (Module Loading)
**Estimated LOC:** ~200-300
**Deliverable:** Robust configuration system

---

### 5.2 Observability
**Goal:** Production monitoring and debugging

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 5.2.1 | Design observability strategy | 🎯 | ⏸️ | Orchestrator | Metrics, logs, traces |
| 5.2.2 | Enhance telemetry | 🤖 | ⏸️ | Agent | Structured logging everywhere |
| 5.2.3 | Add health check endpoint | 🤖 | ⏸️ | Agent | /health with component status |
| 5.2.4 | Add metrics endpoint | 🤖 | ⏸️ | Agent | /metrics with Prometheus format |
| 5.2.5 | Add graceful shutdown | 🤖 | ⏸️ | Agent | Clean shutdown on SIGTERM |
| 5.2.6 | Write observability tests | 🤖 | ⏸️ | Agent | Test health, metrics, shutdown |
| 5.2.7 | Review observability | 🎯 | ⏸️ | Orchestrator | Production-ready monitoring |

**Dependencies:** 5.1 (Configuration Management)
**Estimated LOC:** ~200-300
**Deliverable:** Production observability

---

### 5.3 Documentation
**Goal:** Complete production documentation

| # | Task | Type | Status | Owner | Acceptance Criteria |
|---|------|------|--------|-------|-------------------|
| 5.3.1 | Write README.md | 🤖 | ⏸️ | Agent | Quick start, features, install |
| 5.3.2 | Write API documentation | 🤖 | ⏸️ | Agent | All tools, endpoints documented |
| 5.3.3 | Write deployment guide | 🤖 | ⏸️ | Agent | Production deployment steps |
| 5.3.4 | Write security guide | 🤖 | ⏸️ | Agent | Best practices, hardening |
| 5.3.5 | Write module dev guide | 🤖 | ⏸️ | Agent | How to write modules |
| 5.3.6 | Add code examples | 🤖 | ⏸️ | Agent | Example modules and clients |
| 5.3.7 | Review documentation | 🎯 | ⏸️ | Orchestrator | Complete, clear, accurate |

**Dependencies:** 5.2 (Observability)
**Estimated LOC:** N/A (documentation)
**Deliverable:** Complete documentation

---

## Summary

### Total Estimated Effort
- **Orchestrator Tasks:** ~25 tasks (architecture, design, review)
- **Agent Tasks:** ~60 tasks (implementation, testing)
- **Total LOC:** ~3,500-4,500 lines
- **Timeline:** 5 weeks (aggressive but achievable with agent help)

### Agent Delegation Strategy
**Agent handles:** All implementation, testing, verification
**Orchestrator handles:** Architecture, design, integration review

### Success Criteria
- ✅ Zero clj-kondo warnings across entire codebase
- ✅ All code has telemetry
- ✅ Test coverage >80%
- ✅ All verification workflows pass
- ✅ Production-ready security
- ✅ Complete documentation

---

## Notes

**Agent Instructions:**
Each agent task will be spawned with CLOJURE_EXPERT_CONTEXT.md which enforces:
- Honesty (run code, report actual results)
- Verification (clj-kondo, cljfmt, tests)
- Telemetry (all functions instrumented)
- Security (proper error handling, validation)

**Review Process:**
After each agent task:
1. Orchestrator reviews code quality
2. Orchestrator checks verification output is real
3. Orchestrator tests edge cases
4. Orchestrator verifies integration
5. Accept or provide feedback for iteration

**Version Control:**
- Commit after each completed task
- Tag major milestones (Phase 1 complete, etc.)
- Document decisions in commit messages

---

*Last Updated: 2025-11-21*
*Status: Phase 3.1 complete (v0.2.0-http-transport). Ready for Phase 3.2 or 4.3*
