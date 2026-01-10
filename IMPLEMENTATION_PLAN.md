# bb-mcp-server Implementation Plan

**Status:** Code Browser Phase 1 (Static Browsing)
**Version:** v1.10.3
**Last Updated:** 2026-01-10

---

## Current State

Production-ready MCP server with:
- MCP spec 2025-03-26 compliant
- Stdio and HTTP transports
- Dynamic module system
- 16+ tool modules
- E2E test suite
- Scittle browser nREPL with stable session identity

---

## Dev Environment Quick Reference

**Essential commands:**
```bash
# Run tests (ALWAYS before committing)
bb test:modules          # All module tests
bb lint                  # clj-kondo (must be 0 errors, 0 warnings)
bb format                # cljfmt check

# Start servers
bb server                           # Stdio (Claude Desktop)
bb server --http                    # HTTP on port 3000
bb server --http --config <file>    # Custom config
bb server:stop [port]               # Stop server on port

# Scittle browser dev
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev
# Then open http://localhost:8091

# Code browser dev (when config exists)
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# nREPL CLI
bb nrepl list --mcp <nickname>      # List connections
bb nrepl eval "<code>" --mcp <nick> # Eval code
bb nrepl connect <port>             # Connect to external nREPL

# MCP CLI
bb mcp servers                      # List running servers
bb mcp tools --mcp <nickname>       # List tools
bb mcp call <tool> '<json>' --mcp <nick>  # Call tool
```

**Key files:**
- `bb.edn` - All tasks, paths, deps
- `system.edn` - Default module config
- `bb-scittle-dev-system.edn` - Scittle browser dev config
- `CLAUDE.md` - AI instructions (read first)
- `context.md` - Session state (read at start)
- `docs/design/*.md` - Design documents

**Required reading for Scittle browser development:**
- `docs/SCITTLE_DEV_ENVIRONMENT.md` - Step-by-step setup guide (READ THIS FIRST!)
  - How to start server, connect browser, find nicknames
  - Tool usage (nrepl-eval-local-file vs nrepl-eval)
  - Common pitfalls and troubleshooting

**Verification workflow (run before every commit):**
```bash
bb lint && bb format && bb test:modules
```

---

## Active Work: Scittle Code Browser

**Goal:** Browser-based code browser embedded in Scittle dev environment.

**Design doc:** `docs/design/bb-scittle-code-browser-design.md`

### Phase 0: Dev Infrastructure ✅ Complete

| Task | Description | Status |
|------|-------------|--------|
| 0.1 | Create `bb-code-browser-dev-system.edn` config | ✅ |
| 0.2 | Update bootstrap HTML with preloaded scripts | ✅ |
| 0.3 | Create `scittle-cm6` namespace (reusable CM6 wrapper) | ✅ |
| 0.4 | Implement bidirectional atom sync (bootstrap bundle) | ✅ |
| 0.5 | Add error boundary for safe REPL development | ✅ |
| 0.6 | Test: load UI code via nREPL, iterate live | ✅ |

**Files created:**
- `bb-code-browser-dev-system.edn` - Dev config
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - code-browser HTML
- `modules/sente-browser/src/browser/scittle_cm6.cljs` - CM6 wrapper

**Start code browser:**
```bash
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
# Open http://localhost:8091
```

**UI Loading Pattern:**
```clojure
;; From bb REPL - load UI code via nREPL
(browser-eval! browser-1 '(require '[code-browser.core :as cb]))
(browser-eval! browser-1 '(cb/mount!))

;; Iterate live without page refresh
(browser-eval! browser-1 '(swap! cb/!layout assoc :ns-width "25%"))
```

### Phase 1: Static Browsing ✅ Complete (v1.10.1)

| Task | Description | Status |
|------|-------------|--------|
| 1.1 | Namespace list panel (Reagent) | ✅ 37 namespaces |
| 1.2 | Vars list panel | ✅ Click ns → symbols |
| 1.3 | Source viewer (CM6, read-only) | ✅ Fira Code font |
| 1.4 | Filter components (wildcard/regex) | Pending |
| 1.5 | Wire clojure-lsp as data source | ✅ |

**CM6 Fix (commit 28555f2):** EditorState must be imported separately from `@codemirror/state`, not from `codemirror` meta-package. Use `?deps=` for version pinning.

### Phase 2: Runtime Introspection

| Task | Description | Status |
|------|-------------|--------|
| 2.1 | Mode toggle (Static/Runtime) | Pending |
| 2.2 | Wire nREPL introspection tools | Pending |
| 2.3 | Show REPL-defined vars | Pending |
| 2.4 | Static vs runtime diff view | Pending |

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

### clojure-lsp Module ✅ (Phase 5.5 Complete)

Clojure LSP integration via persistent subprocess. **16 MCP tools, 18 CLI commands.**

**Phase 6 remaining:** Error handling, README, test coverage.

**References:** `modules/clojure-lsp/docs/` for design and CLI examples.

**Version Info (2026-01-02):**
| Component | Installed | Latest | Notes |
|-----------|-----------|--------|-------|
| clojure-lsp | 2025.11.28-12.47.43 | 2025.11.28 | ✅ Latest release |
| clj-kondo (bundled) | 2025.10.24-SNAPSHOT | 2025.12.24 (master) | ⚠️ Older than standalone |
| clj-kondo (standalone) | v2025.12.23 | v2025.12.23 | ✅ Latest |

**Known Issue:** Sporadic NUL byte corruption in clojure-lsp JSON-RPC responses (stdout pollution from bundled clj-kondo). Client is resilient - logs error and continues. See commits `a65aca5` and `8a4eff7` for diagnostics.

**Directive:** If NUL byte crashes recur frequently, install HEAD version:
```bash
brew install --HEAD clojure-lsp/brew/clojure-lsp-native
```
This will get the latest bundled clj-kondo (2025.12.24+).

---

### Static + Live State Integration

Unified view: clojure-lsp (static) + nREPL (runtime). **Phase 0 complete.**

**Design docs:** `docs/design/live-static-state-design-implementation.md`

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | nREPL Introspection Tools (4 tools + CLI wrappers) | ✅ Complete |
| 0.5 | REPL Source Capture (Datalevin + var metadata) | Planned |
| 1-3 | State Monitor Module, Unified CLI | Planned |

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

*Last Updated: 2026-01-02*
