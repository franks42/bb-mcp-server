# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-30

## Previous Session Summary

Completed **Phase 0: nREPL Introspection Tools**:

**4 new MCP tools added to nrepl module:**
- `nrepl-loaded-namespaces` - List all loaded namespaces with prefix filter
- `nrepl-introspect-ns` - List vars in a namespace (publics + interns)
- `nrepl-var-meta` - Get var metadata (arglists, doc, file/line)
- `nrepl-get-value` - Get runtime value with type info and truncation

**Key features:**
- Works with vanilla clojure.core (no cider-nrepl required)
- Optional prefix filtering and metadata inclusion
- Large collection truncation (default 100 items)
- Functions return signature info instead of function objects

**Tests:** 8 new tests, 33 assertions (nrepl module: 42 tests, 164 assertions)

---

## Current Focus

**Static + Live State Integration** - Phase 0 complete!
- 4 introspection tools implemented and tested
- Phase 0.5 (REPL source capture) is next implementation step
- Remaining phases: 0.5, 0.6, 1, 2, 3

**clojure-lsp module** - Feature complete.
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
- ~~Phase 0: nREPL introspection tools~~ ✅ Complete
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

