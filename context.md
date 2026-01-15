# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-15
**Version:** v1.14.0

---

## Current State

Code browser with synced atoms, accumulated state, reactive auto-init, live file watching, clj-kondo rich var classification, **defmethod display**, **top-level forms**, **server epoch detection**, and **aliases/refers panel with shadow warnings**.

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
| 1.5E.7 | Protocol implementations (defrecord/deftype) | **COMPLETE** |
| 1.5E.9 | Top-level forms display | **COMPLETE** |
| 1.5-Epoch | Server epoch for stale data detection | **COMPLETE** |
| 1.5E.12 | Source code highlighting (multi-line) | **COMPLETE** |
| 1.5E.19 | NS-level dependencies in Deps tab | **COMPLETE** |
| 1.5E.20 | Aliases & Refers panel with shadow detection | **COMPLETE** |

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
bb6494e docs: Add Phase 1.5E.12 source code highlighting to roadmap
62ae470 fix(code-browser): Symbol filter and protocol impl source display
05c6f87 fix(code-browser): Show full protocol for protocol methods
983ad9a feat(code-browser): Add protocol implementation display (Phase 1.5E.7)
e9ef9fd feat(atom-sync): Add server epoch for stale data detection
```

---

## What's Next on Roadmap

| Priority | Phase | Description | Notes |
|----------|-------|-------------|-------|
| 1 | **1.5E.10** | Symbol inspector | Multi-view: Source, Doc, Examples, Deps |
| 2 | **1.5E.3** | Project selector | Browse multiple projects |
| 3 | **Phase 2** | Live Mode | nREPL introspection (inspired by clj-ns-browser) |

**Phase 1.5E.10 (Symbol inspector)** is the next significant feature.

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

## Handoff Notes (for next Claude session)

**Server may be running** on ports 3000 (MCP), 8090 (WebSocket), 8091 (Browser UI). Check with `bb server:list`.

**Code browser is stable** - all major features working:
- Synced atoms, accumulated state, live file watching
- clj-kondo classification (64 var types in showcase)
- Protocol/defmethod display with full source
- Symbol filter works correctly
- **Source code highlighting** with multi-line support (Phase 1.5E.12)
- **Aliases & Refers panel** with shadow detection (Phase 1.5E.20)

**Next feature: Phase 1.5E.10** - Symbol inspector (multi-view details)

**Key gotchas:**
- React keys must be unique (use `name-line` not just `name`)
- Server-side code changes require server restart
- Browser `.cljs` can be hot-reloaded via nREPL
- clj-kondo exit code 2 = warnings (use `:continue true` in shell)
- clj-kondo doesn't expose `:refer-clojure :exclude` in analysis output

---

## Session Notes

- **Symbol filter fix** - React key `(:name sym)` wasn't unique; changed to `(str name "-" line)`
- **Protocol impl source** - Shows full defrecord/deftype, not just method lines
- **Showcase file** - `test/bb_mcp_server/kondo_types_showcase.clj` has 64 vars
- **Browser testing** - Use `mcp__chrome-devtools__` or `mcp__playwright__` tools
- **Port 8091** - Browser UI (not 3000 which is MCP HTTP)
- **Reference project** - `../clj-ns-browser` for Phase 2 inspiration

### Session 2026-01-15: Aliases & Refers Panel

**Implemented Phase 1.5E.19 & 1.5E.20:**
- NS-level dependencies shown in Deps tab when viewing namespace symbol
- Aliases panel shows `alias → namespace` mappings (right arrow)
- Refers derived from var-usages with `:refer true` flag (not from namespace-usages)
- Refers shown as `symbol ← namespace` (left arrow - "comes from")
- Shadow detection: refers that shadow clojure.core vars get yellow highlight + ⚠

**Key learnings:**
- clj-kondo's `:namespace-usages` doesn't include `:refer` info
- Refers must be derived from `:var-usages` where `:refer true`
- clojure.core vars don't have `:refer true` - only explicit refers do
- `:refer-clojure :exclude` is NOT exposed in kondo analysis output
- `:exclude` in `:require` is silently ignored (not a valid option)
- For shadow detection, use `(keys (ns-publics 'clojure.core))` dynamically - never hardcode

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj` - Server-side refers extraction + shadow detection
- `modules/sente-browser/src/browser/code_browser.cljs` - Browser aliases panel UI
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - CSS for panel
- `test/bb_mcp_server/kondo_types_showcase.clj` - Added `replace` refer for shadow test

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
