# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Implemented clojure-lsp module Phase 3 - CLI (`bb clojure-lsp`):
- Created `scripts/clojure_lsp_cli.clj` with 12 commands
- Added `clojure-lsp` task to `bb.edn`
- Commands: start, stop, status, definition, references, hover, diagnostics, symbols, call-hierarchy, completions, code-actions, rename
- Uses local-eval to call tools.clj functions on running MCP server
- Tested: help, status, start commands work correctly
- Tagged as `clojure-lsp-v0.3.0-phase3`

**Known Issue:** Navigation commands (hover, definition, etc.) fail with NullPointerException when loading tools.clj via local-eval. Start/status work because they use client.clj directly.

---

## Current Focus

**clojure-lsp module** - Phases 1-3 complete, Phase 4 (MCP Tools) pending.

Before Phase 4, should debug why tools.clj fails to load via local-eval.

---

## Recent Changes

```
a3b8177 feat(clojure-lsp): Implement Phase 3 - CLI (bb clojure-lsp)
cb2a17e feat(clojure-lsp): Implement Phase 2 - Clojure API (tools.clj)
44a4591 docs(clojure-lsp): Update implementation strategy - API-first via local-eval
fdd982a docs: Record CLI lint fixes in IMPLEMENTATION_PLAN and context
78a07d6 fix: Add namespace declarations to CLI scripts
```

---

## Pending Work

**clojure-lsp module** (see `modules/clojure-lsp/IMPLEMENTATION_PLAN.md`):
1. **Debug tools.clj loading** - Fix NullPointerException in local-eval
2. **Phase 4** - Register MCP tools (clj-definition, clj-hover, etc.)
3. **Phase 5** - Polish & documentation

**Other** (see main IMPLEMENTATION_PLAN.md):
1. **bb calc CLI** (low priority)
2. **Phase 14C** - Dynamic loading documentation
3. **Phase 15C/D** - AI knowledge persistence

---

## Active Bug: tools.clj Loading Failure

**Problem:** CLI navigation commands fail with NullPointerException when trying to load tools.clj via local-eval.

**Reproduction:**
```bash
# 1. Start server with clojure-lsp module
bb server --http --port 0 --nickname clj-lsp --config system-clojure-lsp-dev.edn

# 2. Initialize clojure-lsp (THIS WORKS)
bb clojure-lsp start /Users/franksiebenlist/Development/bb-mcp-server --mcp clj-lsp

# 3. Try hover command (THIS FAILS)
bb clojure-lsp hover src/bb_mcp_server/registry.clj 10 5 --mcp clj-lsp
```

**What Works:**
- `bb clojure-lsp help` - CLI parsing
- `bb clojure-lsp status` - Calls `server.clj` functions via local-eval
- `bb clojure-lsp start` - Calls `client.clj` functions via local-eval

**What Fails:**
- `bb clojure-lsp hover/definition/references/etc.` - All commands that call `tools.clj`

**Error from server logs:**
```
:error bb-mcp-server.handlers.tools-call ::handler-failed Tool handler execution failed
  data: {:tool "local-eval.local-eval", :duration-ms 4, :error nil, :error-data nil}
  error: java.lang.NullPointerException
```

**Key Files:**
- `scripts/clojure_lsp_cli.clj:164-169` - `ensure-tools-loaded` tries to require tools namespace
- `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/tools.clj` - The namespace that fails to load
- `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/client.clj` - Works fine via local-eval

**What CLI sends to local-eval:**
```clojure
;; This is sent first to load tools.clj:
(when-not (find-ns 'bb-mcp-server.modules.clojure-lsp.tools)
  (require '[bb-mcp-server.modules.clojure-lsp.tools]))

;; Then this is sent to call the function:
(bb-mcp-server.modules.clojure-lsp.tools/hover {:file "..." :line 10 :column 5})
```

**Hypothesis:** The tools.clj namespace might have a dependency or initialization issue that causes NPE when loaded in local-eval context. Start/status work because they use client.clj/server.clj which are already loaded by the module system.

**Debug approach:**
1. Try `(require '[bb-mcp-server.modules.clojure-lsp.tools])` directly via `bb mcp-eval`
2. Check tools.clj for top-level forms that might fail
3. Check if tools.clj dependencies (client.clj) are properly loaded

---

## Session Notes

Things learned that aren't in CLAUDE.md:

- **Port files** use `.json` extension in `.ports/` directory
- **E2E tests** require server running with `--nickname e2e-test`
- **scittle-nrepl** needs sente-lite bundle at configured path
- **clojure-lsp dev config**: `--config system-clojure-lsp-dev.edn`
- **CLI scripts** must call `(-main)` or `(apply -main *command-line-args*)` at end for bb tasks

---

## Quick Resume

```bash
# Start server for clojure-lsp development
bb server --http --port 0 --nickname clj-lsp --config system-clojure-lsp-dev.edn

# Test CLI
bb clojure-lsp help
bb clojure-lsp status --mcp clj-lsp
bb clojure-lsp start /path/to/project --mcp clj-lsp

# Verify everything works
bb test:modules
bb lint
```

