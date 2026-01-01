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

```
┌─────────────────────────────────────────────────────────────────────┐
│  [Filter: ________] [Static ▼] [Refresh]                            │
├──────────────────┬──────────────────┬───────────────────────────────┤
│ Namespaces       │ Vars             │ Source                        │
│ ──────────────── │ ──────────────── │ ─────────────────────────────│
│ [filter_______]  │ [filter_______]  │                               │
│                  │                  │ (defn my-function             │
│ ☐ app.core      │ app.core/start   │   "Docstring here"            │
│ ☑ app.db        │ app.core/stop    │   [arg1 arg2]                 │
│ ☐ app.handlers  │ ▶app.db/connect  │   (let [x (foo arg1)]         │
│ ☑ app.routes    │ app.db/query     │     (bar x arg2)))            │
│                  │ app.routes/home  │                               │
│                  │                  │                               │
└──────────────────┴──────────────────┴───────────────────────────────┘
```

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

CodeMirror 6 is a JavaScript library. Integration options:

**Option 1: JS Interop**
```clojure
(def cm-view (js/EditorView.
  #js {:doc "code here"
       :extensions #js [(js/clojure)]}))
```

**Option 2: Wrapper Component**
Create a React/Reagent wrapper that manages CM6 lifecycle.

**Option 3: Pre-built Scittle CM6 integration**
Check if one exists (e.g., from Maria.cloud, Clerk).

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

## Dependencies

- `sente-browser` - WebSocket communication, bidirectional atom sync
- `clojure-lsp` - **Primary data source** for static analysis (required)
- `nrepl` - Runtime introspection (Phase 2)
- CodeMirror 6 - Source display (or `<pre>` + highlight.js for MVP)
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

## Questions to Resolve

1. **CodeMirror integration:** Use existing Scittle/CM6 wrapper or build custom? Start with `<pre>` + highlight.js?
2. **Multi-project:** Support browsing multiple projects? (Probably yes - switch via REPL)
3. **Persistence:** Remember selected namespaces across sessions? (localStorage or server-side?)
4. **Theming:** Dark/light mode, match system preference?
5. **Reagent vs vanilla:** Use Reagent atoms or plain Scittle atoms? (Reagent for reactivity)
6. **Initial data load:** Push on connect or request on demand?

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

1. **Prototype bidirectional atom sync** - Core infrastructure
2. **Minimal namespace list** - Server pushes, browser renders
3. **Filter component** - Wildcard matching
4. **Vars panel** - Wire to namespace selection
5. **Source display** - Simple `<pre>` first, CM6 later

---

*Last Updated: 2025-12-31*
