# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-14
**Version:** v1.13.0

---

## Current State

Code browser with synced atoms, accumulated state, reactive auto-init, live file watching, clj-kondo rich var classification, and **file-order symbol sorting**.

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | **COMPLETE** |
| 1.5-Auto | Reactive auto-initialization | **COMPLETE** |
| 1.5-Watch | Live file watching | **COMPLETE** |
| 1.5A | clj-kondo rich var classification | **COMPLETE** |
| 1.5E.1 | File-order symbol sorting | **COMPLETE** |

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
7589871 feat(code-browser): Add file-order symbol sorting (Phase 1.5E.1)
8c55349 docs: Expand Phase 2 with comprehensive live mode plan
84bd11f docs: Add Phase 1.5E.10 symbol inspector (multi-view details)
f13129a docs: Add Phase 1.5E.9 top-level forms display to plan
c7a4b1c docs: Add defmethod and protocol implementation phases to plan
1a4002f docs: Add Phase 1.5E.5 sync mode toggle to plan
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

- **Phase 1.5A complete** - clj-kondo integration working
- **Exit code fix** - clj-kondo returns exit code 2 for warnings; added `:continue true` to shell
- **Showcase file** - `test/bb_mcp_server/kondo_types_showcase.clj` demonstrates all kind labels
- **Phase 2 expanded** - Comprehensive live mode plan inspired by clj-ns-browser
- **Reference project** - `../clj-ns-browser` for feature inspiration (live introspection)
- **Browser testing** - Use `mcp__chrome-devtools__` or `mcp__playwright__` tools!
- **Port 8091** - Browser UI (not 3000 which is MCP HTTP)

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
