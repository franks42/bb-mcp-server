# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-31

## Previous Session Summary

**Browser Session Stability: Phase C Implementation Complete**

Implemented session-id handshake to maintain stable browser identity across WebSocket reconnects.

**Solution Implemented:**
1. Browser generates session-id via `defonce` (persists across WS reconnects)
2. Browser sends `:nrepl/session-hello {:session-id X}` on connect/reconnect
3. Server maintains `!session-registry` mapping session-id → mcp-conn-id
4. On reconnect, server looks up existing mcp-conn-id from registry
5. Browser keeps same nickname (browser-1 stays browser-1)

**Files Modified:**
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Client-side session-id + hello events
- `modules/sente-browser/src/sente_browser/server.clj` - Session registry + hello handler
- `modules/nrepl/src/nrepl/state/connection.clj` - Added `reactivate-browser-connection!`

**Key Insight:** Safari tab throttling pauses JS but does NOT reset the runtime. `defonce` persists the session-id, so on reconnect the same ID is sent to the server.

See `IMPLEMENTATION_PLAN.md` → "Scittle Browser Session Stability" for full details.

---

## Current Focus

**Next priorities:**

1. **Test browser session stability** - Verify Safari reconnects keep same identity
2. **Add introspection commands to `bb nrepl` CLI** - convenience wrappers
3. **Phase 0.5: REPL source capture** - Datalevin + var metadata

**To test browser session stability:**
```bash
# Start Scittle dev server
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev

# Open Safari at http://localhost:8091 - note the browser-N name
# Switch to another tab for 2+ minutes
# Switch back - should still be browser-N (not browser-N+1)

# Check session registry
bb mcp call local-eval.local-eval '{"code":"(sente-browser.server/get-session-registry)"}' --mcp scittle-dev
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
- **Safari background throttling** - Safari tabs throttle JS when unfocused, breaking WebSocket heartbeats
- **sente-lite heartbeat config** - 30s ping interval, 60s pong timeout = 90s before disconnect
- **Monitor browser health** - Use `sente-browser.server/get-connection-health` via local-eval

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

