# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-14 (late evening)
**Version:** v1.31.0-dev (in progress)
**Focus:** Live polling stability + incremental rescans

---

## Current Work (2026-02-14)

### Widget Response Handling Bug — RESOLVED ✅

**Root Cause:** Datalevin deadlock caused by rescan storm.

**Mechanism:**
1. `handle-check-ns-list-fingerprint` and `handle-check-symbol-list-fingerprint` never stored fingerprints after initial computation
2. Every 3-second poll found "no stored fingerprint" → triggered full `rescan-nrepl-sources!`
3. Each rescan does batch-introspect (slow nREPL call) + 2 Datalevin transactions
4. Multiple concurrent rescans deadlocked the Datalevin pod
5. All subsequent queries (including normal fetch operations) blocked indefinitely

**Fixes Applied (handlers.clj):**
- Added `!rescan-in-progress` atom with `compare-and-set!` guard to prevent concurrent rescans
- Fixed `handle-check-ns-list-fingerprint` to compute and store initial fingerprint on first check
- Fixed `handle-check-symbol-list-fingerprint` to compute and store initial fingerprint on first check
- Added telemetry to `dispatch-event` for fetch response tracing
- Removed verbose debug logging from `rescan-nrepl-sources!`

**Verification:**
- clj-kondo: 0 errors, 0 warnings
- cljfmt: clean
- Tests: 80 tests, 629 assertions, 0 failures
- Live browser test: Projects widget (3 projects), namespace list (670 namespaces) load correctly

---

## Fixed Issues ✅

### Fixed: Datalevin Deadlock from Rescan Storm (2026-02-14)

**Problem:** All widgets stuck in perpetual loading state. Datalevin queries hung indefinitely.

**Root Cause:** Fingerprints never stored → infinite rescan loop → concurrent rescans deadlocked Datalevin pod.

**Fix:** Store initial fingerprints + `compare-and-set!` concurrency guard on rescans.

### Fixed: dispatch-event Unbound Bug (2026-02-13)

**Problem:** `code-browser.handlers/dispatch-event` was unbound, blocking all event handling.

**Root Cause:** TWO parenthesis errors in `handlers.clj`.

**Fix:** Corrected paren errors. **Committed.**

### Fixed: nREPL Source Not Registered (2026-02-13)

**Problem:** Widget viewing `nrepl://localhost:7888` but source NOT in `:sources` map.

**Fix:** Added `{:type :nrepl :host "localhost" :port 7888}` to `system-cb-v2-test.edn`. **Committed.**

---

## Known Architectural Issues (Not Yet Fixed)

#### Issue 1: Wasteful Full Rescans

**Problem:** Fingerprint detection identifies specific changes, then rescans EVERYTHING anyway.

**Current Flow:**
1. Fingerprint detects change in ONE namespace (e.g., `user`)
2. Calls `rescan-nrepl-sources!`
3. Calls `batch-introspect` which does `(for [n (all-ns)] ...)` - ALL namespaces
4. Retracts ALL entities from Datalevin
5. Writes ALL entities back to Datalevin

**Correct Implementation Should:**
- Symbol list change in namespace X → rescan ONLY namespace X
- Var value change in X/y → fetch ONLY that var's value
- Namespace added/removed → rescan namespace list only

#### Issue 2: ~~Fingerprints Not Stored~~ FIXED ✅

~~Every fingerprint check reports "No stored fingerprint" because fingerprints aren't persisted.~~
Fixed: Initial fingerprints now stored on first check. Concurrency guard prevents rescan storms.

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

## Next Session Priorities

1. **Implement incremental rescans** (Issue 1 above)
   - Single-namespace rescan instead of full `all-ns` batch-introspect
   - Per-var value refresh instead of full rescan
2. **Integrate runtime-data-sync statechart** (Issue 3 above)
3. **Continue browser testing** - verify symbol-list, source, and var-value widgets work end-to-end
4. **Monitor server stability** - confirm fingerprint polling no longer causes rescan storms

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
