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

Clojure LSP integration via persistent subprocess.

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Foundation (jsonrpc, client, server) | ✅ Complete |
| 2 | Clojure API (tools.clj) | ✅ Complete |
| 3 | CLI (bb clojure-lsp) | ✅ Complete |
| 4 | MCP Tools (11 tools) | ✅ Complete |
| 5 | Watch Mode & Extended CLI | ✅ Complete |
| 5.5 | MCP Tools Parity (16 tools) | ✅ Complete |
| 6 | Polish & Docs | Pending |

**Phase 5.5 Complete (2025-12-29):**

Per Gemini review feedback, added 5 missing MCP tools to achieve full parity with `tools.clj`:
- `clj-find-symbol` - Workspace-wide symbol search by name
- `clj-implementations` - Find protocol/interface implementations
- `clj-format` - Format files via clojure-lsp
- `clj-execute-command` - Execute refactoring commands (cycle-privacy, extract-function, etc.)
- `clj-watch` - Control file watcher (start/stop/status)

Enhanced: `clj-init` now accepts `watch: true` to auto-start file watcher.

**MCP tools: 16 total** | **CLI commands: 18 total**

**Phase 5 Complete (2025-12-29):**

CLI expanded to 18 commands with watch mode:

| Category | Commands |
|----------|----------|
| Lifecycle | `start`, `stop`, `status`, `watch` |
| Navigation | `definition`, `references`, `hover`, `implementations` |
| Search | `find-symbol` (workspace-wide by name) |
| Analysis | `diagnostics`, `symbols`, `call-hierarchy` |
| Refactoring | `completions`, `code-actions`, `rename`, `refactor`, `format` |

Key additions:
- **`bb clojure-lsp watch`** - File watcher using pod-babashka-fswatcher v0.0.7
  - Monitors `.clj/.cljs/.cljc/.edn` recursively
  - Sends `workspace/didChangeWatchedFiles` to keep index fresh
  - Logs via trove for telemetry integration
- **`find-symbol`** - Symbol-centric search (not position-dependent)
- **`format`** - Format files via clojure-lsp
- **`implementations`** - Find protocol implementations
- **`refactor`** - Execute refactoring commands (cycle-privacy, extract-function, etc.)
- **`bb pprint`** - EDN pretty-printer utility for CLI output
- **Default output: EDN** (use `--json` for JSON)

**Phase 6 Tasks:**
- Error handling: timeouts, process crashes, auto-restart
- README.md for the module
- Test coverage for new commands

**Note:** Multi-project support available at bb-mcp-server level (run multiple instances).

**Test Strategy:** Integration tests spawn real `clojure-lsp` subprocess using module's own source files as test corpus.

**References:**
- `modules/clojure-lsp/docs/design-implementation.md` - How it works
- `modules/clojure-lsp/docs/design-rationale.md` - Why decisions were made
- `modules/clojure-lsp/docs/clojure-lsp-cli-examples.md` - Complete CLI examples

---

### Static + Live State Integration (Planned)

Unified view combining clojure-lsp static analysis with nREPL runtime introspection.

**Problem:** clojure-lsp sees files on disk; nREPL sees runtime state. Neither gives complete picture when code is evaluated at REPL without saving.

**Solution:** Hybrid introspection that merges:
- **Static** (clojure-lsp): AST, references, call hierarchy, refactoring
- **Dynamic** (nREPL): Runtime values, dynamically defined vars, loaded namespaces

**Design documents:**
- `docs/design/live-static-state-design-implementation.md` - Original design
- `docs/design/live-static-state-design-implementation-review.md` - Gemini review

**Architecture (per Gemini review):**
- Create dedicated `state-monitor` module (not in clojure-lsp or nrepl)
- Dependency: `state-monitor` → `clojure-lsp` + `nrepl`
- Ensures lower-level modules stay focused on their domains

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | nREPL Introspection Tools | Planned |
| 0.5 | REPL Source Capture | Planned |
| 1 | Namespace-Focused Query | Planned |
| 2 | State Monitor Module | Planned |
| 3 | Full Unification & CLI | Planned |

---

#### Phase 0: nREPL Introspection Tools (Immediate Wins)

Add basic introspection tools to `nrepl` module. No unification logic needed - provides immediate value for AI agents to verify assumptions.

**New MCP Tools:**

| Tool | Purpose | Implementation |
|------|---------|----------------|
| `nrepl-introspect-ns` | List loaded vars in a namespace | `(ns-publics 'ns)` + `(ns-interns 'ns)` |
| `nrepl-get-value` | Get EDN value of a var | `@(resolve 'ns/var)` with pr-str |
| `nrepl-var-meta` | Get var metadata (arglists, doc, etc.) | `(meta #'ns/var)` |
| `nrepl-loaded-namespaces` | List all loaded namespaces | `(all-ns)` |

**Key insight:** These work with vanilla `clojure.core` - no cider-nrepl required.

**Example usage:**
```clojure
;; Agent verifies a var is loaded
(nrepl-introspect-ns {:ns "my.app.core"})
;; => {:publics [foo bar baz], :interns [foo bar baz helper-]}

;; Agent checks actual runtime value
(nrepl-get-value {:symbol "my.app.config/settings"})
;; => {:port 8080, :host "localhost"}
```

---

#### Phase 0.5: REPL Source Capture

**Problem:** REPL-evaluated code loses its source (`:file "NO_SOURCE_FILE"`).

**Solution:** Intercept `nrepl-eval`, capture source, store in Datalevin + var metadata.

**New MCP Tools:**

| Tool | Purpose |
|------|---------|
| `nrepl-var-source` | Get source for a var (file or REPL-captured) |
| `nrepl-eval-history` | List recent evals with defined vars |

**Storage:** Hybrid - Datalevin (persistent) + var metadata (ephemeral backup)

**See:** `docs/design/live-static-state-design-implementation.md` for full spec.

---

#### Phase 1: Namespace-Focused Query

**Recommendation from Gemini:** Prioritize focused queries over global diff.

| Tool | Purpose |
|------|---------|
| `query-namespace` | Compare static vs live for ONE namespace |
| `inspect-value` | LSP-verify symbol exists, then fetch via nREPL |

**Why focused > global:**
- Global diff is slow (network round-trips)
- Global diff is noisy (libraries differ slightly)
- Agent works in one file/namespace at a time

---

#### Phase 2: State Monitor Module

Create `modules/state-monitor/` as orchestrator:

```
state-monitor/
├── src/state_monitor/
│   ├── core.clj        # Module lifecycle, MCP tools
│   ├── query.clj       # Unified query logic
│   └── normalize.clj   # Static↔Live normalization
└── module.edn
```

**Dependencies:**
- `clojure-lsp` module (static analysis)
- `nrepl` module (runtime introspection)

**Normalization challenges:**
- Static sees text: `(defn foo [x] ...)`
- Runtime sees data: `{:arglists '([x])}`
- Must normalize for comparison (ignore line numbers, handle aliases, macro expansions)

---

#### Phase 3: Full Unification & CLI

**CLI:** `bb state ...` commands:
```bash
bb state query my.ns/some-fn    # Static + live info
bb state diff my.ns             # What's different?
bb state sync my.ns             # Reload from disk
bb state watch                  # Alert on divergence
```

**MCP Tools:**
- `state-query-symbol` - Full picture of a symbol
- `state-diff-namespace` - Divergence report
- `state-sync-namespace` - Reload to sync

---

#### Implementation Notes

**Fallback strategy:** Core introspection must work with vanilla `clojure.core`:
- `ns-publics`, `ns-interns`, `all-ns`
- `meta`, `resolve`, `source`

Enhanced features when `cider-nrepl` available:
- `info` op for richer metadata
- `eldoc` for signatures
- `ns-path` for file locations

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
