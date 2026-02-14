# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-14 (evening)
**Version:** v1.31.0-dev (in progress)
**Focus:** 🐛 Widget response handling broken - fetches work but widgets stuck in loading state

---

## Current Work (2026-02-14)

### BLOCKING BUG: Widget Response Handling Broken 🔴

**Status:** INVESTIGATING - Fetches succeed on server but widgets never update.

**Symptoms:**
- Widgets stuck in loading state with `<div class="loading-overlay"><div class="spinner"></div></div>`
- Server receives and processes fetch requests successfully
- Browser NEVER logs receiving fetch responses
- Happens for ALL widgets (projects, namespaces, symbols)
- Happens whether widget created by clicking buttons OR restored from URI hash

**Evidence:**
```bash
# Server shows fetch processed:
bb logs -t cb-v2-test --event handle-fetch
22:20:39.742 INFO Stateless fetch {:uri nil, :property :project-list}

# Browser shows fetch sent:
bb logs -t cb-v2-test --source browser --event fetch
22:20:39.741 INFO Fetch requested

# Browser shows NO response received:
bb logs -t cb-v2-test --source browser --event response
0 entries
```

**Testing Done:**
1. ✅ Clicked "+ Projects" button - fetch sent, server processed, widget stuck loading
2. ✅ Navigated to URI hash - widgets created but stuck loading (no fetch sent)
3. ✅ Manual refresh-widget! call - returns nil, no fetch sent
4. ✅ Fresh page load with all ghost widgets removed - same issue

**Files Involved:**
- `modules/sente-browser/src/browser/code_browser_v2.cljs` - fetch request/response handling
- `modules/code-browser-v2/src/code_browser/handlers.clj` - server-side fetch handler
- Response routing likely through sente websocket events

**Next Steps for Fresh Claude:**
1. Read `code_browser_v2.cljs` and trace fetch response handling
2. Check how `:code-browser-v2/fetch` responses route back to widgets
3. Look for missing event handlers or broken response->widget-state updates
4. Check if response arrives but fails silently during processing

---

## Fixed Issues ✅

### Fixed: dispatch-event Unbound Bug

**Problem:** `code-browser.handlers/dispatch-event` was unbound, blocking all event handling.

**Root Cause:** TWO parenthesis errors in `handlers.clj`:
- Line 577: `rescan-nrepl-sources!` missing one closing paren (had 6, needed 7)
- Line 711: `dispatch-event` extra closing paren (had 4, needed 3)

**Fix:** Corrected paren errors. **Committed.**

### Fixed: nREPL Source Not Registered

**Problem:** Widget viewing `nrepl://localhost:7888` but source NOT in `:sources` map.

**Impact:** `rescan-nrepl-sources!` found no nREPL sources, rescans never executed.

**Fix:** Added `{:type :nrepl :host "localhost" :port 7888}` to `system-cb-v2-test.edn`. **Committed and pushed.**

**Verification:**
```bash
bb nrepl-direct eval "(keys (:sources @code-browser.handlers/!module-state))" -t cb-v2-test
# Now returns: ("dir://bb-mcp-server@..." "dir://hasch@..." "nrepl://localhost:7888@...")
```

---

## Known Architectural Issues (Not Yet Fixed)

#### Issue 1: Wasteful Full Rescans

**Problem:** Built sophisticated fingerprint detection to identify specific changes, then rescan EVERYTHING anyway.

**Current Flow:**
1. Fingerprint detects change in ONE namespace (e.g., `user`)
2. Calls `rescan-nrepl-sources!`
3. Calls `batch-introspect` which does `(for [n (all-ns)] ...)` - ALL 212 namespaces!
4. Retracts ALL entities from Datalevin
5. Writes ALL entities back to Datalevin

**Impact:** Every change to any single var triggers full rescan of entire runtime.

**Correct Implementation Should:**
- Symbol list change in namespace X → rescan ONLY namespace X
- Var value change in X/y → fetch ONLY that var's value
- Namespace added/removed → rescan namespace list only

#### Issue 2: Fingerprints Not Stored

**Problem:** Every fingerprint check reports "No stored fingerprint" because fingerprints aren't persisted in `!module-state` between checks.

**Impact:** Can't detect REAL changes. Every check looks like a first check, triggering rescans even when nothing changed.

#### Issue 3: Runtime Data Sync Statechart Not Integrated

**Problem:** `runtime_data_sync.cljc` exists as documentation but isn't wired into code.

**Impact:** No state machine enforcing valid transitions, no protection against impossible states.

---

## Test Environment

**Running Server:** cb-v2-test (ports 7888, 8090, 8091)
```bash
# Start server
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# Open browser
open http://localhost:8091

# Load UI (if not auto-loaded)
# Click "Load Code Browser" button OR
bb nrepl-direct eval "(scittle.core/eval-string \"(ui-loader/load-code-browser-ui!)\")" -t cb-v2-test/browser-1
```

**Verification Commands:**
```bash
# Check server status
bb nrepl-direct eval "(keys (:sources @code-browser.handlers/!module-state))" -t cb-v2-test

# Check telemetry
bb logs -t cb-v2-test --event handle-fetch --limit 5
bb logs -t cb-v2-test --source browser --event fetch --limit 5

# Check widget state (in browser console or via nREPL)
# scittle.core.eval_string("@code-browser-v2/!widgets")
```

---

## Recent Commits

**Latest (2026-02-14):**
- Fixed dispatch-event unbound bug (paren errors in handlers.clj)
- Fixed nREPL source registration (added to system-cb-v2-test.edn)
- Added extensive telemetry for debugging
- Committed and pushed snapshot

**v1.30.0** - Statechart Service/ManyStore adoption (2026-02-13)
- Added FSM runtime introspection
- Live var value display with type-aware rendering
- Statechart detection in var-value widgets

---

## Next Session - IMMEDIATE ACTION REQUIRED

**BLOCKING:** Widget response handling is broken. Start here:

1. **Trace fetch response flow in browser**
   - Read `modules/sente-browser/src/browser/code_browser_v2.cljs`
   - Find where `:code-browser-v2/fetch` responses are received
   - Check if response handler exists and is wired up
   - Look for sente event routing to widget state updates

2. **Test response arrival**
   - Add telemetry to response handler
   - Verify responses actually arrive from server via websocket
   - Check if they fail silently during processing

3. **Check widget state update logic**
   - Trace from response data → widget atom update
   - Look for missing `swap!` or broken state transition
   - Widget should go from `:loading` state to `:ready` with data

**After response handling fixed, then:**
- Implement fingerprint storage (store after computing)
- Add incremental rescans (single namespace, not all-ns)
- Integrate runtime-data-sync statechart

---

## Session Notes Archive

<details>
<summary>2026-02-13: dispatch-event Unbound Bug (RESOLVED)</summary>

### Problem
Live polling completely non-functional due to `dispatch-event` function being unbound in running server.

### Investigation
1. File evaluation stopped mid-file at line 577
2. Functions after line 577 never bound despite valid syntax
3. Could load functions individually but not from full file
4. Created test artifacts to isolate issue

### Resolution
Found TWO parenthesis errors via GPT-4 review:
- Line 577: missing closing paren
- Line 711: extra closing paren
- clj-kondo showed "inline def" warnings pointing to the issue

### Lesson Learned
**ALWAYS fix clj-kondo warnings, not just errors.** "inline def" warning indicated structural issue that errors didn't catch.

</details>

---

*For detailed debugging history, see git log and claude-mem observations.*
