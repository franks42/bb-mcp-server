# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-13
**Version:** v1.11.8

---

## Current State

Code browser with synced atoms, accumulated state, and reactive auto-init. One phase remaining:

| Phase | Description | Status |
|-------|-------------|--------|
| 1.5-Pre | Migrate to synced atoms | **COMPLETE** |
| 1.5-Acc | Accumulated state (instant back-nav) | **COMPLETE** |
| 1.5-Auto | Reactive auto-initialization | **COMPLETE** |
| 1.5-Watch | Live file watching | Planned |

**Design doc:** `docs/design/atom-sync-design.md` (see Future Enhancements section)

---

## Completed: Phase 1.5-Auto (Reactive Auto-Init)

**Files modified:**
- `modules/atom-sync/src/atom_sync/server.clj` - On-connect callback registry
- `modules/sente-browser/src/sente_browser/code_browser.clj` - Auto-enable on connect
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Sync fix for initial state

**How it works:**
1. Browser connects → `on-browser-connected!` triggers
2. Registered callbacks run (just-in-time atom registration)
3. `code-browser/enable!` called automatically
4. clojure-lsp auto-starts with `user.dir` as project root
5. Browser receives initial sync (any seq accepted for full state)

**Sync fix:** Full state replace (path []) now accepts any seq and resets local tracking.
This handles both initial sync (server at seq N, client at 0) and resync recovery.

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

# Open browser - auto-init happens on first connect!
open http://localhost:8091

# Run tests
bb test:atom-sync
bb lint && bb format
```

**Note:** Code browser and clojure-lsp now auto-initialize reactively when the first browser connects.

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

- **Reactive auto-init** - Code browser and clojure-lsp auto-initialize on first browser connect
- **On-connect callbacks** - `atom-sync.server/register-on-connect!` enables just-in-time init
- **Synced atom access** - Browser: `(bootstrap/get-synced-atom :code-browser)`
- **File watcher** - Start with `clj-watch start` tool or via config
- **Data size** - ~125KB for full ns+vars+source preview (39 ns, 416 vars)
- **First load delay** - clojure-lsp takes a few seconds to init; click Refresh if needed

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
