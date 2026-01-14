# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-14
**Version:** v1.13.0

---

## Current State

Code browser with synced atoms, accumulated state, reactive auto-init, live file watching, clj-kondo rich var classification, **defmethod display**, **top-level forms**, and **server epoch detection**.

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | **COMPLETE** |
| 1.5-Auto | Reactive auto-initialization | **COMPLETE** |
| 1.5-Watch | Live file watching | **COMPLETE** |
| 1.5A | clj-kondo rich var classification | **COMPLETE** |
| 1.5E.1 | File-order symbol sorting | **COMPLETE** |
| 1.5E.2 | Git status display | **COMPLETE** |
| 1.5E.6 | Multimethod implementations (defmethod) | **COMPLETE** |
| 1.5E.9 | Top-level forms display | **COMPLETE** |
| 1.5-Epoch | Server epoch for stale data detection | **COMPLETE** |

---

## Phase 1.5A Summary (2026-01-14)

**Goal:** Replace LSP's generic 3 kinds with clj-kondo's rich `:defined-by` classification.

**Implementation:**
- `analyze-file-with-kondo` - shells out to clj-kondo for on-demand analysis
- `defined-by->label` mapping - converts kondo symbols to human-readable labels
- Falls back to LSP if kondo analysis fails

**New kind labels:**
| Label | From |
|-------|------|
| `defonce` | `clojure.core/defonce` |
| `private-fn` | `clojure.core/defn-` |
| `macro` | `clojure.core/defmacro` |
| `multimethod` | `clojure.core/defmulti` |
| `method` | `clojure.core/defmethod` |
| `protocol` | `clojure.core/defprotocol` |
| `deftype` | `clojure.core/deftype` |
| `defrecord` | `clojure.core/defrecord` |
| `test` | `clojure.test/deftest` |
| `declare` | `clojure.core/declare` |

**File:** `modules/sente-browser/src/sente_browser/code_browser.clj` lines 260-350

---

## Recent Commits

```
03b46d5 fix(code-browser): Show complete defmethod source code
31c70df feat(code-browser): Add defmethod and top-level forms display
54a92e0 docs: Mark Phase 1.5E.2 git status display complete
37fd04d feat(code-browser): Add git status display (Phase 1.5E.2)
6faa86f docs: Mark Phase 1.5E.1 file-order sorting complete
```

---

## Quick Resume

```bash
# Check if server running
bb server:list

# Start server if needed
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Open browser - auto-init happens on first connect!
open http://localhost:8091

# Run tests
bb test:atom-sync
bb lint && bb format
```

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
│ User clicks namespace → clj-kondo analysis → symbols fetched     │
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
| `atom-sync.core` | `get-server-epoch` | Get current epoch (changes on restart) |
| `code-browser` | `analyze-file-with-kondo` | Rich var classification |
| `bootstrap` (browser) | `get-synced-atom` | Get Reagent atom by key |

---

## Key Documentation

| Doc | Purpose |
|-----|---------|
| `docs/design/atom-sync-design.md` | Sync architecture, Phase 1.5-Watch diagram |
| `docs/design/static-code-analysis.md` | Phase 1.5A kondo design |
| `IMPLEMENTATION_PLAN.md` | Task checklists |
| `modules/atom-sync/README.md` | Atom-sync API reference |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Browser dev setup |

---

## Session Notes

- **Server epoch complete** - Server generates unique epoch on start; browser detects restart and resets local sync state
- **Phase 1.5E.6 complete** - defmethod implementations show as `my-multimethod :dispatch-val` with kind `method`
- **Phase 1.5E.9 complete** - top-level forms like `(comment ...)` shown only in file-order view
- **Browser-side filtering** - top-level forms filtered in browser (not server) to preserve data across sort mode toggles
- **Defmethod source fix** - kondo's `:end-row` only covers name line; `find-form-end-line` scans for balanced parens
- **Phase 1.5A complete** - clj-kondo integration working
- **Exit code fix** - clj-kondo returns exit code 2 for warnings; added `:continue true` to shell
- **Showcase file** - `test/bb_mcp_server/kondo_types_showcase.clj` demonstrates all kind labels
- **Phase 2 expanded** - Comprehensive live mode plan inspired by clj-ns-browser
- **Reference project** - `../clj-ns-browser` for feature inspiration (live introspection)
- **Browser testing** - Use `mcp__chrome-devtools__` or `mcp__playwright__` tools!
- **Port 8091** - Browser UI (not 3000 which is MCP HTTP)
- **Future idea** - Pureness indicator for symbols (pure, side-effects, innocent-side-effects)

---

## Browser MCP Tools

Playwright MCP and Chrome DevTools MCP are configured for browser automation.

```bash
# Navigate and snapshot
mcp__chrome-devtools__navigate_page
mcp__chrome-devtools__take_snapshot
mcp__playwright__browser_navigate
mcp__playwright__browser_snapshot

# Interact
mcp__chrome-devtools__click
mcp__chrome-devtools__fill
mcp__playwright__browser_click
```
