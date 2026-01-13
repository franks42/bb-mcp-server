# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.9

---

## Current State

Code browser with synced atoms, accumulated state, reactive auto-init, and live file watching. **All phases complete!**

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | **COMPLETE** |
| 1.5-Auto | Reactive auto-initialization | **COMPLETE** |
| 1.5-Watch | Live file watching | **COMPLETE** |

---

## Phase 1.5-Watch (Live File Watching) - COMPLETE

**Goal:** Edit file → browser updates automatically

### Existing Infrastructure (Already Working!)

1. **File watcher** - `modules/clojure-lsp/.../watcher.clj`
   - Uses pod-babashka-fswatcher
   - Watches .clj/.cljs/.cljc/.edn files
   - Calls `client/notify-did-change!` on file changes

2. **clojure-lsp notifications** - `modules/clojure-lsp/.../client.clj:112`
   ```clojure
   (defn- handle-notification!
     [{:keys [method params]}]
     (case method
       "textDocument/publishDiagnostics"
       (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
       nil))  ;; <-- HOOK POINT: Add callback here
   ```

3. **Synced atom** - Already pushing updates to browser
   ```clojure
   {:symbols-by-ns {"ns.a" [...] "ns.b" [...]}}  ;; Accumulated
   {:source-by-var {"ns.a/foo" {...} ...}}       ;; Accumulated
   ```

### Implementation Approach

```
File saved → watcher → clojure-lsp → publishDiagnostics
                                            ↓
                                    callback triggered
                                            ↓
                          invalidate [:symbols-by-ns "ns.a"]
                                            ↓
                                    re-fetch symbols
                                            ↓
                                  swap! synced atom
                                            ↓
                               Browser auto-updates!
```

**Option A: Callback in clojure-lsp client** (similar to on-connect callbacks)
```clojure
;; In client.clj - add callback registry
(defonce !on-diagnostics-callbacks (atom {}))

(defn register-on-diagnostics! [key callback-fn] ...)

;; In handle-notification!
"textDocument/publishDiagnostics"
(do
  (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
  (run-diagnostics-callbacks! (:uri params)))  ;; NEW
```

**Option B: Add watcher in code-browser** (poll-based, simpler)

### Key Files to Modify

1. `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/client.clj`
   - Add callback registry for `publishDiagnostics`
   - Call callbacks in `handle-notification!`

2. `modules/sente-browser/src/sente_browser/code_browser.clj`
   - Register callback to invalidate cached symbols/source
   - Re-fetch affected namespace data

### URI → Namespace Mapping

The callback receives a file URI like `file:///path/to/ns_a.clj`. Need to:
1. Convert to namespace (use clojure-lsp `textDocument/documentSymbol` or parse file)
2. Or: invalidate ALL cached data for that file path

### Implementation Complete

**Key Features:**
1. **Notification callbacks** - `lsp-client/on-notification!` for `publishDiagnostics`
2. **File watcher auto-start** - Starts automatically with clojure-lsp
3. **Cache invalidation** - Symbols and source cached by ns/var
4. **Reactive debounce** - Waits for clojure-lsp "quiet period" (500ms) instead of fixed delay
5. **New file detection** - Namespace not in list → refresh namespaces
6. **Deleted file detection** - File no longer exists → refresh namespaces
7. **Jitter fix** - Single atomic `swap!` for cache invalidation (prevents UI flicker)

**Debounce pattern:** Each `publishDiagnostics` resets a timer. Re-fetch only fires when
clojure-lsp goes quiet for 500ms, indicating indexing is complete.

**Also fixed:** `!` character base64 encoding in `mcp_client.clj` (prevents JSON escaping issues)

### Design Doc Reference

See `docs/design/atom-sync-design.md` lines 94-116 for complete flow diagram.

---

## Quick Resume

```bash
# Start server
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Open browser - auto-init happens on first connect!
open http://localhost:8091

# Run tests
bb test:atom-sync
bb lint && bb format
```

**Note:** Code browser and clojure-lsp now auto-initialize reactively when the first browser connects.

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│ Browser connects                                                  │
│     ↓                                                            │
│ atom-sync/on-browser-connected!                                   │
│     ↓                                                            │
│ On-connect callbacks run (just-in-time registration)             │
│     ↓                                                            │
│ code-browser/enable! called                                       │
│     ↓                                                            │
│ clojure-lsp auto-starts (ensure-lsp-initialized!)                │
│     ↓                                                            │
│ Synced atom pushed to browser (any seq accepted for path=[])     │
│     ↓                                                            │
│ User clicks namespace → symbols fetched → accumulated in atom    │
│     ↓                                                            │
│ atom-sync pushes incremental updates                             │
│     ↓                                                            │
│ Browser Reagent re-renders automatically                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key APIs

| Module | Function | Purpose |
|--------|----------|---------|
| `atom-sync.server` | `register-on-connect!` | Run callback when browser connects |
| `atom-sync.server` | `on-browser-connected!` | Trigger callbacks + initial sync |
| `atom-sync.core` | `register-synced-atom!` | Register atom for sync |
| `bootstrap` (browser) | `get-synced-atom` | Get Reagent atom by key |

---

## Key Documentation

| Doc | Purpose |
|-----|---------|
| `docs/design/atom-sync-design.md` | Sync architecture, Phase 1.5-Watch diagram |
| `IMPLEMENTATION_PLAN.md` | Task checklists |
| `modules/atom-sync/README.md` | Atom-sync API reference |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Browser dev setup |

---

## Recent Commits

```
fa9aec1 feat(code-browser): Add reactive auto-initialization (Phase 1.5-Auto)
fec744e feat(code-browser): Implement accumulated state (Phase 1.5-Acc)
67526b3 feat(code-browser): Migrate to synced atoms (Phase 1.5-Pre)
```

---

## Session Notes

- **Phase 1.5-Watch complete** - File save → cache invalidate → re-fetch → browser updates
- **Reactive auto-init** - Code browser and clojure-lsp auto-initialize on first browser connect
- **On-connect callbacks** - `atom-sync.server/register-on-connect!` enables just-in-time init
- **Synced atom access** - Browser: `(bootstrap/get-synced-atom :code-browser)`
- **Sync fix applied** - Full state replace (path []) accepts any seq (fixed infinite loop)
- **New/deleted file detection** - Namespace list auto-refreshes on file create/delete

**Known quirks (deferred to future phases):**
- `declare` vs `def` both show as `:variable` (LSP reports both as kind-13)

---

## Browser MCP Tools

Playwright MCP and Chrome DevTools MCP are configured for browser automation.

```bash
# Take snapshot, click, navigate, etc.
mcp__chrome-devtools__take_snapshot
mcp__chrome-devtools__click
mcp__playwright__browser_snapshot
```
