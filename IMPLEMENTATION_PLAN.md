# bb-mcp-server Implementation Plan

**Status:** Phase 20 Complete
**Version:** v1.7.0+
**Last Updated:** 2025-12-29

---

## Current State

Production-ready MCP server with:
- MCP spec 2025-03-26 compliant
- Stdio and HTTP transports
- Dynamic module system
- 16+ tool modules
- E2E test suite

---

## Maintenance Log

### 2025-12-29: CLI Script Lint Fixes

Fixed clj-kondo errors and warnings in CLI scripts caused by implicit `user` namespace usage.

**Issues:**
- Scripts in `scripts/` used implicit `user` namespace
- When analyzed together, clj-kondo reported "redefined var" warnings
- Function arity conflicts between `http_test.clj` and `mcp_cli.clj` (both defined `cmd-init`, `cmd-tools`, `cmd-call` with different signatures)

**Fix:** Added proper `ns` declarations to each script:
- `http_test.clj` → `(ns http-test ...)`
- `mcp_cli.clj` → `(ns mcp-cli ...)`
- `nrepl_cli.clj` → `(ns nrepl-cli ...)`
- `rebel_nrepl_client.clj` → `(ns rebel-nrepl-client ...)`

**Result:** Lint passes with 0 errors, 0 warnings.

---

## Pending Work

### bb calc CLI (Low Priority)

Higher-level wrapper for calculate module:
```bash
# Instead of:
bb mcp call calculate.calculate '{"expr":"(percent-change 100 125)"}'

# Would be:
bb calc "(percent-change 100 125)"
bb calc --help  # Show 100+ available functions
```

Low priority - calculate works fine via `bb mcp call`.

---

### clojure-lsp Module

Clojure LSP integration via persistent subprocess. Phases 1-3 complete.

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Foundation (jsonrpc, client, server) | ✅ Complete |
| 2 | Clojure API (tools.clj) | ✅ Complete |
| 3 | CLI (bb clojure-lsp) | ✅ Complete |
| 4 | MCP Tools | Pending |
| 5 | Polish & Docs | Pending |

**Phase 4 Tasks:**
- Register MCP tools: `clj-definition`, `clj-references`, `clj-hover`, `clj-completions`, `clj-code-actions`, `clj-rename`, `clj-diagnostics`, `clj-document-symbols`, `clj-call-hierarchy`
- Create handlers that delegate to `tools.clj`
- Add module to `system.edn`

**Phase 5 Tasks:**
- Error handling: timeouts, process crashes, auto-restart
- Multi-project support (optional)
- README.md for the module

**Test Strategy:** Integration tests spawn real `clojure-lsp` subprocess and use the module's own source files as test corpus. This provides realistic testing - the LSP analyzes actual Clojure code.

**References:**
- `modules/clojure-lsp/docs/design-implementation.md` - How it works
- `modules/clojure-lsp/docs/design-rationale.md` - Why decisions were made

---

### Phase 14C: Dynamic Loading Documentation (Planned)

| Task | Status |
|------|--------|
| Add "Selective Namespace Loading" section | Planned |
| Clarify two-level dependency model | Planned |
| Make `add-classpath` conditional in ns_loader.clj | Planned |

---

### Phase 15C: AI Knowledge Persistence (Planned)

Store experts, prompts, and conversations in Datalevin.

| Task | Status |
|------|--------|
| Expert definitions in Datalevin | Planned |
| Conversation history persistence | Planned |
| Prompt template storage | Planned |

---

### Phase 15D: Message Bus Migration (Planned)

Evaluate replacing atoms+promises with Datalevin-backed bus.

**Benefits:**
- Free persistence (conversation history as audit log)
- Queryable history via Datalog
- Unified state (bus + database in one component)

**Trade-offs:**
- Millisecond latency vs microsecond (acceptable for AI workloads)

**Decision:** Defer until 15A-C complete.

---

## Completed Phases

### Phase 20: MCP CLI & E2E Testing ✅ (v1.7.0+)

Generic MCP CLI and end-to-end test suite.

**Deliverables:**
- `bb mcp servers` - List running servers
- `bb mcp tools` - List available tools
- `bb mcp call <tool> <args>` - Call any tool
- `bb mcp init` - Get server info
- `bb test:e2e` - 11 tests, 42 assertions

**Files:**
- `scripts/mcp_cli.clj` - CLI dispatcher
- `test/e2e/mcp_client_test.clj` - E2E tests
- `src/bb_mcp_server/mcp_client.clj` - Client library

---

### Phase 19: Scittle-nREPL Dev Environment ✅ (v1.7.0)

Browser-based ClojureScript REPL via Scittle + sente-lite.

```
rebel-readline → nrepl-proxy:1667 → sente-browser:8090 → Browser (Scittle)
```

---

### Phase 18: nrepl CLI ✅ (v1.6.0)

Command-line interface for nREPL operations via MCP.

```bash
bb nrepl connect 7888 --nickname my-repl
bb nrepl eval "(+ 1 2 3)"
bb nrepl load-file src/app/core.clj
```

---

### Phase 17: Bootstrap Testing Suite ✅ (v1.3.0)

Bootstrap config, CLI parsing, and PID file tests. 8 tests, 30 assertions.

---

### Phase 16: nrepl-proxy-server ✅ (v1.3.0)

Shadow-cljs style nREPL proxy with browser routing. 15 tests, 44 assertions.

---

### Phase 15.5: Webserver Module ✅

Static file serving with live reload. 20 tests, 54 assertions.

---

### Phase 15A-B: Datalevin Integration ✅ (v0.15.0)

- `datalevin-pod` - Pod loading, connection management (5 tests)
- `datalevin-mcp` - MCP tools: `schema`, `q`, `transact`, `pull`, `find-by` (20 tests)

---

### Phase 14A-B: Dynamic Module Loading ✅ (v0.14.0)

- External modules via `BB_MCP_EXTERNAL_MODULES`
- Runtime loading via `system/load-new-module!`
- Minimal bootstrap pattern

---

### Phase 13: AI Orchestration ✅ (v0.13.x)

Multi-provider AI orchestration framework:

- **ai-orchestrator** - Provider-agnostic infrastructure
- **claude-subprocess-provider** - Claude CLI subprocess
- **anthropic-http-provider** - Native Anthropic API
- **openai-http-provider** - OpenAI API (+ Gemini compat)
- **message-bus** - Atoms+promises with Global Response Router
- **expert-registry** - File-based expert definitions
- **port-registry** - Port allocation and discovery

Multi-agent demo: 3-agent code review pipeline in 65s.

---

### Phase 11-12: Unified Entry Point & Telemetry ✅ (v0.11.0)

Single `bb server` command with composable flags:
```bash
bb server              # stdio (default)
bb server --http       # HTTP on port 3000
bb server --stdio --http 8080  # both
```

---

### Phase 8-10: Transport Modularization ✅ (v0.10.0)

Extracted monolithic `streamable-http` into:
- `http-core` - Shared HTTP infrastructure
- `mcp-http` - MCP JSON-RPC transport
- `mcp-stdio` - Stdio transport (pure, no deps)
- `rest-api` - REST endpoints + OpenAPI

165 tests, 492 assertions total.

---

### Phases 1-7: Foundation ✅

- Project initialization, bb.edn, tooling
- MCP server (stdio + HTTP)
- Tool registry with Malli validation
- Module system with dependency resolution
- Streamable HTTP with SSE
- REST API with OpenAPI generation

---

## References

- [Transport Modularization Design](docs/design/transport-modularization.md)
- [Module System Design](docs/design/module-system-design.md)
- [AI Orchestrator Architecture](docs/design/ai-orchestrator-architecture.md)
- [AI Experts Framework](docs/design/ai-experts-framework.md)

---

*Last Updated: 2025-12-29*
