# bb-mcp-server Implementation Plan

**Status:** Code Browser Phase 1.5-Pre Complete + Future Phases Designed
**Version:** v1.11.7
**Last Updated:** 2026-01-12

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

#### Phase 1.5-Watch: Live File Watching

**Goal:** Auto-update browser when source files change on disk.

**Design doc:** `docs/design/atom-sync-design.md` (Phase 1.6 section)

**Depends on:** Phase 1.5-Acc (accumulated state makes invalidation cleaner)

| Task | Description | Status |
|------|-------------|--------|
| 1.5-Watch.1 | Add `!notification-callbacks` registry to clojure-lsp client | Pending |
| 1.5-Watch.2 | Add `on-notification!` / `remove-notification-callback!` API | Pending |
| 1.5-Watch.3 | Extend `handle-notification!` to call registered callbacks | Pending |
| 1.5-Watch.4 | Add `uri->namespace` helper (extract ns from file URI) | Pending |
| 1.5-Watch.5 | code-browser: Subscribe to diagnostics notifications | Pending |
| 1.5-Watch.6 | code-browser: On file change, invalidate `[:symbols-by-ns ns]` | Pending |
| 1.5-Watch.7 | code-browser: Re-fetch invalidated ns if currently selected | Pending |
| 1.5-Watch.8 | Test: Edit file, verify browser updates automatically | Pending |

**Flow:**
```
File change → watcher.clj → clojure-lsp → publishDiagnostics
           → handle-notification! → callback → code-browser
           → invalidate cache → refetch if visible → atom-sync → browser
```

---

#### Phase 1.5A: On-Demand Parsing

| Task | Description | Status |
|------|-------------|--------|
| 1.5A.1 | Add `analyze-file` function (shell out to clj-kondo) | Pending |
| 1.5A.2 | Parse `:var-definitions` from kondo output | Pending |
| 1.5A.3 | Map `:defined-by` to labels (function, macro, multimethod, etc.) | Pending |
| 1.5A.4 | Filter by `:ns` field (handle multi-ns files correctly) | Pending |
| 1.5A.5 | Update server handler to use kondo instead of LSP for var list | Pending |
| 1.5A.6 | Update browser UI to display richer kind labels | Pending |

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

### Phase 2: Runtime Introspection (Deferred)

| Task | Description | Status |
|------|-------------|--------|
| 2.1 | Mode toggle (Static/Runtime) | Pending |
| 2.2 | Wire nREPL introspection tools | Pending |
| 2.3 | Show REPL-defined vars | Pending |
| 2.4 | Static vs runtime diff view | Pending |

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

*Last Updated: 2026-01-10*
