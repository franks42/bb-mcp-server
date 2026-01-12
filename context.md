# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.5

---

## Current Work: Phase 1.5-Pre - Migrate Code Browser to Synced Atoms

**Goal:** Refactor code-browser from request/response messaging to reactive synced atoms.

**Status:** Ready to start. Depends on atom-sync module (Phase 1.4) which is COMPLETE.

### Essential Docs for This Work

| Doc | Purpose |
|-----|---------|
| `IMPLEMENTATION_PLAN.md` (Phase 1.5-Pre) | **PRIMARY** - Task breakdown with 4 steps |
| `modules/atom-sync/README.md` | Usage guide for atom-sync API |
| `docs/design/atom-sync-design.md` | Full design, protocol details |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Browser dev setup |

### Migration Steps (from IMPLEMENTATION_PLAN.md)

**Step 1: Register Synced Atom (Parallel)**
- Server: Create `!code-browser-state` atom
- Server: Call `(atom-sync/register-synced-atom! :code-browser !code-browser-state)`
- Server: Update atom IN ADDITION to sending response events (parallel mode)
- Verify: Browser receives `[:sync/op {:key :code-browser ...}]`

**Step 2: Browser Reads from Synced Atom**
- Browser: Get synced atom via `(get-synced-atom :code-browser)`
- Browser: UI components deref synced atom instead of local state
- Verify: UI updates when server pushes
- Keep old event handlers temporarily (become no-ops)

**Step 3: Remove Old Messaging**
- Server: Stop sending response events (only atom updates)
- Browser: Remove old event handlers
- Clean up dead code

**Step 4: Browser → Server Actions**
- Browser: User clicks → send `[:code-browser/select-ns {:ns "..."}]`
- Server: Handle action, `(swap! !code-browser-state ...)`
- Watcher auto-pushes update to all browsers

### Target State Shape

```clojure
{:namespaces ["ns.a" "ns.b" ...]
 :selected-ns "ns.a"
 :vars [{:name "foo" :kind :function :line 10 ...}]
 :selected-var "foo"
 :source {:code "..." :file "..." :start-line 1 :end-line 20}}
```

### Key Files to Modify

**Server-side:**
- `modules/sente-browser/src/sente_browser/server.clj` - Add code-browser handlers
- May need new file for code-browser server logic

**Browser-side:**
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Code browser UI

### Atom-Sync API (Quick Reference)

```clojure
;; Server-side (require '[atom-sync.core :as sync])
(def !state (atom {:count 0}))
(sync/register-synced-atom! :my-key !state)
(swap! !state assoc :count 1)  ; auto-syncs to browsers
(sync/unregister-synced-atom! :my-key)

;; Browser-side (in Scittle)
(def state (get-synced-atom :my-key))  ; Reagent atom
@state  ; => {:count 1}
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
| `IMPLEMENTATION_PLAN.md` | Task tracking - Phase 1.5-Pre section |
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

---

## Recent Commits

```
763f511 docs: Update atom-sync docs and add README user guide
5628e4b feat: Add module-specific bb test tasks
ad1b92f feat(atom-sync): Add server integration layer (Phase 1.4B)
3c532d4 feat(atom-sync): Add seq validation and heartbeat mechanism
29f592c feat: Implement atom-sync core module (Phase 1.4A)
```

---

## Browser MCP Tools Available

**Playwright MCP** and **Chrome DevTools MCP** are configured.

```
# Playwright tools: mcp__playwright__browser_navigate, browser_click, browser_snapshot, etc.
# Chrome DevTools: mcp__chrome-devtools__navigate_page, click, take_snapshot, etc.
```

Use these for browser automation instead of writing JavaScript test files.
