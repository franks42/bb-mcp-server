# bb-scittle-code-browser Design

**Status:** Design Draft
**Created:** 2025-12-31

---

## Vision

Browser-based code browser for Clojure projects, powered by Scittle. Two modes:

1. **Static Mode** - Browse project source via clojure-lsp
2. **Runtime Mode** - Browse live state via nREPL introspection (Phase 2)

**Key Principle:** Embedded in Scittle dev environment, fully REPL-driven from both bb server AND browser. Layout, components, and behavior can be modified live without page refresh.

---

## REPL-Driven Development

### From bb server (via nREPL to browser):
```clojure
;; Push new namespace data
(reset! !namespaces (fetch-project-namespaces))

;; Dynamically add a panel
(browser-eval! browser-1 '(swap! !layout assoc :call-hierarchy true))

;; Change filter behavior
(browser-eval! browser-1 '(reset! !filter-mode :regex))
```

### From browser Scittle REPL:
```clojure
;; Toggle panel visibility
(swap! !layout update :source-panel not)

;; Change column widths
(swap! !layout assoc :ns-width "20%" :vars-width "25%")

;; Add custom rendering
(swap! !var-renderer (fn [var] [:div.custom ...]))
```

### Bidirectional sync:
```
bb-server                          Browser (Scittle)
    │                                    │
    │◄─── browser modifies !layout ──────│
    │     (synced back to server)        │
    │                                    │
    │──── server pushes !namespaces ────►│
    │     (synced to browser)            │
```

---

## UI Layout

Inspired by [clj-ns-browser](https://github.com/franks42/clj-ns-browser) (Smalltalk-style browser).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  [Static/Runtime ▼]  [Refresh]                              [Settings ⚙]   │
├───────────────────┬───────────────────┬─────────────────────────────────────┤
│ Namespaces        │ Vars/Classes      │ Documentation                       │
│ ─────────────────│ ─────────────────│ ───────────────────────────────────│
│ [loaded ▼]        │ [Vars-public ▼]   │ [Doc][Source][Examples][Value][Meta]│
│ [filter_______]   │ [filter_______]   │                                     │
│                   │                   │ clj-info.doc2md/format-source-info  │
│ ☑ clj-info       │ format-arglists   │ ─────────────────────────────────── │
│ ☑ clj-info.doc2md│ format-code-block │ (defn- format-source-info           │
│ ☐ clj-ns-browser │ ▶format-source-info│   "Format source and metadata..."  │
│ ☐ clj-ns-browser.│ format-usage      │   [doc-map]                         │
│   browser        │                   │   (let [{:keys [ns file line]} ...  │
│                   │                   │                                     │
│ 17         [Req] │ 14    [Trace]     │ [Inspect] [Edit] [Browse]           │
└───────────────────┴───────────────────┴─────────────────────────────────────┘
```

**Key UI patterns from clj-ns-browser:**
- Dropdown for filter *mode* (loaded/unloaded, Vars-public/private/macro/etc.)
- Text field for filter *pattern* (regex with visual feedback)
- Counts at bottom (17 namespaces, 14 vars)
- Toggle button row for doc sections (multi-select with Cmd-click)
- Color coding by var type (macro=red, function=green, protocol=blue)

---

## Panel Specifications

### Panel 1: Namespace List

- **Data source (static):** `clj-symbols` or direct file scan
- **Data source (runtime):** `nrepl-loaded-namespaces`
- **Filter:** Text input with wildcard (`*`) or regex support
- **Selection:** Multi-select checkboxes
- **Display:** Namespace names (e.g., `app.core`, `app.db`)

### Panel 2: Vars List

- **Data source (static):** `clj-symbols` for selected namespaces
- **Data source (runtime):** `nrepl-introspect-ns` for selected namespaces
- **Filter:** Text input filtering FQNs
- **Selection:** Single-select (click highlights row)
- **Display:** Fully-qualified names (`ns/var-name`)
- **Indicators:** Function vs value, public vs private

### Panel 3: Source Viewer

- **Editor:** CodeMirror 6 (read-only mode)
- **Syntax:** Clojure highlighting
- **Data source:** File content at symbol location
- **Features:**
  - Line numbers
  - Symbol highlighting
  - Jump to definition (click on symbols)

---

## Data Flow Architecture

### Option A: Server-Push Model (Recommended)

```
┌─────────────┐     sente-lite      ┌─────────────┐
│  bb-server  │ ──────────────────> │   Browser   │
│             │                     │  (Scittle)  │
│ !namespaces │ :sync/namespaces    │ !namespaces │
│ !vars       │ :sync/vars          │ !vars       │
│ !source     │ :sync/source        │ !source     │
└─────────────┘                     └─────────────┘
```

**Server-side atoms:**
```clojure
(defonce !namespaces (atom []))  ; List of ns names
(defonce !vars (atom []))        ; List of var info maps
(defonce !source (atom nil))     ; {:symbol "..." :code "..." :file "..." :line N}
```

**Sync mechanism:**
- Server watches atoms, pushes changes via sente-lite
- Browser receives, updates local atoms
- Reagent re-renders on atom changes

**Pros:**
- Real-time updates when project changes
- clojure-lsp file watcher integration
- Familiar pattern (already using sente-lite)

**Cons:**
- Requires connection management
- State lives on server

### Option B: Request-Response Model

```
Browser                              Server
   │                                    │
   │─── :req/namespaces ───────────────>│
   │<── :res/namespaces ────────────────│
   │                                    │
   │─── :req/vars {:ns [...]} ─────────>│
   │<── :res/vars ──────────────────────│
```

**Pros:**
- Simpler state management
- Browser controls data fetching

**Cons:**
- More latency on interactions
- No live updates

### Option C: Hybrid (Recommended for Phase 1)

- Initial load: Request full namespace list
- Selection changes: Request vars for selected namespaces
- Var selection: Request source
- Background: Server pushes updates when files change

---

## Data Structures

### Namespace Entry
```clojure
{:name "app.core"
 :file "src/app/core.clj"
 :source :static}  ; or :runtime
```

### Var Entry
```clojure
{:name "start"
 :ns "app.core"
 :fqn "app.core/start"
 :kind :function  ; :function, :var, :macro, :protocol
 :private? false
 :arglists '([config] [config opts])
 :doc "Start the application"
 :file "src/app/core.clj"
 :line 42
 :source :static}
```

### Source View
```clojure
{:symbol "app.core/start"
 :code "(defn start\n  \"Start the application\"\n  [config]\n  ...)"
 :file "src/app/core.clj"
 :start-line 42
 :end-line 58
 :language "clojure"}
```

---

## Implementation Plan

### Phase 1: Static Browsing (MVP)

| Task | Description |
|------|-------------|
| 1.1 | Create `modules/scittle-code-browser/` structure |
| 1.2 | Define server-side API (namespace list, vars, source) |
| 1.3 | Implement sente-lite message handlers |
| 1.4 | Create Scittle UI components (three panels) |
| 1.5 | Integrate CodeMirror 6 for source display |
| 1.6 | Add filter functionality |
| 1.7 | Wire up clojure-lsp as data source |

### Phase 2: Runtime Introspection

| Task | Description |
|------|-------------|
| 2.1 | Add mode toggle (Static/Runtime) |
| 2.2 | Wire up nREPL introspection tools |
| 2.3 | Show runtime-only vars (REPL-defined) |
| 2.4 | Diff view: static vs runtime |
| 2.5 | Live value inspection |

### Phase 3: Enhanced Features

| Task | Description |
|------|-------------|
| 3.1 | Click-to-navigate in source |
| 3.2 | Search across all code |
| 3.3 | Call hierarchy visualization |
| 3.4 | Dependency graph |

---

## Bidirectional Atom Sync

Core mechanism for REPL-driven development: atoms synced between bb server and browser.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         bb-mcp-server                               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  !synced-atoms = {:layout     (atom {...})                   │   │
│  │                   :namespaces (atom [...])                   │   │
│  │                   :vars       (atom [...])                   │   │
│  │                   :source     (atom nil)}                    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                    add-watch on each atom                           │
│                              │                                      │
│                              ▼                                      │
│                     push via sente-lite                             │
│                    [:sync/atom {:key :layout :value {...}}]         │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────┬──────────────────────────────────────┐
│                         Browser                                      │
│                              │                                      │
│                    receive [:sync/atom ...]                         │
│                              │                                      │
│                              ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  !synced-atoms = {:layout     (r/atom {...})    ◄── Reagent │   │
│  │                   :namespaces (r/atom [...])                 │   │
│  │                   :vars       (r/atom [...])                 │   │
│  │                   :source     (r/atom nil)}                  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                    add-watch (browser-side)                         │
│                              │                                      │
│                              ▼                                      │
│                    push back to server                              │
│                   [:sync/atom-update {:key :layout :value {...}}]   │
└─────────────────────────────────────────────────────────────────────┘
```

### Server-side API

```clojure
(ns sente-browser.code-browser.sync)

(defonce !synced-atoms (atom {}))

(defn register-synced-atom!
  "Register an atom for bidirectional sync with browser."
  [key atom-ref & {:keys [push-on-change? browser-writable?]
                   :or {push-on-change? true
                        browser-writable? true}}]
  (swap! !synced-atoms assoc key
         {:atom atom-ref
          :push-on-change? push-on-change?
          :browser-writable? browser-writable?})
  (when push-on-change?
    (add-watch atom-ref ::sync
      (fn [_ _ _ new-val]
        (broadcast-to-browsers! [:sync/atom {:key key :value new-val}])))))

;; Usage
(def !layout (atom {:ns-width "20%" :vars-width "30%"}))
(def !namespaces (atom []))

(register-synced-atom! :layout !layout)
(register-synced-atom! :namespaces !namespaces :browser-writable? false)
```

### Browser-side API

```clojure
(ns code-browser.sync
  (:require [reagent.core :as r]))

(defonce !synced-atoms (atom {}))

(defn on-sync-message [{:keys [key value]}]
  (when-let [atom-ref (get @!synced-atoms key)]
    (reset! atom-ref value)))

(defn get-synced-atom [key]
  (or (get @!synced-atoms key)
      (let [new-atom (r/atom nil)]
        (swap! !synced-atoms assoc key new-atom)
        new-atom)))

;; Components use synced atoms directly
(defn namespace-list []
  (let [namespaces @(get-synced-atom :namespaces)
        layout @(get-synced-atom :layout)]
    [:div {:style {:width (:ns-width layout)}}
     (for [ns namespaces]
       ^{:key ns} [:div.ns-item ns])]))
```

### Conflict Resolution

For bidirectional sync, last-write-wins with optional version vectors:

```clojure
;; Simple: last write wins
{:key :layout :value {...} :timestamp 1735689600000}

;; Advanced: version vectors (if needed)
{:key :layout :value {...} :version {:server 5 :browser-1 3}}
```

---

## Technical Considerations

### CodeMirror 6 in Scittle

**Decision:** ES modules via esm.sh + custom Reagent wrapper in separate reusable namespace.

**Rationale:**
- No off-the-shelf CM6 Reagent/Scittle wrapper exists
- [nextjournal/clojure-mode](https://github.com/nextjournal/clojure-mode) provides language support only
- Maria.cloud still uses CM5 (hasn't migrated)
- esm.sh handles bundling/CDN, keeps options open
- Separate namespace (`scittle-cm6`) enables reuse outside this project

**Bootstrap HTML preload:**
```html
<script type="module">
  import {EditorView, basicSetup} from 'https://esm.sh/@codemirror/basic-setup';
  import {EditorState} from 'https://esm.sh/@codemirror/state';
  import {clojure} from 'https://esm.sh/@nextjournal/lang-clojure';
  globalThis.CM = {EditorView, EditorState, basicSetup, clojure};
</script>
```

**Wrapper component (loaded via nREPL, iterated live):**
```clojure
(ns scittle-cm6.core
  "Reusable CodeMirror 6 wrapper for Scittle/Reagent.
   Separate namespace for use outside code-browser."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(defn code-viewer
  "Read-only CodeMirror 6 viewer with Clojure syntax."
  [{:keys [code]}]
  (let [!view (atom nil)]
    (r/create-class
      {:component-did-mount
       (fn [this]
         (let [el (rdom/dom-node this)
               view (js/CM.EditorView.
                      #js {:doc (or code "")
                           :extensions #js [(js/CM.basicSetup)
                                            (js/CM.clojure)
                                            (js/CM.EditorView.editable.of false)]
                           :parent el})]
           (reset! !view view)))
       :component-did-update
       (fn [this [_ old-props]]
         (let [[_ new-props] (r/argv this)]
           (when (and @!view (not= (:code old-props) (:code new-props)))
             (.dispatch @!view
               #js {:changes #js {:from 0
                                   :to (.. @!view -state -doc -length)
                                   :insert (:code new-props)}}))))
       :component-will-unmount
       (fn [_] (when @!view (.destroy @!view)))
       :reagent-render
       (fn [_] [:div.cm-container])})))
```

### Filtering

**Wildcard pattern:**
```clojure
(defn matches-wildcard? [pattern text]
  (let [regex (-> pattern
                  (str/replace "." "\\.")
                  (str/replace "*" ".*"))]
    (re-matches (re-pattern regex) text)))
```

**Regex pattern:**
```clojure
(defn matches-regex? [pattern text]
  (try
    (re-find (re-pattern pattern) text)
    (catch :default _ false)))
```

### State Management in Scittle

Using Reagent atoms:
```clojure
(defonce !app-state
  (r/atom {:namespaces []
           :selected-ns #{}
           :vars []
           :selected-var nil
           :source nil
           :ns-filter ""
           :var-filter ""
           :mode :static}))
```

---

## Module Structure

**Embedded approach:** Components live in `sente-browser` module, loaded into existing Scittle dev environment.

```
modules/sente-browser/
├── src/sente_browser/
│   ├── code_browser/
│   │   ├── server.clj       ; Server-side data fetching, atom sync
│   │   ├── handlers.clj     ; sente-lite message handlers
│   │   └── lsp.clj          ; clojure-lsp integration (optional)
│   └── ...existing files...
├── resources/scittle/
│   ├── code_browser/
│   │   ├── core.cljs        ; Main component, layout
│   │   ├── panels.cljs      ; Namespace, vars, source panels
│   │   ├── filters.cljs     ; Filter logic (wildcard, regex)
│   │   └── codemirror.cljs  ; CM6 wrapper (Phase 2)
│   └── ...existing files...
```

**Loading into Scittle:**
```clojure
;; In bootstrap or on-demand via REPL
(require '[code-browser.core :as cb])
(cb/mount! (js/document.getElementById "app"))

;; Or add to existing layout
(swap! !layout assoc :code-browser [cb/main-panel])
```

---

## Dev Infrastructure

### System Config: `bb-code-browser-dev-system.edn`

```edn
{:server-name "code-browser-dev"
 :modules ["mcp-local-eval"
           "nrepl"
           "mcp-http"
           "clojure-lsp"
           "sente-browser"]
 :config {:sente-browser {:bootstrap-port 8091
                          :ws-port 8090}
          :mcp-http {:port 3000}
          :clojure-lsp {:project-root "."}}}  ;; bb-mcp-server itself
```

**Start:**
```bash
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
```

### Bootstrap HTML Preloads

```html
<!DOCTYPE html>
<html>
<head>
  <title>bb-mcp Code Browser</title>
  <style>/* CSS for panels, CM6, etc. */</style>
</head>
<body>
  <div id="app">Loading...</div>

  <!-- 1. Scittle core + plugins -->
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.reagent.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.promesa.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js"></script>

  <!-- 2. Trove logging -->
  <script src="https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/..." type="application/x-scittle"></script>

  <!-- 3. CodeMirror 6 via ES modules -->
  <script type="module">
    import {EditorView, basicSetup} from 'https://esm.sh/@codemirror/basic-setup';
    import {EditorState} from 'https://esm.sh/@codemirror/state';
    import {clojure} from 'https://esm.sh/@nextjournal/lang-clojure';
    globalThis.CM = {EditorView, EditorState, basicSetup, clojure};
    // Signal CM6 ready, then eval Scittle
    window.CM6_READY = true;
  </script>

  <!-- 4. sente-lite + nREPL adapter bundle -->
  <script src="/sente-lite-nrepl.cljs" type="application/x-scittle"></script>

  <!-- 5. Atom sync + error boundary (bootstrap infrastructure) -->
  <script type="application/x-scittle">
    (ns code-browser.bootstrap
      (:require [reagent.core :as r]))

    ;; Error boundary for safe REPL development
    (defn error-boundary [& children]
      (let [!error (r/atom nil)]
        (r/create-class
          {:component-did-catch (fn [_ e _] (reset! !error e))
           :reagent-render
           (fn [& children]
             (if @!error
               [:div.error [:h3 "Error"] [:pre (str @!error)]
                [:button {:on-click #(reset! !error nil)} "Clear"]]
               (into [:<>] children)))})))

    ;; Synced atoms registry (bidirectional with server)
    (defonce !synced-atoms (atom {}))

    (defn get-synced-atom [key]
      (or (get @!synced-atoms key)
          (let [a (r/atom nil)]
            (swap! !synced-atoms assoc key a)
            a)))

    ;; Dev namespace with preloaded helpers
    (def dev-ns 'code-browser.dev)
  </script>

  <!-- 6. Eval all Scittle tags -->
  <script>scittle.core.eval_script_tags();</script>
</body>
</html>
```

### UI Code Loading via nREPL

UI code is NOT hardcoded in HTML. Load iteratively:

```clojure
;; From bb REPL
(require '[bb-mcp-server.mcp-client :as mcp])

;; Load UI component
(mcp/browser-eval! "browser-1"
  '(do
     (require '[code-browser.panels :as panels])
     (panels/mount! (js/document.getElementById "app"))))

;; Iterate live
(mcp/browser-eval! "browser-1"
  '(swap! code-browser.state/!layout assoc :ns-width "30%"))

;; Hot reload a namespace
(mcp/browser-load-file! "browser-1"
  "modules/sente-browser/resources/scittle/code_browser/panels.cljs")
```

---

## Dependencies

- `sente-browser` - WebSocket communication, bidirectional atom sync
- `clojure-lsp` - **Primary data source** for static analysis (required)
- `nrepl` - Runtime introspection (Phase 2)
- CodeMirror 6 - Source display via esm.sh
- Reagent (via Scittle) - UI framework, reactive atoms

---

## Design Decisions

### LSP-First (No Custom Parsing)

**Decision:** Use clojure-lsp as the sole data source for static analysis. No custom source parsing.

**Rationale:**
- clojure-lsp already parses source correctly (handles macros, protocols, metadata)
- We already have the module working (Phase 5.5 complete)
- Reimplementing parsing has very low ROI
- Better to invest in learning LSP API thoroughly
- LSP provides: symbols, definitions, references, call hierarchy, diagnostics

**LSP Tools for Code Browser:**

| Need | LSP Tool | Returns |
|------|----------|---------|
| Namespace list | `clj-symbols` (workspace) | All symbols grouped by file |
| Vars in namespace | `clj-symbols` (document) | Functions, vars, protocols in file |
| Var metadata | `clj-hover` | Arglists, docstring, type info |
| Var source location | `clj-definition` | File, line, column |
| Source code | Read file at location | Actual code text |

---

## Multi-File Namespace Handling

Some codebases split a single namespace across multiple files (via `in-ns` or split definitions). This requires special handling in both views.

### Detection

```clojure
;; A namespace spans multiple files if:
(> (count (distinct (map :filename symbols-for-ns))) 1)
```

### Two Views, Two Approaches

| View | Purpose | Multi-file handling |
|------|---------|---------------------|
| **Alpha-sorted** | Finding things | Merge all vars, sort alphabetically. Optional file badges. |
| **File/Eval-order** | Understanding structure | Show files in load order with dividers, forms in sequence within. |

### File-Order View Layout (Multi-File)

```
📁 foo.bar (File Order)
──────────────────
─── core.clj ───────────
  ns         namespace
  helper     private-fn
  fn1        function
─── extra.clj ──────────
  ns         namespace   ← Each file has its own ns form!
  fn2        function
─── macros.clj ─────────
  ns         namespace
  fn3        macro
```

**Key insight:** Each file has its own `(ns foo.bar ...)` with potentially different `:require` clauses.
- Aliases/refers may differ per file
- Show each file's ns form separately
- Aliases panel shows merged view with file indicators + conflict warnings

### Alpha-Sorted View Layout (Simpler)

```
📁 foo.bar (Alpha)
──────────────────
  fn1        function     [core.clj]    ← optional file badge
  fn2        function     [extra.clj]
  fn3        macro        [macros.clj]
  helper     private-fn   [core.clj]
```

- Single merged list, sorted alphabetically
- NS form shown once (from "primary" file - first alphabetically)
- Optional `[file.clj]` badge for disambiguation

### Inferring File Load Order

**Algorithm:** Build dependency graph from var-usages across files in same ns:
```clojure
;; For each file F in namespace N:
;;   For each var-usage U in F where U.to == N (same ns):
;;     Find which file D defines that var
;;     If D != F: Add edge D → F (D must load before F)
;; Topologically sort files
```

**Data available from clj-kondo:**
- `:var-definitions` with `:filename` - which file defines each var
- `:var-usages` with `:filename`, `:to` (target ns), `:name` - cross-file usage

**Edge cases:**
- `declare` - forward reference, complicates simple dependency analysis
- Circular deps - error condition, show warning to user
- Independent files - no dependency detected, use alphabetical fallback
- Macros - expand at compile time, may not appear in var-usages

### Aliases Panel for Multi-File NS

```
Aliases (merged from 3 files)
  str → clojure.string       [core.clj]
  set → clojure.set          [extra.clj]
  ⚠️ json → cheshire.core    [core.clj]
  ⚠️ json → data.json        [extra.clj]  ← CONFLICT!
```

**Conflict detection:** Same alias pointing to different namespaces across files.

### UI Indicators

- **Namespace list:** `foo.bar (3 files)` - count indicator for multi-file ns
- **Symbol list:** File dividers in file-order view, badges in alpha view
- **Aliases panel:** File source indicators, conflict warnings

---

## Design Decisions Summary

| Question | Decision | Rationale |
|----------|----------|-----------|
| CodeMirror integration | ES modules via esm.sh + custom wrapper | No existing wrapper; esm.sh is simple |
| CM6 namespace | Separate `scittle-cm6` | Reusable outside code-browser |
| Reagent vs vanilla | Reagent atoms | Reactivity needed |
| UI code loading | Via nREPL (not hardcoded) | Live iteration without page refresh |
| Data source | clojure-lsp only (no custom parsing) | LSP handles macros/protocols correctly |
| Dev config | Dedicated `bb-code-browser-dev-system.edn` | All modules in one config |
| Default test project | bb-mcp-server itself | Dogfooding, always available |
| Atom sync location | Bootstrap bundle (preloaded) | Must exist before UI code loads |

**Open questions:**
- **Persistence:** localStorage for layout preferences?
- **Theming:** Dark/light mode, match system preference?
- **Initial data load:** Push on connect or request on demand?

---

## Feedback / Ideas

**UI/UX:**
- Tree view for namespaces (grouped by prefix: `app.*`, `lib.*`)
- "Go to REPL" button for selected var
- Docstrings in tooltip on hover
- Breadcrumb navigation: project > namespace > var
- Keyboard navigation (j/k for list, Enter for select)
- Resizable panels via drag handles

**REPL-driven power features:**
- `(cb/watch-var! 'my.ns/foo)` - Auto-refresh source when var changes
- `(cb/diff-view! 'my.ns)` - Show static vs runtime diff
- `(cb/eval-in-context! 'my.ns/foo expr)` - Eval with var's bindings
- Layout presets: `(cb/layout! :minimal)`, `(cb/layout! :full)`
- Custom panels: `(cb/add-panel! :my-panel [my-component])`

**Integration points:**
- rebel-readline integration (select var → paste into REPL)
- clojure-lsp watch mode (auto-refresh on file save)
- Browser notifications on namespace reload

---

## Next Steps

**Phase 0: Dev Infrastructure**
1. Create `bb-code-browser-dev-system.edn` config
2. Update bootstrap HTML with preloaded scripts (Reagent, CM6, trove)
3. Create `scittle-cm6` namespace (reusable CM6 wrapper)
4. Implement atom sync primitives in bootstrap bundle
5. Add error boundary for safe REPL development
6. Test: load UI code via nREPL, iterate live

**Phase 1: Static Browsing**
1. Namespace list panel (Reagent)
2. Vars list panel
3. Source viewer (CM6, read-only)
4. Filter components (wildcard/regex)
5. Wire clojure-lsp as data source

---

*Last Updated: 2026-01-15*
