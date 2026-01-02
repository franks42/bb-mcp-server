# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2026-01-02

## Previous Session Summary

**Fixed `:invalid-format` Warnings in sente-lite Client**

Root cause identified and fixed. Two functions were returning values that sente-lite echoed back to browser as raw data (not event vectors).

**Root Cause:**
sente-lite's `on-websocket-message` sends any truthy return from `route-message` back to the client. The client's `parse-message` expects vectors, not raw values.

**Fixes Made (`server.clj`):**
1. **`handle-client-ready!`** - Was returning `true` from `send-describe-probe!`
   - Added explicit `nil` return after sending probe

2. **`promote-to-validated!`** - Was returning `mcp-conn-id` string
   - Changed to return `nil` (`:server/ready` event already sends info to browser)

**Verification:**
- ✅ No more `:invalid-format` warnings in browser console
- ✅ Handshake flow works correctly
- ✅ Lint: 0 errors, 0 warnings

**Key Learning:**
- sente-lite `on-message` callback return values get sent back to client
- Always return `nil` from message handlers unless you want to send a response
- Added debug logging to sente-lite client to show raw data on parse errors

---

## Current Focus

**Code Browser Phase 1: Ready for Testing with clojure-lsp**

The event dispatch architecture is complete and working. Next step is testing with clojure-lsp running to verify full data flow.

**To Test Full Flow:**
```bash
# Start server
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# Initialize clojure-lsp (REQUIRED)
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev

# Use Playwright test
node test/scripts/code_browser_event_test.mjs
```

**Remaining Work:**
1. Test with clojure-lsp running (should return actual namespaces)
2. Add CSS styling for three-panel layout
3. Test symbol/source navigation

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
2c3f35b docs: Update context.md with React/ReactDOM fix and session note
bb79ed3 fix(code-browser): Add React/ReactDOM scripts required for Reagent
e11cce8 feat(code-browser): Complete Phase 0 dev infrastructure
59793fb docs: Add Dev Environment Quick Reference section
83ba763 docs: Update context.md with code browser Phase 0 status
```

**Uncommitted changes:**
- `modules/sente-browser/src/sente_browser/server.clj` - Fixed return values causing `:invalid-format` warnings
- `modules/sente-browser/src/browser/code_browser.cljs` - Fixed reagent.dom require
- `test/scripts/code_browser_event_test.mjs` - Uses nrepl-eval-local-file tool
- (sente_lite project) `client_scittle.cljs` - Added raw-data logging for debugging

---

## Pending Work

**Immediate (Code Browser Phase 1):**
- Test with clojure-lsp running
- Add CSS styling for three-panel layout
- Test end-to-end flow: namespaces → symbols → source viewer

**Static + Live State Integration** (next priority):
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
- **React/ReactDOM for Reagent** - Scittle's reagent.js plugin requires React/ReactDOM loaded first (not bundled)
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable (see code_browser.clj for full mapping)
- **Scittle reagent.dom** - Available in inline script but NOT when loading via nREPL eval; use bootstrap/mount-root! wrapper
- **nrepl-eval-local-file** - Correct tool for loading .cljs files into Scittle (reads locally, evals via nrepl-eval)
- **sente-lite on-message callback** - Return values from `on-message` callback get sent back to client; ALWAYS return `nil` unless you explicitly want to send a response event

---

## Quick Resume

```bash
# Start Code Browser dev server
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# Initialize clojure-lsp (REQUIRED)
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev

# Run automated test
node test/scripts/code_browser_event_test.mjs

# Or manual testing - open browser at http://localhost:8091
bb nrepl list --mcp code-browser-dev

# Load and mount code browser (use the actual browser-N nickname)
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"modules/sente-browser/src/browser/scittle_cm6.cljs","connection":"browser-1"}' --mcp code-browser-dev
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"modules/sente-browser/src/browser/code_browser.cljs","connection":"browser-1"}' --mcp code-browser-dev
bb nrepl eval "(code-browser/mount!)" --connection browser-1 --mcp code-browser-dev

# Verify everything works
bb test:modules
bb lint
```

