# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-30

## Previous Session Summary

**Scittle/SCI Compatibility Testing: ✅ COMPLETE**

All 4 Phase 0 introspection tools verified working with Scittle browser via nrepl-proxy.

**What works with Scittle:**
- `all-ns` - returns 21 namespaces
- `ns-publics`, `ns-interns` - returns var lists
- `resolve` - works (returns function directly, not var)
- `meta` on vars - returns `nil` (SCI limitation, not a bug)
- `var?` - returns `false` for SCI "vars" (they're functions)

**All 4 MCP introspection tools tested:**
1. `nrepl.nrepl-loaded-namespaces` - ✅ returns 21 namespaces
2. `nrepl.nrepl-introspect-ns` - ✅ returns publics/interns for clojure.string
3. `nrepl.nrepl-var-meta` - ✅ works (returns null metadata - expected SCI behavior)
4. `nrepl.nrepl-get-value` - ✅ works (after fix for SCI compatibility)

**Bug Fix Applied:**
- `nrepl_get_value.clj` - Fixed to handle SCI where `resolve` returns functions directly instead of vars
- Changed `@v` to `(if (var? v) @v v)` for SCI compatibility
- Also handle metadata extraction with `(when (var? v) (meta v))`

---

## Current Focus

**Scittle testing complete. Next priorities:**

1. **Add introspection commands to `bb nrepl` CLI** - convenience wrappers (see Pending Work)
2. **Phase 0.5: REPL source capture** - Datalevin + var metadata

**To start Scittle dev server for testing:**
```bash
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev
bb nrepl list --mcp scittle-dev  # Shows browser connections
```

---

## Key Discovery: Var Metadata Across Clojure Variants

**See:** `docs/design/clojure-var-metadata-research.md` for full research.

**Summary:**
- SCI user-defined vars have full metadata; built-ins are plain JS functions (no metadata)
- clojure-lsp uses static analysis + clojuredocs.org API, not runtime metadata
- Phase 0 tools work across JVM Clojure, Babashka, AND Scittle with graceful degradation

---

## Recent Changes

```
d0d1a17 docs: Add detailed bug report for tools.clj loading failure
53f1854 docs: Update context.md for clojure-lsp Phase 3 completion
a3b8177 feat(clojure-lsp): Implement Phase 3 - CLI (bb clojure-lsp)
```

---

## Pending Work

**Immediate:**
- ~~**Scittle compatibility testing**~~ - ✅ All 4 tools verified
- **Add introspection commands to `bb nrepl` CLI** - convenience wrappers:
  - `bb nrepl namespaces [--prefix X]` → calls `nrepl.nrepl-loaded-namespaces`
  - `bb nrepl vars <ns>` → calls `nrepl.nrepl-introspect-ns`
  - `bb nrepl meta <symbol>` → calls `nrepl.nrepl-var-meta`
  - `bb nrepl value <symbol>` → calls `nrepl.nrepl-get-value`
  - File: `scripts/nrepl_cli.clj`

**Static + Live State Integration** (next priority):
- ~~Phase 0: nREPL introspection tools~~ ✅ Complete
- Phase 0.5: REPL source capture (Datalevin + var metadata)
- Phase 0.6: Top-level non-def forms visibility
- Phase 1-3: See `IMPLEMENTATION_PLAN.md`

**clojure-lsp module** (Phase 6):
1. Error handling (crash detection, auto-restart)
2. README.md for the module
3. Test coverage for new commands

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
- **SCI introspection** - Scittle IS SCI (not ClojureScript), supports `all-ns`, `ns-publics`, `resolve`, etc.
- **SCI metadata nuance** - User-defined vars have full metadata; built-ins return nil (they're JS functions)
- **`doc` in Scittle** - Exists in `clojure.repl`, works for user-defined vars, use `with-out-str` to capture
- **clojure-lsp static analysis** - Parses source directly, uses clojuredocs.org for built-in docs
- **Playwright for browser testing** - Use `node scripts/scittle_browser.mjs --headless` to avoid tab throttling

---

## Quick Resume

```bash
# Start Scittle dev server
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev

# List browser connections (refresh browser at http://localhost:8091 if needed)
bb nrepl list --mcp scittle-dev

# Test introspection primitives with bb nrepl eval (use latest browser-N)
bb nrepl eval "(count (all-ns))" --connection browser-13 --mcp scittle-dev
bb nrepl eval "(keys (ns-publics 'clojure.string))" --connection browser-13 --mcp scittle-dev
bb nrepl eval "(meta #'clojure.string/join)" --connection browser-13 --mcp scittle-dev
bb nrepl eval "@(resolve 'clojure.string/join)" --connection browser-13 --mcp scittle-dev

# Test MCP tool wrappers (alternative approach)
bb mcp call nrepl.nrepl-loaded-namespaces '{"connection":"browser-13"}' --mcp scittle-dev
bb mcp call nrepl.nrepl-introspect-ns '{"ns":"clojure.string","connection":"browser-13"}' --mcp scittle-dev
bb mcp call nrepl.nrepl-var-meta '{"symbol":"clojure.string/join","connection":"browser-13"}' --mcp scittle-dev
bb mcp call nrepl.nrepl-get-value '{"symbol":"clojure.string/join","connection":"browser-13"}' --mcp scittle-dev

# Verify everything works
bb test:modules
bb lint
```

