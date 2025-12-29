# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Fixed critical NullPointerException bug in local-eval module:
- **Root cause:** `(class nil)` returns `nil`, then `.getName` on `nil` throws NPE
- **Location:** `modules/local-eval/src/local_eval/eval.clj:132`
- **Fix:** `(if (some? result) (.getName (class result)) "nil")`
- **Impact:** All clojure-lsp CLI navigation commands now work (hover, definition, references, etc.)

clojure-lsp module Phases 1-3 complete - all 12 CLI commands functional.

---

## Current Focus

**clojure-lsp module** - Phases 1-3 complete. Ready for Phase 4 (MCP Tools).

Phase 4 will register proper MCP tools (clj-definition, clj-hover, etc.) instead of relying on local-eval.

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

