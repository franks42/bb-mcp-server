# Implementation Plan: Clojure LSP Module

This document outlines the step-by-step implementation plan for the `clojure-lsp` MCP module.

## Implementation Strategy

**API-First Development via local-eval:**

Instead of building MCP tools immediately, we:

1. Build a clean **Clojure API** (`tools.clj`)
2. Test interactively via **local-eval** (REPL-driven development)
3. Build **bb clojure-lsp CLI** that uses local-eval to call the API
4. Wire up **MCP tools** as thin wrappers (last step)

This approach enables:
- Fast iteration without MCP protocol overhead
- Live reload of code changes
- Full introspection of LSP state
- API validation before committing to MCP tool shapes

### Minimal Development Config

Use a minimal server config with only the modules needed for development:

**`system-clojure-lsp-dev.edn`** (based on `system-datalevin-test.edn` pattern):
```clojure
;; Minimal config for clojure-lsp development
;; Usage: BB_MCP_SYSTEM_CONFIG=system-clojure-lsp-dev.edn bb server --http

{:modules-dir "modules"
 :modules ["local-eval" "nrepl" "clojure-lsp"]}
```

| Module | Purpose |
|--------|---------|
| **local-eval** | Interactive testing, CLI implementation, live reload |
| **nrepl** | Reference for async patterns (promises, reader loop, pending map) |
| **clojure-lsp** | The module under development |

**Start server:**
```bash
BB_MCP_SYSTEM_CONFIG=system-clojure-lsp-dev.edn bb server --http
```

The nrepl module's async machinery (`nrepl/state/*.clj`, `nrepl/client/*.clj`) serves as a code reference - its patterns are nearly identical to clojure-lsp's needs.

```
┌─────────────────────────────────────────────────────────┐
│  Development Flow                                       │
│                                                         │
│  1. Edit tools.clj                                     │
│         │                                               │
│         ▼                                               │
│  2. local-eval: (require '[...tools] :reload)          │
│         │                                               │
│         ▼                                               │
│  3. local-eval: (tools/definition {...})               │
│         │                                               │
│         ▼                                               │
│  4. Iterate → back to step 1                           │
│                                                         │
│  Once stable:                                           │
│  5. bb clojure-lsp CLI (uses local-eval)               │
│  6. MCP tools (thin wrappers)                          │
└─────────────────────────────────────────────────────────┘
```

---

## Guiding Principles

1. **Strict Linting & Formatting**:
   - Run `clj-kondo --lint modules/clojure-lsp/src modules/clojure-lsp/test` after every edit.
   - Run `cljfmt check modules/clojure-lsp/src modules/clojure-lsp/test` after every edit.
   - **Zero Warnings Policy**: Warnings are not acceptable.

2. **Testing**:
   - Every feature implementation must include accompanying tests.
   - Use local-eval for interactive testing during development.

3. **Module Isolation**:
   - All source code, tests, and documentation reside within `modules/clojure-lsp/`.

4. **Git Workflow**:
   - Commit, tag, and push after completing each phase.
   - Tag format: `clojure-lsp-v0.X.0-phaseN`.

5. **Telemetry**:
   - Use `taoensso.trove/log!` for monitoring and debugging.

---

## Phase 1: Foundation & Process Management ✅

- [x] Scaffold module structure (src/, test/)
- [x] Implement `module.edn` for dynamic discovery
- [x] Implement `jsonrpc.clj` - Content-Length framing
- [x] Implement `client.clj` - Async LSP client with promise-based requests
- [x] Implement `server.clj` - Process lifecycle (start!/stop!)
- [x] Tests for JSON-RPC framing
- [x] Tests for client lifecycle

**Status:** Complete. Foundation is solid.

---

## Phase 2: Clojure API (tools.clj) ✅

Build the core Clojure API for LSP operations. Test via local-eval.

- [x] Create `system-clojure-lsp-dev.edn` config file
- [x] Create `tools.clj` namespace
- [x] Implement `with-file` helper (did-open → operation → did-close)
- [x] Implement navigation functions:
  - [x] `(definition {:file f :line l :column c})`
  - [x] `(references {:file f :line l :column c})`
  - [x] `(hover {:file f :line l :column c})`
- [x] Implement completion/refactoring:
  - [x] `(completions {:file f :line l :column c})`
  - [x] `(code-actions {:file f :line l :column c})`
  - [x] `(rename {:file f :line l :column c :new-name n})`
- [x] Implement analysis:
  - [x] `(document-symbols {:file f})`
  - [x] `(diagnostics)` / `(diagnostics {:file f})`
  - [x] `(call-hierarchy {:file f :line l :column c :direction :incoming|:outgoing})`
- [x] Test all functions via local-eval interactively
- [x] Lint & format check
- [x] Git commit/tag/push

**Status:** Complete. All tools.clj functions tested via local-eval.

**Testing via local-eval:**
```clojure
;; Start server with clojure-lsp module
;; bb server --http

;; Via local-eval:
(require '[bb-mcp-server.modules.clojure-lsp.client :as client])
(require '[bb-mcp-server.modules.clojure-lsp.tools :as tools] :reload)

(client/start! {:project-root "/path/to/project"})
(tools/definition {:file "/path/to/file.clj" :line 42 :column 10})
(tools/hover {:file "/path/to/file.clj" :line 42 :column 10})

;; Inspect state
@#'client/state
(client/get-diagnostics)
```

---

## Phase 3: CLI (bb clojure-lsp)

Build CLI that uses local-eval to interact with running server.

- [ ] Create `scripts/clojure_lsp_cli.clj`
- [ ] Add tasks to `bb.edn`:
  ```clojure
  clojure-lsp {:doc "Clojure LSP CLI"
               :task (load-file "scripts/clojure_lsp_cli.clj")}
  ```
- [ ] Implement commands via local-eval calls:
  - [ ] `bb clojure-lsp start <project-root> [--executable path]`
  - [ ] `bb clojure-lsp stop`
  - [ ] `bb clojure-lsp status`
  - [ ] `bb clojure-lsp definition <file> <line> <col>`
  - [ ] `bb clojure-lsp references <file> <line> <col>`
  - [ ] `bb clojure-lsp hover <file> <line> <col>`
  - [ ] `bb clojure-lsp diagnostics [file]`
  - [ ] `bb clojure-lsp symbols <file>`
  - [ ] `bb clojure-lsp completions <file> <line> <col>`
  - [ ] `bb clojure-lsp rename <file> <line> <col> <new-name>`
  - [ ] `bb clojure-lsp code-actions <file> <line> <col>`
- [ ] Lint & format check
- [ ] Git commit/tag/push

**Implementation pattern:**
```clojure
(defn cmd-definition [{:keys [file line column server]}]
  (mcp-client/call-tool
    server
    "local-eval"
    {:code (pr-str
             `(do
                (require '[bb-mcp-server.modules.clojure-lsp.tools :as t])
                (t/definition {:file ~file :line ~line :column ~column})))}))
```

---

## Phase 4: MCP Tools

Wire up MCP tools as thin wrappers around the Clojure API.

- [ ] Update `module.edn` with all tools:
  - [ ] `clj-init` (already exists)
  - [ ] `clj-definition`
  - [ ] `clj-references`
  - [ ] `clj-hover`
  - [ ] `clj-completions`
  - [ ] `clj-code-actions`
  - [ ] `clj-rename`
  - [ ] `clj-diagnostics`
  - [ ] `clj-document-symbols`
  - [ ] `clj-call-hierarchy`
- [ ] Create MCP handler functions that delegate to `tools.clj`
- [ ] Add module to `system.edn`
- [ ] Test via `bb mcp call clojure-lsp.clj-definition ...`
- [ ] Lint & format check
- [ ] Git commit/tag/push

---

## Phase 5: Polish & Documentation

- [ ] Error handling: timeouts, process crashes, auto-restart
- [ ] Multi-project support (optional): `{project-root → client-state}` map
- [ ] README.md for the module with usage instructions
- [ ] Final test suite run
- [ ] Lint & format check
- [ ] Git commit/tag/push

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Interfaces                              │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ bb clojure-  │  │  local-eval  │  │  MCP Tools   │       │
│  │ lsp CLI      │  │  (REPL)      │  │  (Claude)    │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
│         │                 │                 │                │
│         └────────────┬────┴────────────────┘                │
│                      ▼                                       │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                    tools.clj                          │  │
│  │    Clojure API: definition, references, hover, etc.   │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                    client.clj                         │  │
│  │    Async LSP client: request!, notify!, did-open!     │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                    jsonrpc.clj                        │  │
│  │    Content-Length framing: write-message!, read!      │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              ▼                               │
│                    ┌─────────────────┐                      │
│                    │  clojure-lsp    │                      │
│                    │  (subprocess)   │                      │
│                    └─────────────────┘                      │
└─────────────────────────────────────────────────────────────┘
```

---

## Status Summary

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Foundation (jsonrpc, client, server) | ✅ Complete |
| 2 | Clojure API (tools.clj) | ✅ Complete |
| 3 | CLI (bb clojure-lsp) | 🔲 Not started |
| 4 | MCP Tools | 🔲 Not started |
| 5 | Polish & Docs | 🔲 Not started |

---

*Last Updated: 2025-12-29*
