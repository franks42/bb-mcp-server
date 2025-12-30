# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Completed **Static + Live State Integration** design documentation:

**Design expanded** (`docs/design/live-static-state-design-implementation.md`):
- Phase 0: 4 nREPL introspection tools (immediate wins)
- Phase 0.5: REPL source capture with Datalevin + var metadata
- Phase 0.6: Top-level non-def forms visibility (NEW)
- Phase 1-3: Namespace-focused query, state-monitor module, CLI

**Key problems addressed:**
- REPL-evaluated code loses source (`:file "NO_SOURCE_FILE"`)
- Top-level non-def forms (e.g., `(println ...)`) invisible to introspection
- Gap between static (files on disk) and live (JVM runtime) state

**Gemini reviews added:**
- `docs/design/live-static-state-design-implementation-review.md`
- `gemini-review-20251229.md` (clojure-lsp feature complete)

---

## Current Focus

**Static + Live State Integration** - Design complete, ready for implementation.
- Design reviewed by Gemini, all phases documented
- Phase 0 (nREPL introspection tools) is first implementation step
- 6 phases specified: 0, 0.5, 0.6, 1, 2, 3

**clojure-lsp module** - v1.8.0 released. Feature complete.
- 16 MCP tools, 18 CLI commands
- Phase 6 pending (error handling, README, tests)

---

## Recent Changes

```
5a5205a docs: Expand live-static-state design with tool specs and Gemini review
389a820 docs: Add Phase 0.5 - REPL Source Capture
501139e docs: Update context.md - design complete, ready for Phase 0
e239dd5 docs: Expand live-static-state design with tool specs and Gemini review
1c7a157 docs: Detail Static + Live State Integration phases per Gemini review
76afabd docs: Add Phase 5.5 (MCP Tools Parity) to implementation plan
```

---

## Pending Work

**Static + Live State Integration** (next priority):
- Phase 0: Add 4 nREPL introspection tools to nrepl module
  - `nrepl-loaded-namespaces`, `nrepl-introspect-ns`, `nrepl-var-meta`, `nrepl-get-value`
- Phase 0.5: REPL source capture (Datalevin + var metadata)
  - `nrepl-var-source`, `nrepl-eval-history`
- Phase 0.6: Top-level non-def forms visibility
  - `clj-ns-top-level-forms`
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

