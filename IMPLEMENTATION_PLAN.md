# bb-mcp-server Implementation Plan

**Status:** Code Browser Phase 1.5 Complete (synced atoms, file watching, epoch detection) + Enhancements In Progress
**Version:** v1.13.0
**Last Updated:** 2026-01-14

---

## Current State

Production-ready MCP server with:
- MCP spec 2025-03-26 compliant
- Stdio and HTTP transports
- Dynamic module system
- 16+ tool modules
- E2E test suite
- Scittle browser nREPL with stable session identity

---

## Dev Environment Quick Reference

**Essential commands:**
```bash
# Run tests (ALWAYS before committing)
bb test:modules          # All module tests
bb lint                  # clj-kondo (must be 0 errors, 0 warnings)
bb format                # cljfmt check

# Start servers
bb server                           # Stdio (Claude Desktop)
bb server --http                    # HTTP on port 3000
bb server --http --config <file>    # Custom config
bb server:stop [port]               # Stop server on port

# Scittle browser dev
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev
# Then open http://localhost:8091

# Code browser dev (when config exists)
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# nREPL CLI
bb nrepl list --mcp <nickname>      # List connections
bb nrepl eval "<code>" --mcp <nick> # Eval code
bb nrepl connect <port>             # Connect to external nREPL

# MCP CLI
bb mcp servers                      # List running servers
bb mcp tools --mcp <nickname>       # List tools
bb mcp call <tool> '<json>' --mcp <nick>  # Call tool
```

**Key files:**
- `bb.edn` - All tasks, paths, deps
- `system.edn` - Default module config
- `bb-scittle-dev-system.edn` - Scittle browser dev config
- `CLAUDE.md` - AI instructions (read first)
- `context.md` - Session state (read at start)
- `docs/design/*.md` - Design documents

**Required reading for Scittle browser development:**
- `docs/SCITTLE_DEV_ENVIRONMENT.md` - Step-by-step setup guide (READ THIS FIRST!)
  - How to start server, connect browser, find nicknames
  - Tool usage (nrepl-eval-local-file vs nrepl-eval)
  - Common pitfalls and troubleshooting

**Verification workflow (run before every commit):**
```bash
bb lint && bb format && bb test:modules
```

---

## Phase Template (with Checkpoints)

Use this structure when planning new phases to prevent context loss:

```
### Phase N: [Feature Name]

| Task | Description | Status |
|------|-------------|--------|
| N.0 | **Checkpoint:** Document starting state in context.md | Pending |
| N.1 | Research/explore existing code | Pending |
| N.2 | Implement core logic | Pending |
| N.3 | **Checkpoint:** Update context.md before multi-file changes | Pending |
| N.4 | Integration/wiring | Pending |
| N.5 | Tests + verification | Pending |
| N.6 | **Checkpoint:** Final state + learnings in context.md | Pending |
```

**Checkpoint contents (in context.md):**
- Starting state: branch, relevant files, what exists
- Mid-phase: approach chosen, files being modified, key decisions
- Final: results, test status, session notes for next time

---

## Active Work: Scittle Code Browser

**Goal:** Browser-based code browser embedded in Scittle dev environment.

**Design doc:** `docs/design/bb-scittle-code-browser-design.md`

### Phase 0: Dev Infrastructure ✅ Complete

| Task | Description | Status |
|------|-------------|--------|
| 0.1 | Create `bb-code-browser-dev-system.edn` config | ✅ |
| 0.2 | Update bootstrap HTML with preloaded scripts | ✅ |
| 0.3 | Create `scittle-cm6` namespace (reusable CM6 wrapper) | ✅ |
| 0.4 | Implement bidirectional atom sync (bootstrap bundle) | ✅ |
| 0.5 | Add error boundary for safe REPL development | ✅ |
| 0.6 | Test: load UI code via nREPL, iterate live | ✅ |

**Files created:**
- `bb-code-browser-dev-system.edn` - Dev config
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - code-browser HTML
- `modules/sente-browser/src/browser/scittle_cm6.cljs` - CM6 wrapper

**Start code browser:**
```bash
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
# Open http://localhost:8091
```

**UI Loading Pattern:**
```clojure
;; From bb REPL - load UI code via nREPL
(browser-eval! browser-1 '(require '[code-browser.core :as cb]))
(browser-eval! browser-1 '(cb/mount!))

;; Iterate live without page refresh
(browser-eval! browser-1 '(swap! cb/!layout assoc :ns-width "25%"))
```

### Phase 1: Static Browsing ✅ Complete (v1.10.1)

| Task | Description | Status |
|------|-------------|--------|
| 1.1 | Namespace list panel (Reagent) | ✅ 37 namespaces |
| 1.2 | Vars list panel | ✅ Click ns → symbols |
| 1.3 | Source viewer (CM6, read-only) | ✅ Fira Code font |
| 1.4 | Filter components (wildcard/regex) | Pending |
| 1.5 | Wire clojure-lsp as data source | ✅ |

**CM6 Fix (commit 28555f2):** EditorState must be imported separately from `@codemirror/state`, not from `codemirror` meta-package. Use `?deps=` for version pinning.

### Phase 1.5: Enhanced Var Classification (clj-kondo)

Replace LSP's 3 generic kinds with kondo's rich `:defined-by` classification.

**Design doc:** `docs/design/static-code-analysis.md`

### Phase 1.4: Synced Atoms Module (One-Way Sync)

**Design doc:** `docs/design/atom-sync-design.md`

**Scope:** One-way sync (server → browser). Server owns atoms, browser observes.
Shared atoms (all browsers see same value). Full state sync with sequence numbers.

**New module:** `modules/atom-sync/`

#### Phase 1.4A: Core Sync Logic (Transport-Independent)

Test sync logic between two atoms in same process - no WebSocket yet.

| Task | Description | Status |
|------|-------------|--------|
| 1.4A.1 | Create `modules/atom-sync/` directory and `module.edn` | **Done** |
| 1.4A.2 | Create `src/atom_sync/core.clj` - transport-independent sync logic | **Done** |
| 1.4A.3 | Implement `deep-diff->ops` - generate sync ops from old/new values | **Done** |
| 1.4A.4 | Implement `apply-sync-op` - apply op to target atom | **Done** |
| 1.4A.5 | Implement `!synced-atoms` registry {:key {:atom ref :seq n :last-value v}} | **Done** |
| 1.4A.6 | Implement `register-synced-atom!` with add-watch | **Done** |
| 1.4A.7 | Implement `unregister-synced-atom!` with remove-watch | **Done** |
| 1.4A.8 | Implement `generate-sync-ops!` - diff + increment seq + return ops | **Done** |
| 1.4A.9 | Implement `!subscribers` registry for push callbacks | **Done** |
| 1.4A.10 | Implement `subscribe!` / `unsubscribe!` - register push callback | **Done** |
| 1.4A.11 | Implement `apply-sync-op-validated` - seq validation returns :applied/:stale/:gap | **Done** |
| 1.4A.12 | Implement `reset-expected-seq!` - reset seq tracking after resync | **Done** |
| 1.4A.13 | Implement `get-server-seq` / `check-sync-status` - server-side sync check | **Done** |
| 1.4A.14 | Implement `handle-heartbeat` - heartbeat response with resync ops | **Done** |

#### Phase 1.4A-Test: Local Sync Testing (No Transport)

| Task | Description | Status |
|------|-------------|--------|
| 1.4A-T.1 | Test: `deep-diff->ops` on flat maps | **Done** |
| 1.4A-T.2 | Test: `deep-diff->ops` on nested maps | **Done** |
| 1.4A-T.3 | Test: `deep-diff->ops` with vector values (wholesale replace) | **Done** |
| 1.4A-T.4 | Test: `apply-sync-op` roundtrip (diff → apply → equal) | **Done** |
| 1.4A-T.5 | Test: Local two-atom sync (source → ops → target) | **Done** |
| 1.4A-T.6 | Test: Seq increments correctly on each change | **Done** |
| 1.4A-T.7 | Test: Subscriber callback receives ops on atom change | **Done** |
| 1.4A-T.8 | Test: Gap detection (seq jump → error/resync signal) | **Done** |
| 1.4A-T.9 | Test: Stale op rejection (old seq ignored) | **Done** |
| 1.4A-T.10 | Test: `apply-sync-op-validated` returns :applied/:stale/:gap | **Done** |
| 1.4A-T.11 | Test: Heartbeat `check-sync-status` and `handle-heartbeat` | **Done** |
| 1.4A-T.12 | Test: Scittle browser compatibility (21 tests) | **Done** |

**Local sync test pattern:**
```clojure
;; Two atoms, same process, no network
(def !source (atom {:count 0}))
(def !target (atom nil))

;; Subscribe target to source
(subscribe! :my-state
  (fn [ops]
    (doseq [op ops]
      (apply-sync-op !target op))))

(register-synced-atom! :my-state !source)

;; Change source → target updates automatically
(swap! !source assoc :count 1)
@!target  ; => {:count 1}
```

#### Phase 1.4B: Server Integration (sente-lite Transport)

Wire core sync logic to sente-lite WebSocket transport.

| Task | Description | Status |
|------|-------------|--------|
| 1.4B.1 | Create `src/atom_sync/server.clj` - sente-lite integration | **Done** |
| 1.4B.2 | Implement `init!` - subscribe to core with broadcast callback | **Done** |
| 1.4B.3 | Implement `on-browser-connected!` - push all atoms to new client | **Done** |
| 1.4B.4 | Wire into `sente-browser.server/promote-to-validated!` | **Done** |
| 1.4B.5 | Add `:sync/resync-request` handler for gap recovery | **Done** |
| 1.4B.6 | Test: register atom, change it, verify browser receives | **Done** |

**Transport layer is thin:**
```clojure
;; server.clj - just wires core to sente-lite
(defn init! []
  (core/subscribe! ::broadcast
    (fn [ops]
      (doseq [op ops]
        (sente-browser.server/broadcast-to-browsers! op)))))
```

#### Phase 1.4C: Browser-Side (Scittle)

| Task | Description | Status |
|------|-------------|--------|
| 1.4C.1 | Update bootstrap.clj: add `!sync-state` for seq tracking | Pending |
| 1.4C.2 | Update `on-sync-message` to handle new [:sync/op {...}] format | Pending |
| 1.4C.3 | Implement `apply-sync-op` with seq validation | Pending |
| 1.4C.4 | Implement gap detection → request resync | Pending |
| 1.4C.5 | Implement stale message rejection (seq < expected) | Pending |

#### Phase 1.4D: Testing & Verification

| Task | Description | Status |
|------|-------------|--------|
| 1.4D.1 | Unit test: register/unregister atoms | Pending |
| 1.4D.2 | Unit test: push increments seq correctly | Pending |
| 1.4D.3 | Integration test: atom change → browser receives | Pending |
| 1.4D.4 | Integration test: browser reconnect → gets fresh state | Pending |
| 1.4D.5 | Integration test: seq gap → resync triggered | Pending |
| 1.4D.6 | Manual test: open browser, change atom via REPL, see update | Pending |

#### Phase 1.4E: Documentation

| Task | Description | Status |
|------|-------------|--------|
| 1.4E.1 | Add usage examples to design doc | Pending |
| 1.4E.2 | Document message protocol in README or design doc | Pending |

**Message Protocol (from design doc):**
```clojure
[:sync/op {:key   :my-atom
           :seq   42
           :op    :assoc-in
           :path  []           ; [] = full replace
           :value {...}}]
```

**Design decisions made:**
- ✅ One-way sync first (server → browser)
- ✅ Shared atoms (all browsers see same value)
- ✅ Seq numbers in registry, not atom value
- ✅ Full sync for Phase 1 (path [])
- ✅ Delta sync deferred (deep-diff->ops ready when needed)
- ✅ Vectors replaced wholesale (sufficient for our use case)

**Future Phase 2 (Bidirectional):**
- Browser → server sync
- Conflict resolution
- Per-client atoms (if needed)

---

#### Phase 1.5-Pre: Migrate Code Browser to Synced Atoms ✅ Complete (v1.11.7)

Refactor from request/response messaging to synced atoms for cleaner state management.

**Depends on:** Phase 1.4 (atom-sync module)

**Approach:** Parallel migration - keep messaging working, add synced atoms alongside, verify, then switch over.

##### Step 1: Register Synced Atom (Parallel to Existing) ✅

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Pre.1a | Server: Create `!code-browser-state` atom with state shape | ✅ |
| 1.5-Pre.1b | Server: Call `(atom-sync/register-synced-atom! :code-browser !code-browser-state)` | ✅ |
| 1.5-Pre.1c | Server: Update atom IN ADDITION to sending response events | ✅ |
| 1.5-Pre.1d | Verify: Browser receives [:sync/op {:key :code-browser ...}] | ✅ |

##### Step 2: Browser Reads from Synced Atom ✅

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Pre.2a | Browser: Get synced atom via `(get-synced-atom :code-browser)` | ✅ |
| 1.5-Pre.2b | Browser: UI components deref synced atom instead of local state | ✅ |
| 1.5-Pre.2c | Verify: UI updates when server pushes [:sync/op ...] | ✅ |
| 1.5-Pre.2d | Browser: Keep old event handlers temporarily (become no-ops) | ✅ |

##### Step 3: Remove Old Messaging ✅

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Pre.3a | Server: Stop sending response events (only atom updates) | ✅ |
| 1.5-Pre.3b | Browser: Remove old event handlers | ✅ |
| 1.5-Pre.3c | Clean up: Remove dead code from both sides | ✅ |

##### Step 4: Browser → Server Actions (One-Way Pattern) ✅

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Pre.4a | Browser: User clicks ns → send [:code-browser/select-ns {:ns "..."}] | ✅ |
| 1.5-Pre.4b | Server: Handle action, `(swap! !code-browser-state ...)` | ✅ |
| 1.5-Pre.4c | Server: Watcher auto-pushes update to all browsers | ✅ |
| 1.5-Pre.4d | Verify: Click → server update → all browsers see change | ✅ |

**Bug Fix (2026-01-12):** Fixed seq-per-op in `atom-sync/core.clj` - each op now gets unique seq number when single swap changes multiple keys.

**Benefits:**
- Server builds state → automatically syncs to browser
- Adding fields to var maps (e.g., `:defined-by`) just works
- Browser picks what it needs for UI, ignores the rest
- Less boilerplate, more generic

**State shape:**
```clojure
{:namespaces ["ns.a" "ns.b" ...]
 :selected-ns "ns.a"
 :vars [{:name "foo" :kind :function :line 10 :defined-by 'clojure.core/defn ...}]
 :selected-var "foo"
 :source {:code "..." :file "..." :start-line 1 :end-line 20}}
```

**Action events (browser → server):**
```clojure
[:code-browser/select-ns {:ns "my.namespace"}]
[:code-browser/select-var {:var "my-fn"}]
[:code-browser/refresh]
```

#### Phase 1.5-Acc: Accumulated State Structure ✅ Complete

**Goal:** Retain previously fetched data instead of replacing it. Instant back-navigation.

**Design doc:** `docs/design/atom-sync-design.md` (Phase 1.7 section)

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Acc.1 | Server: Change state shape to `{:symbols-by-ns {ns symbols}}` | ✅ |
| 1.5-Acc.2 | Server: Change state shape to `{:source-by-var {qualified-name source}}` | ✅ |
| 1.5-Acc.3 | Server: Update handlers to `assoc-in` instead of `assoc` | ✅ |
| 1.5-Acc.4 | Browser: Update reads to `(get-in state [:symbols-by-ns selected-ns])` | ✅ |
| 1.5-Acc.5 | Browser: Update source read to `(get-in state [:source-by-var key])` | ✅ |
| 1.5-Acc.6 | Test: Click ns.a → ns.b → ns.a, verify no refetch | ✅ |

**Commit:** `fec744e` feat(code-browser): Implement accumulated state (Phase 1.5-Acc)

**Benefits:**
- Instant back-navigation (no refetch)
- Progressive caching via browsing
- File watcher friendly (can invalidate specific entries)
- Zero extra data transfer

---

#### Phase 1.5-Watch: Live File Watching ✅ Complete (2026-01-13)

**Goal:** Auto-update browser when source files change on disk.

**Design doc:** `docs/design/atom-sync-design.md` (Phase 1.6 section)

**Depends on:** Phase 1.5-Acc (accumulated state makes invalidation cleaner)

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Watch.1 | Add `!notification-callbacks` registry to clojure-lsp client | ✅ |
| 1.5-Watch.2 | Add `on-notification!` / `remove-notification-callback!` API | ✅ |
| 1.5-Watch.3 | Extend `handle-notification!` to call registered callbacks | ✅ |
| 1.5-Watch.4 | Add `uri->namespace` helper (extract ns from file URI) | ✅ |
| 1.5-Watch.5 | code-browser: Subscribe to diagnostics notifications | ✅ |
| 1.5-Watch.6 | code-browser: On file change, invalidate `[:symbols-by-ns ns]` | ✅ |
| 1.5-Watch.7 | code-browser: Re-fetch invalidated ns if currently selected | ✅ |
| 1.5-Watch.8 | Test: Edit file, verify browser updates automatically | ✅ |

**Commits:**
- `59e6243` feat(code-browser): Implement reactive debounce for file watching (Phase 1.5-Watch)
- `8bb84aa` feat(code-browser): Implement live file watching (Phase 1.5-Watch)

**Flow:**
```
File change → watcher.clj → clojure-lsp → publishDiagnostics
           → handle-notification! → callback → code-browser
           → invalidate cache → refetch if visible → atom-sync → browser
```

---

#### Phase 1.5-Epoch: Server Epoch for Stale Data Detection ✅ Complete (2026-01-14)

**Goal:** Prevent stale cached data when server restarts by adding epoch tracking.

**Problem:** When server restarts, browser has old seq numbers cached. New server starts fresh (seq 0), but browser ignores ops with lower seq than expected.

**Solution:** Add epoch (timestamp) to all sync ops. Browser tracks epoch per key and resets local state when epoch changes.

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Epoch.1 | Add `!server-epoch` atom (timestamp on server start) | ✅ |
| 1.5-Epoch.2 | Add `get-server-epoch` / `reset-server-epoch!` API | ✅ |
| 1.5-Epoch.3 | Include `:epoch` in all sync ops (incremental + full) | ✅ |
| 1.5-Epoch.4 | Module `:start` resets epoch on server startup | ✅ |
| 1.5-Epoch.5 | Browser: Track `!last-epoch` per key | ✅ |
| 1.5-Epoch.6 | Browser: On epoch change, reset `!sync-state` and `!resync-pending` | ✅ |
| 1.5-Epoch.7 | Test: Restart server, verify browser gets fresh state | ✅ |

**Protocol:**
```clojure
[:sync/op {:key :code-browser
           :seq 42
           :epoch 1736876543210  ; <-- NEW: server start timestamp
           :op :assoc-in
           :path [:selected-ns]
           :value "my.namespace"}]
```

**Files modified:**
- `modules/atom-sync/src/atom_sync/core.clj` - Server-side epoch tracking
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Browser-side epoch detection

---

#### Phase 1.5A: On-Demand Parsing ✅ Complete

| Task | Description | Status |
|------|-------------|--------|
| 1.5A.1 | Add `analyze-file` function (shell out to clj-kondo) | ✅ |
| 1.5A.2 | Parse `:var-definitions` from kondo output | ✅ |
| 1.5A.3 | Map `:defined-by` to labels (function, macro, multimethod, etc.) | ✅ |
| 1.5A.4 | Filter by `:ns` field (handle multi-ns files correctly) | ✅ |
| 1.5A.5 | Update server handler to use kondo instead of LSP for var list | ✅ |
| 1.5A.6 | Update browser UI to display richer kind labels | ✅ |
| 1.5A.7 | Fix: Handle non-zero exit codes from clj-kondo (exit 2 = warnings) | ✅ |

**Commits:**
- `6c06c7b` feat(code-browser): Add clj-kondo analysis for rich var classification
- `7deb52d` fix(code-browser): Handle clj-kondo non-zero exit codes gracefully

**Kind labels implemented:** function, private-fn, variable, defonce, declare, macro, multimethod, method, protocol, deftype, defrecord, test

**Showcase file:** `test/bb_mcp_server/kondo_types_showcase.clj`

#### Phase 1.5B: Caching

| Task | Description | Status |
|------|-------------|--------|
| 1.5B.1 | Add atom/cache for kondo results keyed by file path | Pending |
| 1.5B.2 | Store file mtime with cached result | Pending |
| 1.5B.3 | Return cached result if file unchanged | Pending |

#### Phase 1.5C: Cache Invalidation

| Task | Description | Status |
|------|-------------|--------|
| 1.5C.1 | Check file mtime before returning cached result | Pending |
| 1.5C.2 | Option: Wire to LSP file watcher events for proactive invalidation | Pending |

#### Future: Datalog Storage (Radar)

| Task | Description | Status |
|------|-------------|--------|
| 1.5D.1 | Store kondo analysis in Datalevin (queryable var definitions) | Future |
| 1.5D.2 | Store source code in Datalevin (full-text search, history) | Future |
| 1.5D.3 | Cross-file queries: "find all usages of protocol X" | Future |

**Benefits of Datalog storage:**
- Queryable: Find all macros, all multimethods, all protocol implementations
- Cross-file: Track usages, dependencies, call graphs
- History: Track changes over time (if storing snapshots)
- Unified: Same DB as other Datalevin modules (AI knowledge, etc.)

**Var types to distinguish:**
- `defn` → function
- `defn-` → private-fn
- `defmacro` → macro
- `defmulti` → multimethod
- `defmethod` → method
- `defprotocol` → protocol
- `deftype` → deftype
- `defrecord` → defrecord
- `deftest` → test
- `def` → variable
- `defonce` → defonce
- `declare` → declare

---

### Phase 1.5E: Code Browser Enhancements

User-requested improvements for better code navigation and project awareness.

#### Phase 1.5E.1: File-Order Symbol Sorting ✅ Complete (2026-01-14)

**Goal:** Show symbols in file order (by line number) instead of alphabetically, to understand eval dependencies.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.1.1 | Add sort-mode toggle to browser state (`:alpha` or `:file-order`) | ✅ |
| 1.5E.1.2 | Server: Sort by `:line` instead of `:name` when file-order selected | ✅ |
| 1.5E.1.3 | Browser: Add toggle button in vars panel header | ✅ |
| 1.5E.1.4 | Persist sort preference (or default to file-order) | ✅ (defaults to `:file-order`) |

**Commit:** `7589871` feat(code-browser): Add file-order symbol sorting (Phase 1.5E.1)

**Implementation notes:**
- clj-kondo already provides `:row` (line number) for each symbol
- Change `(sort-by :name)` to `(sort-by :line)` based on mode
- UI: Toggle button or dropdown in vars panel

#### Phase 1.5E.2: Git Status Display ✅ Complete (2026-01-14)

**Goal:** Show project directory and git branch/status for context awareness.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.2.1 | Backend: Add `get-git-info` function (shell out to git) | ✅ |
| 1.5E.2.2 | Return: branch name, clean/dirty status, remote tracking | ✅ |
| 1.5E.2.3 | Add git info to code-browser state atom | ✅ |
| 1.5E.2.4 | Browser: Display project path + branch in header | ✅ |
| 1.5E.2.5 | Browser: Show dirty indicator (e.g., "*" or icon) | ✅ |
| 1.5E.2.6 | Auto-refresh git status on file changes (piggyback on watcher) | ✅ |

**Commit:** `37fd04d` feat(code-browser): Add git status display (Phase 1.5E.2)

**Git commands needed:**
```bash
git rev-parse --show-toplevel        # Project root
git rev-parse --abbrev-ref HEAD      # Current branch
git status --porcelain               # Clean/dirty (empty = clean)
git rev-parse --abbrev-ref @{u}      # Upstream branch (if tracking)
```

**State addition:**
```clojure
{:git {:project-root "/path/to/project"
       :branch "main"
       :dirty? false
       :upstream "origin/main"}}
```

#### Phase 1.5E.3: Project Directory Selector (Larger Feature)

**Goal:** Allow user to select different project directories for browsing.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.3.1 | Backend: Add `list-directories` function | Pending |
| 1.5E.3.2 | Backend: Add `is-project-root?` validator (look for deps.edn/bb.edn/project.clj) | Pending |
| 1.5E.3.3 | Backend: Add `set-project-root!` action handler | Pending |
| 1.5E.3.4 | Backend: Reinitialize LSP when project changes | Pending |
| 1.5E.3.5 | Browser: Add project selector UI (dropdown or tree) | Pending |
| 1.5E.3.6 | Browser: Show current project path prominently | Pending |
| 1.5E.3.7 | Persist recent projects list | Pending |

**Design questions:**
- Tree browser vs dropdown of recent/configured projects?
- How to handle LSP reinitialization (async, loading indicator)?
- Session persistence (remember last project)?

#### Phase 1.5E.4: Branch Switching (Complex, Potential Footguns)

**Goal:** Allow switching git branches from the browser.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.4.1 | Backend: Add `list-branches` function | Pending |
| 1.5E.4.2 | Backend: Add `checkout-branch!` action with safety checks | Pending |
| 1.5E.4.3 | Safety: Check for uncommitted changes before switching | Pending |
| 1.5E.4.4 | Safety: Warn if switching would lose state | Pending |
| 1.5E.4.5 | Browser: Add branch dropdown in git status area | Pending |
| 1.5E.4.6 | Invalidate all caches after branch switch | Pending |
| 1.5E.4.7 | Refresh namespace list after switch | Pending |

**Safety considerations:**
- Never switch with uncommitted changes (or require confirmation)
- File changes invalidate LSP cache
- May need to reinitialize clojure-lsp after switch
- Consider read-only mode first (just display, no switch)

#### Phase 1.5E.6: Multimethod Implementations (defmethod) ✅ Complete (2026-01-14)

**Goal:** Show `defmethod` implementations linked to their `defmulti`.

**Discovery:** clj-kondo `:var-usages` includes defmethod data:
```clojure
{:name my-multimethod
 :defmethod true              ; Flag identifying method impl
 :dispatch-val-str ":default" ; Dispatch value as string!
 :row 59}
```

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.6.1 | Extend kondo analysis to fetch `:var-usages` | ✅ |
| 1.5E.6.2 | Filter var-usages for `:defmethod true` | ✅ |
| 1.5E.6.3 | Create method entries with dispatch value in name | ✅ |
| 1.5E.6.4 | Link methods to parent multimethod (group or indent) | Deferred |
| 1.5E.6.5 | Browser: Display as `my-multimethod :default` with kind `method` | ✅ |

**Implementation:**
- `extract-defmethods` - filters var-usages for `:defmethod true`
- Display format: `my-multimethod :dispatch-val` with kind `method`
- Flat display (grouping deferred to future enhancement)

**Commit:** `31c70df` feat(code-browser): Add defmethod and top-level forms display

#### Phase 1.5E.7: Protocol Implementations ✅ Complete (2026-01-14)

**Goal:** Show protocol method implementations from `defrecord`/`deftype`.

**Discovery:** clj-kondo `:protocol-impls` provides rich data:
```clojure
{:method-name protocol-method
 :protocol-name MyProtocol
 :defined-by clojure.core/defrecord  ; or deftype
 :impl-ns bb-mcp-server.kondo-types-showcase
 :row 79}
```

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.7.1 | Extend kondo analysis to fetch `:protocol-impls` | ✅ |
| 1.5E.7.2 | Create impl entries showing implementing type | ✅ |
| 1.5E.7.3 | Link impls to protocol definition | ✅ |
| 1.5E.7.4 | Browser: Display as `protocol-method (MyRecord)` with kind `protocol-impl` | ✅ |

**Implementation:**
- Added `:protocol-impls true` to clj-kondo config
- `find-containing-type` - finds defrecord/deftype by line range
- `extract-protocol-impls` - creates symbols with name `method (Type)` and kind `:protocol-impl`
- Browser shows implementations in file order with clickable source

**Display:**
- `protocol-method (MyRecord)` with kind `protocol-impl`
- `protocol-method (MyType)` with kind `protocol-impl`

#### Phase 1.5E.8: Enhanced Protocol Display

**Goal:** Enrich protocol definitions with implementation info.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.8.1 | Show protocol methods as children of protocol | Pending |
| 1.5E.8.2 | For each method, show count of implementations | Pending |
| 1.5E.8.3 | Click protocol method → see all implementations | Pending |
| 1.5E.8.4 | Click implementation → jump to source in defrecord/deftype | Pending |

**Example display:**
```
MyProtocol           protocol
  protocol-method    protocol-method  (2 impls)
    → MyRecord       impl
    → MyType         impl
```

#### Phase 1.5E.9: Top-Level Forms Display ✅ Complete (2026-01-14)

**Goal:** Show non-defining top-level forms (side effects, comments, config).

**Discovery:** Filter `:var-usages` for forms NOT inside var-definitions AND at column 1:
```clojure
;; Top-level forms detected:
Line 5      println    (side effect at load time)
Lines 7-9   comment    (comment block with examples)
Line 11     set!       (dynamic var config)
Lines 15-17 do         (do block)
Line 19     require    (require outside ns)
```

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.9.1 | Extend analysis: get var-usages, filter by col=1, outside defs | ✅ |
| 1.5E.9.2 | Categorize forms: comment, side-effect, config, require, do | ✅ |
| 1.5E.9.3 | Generate display name from form type + line range | ✅ |
| 1.5E.9.4 | **Only show in file-order view** (not alphabetical) | ✅ |
| 1.5E.9.5 | Visual distinction (different color/icon for non-def forms) | Deferred |

**Implementation:**
- `extract-top-level-forms` - filters var-usages by col=1 and form name allowlist
- Forms marked with `:top-level? true` for browser-side filtering
- Browser filters out top-level forms in `:alpha` mode
- Display format: `(comment ...)` with kind `comment`

**Display decision:**
- **Alphabetical view:** Hide top-level forms (no meaningful name to sort)
- **File-order view:** Show them inline where they occur (reveals load sequence)

**Example file-order display:**
```
my-variable          variable      line 13
(println ...)        side-effect   line 15
(comment ...)        comment       lines 17-25
my-function          function      line 27
(set! *warn...*)     config        line 30
```

**Commit:** `31c70df` feat(code-browser): Add defmethod and top-level forms display

**Useful for:**
- Finding `(comment ...)` blocks with examples/experiments
- Understanding load-time side effects
- Spotting `(require ...)` outside ns form (sometimes a smell)

#### Phase 1.5E.10: Symbol Inspector (Multi-View Details)

**Goal:** When a symbol is selected, offer multiple views beyond just source code.

**Views:**

| View | Description | Data Source |
|------|-------------|-------------|
| **Source** | Full source code (current) | LSP / file read |
| **Docstring** | Extracted, formatted docstring only | kondo `:doc` field or parse source |
| **Examples** | Usage examples | ClojureDocs (core), nearby `(comment ...)` blocks |
| **Dependents** | Vars that USE this symbol ("who calls me?") | kondo `:var-usages` where `:name` = this var |
| **Dependencies** | Vars this symbol USES ("who do I call?") | kondo `:var-usages` within this var's line range |
| **Metadata** | Arglists, type hints, deprecation, etc. | kondo analysis + source parsing |

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.10.1 | Add view selector UI (tabs or dropdown) | Pending |
| 1.5E.10.2 | Docstring view: Extract and format `:doc` | Pending |
| 1.5E.10.3 | Examples view: Fetch from ClojureDocs for `clojure.core/*` | Pending |
| 1.5E.10.4 | Examples view: Find nearby `(comment ...)` blocks in same file | Pending |
| 1.5E.10.5 | Dependents view: Query var-usages for refs to this symbol | Pending |
| 1.5E.10.6 | Dependencies view: Query var-usages within symbol's source range | Pending |
| 1.5E.10.7 | Click on dependent/dependency → navigate to that symbol | Pending |
| 1.5E.10.8 | Metadata view: Show arglists, type hints, private?, deprecated? | Pending |

**Example UI:**
```
┌─ my-function ──────────────────────────────────┐
│ [Source] [Doc] [Examples] [Dependents] [Deps]  │
├────────────────────────────────────────────────┤
│ ;; Dependents (3 vars call my-function):       │
│   → other-fn        (line 45)                  │
│   → handler         (line 102)                 │
│   → test-my-fn      (line 230)                 │
└────────────────────────────────────────────────┘
```

**Data sources for dependencies:**
```clojure
;; Find who calls `my-fn`:
(->> var-usages
     (filter #(= 'my-fn (:name %)))
     (filter #(not= (:from %) (:to %))))  ; exclude self

;; Find what `my-fn` calls (usages within its source range):
(->> var-usages
     (filter #(and (>= (:row %) start-line)
                   (<= (:row %) end-line)))
     (filter #(not= 'my-fn (:name %))))   ; exclude self
```

**Priority order:**
1. ~~**1.5E.1** - File-order sorting~~ ✅ Complete
2. ~~**1.5E.2** - Git status display~~ ✅ Complete
3. ~~**1.5E.6** - Multimethod implementations~~ ✅ Complete
4. ~~**1.5E.7** - Protocol implementations~~ ✅ Complete
5. ~~**1.5E.9** - Top-level forms~~ ✅ Complete
6. **1.5E.10** - Symbol inspector ← **NEXT** (multi-view details)
7. **1.5E.3** - Project selector (larger, architectural)
8. **1.5E.8** - Enhanced protocol display (nice-to-have)
9. **1.5E.4** - Branch switching (complex, defer)
10. **1.5E.11** - Multi-file namespace handling

#### Phase 1.5E.11: Multi-File Namespace Handling

**Goal:** Handle namespaces that span multiple files (via `in-ns` or split definitions).

**Context:** While uncommon, some codebases split a namespace across multiple files. This affects:
- Symbol aggregation (which symbols belong together)
- File-order view (order within file + order of files)
- Source navigation (which file to show)

**Detection:** `(> (count (distinct (map :filename symbols-for-ns))) 1)`

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.11.1 | Detect multi-file namespaces from kondo analysis | Pending |
| 1.5E.11.2 | Show file grouping or badge when ns spans files | Pending |
| 1.5E.11.3 | Research: Can we determine file load order? | Pending |
| 1.5E.11.4 | File-order view: Show files in load order, symbols within each | Pending |
| 1.5E.11.5 | Visual indicator for multi-file namespaces in ns list | Pending |

**Open questions:**
- How to determine file load order? (require chain analysis, deps.edn paths order, or unknown?)
- Display: Nested (ns → file → symbols) vs flat with file badge?
- Should we warn about `in-ns` usage as potential code smell?

**Load order complexity:**
```
my.namespace (3 files)
├── core.clj        [loaded 1st via require]
│   └── symbols in file order...
├── impl.clj        [loaded 2nd via require in core.clj]
│   └── symbols in file order...
└── extra.clj       [loaded 3rd via in-ns]
    └── symbols in file order...
```

**Possible data sources for load order:**
- `:namespace-usages` in kondo (shows require dependencies)
- Static analysis of require chains
- deps.edn `:paths` order (first path wins for same-named files)
- May be unknowable statically in some cases

#### Phase 1.5E.5: Sync Mode Toggle (Future - If Needed)

**Goal:** Support both shared (collaborative) and independent (per-client) browsing modes.

**Context:** Current architecture uses shared server-side state - all browsers see the same selection. This is intentional for pair programming/teaching but may not suit all use cases.

| Task | Description | Status |
|------|-------------|--------|
| 1.5E.5.1 | Add `:sync-mode` to state (`:shared` or `:independent`) | Future |
| 1.5E.5.2 | Independent mode: Move selection state to client-side | Future |
| 1.5E.5.3 | Independent mode: Use RPC for data fetch, signals for invalidation | Future |
| 1.5E.5.4 | Shared mode: Keep current behavior (all browsers synced) | Future |
| 1.5E.5.5 | Browser: Add mode toggle in UI header | Future |
| 1.5E.5.6 | Consider per-client state accumulation limits (LRU cache) | Future |

**Trade-offs:**
- **Shared mode:** Collaborative, instant back-nav for all, simpler server logic
- **Independent mode:** Per-user browsing, less bandwidth, more client complexity

**Trigger:** Implement when multi-user independence becomes a real requirement.

**Reference:** See `docs/atom-sync-review-gemini.md` for architectural discussion.

---

### Phase 1.6: Synthetic Special Forms Namespace

Create a browsable `**special-forms**` pseudo-namespace for discoverability.

**Design doc:** `docs/design/static-code-analysis.md`

| Task | Description | Status |
|------|-------------|--------|
| 1.6.1 | Add `**special-forms**` to namespace list (sorted first or last) | Pending |
| 1.6.2 | Hard-code special form list per platform (CLJ/CLJS/SCI) | Pending |
| 1.6.3 | Fetch/cache ClojureDocs content for each special form | Pending |
| 1.6.4 | Display special form docs in source panel (no source, show docs+examples) | Pending |

**Special forms to include:** `&`, `.`, `case*`, `catch`, `def`, `do`, `finally`, `fn`, `if`, `let`, `letfn`, `loop`, `new`, `quote`, `recur`, `set!`, `throw`, `try`, `var`, plus platform-specific ones.

**UI Notes:**
- Namespace panel: Show `**special-forms**` as distinct entry
- Vars panel: List all special forms with kind = "special-form"
- Source panel: "No source - bootstraps the language" + docs + examples

### Phase 2: Live System Mode (clj-ns-browser-Inspired)

**Goal:** Add a "Live Mode" that connects to a running nREPL and introspects the live system, inspired by [clj-ns-browser](https://github.com/franks42/clj-ns-browser).

**Key Insight:** Static analysis (Phase 1.x) and live introspection are complementary:
- **Static:** Source files on disk via LSP + clj-kondo
- **Live:** Running process via nREPL introspection

**Reuse:** Same UI components (namespace list, symbol list, source panel), different data source.

**Reference:** `../clj-ns-browser` for feature inspiration.

#### Phase 2.0: Architecture

| Task | Description | Status |
|------|-------------|--------|
| 2.0.1 | Design data abstraction layer (static vs live providers) | Pending |
| 2.0.2 | Define common data shapes for namespaces/vars | Pending |
| 2.0.3 | Document in `docs/design/live-mode-design.md` | Pending |

#### Phase 2.1: Mode Toggle & nREPL Connection

| Task | Description | Status |
|------|-------------|--------|
| 2.1.1 | Add mode toggle UI: Static / Live | Pending |
| 2.1.2 | Show nREPL connection selector (use existing nrepl module) | Pending |
| 2.1.3 | Connection status indicator in UI | Pending |
| 2.1.4 | Handle connection loss gracefully | Pending |

#### Phase 2.2: Live Namespace List

| Task | Description | Status |
|------|-------------|--------|
| 2.2.1 | Fetch loaded namespaces via `(all-ns)` | Pending |
| 2.2.2 | Show loaded vs unloaded distinction | Pending |
| 2.2.3 | One-click `require` for unloaded namespaces | Pending |
| 2.2.4 | Live updates when namespaces are loaded/created | Pending |

#### Phase 2.3: Live Var List

| Task | Description | Status |
|------|-------------|--------|
| 2.3.1 | Fetch vars via `(ns-publics 'ns)` / `(ns-interns 'ns)` | Pending |
| 2.3.2 | Get var metadata via `(meta #'var)` | Pending |
| 2.3.3 | Classify vars from metadata (`:macro`, `:dynamic`, etc.) | Pending |
| 2.3.4 | Show private vars toggle (`ns-interns` vs `ns-publics`) | Pending |
| 2.3.5 | Highlight REPL-defined vars (no source file) | Pending |

#### Phase 2.4: Live Value Inspection

| Task | Description | Status |
|------|-------------|--------|
| 2.4.1 | Show var value via `@#'var` or `(var-get #'var)` | Pending |
| 2.4.2 | Show value type | Pending |
| 2.4.3 | Pretty-print complex values (truncated) | Pending |
| 2.4.4 | Show atom/ref current value with deref | Pending |
| 2.4.5 | Live value updates (polling or watch) | Pending |

#### Phase 2.5: Enhanced Live Features

| Task | Description | Status |
|------|-------------|--------|
| 2.5.1 | **Predefined filters:** macros, functions, protocols, multimethods, dynamic vars | Pending |
| 2.5.2 | **Regex filter:** Filter vars by regex pattern | Pending |
| 2.5.3 | **Docstring search:** Match regex within docstrings | Pending |
| 2.5.4 | **Color coding:** Visual distinction by var type | Pending |

#### Phase 2.6: tools.trace Integration

| Task | Description | Status |
|------|-------------|--------|
| 2.6.1 | Add trace button to var display | Pending |
| 2.6.2 | Call `(trace-vars #'var)` via nREPL | Pending |
| 2.6.3 | Show trace status indicator | Pending |
| 2.6.4 | Remove trace with `(untrace-vars #'var)` | Pending |
| 2.6.5 | Display trace output in console panel | Pending |

#### Phase 2.7: ClojureDocs Integration

| Task | Description | Status |
|------|-------------|--------|
| 2.7.1 | Fetch examples from ClojureDocs API | Pending |
| 2.7.2 | Fetch comments and see-alsos | Pending |
| 2.7.3 | Cache with TTL (1 hour) | Pending |
| 2.7.4 | Offline fallback with cached data | Pending |

#### Phase 2.8: Static vs Live Diff View

| Task | Description | Status |
|------|-------------|--------|
| 2.8.1 | Compare static analysis with live state | Pending |
| 2.8.2 | Highlight vars that exist only at runtime | Pending |
| 2.8.3 | Highlight vars in source but not loaded | Pending |
| 2.8.4 | Show redefined vars (source differs from loaded) | Pending |

**clj-ns-browser features for reference:**
- Loaded/unloaded namespace distinction with one-click require
- Var type filters (macros, functions, protocols, multimethods, dynamic, deftypes, defrecords)
- Regex filter on var names and docstrings
- tools.trace integration
- ClojureDocs examples, comments, see-alsos
- Var value and @var inspection with metadata
- Live updates as vars are defined at REPL
- Color coding by var type

### Phase 3: Symbol-at-Point (Interactive Code Exploration)

Click any symbol in CM6 source viewer → open appropriate view.

**Design doc:** `docs/design/static-code-analysis.md`

**Leverages Phase 1.6** - reuses special form detection and docs.

#### Phase 3A: Basic Symbol-at-Point (Functions/Macros)

| Task | Description | Status |
|------|-------------|--------|
| 3A.1 | Add click handler to CM6 editor for symbol selection | Pending |
| 3A.2 | Get cursor position and extract symbol text | Pending |
| 3A.3 | Call LSP `textDocument/hover` for symbol info | Pending |
| 3A.4 | Display hover result in panel or popup | Pending |

**Works immediately** for functions, macros, project vars via LSP.

#### Phase 3B: Special Form Handling

| Task | Description | Status |
|------|-------------|--------|
| 3B.1 | Check if symbol is in special form list (from Phase 1.6) | Pending |
| 3B.2 | Reuse special form docs from Phase 1.6 cache | Pending |
| 3B.3 | Display: "Special Form - bootstraps the language" + docs | Pending |

#### Phase 3C: Navigation

| Task | Description | Status |
|------|-------------|--------|
| 3C.1 | Click on symbol → navigate to its definition in source panel | Pending |
| 3C.2 | Click on special form → open `**special-forms**` ns with that form selected | Pending |

---

## Pending Work

### bb calc CLI (Low Priority)

Higher-level wrapper for calculate module:
```bash
# Instead of:
bb mcp call calculate.calculate '{"expr":"(percent-change 100 125)"}'

# Would be:
bb calc "(percent-change 100 125)"
bb calc --help  # Show 100+ available functions
```

Low priority - calculate works fine via `bb mcp call`.

---

### clojure-lsp Module ✅ (Phase 5.5 Complete)

Clojure LSP integration via persistent subprocess. **16 MCP tools, 18 CLI commands.**

**Phase 6 remaining:** Error handling, README, test coverage.

**References:** `modules/clojure-lsp/docs/` for design and CLI examples.

**Version Info (2026-01-02):**
| Component | Installed | Latest | Notes |
|-----------|-----------|--------|-------|
| clojure-lsp | 2025.11.28-12.47.43 | 2025.11.28 | ✅ Latest release |
| clj-kondo (bundled) | 2025.10.24-SNAPSHOT | 2025.12.24 (master) | ⚠️ Older than standalone |
| clj-kondo (standalone) | v2025.12.23 | v2025.12.23 | ✅ Latest |

**Known Issue:** Sporadic NUL byte corruption in clojure-lsp JSON-RPC responses (stdout pollution from bundled clj-kondo). Client is resilient - logs error and continues. See commits `a65aca5` and `8a4eff7` for diagnostics.

**Directive:** If NUL byte crashes recur frequently, install HEAD version:
```bash
brew install --HEAD clojure-lsp/brew/clojure-lsp-native
```
This will get the latest bundled clj-kondo (2025.12.24+).

---

### Static + Live State Integration

Unified view: clojure-lsp (static) + nREPL (runtime). **Phase 0 complete.**

**Design docs:** `docs/design/live-static-state-design-implementation.md`

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | nREPL Introspection Tools (4 tools + CLI wrappers) | ✅ Complete |
| 0.5 | REPL Source Capture (Datalevin + var metadata) | Planned |
| 1-3 | State Monitor Module, Unified CLI | Planned |

---

### Phase 14C: Dynamic Loading Documentation (Planned)

| Task | Status |
|------|--------|
| Add "Selective Namespace Loading" section | Planned |
| Clarify two-level dependency model | Planned |
| Make `add-classpath` conditional in ns_loader.clj | Planned |

---

### Phase 15C: AI Knowledge Persistence (Planned)

Store experts, prompts, and conversations in Datalevin.

| Task | Status |
|------|--------|
| Expert definitions in Datalevin | Planned |
| Conversation history persistence | Planned |
| Prompt template storage | Planned |

---

### Phase 15D: Message Bus Migration (Planned)

Evaluate replacing atoms+promises with Datalevin-backed bus.

**Benefits:**
- Free persistence (conversation history as audit log)
- Queryable history via Datalog
- Unified state (bus + database in one component)

**Trade-offs:**
- Millisecond latency vs microsecond (acceptable for AI workloads)

**Decision:** Defer until 15A-C complete.

---

## Completed Phases

### Phase 20: MCP CLI & E2E Testing ✅ (v1.7.0+)

Generic MCP CLI and end-to-end test suite.

**Deliverables:**
- `bb mcp servers` - List running servers
- `bb mcp tools` - List available tools
- `bb mcp call <tool> <args>` - Call any tool
- `bb mcp init` - Get server info
- `bb test:e2e` - 11 tests, 42 assertions

**Files:**
- `scripts/mcp_cli.clj` - CLI dispatcher
- `test/e2e/mcp_client_test.clj` - E2E tests
- `src/bb_mcp_server/mcp_client.clj` - Client library

---

### Phase 19: Scittle-nREPL Dev Environment ✅ (v1.7.0)

Browser-based ClojureScript REPL via Scittle + sente-lite.

```
rebel-readline → nrepl-proxy:1667 → sente-browser:8090 → Browser (Scittle)
```

---

### Phase 18: nrepl CLI ✅ (v1.6.0)

Command-line interface for nREPL operations via MCP.

```bash
bb nrepl connect 7888 --nickname my-repl
bb nrepl eval "(+ 1 2 3)"
bb nrepl load-file src/app/core.clj
```

---

### Phase 17: Bootstrap Testing Suite ✅ (v1.3.0)

Bootstrap config, CLI parsing, and PID file tests. 8 tests, 30 assertions.

---

### Phase 16: nrepl-proxy-server ✅ (v1.3.0)

Shadow-cljs style nREPL proxy with browser routing. 15 tests, 44 assertions.

---

### Phase 15.5: Webserver Module ✅

Static file serving with live reload. 20 tests, 54 assertions.

---

### Phase 15A-B: Datalevin Integration ✅ (v0.15.0)

- `datalevin-pod` - Pod loading, connection management (5 tests)
- `datalevin-mcp` - MCP tools: `schema`, `q`, `transact`, `pull`, `find-by` (20 tests)

---

### Phase 14A-B: Dynamic Module Loading ✅ (v0.14.0)

- External modules via `BB_MCP_EXTERNAL_MODULES`
- Runtime loading via `system/load-new-module!`
- Minimal bootstrap pattern

---

### Phase 13: AI Orchestration ✅ (v0.13.x)

Multi-provider AI orchestration framework:

- **ai-orchestrator** - Provider-agnostic infrastructure
- **claude-subprocess-provider** - Claude CLI subprocess
- **anthropic-http-provider** - Native Anthropic API
- **openai-http-provider** - OpenAI API (+ Gemini compat)
- **message-bus** - Atoms+promises with Global Response Router
- **expert-registry** - File-based expert definitions
- **port-registry** - Port allocation and discovery

Multi-agent demo: 3-agent code review pipeline in 65s.

---

### Phase 11-12: Unified Entry Point & Telemetry ✅ (v0.11.0)

Single `bb server` command with composable flags:
```bash
bb server              # stdio (default)
bb server --http       # HTTP on port 3000
bb server --stdio --http 8080  # both
```

---

### Phase 8-10: Transport Modularization ✅ (v0.10.0)

Extracted monolithic `streamable-http` into:
- `http-core` - Shared HTTP infrastructure
- `mcp-http` - MCP JSON-RPC transport
- `mcp-stdio` - Stdio transport (pure, no deps)
- `rest-api` - REST endpoints + OpenAPI

165 tests, 492 assertions total.

---

### Phases 1-7: Foundation ✅

- Project initialization, bb.edn, tooling
- MCP server (stdio + HTTP)
- Tool registry with Malli validation
- Module system with dependency resolution
- Streamable HTTP with SSE
- REST API with OpenAPI generation

---

## References

- [Transport Modularization Design](docs/design/transport-modularization.md)
- [Module System Design](docs/design/module-system-design.md)
- [AI Orchestrator Architecture](docs/design/ai-orchestrator-architecture.md)
- [AI Experts Framework](docs/design/ai-experts-framework.md)

---

*Last Updated: 2026-01-14*
