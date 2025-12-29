# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Completed clojure-lsp module Phase 5 (Watch Mode & Extended CLI):

**CLI expanded to 18 commands:**
- Lifecycle: `start`, `stop`, `status`, `watch`
- Navigation: `definition`, `references`, `hover`, `implementations`
- Search: `find-symbol` (workspace-wide by name)
- Analysis: `diagnostics`, `symbols`, `call-hierarchy`
- Refactoring: `completions`, `code-actions`, `rename`, `refactor`, `format`

**Key additions:**
- `bb clojure-lsp watch` - File watcher using pod-babashka-fswatcher v0.0.7
- `find-symbol` - Symbol-centric search (vs position-dependent definition/references)
- `format`, `implementations`, `refactor` commands
- `bb pprint` - EDN pretty-printer utility
- Default output changed to EDN (use `--json` for JSON)

**New design document:** `docs/design/live-static-state-design-implementation.md`
- Explores combining static (clojure-lsp) and dynamic (nREPL) views
- Addresses gap when code is evaluated at REPL without saving to file

---

## Current Focus

**Static + Live State Integration** - Design phase.

The core insight: clojure-lsp sees static files, nREPL sees runtime state. When working interactively at the REPL, these can diverge. The design document explores how to provide a unified view.

**clojure-lsp module** - Phase 5 complete. Ready for Phase 6 (Polish & Docs).

---

## Recent Changes

```
2f8715e feat(clojure-lsp): Add watch mode for incremental index updates
0a34e21 feat(clojure-lsp): Add find-symbol, format, implementations, refactor commands
b1006a2 docs: Rename cli-examples.md to clojure-lsp-cli-examples.md
b034688 feat(clojure-lsp): Change default output to EDN
76e3fb6 feat: Add bb pprint utility and update CLI examples
ac2d061 docs: Update context.md for Phase 4 completion
8e707b9 docs(clojure-lsp): Add CLI examples with real tool responses
ddb59a2 docs: Mark clojure-lsp Phase 4 complete
63e9868 feat(clojure-lsp): Implement Phase 4 - MCP Tools
```

---

## Pending Work

**clojure-lsp module** (Phase 6):
1. Error handling (crash detection, auto-restart)
2. README.md for the module
3. Test coverage for new commands

**Static + Live State Integration:**
1. Design document - `docs/design/live-static-state-design-implementation.md`
2. Evaluate cider-nrepl middleware integration
3. Unified query API design

**Other** (see IMPLEMENTATION_PLAN.md):
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
- **pod-babashka-fswatcher** v0.0.7 works for recursive file watching
- **LSP is position-centric** (file/line/col) - `find-symbol` provides name-centric alternative

---

## Quick Resume

```bash
# Start server with clojure-lsp
bb server --http --config system.edn --nickname my-server

# Initialize clojure-lsp for a project
bb clojure-lsp start . --mcp my-server

# Start file watcher (keeps index fresh)
bb clojure-lsp watch --mcp my-server

# Search symbols by name
bb clojure-lsp find-symbol register --mcp my-server

# Verify everything works
bb test:modules
bb lint
```

