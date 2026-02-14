# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-14
**Version:** v1.31.0-dev (in progress)
**Focus:** 🐛 Code Browser v2 live polling - architectural issues discovered

---

## Current Work (2026-02-14)

### Fixed: dispatch-event Unbound Bug ✅

**Problem:** `code-browser.handlers/dispatch-event` was unbound, blocking all event handling.

**Root Cause:** TWO parenthesis errors in `handlers.clj`:
- Line 577: `rescan-nrepl-sources!` missing one closing paren (had 6, needed 7)
- Line 711: `dispatch-event` extra closing paren (had 4, needed 3)
- File was balanced but functions appeared "inline" inside previous function
- clj-kondo warned "inline def" - the key clue we should have heeded

**Fix:** Corrected both paren errors. Server restart confirms dispatch-event is now bound and functional.

**Commit:** Already committed in previous session.

### Discovered: Critical Architectural Issues 🚨

While demonstrating live polling, discovered **two major design flaws:**

#### Issue 1: nREPL Source Not Registered

**Problem:** Widget viewing `nrepl://localhost:7888` but that source is NOT in `:sources` map in `!module-state`.

**Evidence:**
```clojure
(keys (:sources @code-browser.handlers/!module-state))
=> ("dir://bb-mcp-server@c5dc1b7" "dir://hasch@ed83bf8")
;; nrepl://localhost:7888 is MISSING!
```

**Impact:** `rescan-nrepl-sources!` loops through `:sources` looking for nREPL sources (line 537), finds none, does nothing. Rescans are called but never execute.

**Telemetry Confirms:**
- `rescan-nrepl-sources-called` events every 3s ✅
- NO `scanning-nrepl-source` events ❌
- NO `resync-complete` events ❌
- NO `batch-introspect-complete` events ❌

**Fix Required:** When nREPL project is added, must register the source in `:sources` map.

#### Issue 2: Wasteful Full Rescans

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

**Statechart Gap:** `runtime_data_sync.cljc` doesn't specify rescan granularity. Need to define incremental rescan events.

#### Issue 3: Fingerprints Not Stored

**Problem:** Every fingerprint check reports "No stored fingerprint" because fingerprints aren't persisted in `!module-state` between checks.

**Impact:** Can't detect REAL changes. Every check looks like a first check, triggering rescans even when nothing changed.

**Telemetry Evidence:**
```
21:47:29.834 DEBUG No stored fingerprint, triggering initial rescan
21:47:27.218 DEBUG No stored fingerprint, triggering initial rescan
21:47:23.809 DEBUG No stored fingerprint, triggering initial rescan
```

**Fix Required:** Store fingerprints after computing them, compare on subsequent checks.

### Statechart vs Implementation Analysis

**Statechart Definition:** (`src/statecharts/machines/runtime_data_sync.cljc`)
- States: `:idle`, `:syncing`, `:ready`, `:resyncing`, `:error`
- Events: `:widget-opened`, `:poll-detected-change`, `:rescan-complete`, etc.
- Critical invariant: "Widgets MUST NOT query while in :syncing/:resyncing"

**Implementation Status:**
- ✅ Widget lifecycle statechart IS integrated (in browser code)
- ❌ Runtime data sync statechart is NOT integrated (documentation only)
- ✅ Polling infrastructure works (3s interval firing)
- ✅ Events route correctly (dispatch-event fixed)
- ✅ Widgets refresh on change signal
- ❌ Rescans don't execute (no nREPL sources registered)
- ❌ Rescans are too broad (full rescan instead of incremental)
- ❌ Change detection broken (fingerprints not stored)

**Timing Analysis:**
```
21:57:50.777 - rescan-nrepl-sources! called    (SERVER)
21:57:50.786 - Widget refresh requested        (BROWSER - 9ms later)
21:57:50.818 - Fetch response received         (BROWSER - 41ms later)
```

Rescan completes before fetch (synchronous), but rescan does nothing because nREPL source not registered.

### Files Needing Changes

**Priority 1 - Make polling functional:**
- `modules/code-browser-v2/src/code_browser/handlers.clj`
  - Register nREPL source when project added
  - Store fingerprints after computing them

**Priority 2 - Fix inefficiency:**
- `modules/code-browser-v2/src/code_browser/handlers.clj`
  - Implement incremental rescan for namespace changes
  - Implement incremental rescan for var value changes
- `modules/code-browser-v2/src/code_browser/sources/nrepl.clj`
  - Add `rescan-namespace` method for single-namespace rescans
- `src/statecharts/machines/runtime_data_sync.cljc`
  - Add granular rescan events to statechart

### Test Status

**Server:**
- `bb test:module code-browser-v2` - All tests passing

**Browser:**
- Polling fires every 3s ✅
- Events reach server ✅
- dispatch-event routes correctly ✅
- Widgets call refresh-widget! ✅
- Fetches succeed ✅
- Data doesn't update (rescans don't run) ❌

**Verification Commands:**
```bash
# Check nREPL source registration
bb nrepl-direct eval "(keys (:sources @code-browser.handlers/!module-state))" -t cb-v2-test

# Check runtime has our test vars
bb nrepl-direct eval "(keys (ns-publics 'user))" -t cb-v2-test

# Check fingerprint storage
bb logs -t cb-v2-test --event symbol-list-first-check --limit 5

# Check if rescans execute
bb logs -t cb-v2-test --event resync-complete --limit 5
```

---

## Recent Commits

**v1.30.0** - Statechart Service/ManyStore adoption (2026-02-13)
- Added FSM runtime introspection
- Live var value display with type-aware rendering
- Statechart detection in var-value widgets

**Uncommitted:**
- Fixed dispatch-event unbound bug (paren errors)
- Added extensive telemetry for debugging polling
- Discovered nREPL source registration issue

---

## Key Project Files

**Core:**
- `modules/code-browser-v2/src/code_browser/handlers.clj` - Server event handlers
- `modules/sente-browser/src/browser/code_browser_v2.cljs` - Browser UI + polling
- `src/statecharts/machines/runtime_data_sync.cljc` - Sync lifecycle statechart

**Runtime Introspection:**
- `modules/code-browser-v2/src/code_browser/sources/nrepl.clj` - nREPL adapter
- `modules/code-browser-v2/src/code_browser/sources/runtime/babashka.clj` - Babashka introspection

**Configuration:**
- `system-cb-v2-test.edn` - Test server config (ports 7888, 8090, 8091)
- `.ports/cb-v2-test.json` - Running server info

---

## Next Session Priorities

1. **Fix nREPL source registration** - Make rescans actually execute
2. **Implement fingerprint storage** - Enable real change detection
3. **Add incremental rescans** - Stop rescanning entire runtime for single var changes
4. **Integrate runtime-data-sync statechart** - Wire up state machine for proper lifecycle management

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
