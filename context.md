# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2026-01-01

## Previous Session Summary

**Browser Session Stability: Ready Handshake Protocol Complete & Tested**

Implemented event-driven ready handshake to maintain stable browser identity across WebSocket reconnects. This replaces the original session-hello approach which had a race condition.

**Solution Implemented (Ready Handshake Protocol):**
1. Browser connects via WebSocket
2. Browser sends `:client/ready {:session-id X}` when handlers ready
3. Server validates nREPL capability via `:describe` probe
4. Server sends `:server/ready {:nickname ... :reconnect true/false}`
5. Both sides now ready for normal communication

**Why ready handshake instead of polling:**
- Original session-hello had race condition: message arrived before 500ms sync-task created entry
- Ready handshake is event-driven: no polling, no race conditions
- Session registry lookup happens at exactly the right moment

**Files Modified:**
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Send `:client/ready`, handle `:server/ready`
- `modules/sente-browser/src/sente_browser/server.clj` - `handle-client-ready!`, removed sync-task polling
- `modules/nrepl/src/nrepl/state/connection.clj` - Added `reactivate-browser-connection!`

**Testing Results (2025-12-31):**
- ✅ Safari and Chrome connect with unique browser-N identities
- ✅ Laptop sleep/wake: Both browsers reconnect with SAME identity
- ✅ Server logs show `:reconnect true` on reconnection
- ✅ Multiple reconnects maintain stable identity

See `IMPLEMENTATION_PLAN.md` → "Scittle Browser Session Stability" for full details.

---

## Current Focus

**Completed:**
- ✅ **Browser session stability** - Ready handshake protocol tested and working
- ✅ **CLI introspection wrappers** - 4 new `bb nrepl` commands implemented
- ✅ **Code browser design decisions** - CM6, dev config, UI loading pattern
- ✅ **Code Browser Phase 0** - Dev infrastructure complete

**Code Browser Phase 0 Complete:**

| Task | Status |
|------|--------|
| Create `bb-code-browser-dev-system.edn` config | ✅ Complete |
| Update bootstrap HTML with preloads | ✅ Complete |
| Create `scittle-cm6` namespace (reusable) | ✅ Complete |
| Implement atom sync in bootstrap bundle | ✅ Complete |
| Add error boundary for REPL dev | ✅ Complete |
| Test: load UI via nREPL, iterate live | ✅ Complete |

**Files created/modified:**
- `bb-code-browser-dev-system.edn` - Dev config with all modules
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Added code-browser HTML with CM6, Reagent, Promesa, error boundary, atom sync
- `modules/sente-browser/src/browser/scittle_cm6.cljs` - Reusable CM6 Reagent wrapper

**Next: Code Browser Phase 1 (Static Browsing)**

See: `docs/design/bb-scittle-code-browser-design.md` and `IMPLEMENTATION_PLAN.md`

**Quick verification of code browser:**
```bash
# Start Code Browser dev server
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# Open browser at http://localhost:8091 (note: refresh to get latest HTML)
bb nrepl list --mcp code-browser-dev

# Load scittle-cm6 into browser
bb nrepl eval "(require '[scittle-cm6 :as cm6])" --connection browser-1 --mcp code-browser-dev

# Mount a test component
bb nrepl eval "[cm6/editor {:value \"(+ 1 2)\" :language :clojure :read-only true}]" --connection browser-1 --mcp code-browser-dev
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
(pending) feat(code-browser): Phase 0 dev infrastructure complete
59793fb docs: Add Dev Environment Quick Reference section
83ba763 docs: Update context.md with code browser Phase 0 status
ea824c1 docs: Update implementation plan and design for code browser
```

---

## Pending Work

**Immediate:**
- ~~**Scittle compatibility testing**~~ - ✅ All 4 tools verified
- ~~**Browser session stability**~~ - ✅ Ready handshake protocol complete
- ~~**Add introspection commands to `bb nrepl` CLI**~~ - ✅ Complete:
  - `bb nrepl namespaces [--prefix X]` → calls `nrepl.nrepl-loaded-namespaces`
  - `bb nrepl vars <ns>` → calls `nrepl.nrepl-introspect-ns`
  - `bb nrepl meta <symbol>` → calls `nrepl.nrepl-var-meta`
  - `bb nrepl value <symbol>` → calls `nrepl.nrepl-get-value`

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

# Test introspection via CLI wrappers (use latest browser-N)
bb nrepl namespaces --connection browser-15 --mcp scittle-dev
bb nrepl namespaces --prefix clojure --connection browser-15 --mcp scittle-dev
bb nrepl vars clojure.string --connection browser-15 --mcp scittle-dev
bb nrepl meta user/my-var --connection browser-15 --mcp scittle-dev --pprint
bb nrepl value user/my-var --connection browser-15 --mcp scittle-dev

# Or use raw MCP tool calls
bb mcp call nrepl.nrepl-loaded-namespaces '{"connection":"browser-15"}' --mcp scittle-dev
bb mcp call nrepl.nrepl-introspect-ns '{"ns":"clojure.string","connection":"browser-15"}' --mcp scittle-dev

# Verify everything works
bb test:modules
bb lint
```

