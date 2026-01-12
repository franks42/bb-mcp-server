# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-12
**Version:** v1.11.2

---

## Current Work: Atom Sync Module (Phase 1.4)

**Goal:** One-way sync of Clojure atoms from server (Babashka) to browser (Scittle) over sente-lite WebSocket.

**Status:** Phase 1.4B complete (server integration). Ready for Phase 1.4C (browser-side).

### Essential Docs for This Work

| Doc | Purpose |
|-----|---------|
| `docs/design/atom-sync-design.md` | **PRIMARY** - Full design, protocol, code examples |
| `IMPLEMENTATION_PLAN.md` (Phase 1.4) | Task breakdown with status tracking |
| `modules/sente-browser/` | Existing WebSocket infrastructure |

### Architecture Summary

```
core.clj (transport-independent)     server.clj (thin wrapper)
┌────────────────────────────────┐   ┌─────────────────────────┐
│ deep-diff->ops                 │   │ wires core to sente-lite│
│ apply-sync-op                  │──▶│ broadcast callback      │
│ apply-sync-op-validated        │   │ ~20 lines               │
│ register/unregister-synced-atom│   └─────────────────────────┘
│ subscribe!/unsubscribe!        │
│ seq validation, heartbeat      │
└────────────────────────────────┘
```

### Completed (Phase 1.4A + 1.4B)

**Phase 1.4A (core):**
- `modules/atom-sync/` module created with `core.clj`
- `deep-diff->ops` - generates sync ops from old/new values
- `apply-sync-op` - applies ops to target atoms
- `apply-sync-op-validated` - seq validation returns :applied/:stale/:gap
- Registry with `register-synced-atom!` / `unregister-synced-atom!`
- Subscriber system with `subscribe!` / `unsubscribe!`
- Heartbeat: `get-server-seq`, `check-sync-status`, `handle-heartbeat`

**Phase 1.4B (server):**
- `server.clj` - thin wrapper wiring core to sente-lite transport
- `init!` / `stop!` - lifecycle management
- `on-browser-connected!` - push all atoms to new browsers
- `dispatch-event` - handles :sync/resync-request and :sync/heartbeat
- Wired into sente-browser.server (init, promote-to-validated, message dispatch)
- 29 Babashka tests, 130 assertions - all passing
- 21 Scittle browser tests - all passing

### Next Steps (Phase 1.4C)

1. Update bootstrap.clj: add `!sync-state` for seq tracking
2. Update `on-sync-message` to handle [:sync/op {...}] format
3. Implement `apply-sync-op` with seq validation in browser
4. Implement gap detection → request resync

### Key Design Decisions Made

- One-way sync first (server → browser)
- Shared atoms (all browsers see same value)
- Seq numbers in registry, not atom value
- Full sync for Phase 1 (path [])
- Vectors replaced wholesale (sufficient for code-browser)
- `differ` library investigated but our `deep-diff->ops` (~25 lines) is simpler

### Message Protocol

```clojure
[:sync/op {:key   :my-atom
           :seq   42
           :op    :assoc-in
           :path  []           ; [] = full replace
           :value {...}}]
```

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
```

---

## Key Documentation

| Doc | When to Read |
|-----|--------------|
| `docs/design/atom-sync-design.md` | For atom-sync implementation |
| `docs/bb-tasks-reference.md` | Before writing curl/bash commands |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Before Scittle/browser work |
| `docs/agent-delegation-guide.md` | For multi-file tasks with subagents |
| `IMPLEMENTATION_PLAN.md` | For task tracking and planning |

---

## Session Notes

Things not in CLAUDE.md or other docs:

- **64KB buffer boundary** - macOS pipe buffer is 64KB; `BufferedReader.read()` may return partial data
- **sente-lite on-message callback** - MUST return `nil`; truthy values get echoed to client
- **Scittle reagent.dom** - Available via nREPL eval with `(require '[reagent.dom :as rdom])`
- **nrepl-eval-local-file** - Correct tool for loading .cljs into Scittle
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable
- **Reagent Form-3 gotcha** - Values in outer `let` are captured at mount time, not updated
- **CLI vs MCP** - CLI wrappers (`bb mcp`, `bb nrepl`) are often easier than native MCP tools
- **Playwright/DevTools MCP** - Browser automation tools available for testing
- **Agent delegation** - Use Task tool with subagents for multi-file work
- **Checkpoints in todos** - Always include checkpoint tasks in phase plans to survive compaction
- **lint-fix workflow** - Use `bb lint-fix <file>` after editing Clojure (auto-fixes paren errors)
- **parmezan** - Tool that fixes unbalanced parens heuristically; lint-fix uses it automatically
- **differ library** - Fork at jeremyrsellars/differ is SCI-compatible, but our simple diff is sufficient

---

## Recent Commits

```
3c532d4 feat(atom-sync): Add seq validation and heartbeat mechanism
701e239 test: Add Scittle browser test for atom-sync
29f592c feat: Implement atom-sync core module (Phase 1.4A)
1396352 docs: Complete atom-sync design and update implementation plan
f6e4f79 docs: Add static code analysis design and update implementation plan
```

---

## Browser MCP Tools Available

**Playwright MCP** and **Chrome DevTools MCP** are configured.

```
# Playwright tools: mcp__playwright__browser_navigate, browser_click, browser_snapshot, etc.
# Chrome DevTools: mcp__chrome-devtools__navigate_page, click, take_snapshot, etc.
```

Use these for browser automation instead of writing JavaScript test files.
