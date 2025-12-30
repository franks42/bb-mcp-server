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

**clojure-lsp module** - v1.8.0 released. Phase 5.5 complete.
- 16 MCP tools, 18 CLI commands
- Ready for Phase 6 (Polish & Docs)

**Static + Live State Integration** - Design complete, ready for Phase 0.
- Design reviewed by Gemini
- Phase 0 (nREPL introspection tools) is next implementation step
- 10 tools specified across 4 phases

---

## Recent Changes

```
e239dd5 docs: Expand live-static-state design with tool specs and Gemini review
1c7a157 docs: Detail Static + Live State Integration phases per Gemini review
76afabd docs: Add Phase 5.5 (MCP Tools Parity) to implementation plan
284c488 docs: Update context.md for Phase 5.5 completion
f47642f feat(clojure-lsp): Add 5 missing MCP tools per Gemini review
c7b0b71 docs: Update plans and add static+live state design
```

---

## Pending Work

**Static + Live State Integration** (next priority):
- Phase 0: Add 4 nREPL introspection tools to nrepl module
  - `nrepl-loaded-namespaces`, `nrepl-introspect-ns`, `nrepl-var-meta`, `nrepl-get-value`
- Phase 1-3: See `IMPLEMENTATION_PLAN.md`

**clojure-lsp module** (Phase 6):
1. Error handling (crash detection, auto-restart)
2. README.md for the module
3. Test coverage for new commands

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

