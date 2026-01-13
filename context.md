# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.7

---

## Current State

Code browser working with synced atoms. Two new phases planned:

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | Planned |
| 1.5-Watch | Live file watching | Planned |

**Design doc:** `docs/design/atom-sync-design.md` (see Future Enhancements section)

---

## Next Up: Phase 1.5-Acc (Accumulated State)

**Goal:** Stop discarding fetched data. Keep symbols/source in maps keyed by ns/var.

**Current state shape:**
```clojure
{:symbols [...]}           ;; REPLACED on each ns selection
{:source {...}}            ;; REPLACED on each var selection
```

**Target state shape:**
```clojure
{:symbols-by-ns {"ns.a" [...] "ns.b" [...]}}   ;; ACCUMULATED
{:source-by-var {"ns.a/foo" {...} ...}}        ;; ACCUMULATED
```

**Changes needed:**
- Server: `assoc-in [:symbols-by-ns ns]` instead of `assoc :symbols`
- Browser: `(get-in state [:symbols-by-ns selected-ns])` instead of `(:symbols state)`

**Benefits:** Instant back-navigation, foundation for file-watcher invalidation.

---

## After That: Phase 1.5-Watch (Live File Watching)

**Goal:** Edit file → browser updates automatically.

**Leverages existing infrastructure:**
- File watcher: `modules/clojure-lsp/.../watcher.clj` (already running)
- clojure-lsp sends `textDocument/publishDiagnostics` on file change
- Just need to hook callback into `handle-notification!`

---

## Completed: Phase 1.5-Pre (Synced Atoms)

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj` - Server handlers
- `modules/sente-browser/src/browser/code_browser.cljs` - Browser UI
- `modules/atom-sync/src/atom_sync/core.clj` - Bug fix (seq-per-op)

**Bug fix:** When swap! changes multiple keys, each op now gets unique seq.

---

## Quick Resume

```bash
# Start server
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Browser URL
http://localhost:8091

# Initialize clojure-lsp (required for code browser)
bb mcp call clojure-lsp.clj-init '{}' --mcp code-browser-dev

# Run tests
bb test:atom-sync
bb lint && bb format
```

---

## Key Documentation

| Doc | Purpose |
|-----|---------|
| `docs/design/atom-sync-design.md` | Sync architecture, future phases |
| `IMPLEMENTATION_PLAN.md` | Task checklists |
| `modules/atom-sync/README.md` | Atom-sync usage |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Browser dev setup |

---

## Session Notes

- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **Synced atom access** - Browser: `(bootstrap/get-synced-atom :code-browser)`
- **File watcher** - Start with `clj-watch start` tool or via config
- **Data size** - ~125KB for full ns+vars+source preview (39 ns, 416 vars)

---

## Recent Commits

```
5b0777c docs: Add Phase 1.5-Acc and 1.5-Watch to design and plan
67526b3 feat(code-browser): Migrate to synced atoms (Phase 1.5-Pre)
06f0bfd docs: Update atom-sync design with seq-per-op fix
bd65589 fix(atom-sync): Assign unique seq per op in multi-key swap
```

---

## Browser MCP Tools

Playwright MCP and Chrome DevTools MCP are configured for browser automation.

```
# Take snapshot, click, navigate, etc.
mcp__chrome-devtools__take_snapshot
mcp__chrome-devtools__click
mcp__chrome-devtools__navigate_page
```
