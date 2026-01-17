# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-17
**Version:** v1.14.3

---

## Current State

Code browser with synced atoms, accumulated state, reactive auto-init, live file watching, clj-kondo rich var classification, **defmethod display**, **top-level forms**, **server epoch detection**, **aliases/refers panel with shadow warnings**, **multi-file namespace support with file dividers**, **lazy JAR dependency exploration**, and **git repo cloning from URL**.

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
| 1.5E.11 | Multi-file NS + (in-ns) detection | **COMPLETE** |
| 1.5E.18 | Lazy JAR Dependency Exploration | **COMPLETE** |
| 1.5E.16 | Directory Browser (tree navigation) | **COMPLETE** |
| 1.5E.17 | Clone Git Repo from URL | **COMPLETE** |

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
[pending] fix(code-browser): Fix namespace auto-loading after project selection
bb6494e docs: Add Phase 1.5E.12 source code highlighting to roadmap
62ae470 fix(code-browser): Symbol filter and protocol impl source display
05c6f87 fix(code-browser): Show full protocol for protocol methods
983ad9a feat(code-browser): Add protocol implementation display (Phase 1.5E.7)
e9ef9fd feat(atom-sync): Add server epoch for stale data detection
```

### Fix: Namespace Auto-Loading After Project Selection (2026-01-16)

**Problem:** After selecting a project via directory browser, namespace list stayed empty until user clicked Refresh button manually.

**Root Cause:** In `handle-set-project-root`, the `handle-request-namespaces` call was inside the same `(future ...)` block as LSP initialization, positioned AFTER the try/catch. This meant namespace loading waited for LSP to complete/fail (35+ seconds) before running.

**Fix:** Moved namespace loading to a SEPARATE `(future ...)` block:
```clojure
;; LSP init in its own future (can take 30+ seconds)
(future
  (try ... LSP init ... (catch ...)))

;; Namespace loading in separate future (runs in parallel, ~2 seconds via clj-kondo)
(future
  (refresh-git-info!)
  (handle-request-namespaces {}))
```

**Result:** Namespaces now auto-load within ~2-3 seconds of project selection (clj-kondo analysis time) instead of waiting 35+ seconds for LSP.

**File:** `modules/sente-browser/src/sente_browser/code_browser.clj` - `handle-set-project-root` function

---

## What's Next on Roadmap

| Priority | Phase | Description | Notes |
|----------|-------|-------------|-------|
| 1 | **1.5E.4** | Branch switching | Complex, defer for now |
| 2 | **Phase 2** | Live Mode | nREPL introspection (inspired by clj-ns-browser) |

**Phase 1.5E.17 (Clone git repo from URL)** is now complete. Next priority is Phase 2 (Live Mode) for nREPL introspection.

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
| `docs/design/static-live-code-revision-history.md` | Vision: datalog DB, runtime tracking, git diffs |
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
- **Multi-file namespace support** with file dividers (Phase 1.5E.11)

**Next feature: Phase 1.5E.10** - Symbol inspector (multi-view details)

**Key gotchas:**
- React keys must be globally unique across namespace switches - include `selected-ns` and `filename` in keys
- Server-side code changes require server restart
- Browser `.cljs` can be hot-reloaded via nREPL
- clj-kondo exit code 2 = warnings (use `:continue true` in shell)
- clj-kondo doesn't expose `:refer-clojure :exclude` in analysis output
- Multi-file NS: Use kondo's `:ns-files` mapping (LSP doesn't index test files well)

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

### Session 2026-01-15 (continued): Design Doc

**Created `docs/design/static-live-code-revision-history.md`** capturing the vision for:
- Static analysis (file-based, current)
- Git diffs integration (Phase A)
- Datalog DB code storage (Phase B)
- Runtime REPL tracking (Phase C)
- Unified timeline view (Phase D)

### Session 2026-01-15 (later): Phase 1.5E.11 - (in-ns) Namespace Detection

**Problem:** Namespaces defined via `(in-ns ...)` instead of `(ns ...)` were not appearing in Code Browser.

**Root cause discovered:**
- LSP `workspace/symbol` response does NOT include `containerName` field
- The old `extract-namespaces` function relied on kind=3 symbols (ns declarations) only
- Files with `(in-ns 'target-ns)` have no `(ns ...)` form, so no kind=3 symbol
- However, clj-kondo correctly identifies the namespace in var-definitions via `:ns` field

**Solution implemented:**
- Added `analyze-project-with-kondo` - runs clj-kondo on `src`, `test`, `modules` directories
- Added `extract-namespaces-from-kondo-vars` - extracts unique `:ns` values from var-definitions
- Added `compute-ns-file-counts-kondo` - computes multi-file namespace info from kondo data
- Updated `handle-request-namespaces` to use kondo-based detection (with LSP fallback)

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj` (lines 290-346 new functions, 1091-1114 updated)
- Removed unused `compute-ns-file-counts` (old LSP-based version)

**Test file:** `test/bb_mcp_server/in_ns_test.clj` contains `(in-ns 'bb-mcp-server.target-ns)`

**Verification:** `bb-mcp-server.target-ns` now appears in namespace list at http://localhost:8091

### Session 2026-01-15 (final): Phase 1.5E.11 - Multi-file Symbol Loading Fix

**Problem:** Multi-file namespaces (like `mock-claude`, `user`) showed "(N files)" badge but returned "Namespace not found" error when selected.

**Root cause:**
- `handle-request-symbols` used `get-namespace-file` which relies on LSP
- LSP doesn't index test files well, returning empty for `mock-claude`
- But kondo correctly identifies files via `compute-ns-file-counts-kondo`

**Solution implemented:**
- Added `:ns-files {}` field to state atom for ALL namespace → files mappings
- Created `compute-ns-files-kondo` function (extracts `:ns` + `:filename` from var-defs)
- Modified `handle-request-namespaces` to compute and store `:ns-files`
- Modified `handle-request-symbols` to use kondo's ns-files first, fall back to LSP
- Added multi-file merging: analyzes ALL files for a namespace and merges results

**Verification:**
- `mock-claude (2 files)` → Shows "10 symbols in 2 files" with file divider
- `user (18 files)` → Shows "115 symbols in 18 files" with file dividers

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj`:
  - Added `compute-ns-files-kondo` function
  - Added `:ns-files {}` to state atom
  - Updated `handle-request-namespaces` to store ns-files
  - Updated `handle-request-symbols` with multi-file support
  - Updated both state reset locations

**Phase 1.5E.11 is now COMPLETE.**

### Session 2026-01-15 (followup): Multi-file UX Fixes

**Two issues fixed:**

1. **File divider disambiguation** - Both mock-claude files had identical dividers (`mock_claude.clj`)
   - Root cause: `uri->filename` only extracted basename
   - Fix: Changed to include 3 path segments (grandparent/parent/filename.clj)
   - Result: `claude-manager/test/mock_claude.clj` vs `claude-subprocess-provider/test/mock_claude.clj`

2. **Ghost artifact prevention** - Old symbols persisted when switching namespaces
   - Root cause: React keys were `name-line` which weren't unique across namespaces
   - Fix: Changed keys to include `selected-ns` and `filename`: `(str selected-ns "-" (:filename item) "-" (:name item) "-" (:line item))`
   - Result: Clean namespace switching with no ghost artifacts

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj` - `uri->filename` function
- `modules/sente-browser/src/browser/code_browser.cljs` - React keys in symbols panel

**Key learning:** React keys must be globally unique when items can be swapped between lists. Include parent context (namespace) in keys to ensure proper unmounting.

### Session 2026-01-15 (evening): Phase 1.5E.18 - Lazy JAR Dependency Exploration

**Goal:** Enable exploring external JAR dependencies (like `clojure.string`, `taoensso.trove`) without analyzing all JARs at startup.

**Implementation:**
- **NS→JAR mapping at startup:** Quick scan of JAR entry paths to map namespaces to JAR files
- **Lazy JAR analysis:** Only analyze JAR with clj-kondo when user clicks on external dependency
- **JAR analysis caching:** Cache results in `:jar-analyses` to avoid re-analyzing
- **Explored Dependencies section:** Shows explored JAR namespaces in namespace panel

**New state fields in `!code-browser-state`:**
- `:ns->jar {}` - Maps namespace names to JAR file paths
- `:jar-analyses {}` - Caches clj-kondo analysis per JAR path
- `:explored-deps []` - List of explored JAR namespace names

**New functions added:**
- `build-ns->jar-mapping` - Scans JAR files on classpath to build namespace mapping
- `initialize-ns->jar-mapping!` - Async initialization of mapping
- `analyze-jar-with-kondo` - Runs clj-kondo on JAR file
- `get-jar-namespace-symbols` - Extracts symbols for a specific namespace from JAR
- `handle-explore-jar-dep` - Event handler for exploring JAR dependency
- `is-project-namespace?` - Checks if namespace is from project or JAR

**Browser changes:**
- External dependencies shown with 📦 icon in Deps tab
- Clicking 📦 dep triggers JAR exploration
- "Explored Dependencies" section appears in namespace panel
- Count shows "N namespaces + M JAR"

**Verification:**
- Clicked `taoensso.trove` → Successfully explored, showed 4 symbols
- Clicked `clojure.string` → Successfully explored, showed 25 symbols
- Both appear in "Explored Dependencies" with 📦 icons
- Count shows "188 namespaces + 2 JAR"

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj` - ~15 new functions, 2 event handlers
- `modules/sente-browser/src/browser/code_browser.cljs` - External dep detection + explore UI
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - CSS for external deps

**Minor issues noted (future fixes):**
- `*log-fn*` variable appears in symbols list across explored namespaces (symbol mixing)
- This is cosmetic and doesn't affect functionality

**Phase 1.5E.18 is now COMPLETE.**

### Session 2026-01-15 (late): JAR Navigation Fix

**Problem:** Clicking on JAR dependency symbols (like `clojure.core/reset!`) in the Deps tab didn't navigate to the JAR namespace with the symbol selected - unlike project source symbols.

**Root causes identified:**
1. `handle-navigate-to-symbol` was setting `:symbols` but not `:symbols-by-ns` for JAR namespaces
   - `find-cached-symbol` looks in `:symbols-by-ns` to get line info for source extraction
2. `jar-source-data` in `handle-request-var-source` was missing `:start-line` and `:end-line` fields
   - Caused "lines -" display instead of actual line numbers

**Fixes applied:**
1. Updated `handle-navigate-to-symbol` (lines 1929-1946) to use functional swap setting both `:symbols` and `:symbols-by-ns`:
   ```clojure
   (swap! !code-browser-state
          (fn [state]
            (-> state
                (assoc :selected-ns ns)
                (assoc :symbols symbols)
                (assoc :selected-symbol name)
                (assoc-in [:symbols-by-ns ns] (vec symbols)))))
   ```

2. Added `:start-line` and `:end-line` to `jar-source-data` (lines 1693-1699):
   ```clojure
   jar-source-data {:code (:code source-data)
                    :file (:file source-data)
                    :ns ns
                    :var-name var-name
                    :from-jar true
                    :start-line (:start-line source-data)
                    :end-line (:end-line source-data)}
   ```

**Verification:**
- Selected `bb-mcp-server.telemetry` → `init!` → Deps tab → clicked `reset!` (📦)
- ✅ Navigated to `clojure.core` with 791 symbols displayed
- ✅ `reset!` selected in inspector
- ✅ Source shows `jar:/.../clojure-1.12.3.jar!clojure/core.clj lines 2393-2398`

**Files modified:**
- `modules/sente-browser/src/sente_browser/code_browser.clj`:
  - `handle-navigate-to-symbol` - update both `:symbols` and `:symbols-by-ns`
  - `handle-request-var-source` - add line numbers to `jar-source-data`

### Session 2026-01-16: Phase 1.5E.16 - Directory Browser

**Goal:** Add tree-based directory navigation to select project directories visually.

**Implementation:**
- Created new module: `modules/directory-browser/`
- `directory-browser.core` provides filesystem operations:
  - `list-directory` - Lists directory with metadata (type, size, modified, readable/writable)
  - `get-directory-properties` - Detects project/workspace types
  - `breadcrumbs` - Generates navigation path segments
  - `expand-path` - Handles `~` and `$ENV` expansion
- Project detection: clojure (deps.edn, bb.edn, project.clj, shadow-cljs.edn), node, python, rust, go, java
- Workspace detection: git, vscode, cursor, windsurf, idea, eclipse

**Browser UI:**
- 📁 button next to project path input opens dialog
- Dialog shows: breadcrumb navigation, directory tree, property badges
- Property badges: `git`, `clj`, `py`, `node`, `java`, `vscode`, etc.
- "Show hidden" checkbox toggle
- "Select as Project" button to add directory as project

**Module integration fixes:**
- Fixed `module.edn` format: changed `:namespace`/`:dependencies` to `:entry`/`:requires`
- Fixed `start` function signature: `[config]` → `[_deps config]` for module protocol
- Added `convert-sets-to-vecs` for JSON serialization of Clojure sets
- Added breadcrumbs to server response

**Files created/modified:**
- `modules/directory-browser/module.edn` - Module configuration
- `modules/directory-browser/src/directory_browser/core.clj` - Core functionality
- `modules/sente-browser/src/sente_browser/code_browser.clj` - Server handlers
- `modules/sente-browser/src/browser/code_browser.cljs` - Browser UI

**Phase 1.5E.16 is now COMPLETE.**

### Session 2026-01-17: Phase 1.5E.17 - Clone Git Repo from URL

**Goal:** Allow users to enter a git repository URL, clone it to a temp directory, and browse it.

**Implementation:**
- **Browser (code_browser.cljs):**
  - Added `clone-repo!` function to send clone request to server
  - Added `git-url?` helper to detect git URLs (github.com, gitlab.com, bitbucket.org, .git suffix, git@, https://, ssh://)
  - Added event handlers for `:code-browser/clone-progress` and `:code-browser/clone-result`
  - Added `clone-repo-input` UI component with input field, clone button, and status messages
  - UI shows hourglass during clone, success/error message on completion
  - Integrated into `git-status-bar` component

- **Server (code_browser.clj):**
  - Added `extract-repo-name` function to parse repo name from URL (handles https://, git@, etc.)
  - Added `handle-clone-repo` function that:
    - Creates temp directory with `babashka.fs/create-temp-dir`
    - Runs `git clone --depth 1` for fast shallow clone
    - Adds cloned project to projects list
    - Sets as current project (triggers namespace loading)
    - Returns success/error result

- **Server Event Handling (server.clj):**
  - Added special case for `:code-browser/clone-repo` in `on-browser-message`
  - Runs clone in `(future ...)` to avoid blocking WebSocket
  - Sends progress and result events back via `send-to-browser!`

**Test verification:**
- Entered `https://github.com/taoensso/trove` in URL input
- Clone button enabled (detected as git URL)
- Clicked clone → UI showed "⏳ Cloning trove..."
- Clone completed → "Cloned to trove" message
- Project switched to "trove" automatically
- 10 namespaces from trove library loaded and displayed
- Git branch shows "main" with 🌿 indicator

**Files modified:**
- `modules/sente-browser/src/browser/code_browser.cljs` - Clone UI and event handling
- `modules/sente-browser/src/sente_browser/code_browser.clj` - Clone handler
- `modules/sente-browser/src/sente_browser/server.clj` - Async clone dispatch

**Phase 1.5E.17 is now COMPLETE.**

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
