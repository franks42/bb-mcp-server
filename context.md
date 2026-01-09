# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-08
**Version:** v1.10.2 (tagged, pushed)

---

## Current State

**CM6 fix verified with Playwright test** - working directory has corrected code, verified by lint/format/tests AND automated browser test.

### CM6 Source Viewer Fix (TESTED)
The issue was in `scittle_cm6.cljs` Form-3 Reagent component. The fix:
- Use plain `atom` (not ratom) for `!last-value` to avoid re-render loops
- Initialize `!last-value` to `nil`, set in `component-did-mount`
- In `reagent-render`: receive new props as argument, compare with `!last-value`, update CM6 if different

**Test result:**
```
[test] SUCCESS: CM6 editor updates correctly when value prop changes!
```

### v1.10.2 Changes (committed)
- `modules/sente-browser/src/browser/scittle_cm6.cljs` - CM6 update fix
- `test/bb_mcp_server/bootstrap/config_test.clj` - Syntax errors fixed
- `test/scripts/test_cm6_update.mjs` - Playwright test for CM6 updates
- `docs/SCITTLE_DEV_ENVIRONMENT.md` - Added Playwright testing + Clean Restart sections

---

## What Was Accomplished This Session

1. **clojure-lsp NUL byte issue** - Investigated and hardened:
   - `a65aca5` - Read loop resilient to JSON parse errors
   - `8a4eff7` - Capture stderr, add NUL byte diagnostics
   - Issue reproduced: "CTRL-CHAR, code 0" at column 73704
   - Root cause: clj-kondo stdout pollution (known LSP issue)
   - Directive: Install HEAD clojure-lsp if crashes recur

2. **Version check:**
   - clojure-lsp 2025.11.28 - latest release
   - clj-kondo bundled 2025.10.24 - older than standalone
   - clj-kondo standalone 2025.12.23 - latest

3. **Tagged v1.10.1** - last known good state

4. **Updated IMPLEMENTATION_PLAN.md** with version info

---

## Recent Commits

```
b2add29 fix(code-browser): CM6 editor update attempt (superseded by working dir fix)
521ca6e docs: Update plan - Phase 1 complete, clojure-lsp version info
8a4eff7 feat(clojure-lsp): Capture stderr and add NUL byte diagnostics
a65aca5 fix(clojure-lsp): Make read loop resilient to JSON parse errors
28555f2 fix(code-browser): CM6 source viewer working - EditorState import fix
```

---

## Session Notes

Things not in CLAUDE.md or other docs:

- **sente-lite on-message callback** - MUST return `nil`; truthy values get echoed to client
- **Scittle reagent.dom** - Not available via nREPL eval; use `bootstrap/mount-root!`
- **nrepl-eval-local-file** - Correct tool for loading .cljs into Scittle
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable
- **Reagent Form-3 gotcha** - Values in outer `let` are captured at mount time, not updated
- **clojure-lsp NUL bytes** - Sporadic stdout pollution from bundled clj-kondo

---

## Quick Resume

```bash
# See docs/SCITTLE_DEV_ENVIRONMENT.md for full guide
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev
```
