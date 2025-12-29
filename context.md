# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Completed clojure-lsp module Phase 4 (MCP Tools):
- Registered 11 MCP tools in `core.clj`: clj-init, clj-status, clj-definition, clj-references, clj-hover, clj-completions, clj-code-actions, clj-rename, clj-diagnostics, clj-document-symbols, clj-call-hierarchy
- Added clojure-lsp to `system.edn` modules list
- Created comprehensive CLI examples documentation (`modules/clojure-lsp/docs/cli-examples.md`)
- Updated design docs with dependency navigation and multi-project findings

Key insight: Multi-project support already exists at bb-mcp-server level (run multiple instances). clojure-lsp itself doesn't support workspace/workspaceFolders.

---

## Current Focus

**clojure-lsp module** - Phase 4 complete. Ready for Phase 5 (Polish & Docs).

Phase 5 tasks:
- Error handling: timeouts, process crashes, auto-restart
- README.md for the module
- Test coverage gaps

---

## Recent Changes

```
8e707b9 docs(clojure-lsp): Add CLI examples with real tool responses
ddb59a2 feat(clojure-lsp): Implement Phase 4 - MCP Tools
a3b8177 feat(clojure-lsp): Implement Phase 3 - CLI (bb clojure-lsp)
cb2a17e feat(clojure-lsp): Implement Phase 2 - Clojure API (tools.clj)
44a4591 docs(clojure-lsp): Update implementation strategy - API-first via local-eval
```

---

## Pending Work

**clojure-lsp module** (see `IMPLEMENTATION_PLAN.md`):
1. **Phase 5** - Error handling (crash detection, auto-restart), README.md

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
- **clojure-lsp startup**: ~700ms for medium projects, can navigate into Maven jar dependencies (read-only)

---

## Quick Resume

```bash
# Start server with clojure-lsp
bb server --http --config system.edn --nickname my-server

# Test MCP tools
bb mcp tools --mcp my-server | grep clojure-lsp
bb mcp call clojure-lsp.clj-status '{}' --mcp my-server

# Verify everything works
bb test:modules
bb lint
```

