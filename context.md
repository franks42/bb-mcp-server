# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.7

---

## Current State

Code browser with synced atoms and accumulated state. One phase remaining:

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | **COMPLETE** |
| 1.5-Watch | Live file watching | Planned |

**Design doc:** `docs/design/atom-sync-design.md` (see Future Enhancements section)

---

## Completed: Phase 1.5-Acc (Accumulated State)

**Commit:** `fec744e`

**State shape:**
```clojure
{:symbols-by-ns {"ns.a" [...] "ns.b" [...]}}   ;; ACCUMULATED
{:source-by-var {"ns.a/foo" {...} ...}}        ;; ACCUMULATED
```

**Result:** Instant back-navigation - click ns.a → ns.b → ns.a shows cached data.

---

## Next Up: Phase 1.5-Watch (Live File Watching)

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
fec744e feat(code-browser): Implement accumulated state (Phase 1.5-Acc)
7148200 docs: Update context.md with Phase 1.5-Acc/Watch roadmap
5b0777c docs: Add Phase 1.5-Acc and 1.5-Watch to design and plan
67526b3 feat(code-browser): Migrate to synced atoms (Phase 1.5-Pre)
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
