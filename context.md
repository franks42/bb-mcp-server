# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Completed clojure-lsp module Phase 5.5 (MCP Tools Parity):

**MCP tools expanded to 16** (was 11) per Gemini review feedback:
- `clj-find-symbol` - Workspace symbol search by name
- `clj-implementations` - Find protocol/interface implementations
- `clj-format` - Format files via clojure-lsp
- `clj-execute-command` - Execute refactoring commands
- `clj-watch` - Control file watcher (start/stop/status)

**Enhanced tools:**
- `clj-init` now accepts `watch: true` to auto-start file watcher

**CLI has 18 commands** (unchanged):
- Lifecycle: `start`, `stop`, `status`, `watch`
- Navigation: `definition`, `references`, `hover`, `implementations`
- Search: `find-symbol` (workspace-wide by name)
- Analysis: `diagnostics`, `symbols`, `call-hierarchy`
- Refactoring: `completions`, `code-actions`, `rename`, `refactor`, `format`

**Design document:** `docs/design/live-static-state-design-implementation.md`
- Explores combining static (clojure-lsp) and dynamic (nREPL) views
- Addresses gap when code is evaluated at REPL without saving to file

---

## Current Focus

**clojure-lsp module** - Phase 5.5 complete. Ready for Phase 6 (Polish & Docs).
- All `tools.clj` functionality now exposed via MCP tools
- Full parity between CLI (18 commands) and MCP (16 tools)

**Static + Live State Integration** - Design phase.
- Design document at `docs/design/live-static-state-design-implementation.md`
- Explores combining clojure-lsp (static) with nREPL (runtime) views

---

## Recent Changes

```
f47642f feat(clojure-lsp): Add 5 missing MCP tools per Gemini review
c7b0b71 docs: Update plans and add static+live state design
2f8715e feat(clojure-lsp): Add watch mode for incremental index updates
0a34e21 feat(clojure-lsp): Add find-symbol, format, implementations, refactor commands
b1006a2 docs: Rename cli-examples.md to clojure-lsp-cli-examples.md
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

