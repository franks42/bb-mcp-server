# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.7

---

## Completed: Phase 1.5-Pre - Migrate Code Browser to Synced Atoms

**Goal:** Refactor code-browser from request/response messaging to reactive synced atoms.

**Status:** COMPLETE ✓ (tested in browser)

### What Changed

**Server-side (`modules/sente-browser/src/sente_browser/code_browser.clj`):**
- Added `!code-browser-state` atom with shape matching target state
- Registered atom via `atom-sync/register-synced-atom!` on `enable!`
- All handlers now `swap!` the synced atom (auto-pushes to browsers)
- Added `handle-clear-error` for browser dismiss action

**Browser-side (`modules/sente-browser/src/browser/code_browser.cljs`):**
- Added `get-server-state` fn to access synced atom from bootstrap
- Added `!ui-state` atom for local-only filter state
- All components now read from synced atom
- Removed legacy `!state` atom and event handlers
- Removed `register-handler!` (synced atoms handled by bootstrap)
- Error dismiss now sends `:code-browser/clear-error` event

### State Shape (Final)

```clojure
;; Server synced atom
{:namespaces ["ns.a" "ns.b" ...]
 :selected-ns "ns.a"
 :symbols [{:name "foo" :kind :function :line 10 ...}]
 :selected-symbol "foo"
 :source {:code "..." :file "..." :start-line 1 :end-line 20}
 :loading? false
 :error nil}

;; Browser local UI state
{:ns-filter ""
 :symbol-filter ""}
```

### Files Modified

| File | Changes |
|------|---------|
| `modules/sente-browser/src/sente_browser/code_browser.clj` | Added synced atom, updated handlers |
| `modules/sente-browser/src/browser/code_browser.cljs` | Migrated to synced atom, removed legacy code |
| `modules/atom-sync/src/atom_sync/core.clj` | Fixed seq-per-op bug in `on-atom-change` |

### Bug Fix: Seq-Per-Op (2026-01-12)

**Problem:** When `swap!` changed multiple keys, all ops got the same seq. Browser applied first op, rejected rest as "stale".

**Fix:** Increment seq per op, not per swap:
```clojure
;; in on-atom-change
(map-indexed
  (fn [idx [op-type op-data]]
    [op-type (assoc op-data :seq (+ start-seq idx 1))])
  base-ops)
```

---

## Completed: Atom Sync Module (Phase 1.4)

**Module:** `modules/atom-sync/`

- `core.clj` - Transport-independent sync logic
- `server.clj` - sente-lite integration
- 29 tests, 130 assertions - all passing
- Full docs: `modules/atom-sync/README.md`

---

## Quick Resume

```bash
# Start server for browser development
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# After editing Clojure files
bb lint-fix <file>

# List running servers
bb server:list

# Stop server
bb server:stop code-browser-dev

# Run atom-sync tests
bb test:atom-sync
```

---

## Key Documentation

| Doc | When to Read |
|-----|--------------|
| `IMPLEMENTATION_PLAN.md` | Task tracking |
| `modules/atom-sync/README.md` | Atom-sync usage |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Before Scittle/browser work |
| `docs/bb-tasks-reference.md` | Before writing curl/bash commands |

---

## Session Notes

Things not in CLAUDE.md or other docs:

- **sente-lite on-message callback** - MUST return `nil`; truthy values get echoed to client
- **Scittle symbol order** - Symbols must be defined before use (no forward refs)
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable
- **Reagent Form-3 gotcha** - Values in outer `let` are captured at mount time
- **lint-fix workflow** - Use `bb lint-fix <file>` after editing Clojure
- **Playwright/DevTools MCP** - Browser automation tools available for testing
- **trove for logging** - Use `(log/log! {:level :info :id ::my-id :msg "..." :data {...}})`
- **Synced atom access** - Browser uses `(bootstrap/get-synced-atom :key)` for Reagent atom

---

## Recent Commits

```
2811e5d docs: Update context.md for Phase 1.5-Pre handoff
763f511 docs: Update atom-sync docs and add README user guide
5628e4b feat: Add module-specific bb test tasks
ad1b92f feat(atom-sync): Add server integration layer (Phase 1.4B)
3c532d4 feat(atom-sync): Add seq validation and heartbeat mechanism
```

---

## Browser MCP Tools Available

**Playwright MCP** and **Chrome DevTools MCP** are configured.

```
# Playwright tools: mcp__playwright__browser_navigate, browser_click, browser_snapshot, etc.
# Chrome DevTools: mcp__chrome-devtools__navigate_page, click, take_snapshot, etc.
```

Use these for browser automation instead of writing JavaScript test files.
