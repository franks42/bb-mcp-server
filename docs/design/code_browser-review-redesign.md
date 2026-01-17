# Code Browser Review & Redesign

**Status:** Design Discussion
**Date:** 2026-01-17
**Files under review:**
- `modules/sente-browser/src/browser/code_browser.cljs` (1,101 lines, 44 KB)
- `modules/sente-browser/src/sente_browser/code_browser.clj` (2,458 lines, 128 KB)

---

## Motivation

The code browser has grown organically through 20+ phases, accumulating features without systematic refactoring. Now at ~3,500 lines total, it's time to:

1. **Identify coupling and complexity** - What's tangled? What's hard to extend?
2. **Design for modularity** - Clean separation of concerns
3. **Simplify reactive dependencies** - Clear data flow, predictable updates
4. **Enable future extension** - Phase 2 (Live Mode) and beyond

**Constraints:**
- No backwards compatibility required - we own both ends
- Can replace both server and client together
- atom-sync infrastructure is solid and should be kept

---

## Current Architecture Overview

### Server Side (`code_browser.clj`)

```
┌─────────────────────────────────────────────────────────────┐
│                    code_browser.clj                          │
├─────────────────────────────────────────────────────────────┤
│ State: !code-browser-state (single atom, ~20 keys)          │
├─────────────────────────────────────────────────────────────┤
│ Analysis Layer:                                              │
│   - clj-kondo integration (analyze-file, analyze-project)   │
│   - LSP integration (fallback, go-to-definition)            │
│   - JAR analysis (lazy scanning, source reading)            │
│   - Git operations (status, clone)                          │
├─────────────────────────────────────────────────────────────┤
│ Event Handlers (~15 handlers):                               │
│   - handle-request-namespaces                               │
│   - handle-request-symbols                                  │
│   - handle-request-var-source                               │
│   - handle-set-project-root                                 │
│   - handle-explore-jar-dep                                  │
│   - handle-navigate-to-symbol                               │
│   - handle-clone-repo                                       │
│   - ... etc                                                 │
├─────────────────────────────────────────────────────────────┤
│ Side Effects:                                                │
│   - File watching (debounced)                               │
│   - LSP initialization (async)                              │
│   - atom-sync push (automatic via watcher)                  │
└─────────────────────────────────────────────────────────────┘
```

### Browser Side (`code_browser.cljs`)

```
┌─────────────────────────────────────────────────────────────┐
│                    code_browser.cljs                         │
├─────────────────────────────────────────────────────────────┤
│ State:                                                       │
│   - !synced-state (from atom-sync, server-owned)            │
│   - !ui-state (local UI state - expanded panels, filters)   │
├─────────────────────────────────────────────────────────────┤
│ Event Sending:                                               │
│   - select-namespace!, select-symbol!, refresh!             │
│   - add-project!, clone-repo!, explore-jar-dep!             │
│   - navigate-to-symbol!                                     │
├─────────────────────────────────────────────────────────────┤
│ Event Receiving:                                             │
│   - handle-directory-event (clone progress/result)          │
├─────────────────────────────────────────────────────────────┤
│ UI Components (~15 components):                              │
│   - namespace-panel, symbols-panel, source-panel            │
│   - symbol-inspector (tabs: Source, Doc, Deps, Callers)     │
│   - aliases-panel, git-status-bar                           │
│   - directory-browser-dialog, clone-repo-input              │
│   - ... etc                                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## Identified Problems

### 1. Monolithic State Atom

**Problem:** Single `!code-browser-state` atom with ~20 keys creates implicit coupling.

```clojure
;; Current state shape (simplified)
{:namespaces [...]
 :selected-ns "..."
 :symbols [...]
 :symbols-by-ns {...}
 :selected-symbol "..."
 :source-by-var {...}
 :git {...}
 :projects [...]
 :current-project "..."
 :ns-files {...}
 :namespace-usages [...]
 :var-usages [...]
 :explored-deps [...]
 :ns->jar {...}
 :jar-analyses {...}
 :callers-cache {...}
 :deps-cache {...}
 ...}
```

**Issues:**
- Any change triggers full atom-sync diff
- Hard to reason about which handler affects which state
- Caching (symbols-by-ns, source-by-var) mixed with selection state
- No clear ownership boundaries

### 2. Handler Spaghetti

**Problem:** Handlers have complex interdependencies and side effects.

```clojure
;; Example: handle-set-project-root does:
;; 1. Validates path
;; 2. Resets most state fields
;; 3. Spawns async LSP init
;; 4. Spawns async namespace loading
;; 5. Refreshes git info
;; 6. Initializes NS->JAR mapping
```

**Issues:**
- Hard to test individual behaviors
- Side effects scattered throughout
- No clear "what happens when X" documentation
- Race conditions possible between async operations

### 3. Mixed Concerns

**Problem:** Single file mixes multiple distinct responsibilities.

**Server concerns currently mixed:**
- Static analysis (clj-kondo)
- LSP integration
- JAR/dependency analysis
- Git operations
- File watching
- State management
- Event routing

**Browser concerns currently mixed:**
- UI components (Reagent)
- Event sending
- Event receiving
- Local state management
- Derived computations

### 4. Unclear Reactive Dependencies

**Problem:** Hard to trace what triggers what.

```
User clicks namespace
  → send-event! :code-browser/select-ns
  → server handler
  → swap! state with multiple assocs
  → atom-sync watcher fires
  → generates diff ops
  → pushes to browser
  → Reagent re-renders... what exactly?
```

**Issues:**
- No explicit dependency graph
- Reagent reactions are implicit
- Server-side triggers are scattered
- Debouncing logic embedded in handlers

### 5. Caching Strategy Unclear

**Problem:** Multiple caching approaches without clear strategy.

- `:symbols-by-ns` - accumulates forever
- `:source-by-var` - accumulates forever
- `:jar-analyses` - accumulates forever
- `:callers-cache` / `:deps-cache` - per-request caches
- File watcher invalidation - partial, tied to selected-ns

**Questions:**
- When should caches invalidate?
- What's the memory budget?
- How does file watching interact with caches?

---

## Design Goals for Refactor

### G1: Clear Module Boundaries

Split into focused modules with explicit interfaces:

```
code-browser/
├── analysis/
│   ├── kondo.clj      # clj-kondo integration
│   ├── lsp.clj        # LSP fallback operations
│   └── jar.clj        # JAR scanning and source reading
├── git/
│   └── operations.clj # Git status, clone, branch
├── state/
│   ├── core.clj       # State atoms and accessors
│   ├── cache.clj      # Caching with invalidation
│   └── sync.clj       # atom-sync registration
├── handlers/
│   ├── navigation.clj # select-ns, select-symbol, navigate
│   ├── project.clj    # set-project, add-project, clone
│   └── analysis.clj   # request-symbols, request-source
└── core.clj           # Public API, initialization
```

### G2: Explicit State Domains

Separate state into distinct atoms with clear ownership:

```clojure
;; Project state (changes rarely)
!project-state
{:projects [...]
 :current-project "..."
 :ns->jar {...}}

;; Navigation state (changes on user interaction)
!navigation-state
{:selected-ns "..."
 :selected-symbol "..."
 :selected-tab :source}

;; Cache state (accumulates, has invalidation)
!cache-state
{:symbols-by-ns {...}
 :source-by-var {...}
 :jar-analyses {...}}

;; Derived/computed (recalculated on demand)
!derived-state
{:namespaces [...]      ; from project analysis
 :current-symbols [...] ; from cache + selection
 :current-source {...}} ; from cache + selection
```

### G3: Declarative Data Flow

Make dependencies explicit:

```clojure
;; Server: Declare what depends on what
(def state-graph
  {:namespaces    [:current-project]
   :symbols       [:selected-ns :symbols-by-ns]
   :source        [:selected-symbol :source-by-var]
   :git-info      [:current-project]
   :aliases       [:selected-ns :namespace-usages]})

;; When :current-project changes, recompute :namespaces and :git-info
;; When :selected-ns changes, recompute :symbols and :aliases
```

### G4: Command/Query Separation

Separate state changes from data retrieval:

```clojure
;; Commands (change state, return nil or status)
(select-namespace! ns)
(select-symbol! sym)
(set-project! path)

;; Queries (read state, pure functions)
(get-namespaces state)
(get-symbols state ns)
(get-source state ns var)

;; Effects (side effects, explicit)
(analyze-with-kondo! path)
(start-lsp! project-root)
(clone-repo! url target-dir)
```

### G5: Browser Components as Pure Functions

Components should be pure renders of state:

```clojure
;; Pure component - just renders props
(defn namespace-item [{:keys [ns selected? file-count on-click]}]
  [:div {:class (when selected? "selected")
         :on-click on-click}
   ns
   (when (> file-count 1)
     [:span.badge (str "(" file-count " files)")])])

;; Container - connects to state
(defn namespace-list []
  (let [namespaces @(subscribe [:namespaces])
        selected   @(subscribe [:selected-ns])]
    [:div.namespace-list
     (for [ns namespaces]
       ^{:key ns}
       [namespace-item {:ns ns
                        :selected? (= ns selected)
                        :on-click #(dispatch [:select-ns ns])}])]))
```

---

## Proposed Architecture

### Option A: Micro-Modules with Message Bus

```
┌─────────────────────────────────────────────────────────────┐
│                      Message Bus                             │
│  (all communication flows through central dispatcher)        │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Analysis   │      │    Git      │      │  Navigation │
│   Module    │      │   Module    │      │   Module    │
└─────────────┘      └─────────────┘      └─────────────┘
```

**Pros:** Very decoupled, easy to test, easy to extend
**Cons:** More boilerplate, indirection overhead

### Option B: Layered Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  (Browser components, event sending)                         │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                         │
│  (Handlers, state transitions, business logic)               │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    Domain Layer                              │
│  (Analysis, Git, JAR reading - pure functions)               │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    Infrastructure Layer                      │
│  (atom-sync, LSP client, shell commands)                     │
└─────────────────────────────────────────────────────────────┘
```

**Pros:** Clear hierarchy, familiar pattern, good testability
**Cons:** Can become rigid, vertical slicing harder

### Option C: Feature Slices

```
code-browser/
├── namespaces/          # Namespace browsing feature
│   ├── server.clj       # Server handlers
│   ├── client.cljs      # Browser components
│   └── state.cljc       # Shared state shape
├── symbols/             # Symbol browsing feature
│   ├── server.clj
│   ├── client.cljs
│   └── state.cljc
├── source/              # Source viewing feature
│   ├── server.clj
│   ├── client.cljs
│   └── state.cljc
├── dependencies/        # JAR exploration feature
│   ├── server.clj
│   ├── client.cljs
│   └── state.cljc
├── git/                 # Git integration feature
│   ├── server.clj
│   └── client.cljs
└── shared/              # Common utilities
    ├── state.cljc       # State atoms, sync setup
    └── events.cljc      # Event definitions
```

**Pros:** Feature-focused, easy to understand scope, good for parallel work
**Cons:** Cross-feature dependencies need care, some duplication

---

## Future Use Cases (Design Drivers)

These future requirements should heavily influence the new design:

### Unified Project Model

Projects come from many sources but should be treated uniformly:

| Source | Example | Version |
|--------|---------|---------|
| Local directory | `/path/to/bb-mcp-server` | git commit SHA / branch |
| JAR file | `~/.m2/.../trove-1.0.0.jar` | Maven version |
| GitHub URL | `github.com/taoensso/trove` | branch / tag / commit |
| Live nREPL | `localhost:7888` | runtime (no version) |

**Key insight:** The UI and code browser logic shouldn't care WHERE the data comes from. A namespace is a namespace whether it's from a local file, a JAR, or a live REPL.

### Hierarchical Data Model

```
Project (versioned)
  └── Namespace
        └── Symbol (var / toplevel-form / etc)
              ├── code
              ├── deps (what I call)
              ├── callers (who calls me)
              ├── docs
              └── examples
```

This is the **universal structure** regardless of source.

### URI Schema for Unique Identification

A URI scheme could provide:
- Unique identification of any element
- Natural hierarchy encoding
- Version awareness
- Cross-reference capability
- Serializable references (for history, bookmarks, linking)

**Proposed URI format:**

```
<source>://<project>@<version-or-snapshot>/<namespace>/<symbol>

Static source examples:
  dir://bb-mcp-server@abc123/bb-mcp-server.main/register!
  dir://bb-mcp-server@main/bb-mcp-server.main/register!    ; branch ref
  jar://taoensso.trove@1.0.0/taoensso.trove/log!
  github://taoensso/trove@v1.2.0/taoensso.trove.core/init!

Live source examples:
  nrepl://localhost:7888@01950a3b-1234-7def/user/my-fn     ; UUIDv7 snapshot
  nrepl://localhost:7888@latest/user/my-fn                  ; "current" (re-fetches)
```

**URI parsing yields:**
```clojure
{:source :dir|:jar|:github|:nrepl
 :project "bb-mcp-server"
 :version "abc123"           ; or UUIDv7 for live
 :version-type :static|:temporal
 :namespace "bb-mcp-server.main"
 :symbol "register!"}
```

**Benefits:**
- Parse any URI to get (source, project, version, ns, symbol)
- Build dependency graphs with URI references
- "Go to definition" becomes "resolve URI"
- History/bookmarks are just lists of URIs
- Diff between versions: compare URIs at different @version

### UI Consistency

Current UI treats projects differently from namespaces differently from symbols. But they're all **lists of things you can select**:

```
┌─────────────┬─────────────┬─────────────┬─────────────┐
│  Projects   │ Namespaces  │   Symbols   │   Detail    │
│  (select)   │  (select)   │  (select)   │   (view)    │
├─────────────┼─────────────┼─────────────┼─────────────┤
│ bb-mcp ←    │ main ←      │ register! ← │ Source tab  │
│ trove       │ registry    │ unregister! │ Doc tab     │
│ clojure.core│ handlers    │ list-tools  │ Deps tab    │
│             │             │             │ Callers tab │
└─────────────┴─────────────┴─────────────┴─────────────┘
```

**Same pattern everywhere:**
- List of items
- Filter/search
- Single selection
- Selection drives next panel

### Version Awareness

Versions are essential for:
- Knowing which commit you're looking at
- Comparing changes between versions
- Reproducible references
- Detecting stale data (server restart, file changes)

**Two fundamentally different source types:**

| Property | Static Sources | Live Sources (nREPL) |
|----------|----------------|----------------------|
| **Identity** | Immutable version | Temporal snapshot |
| **Examples** | Git SHA, Maven version, tag | UUIDv7 timestamp |
| **Consistency** | Guaranteed | Best-effort (non-transactional) |
| **Repeatability** | Same version = same data | Never identical twice |
| **Caching** | Safe to cache indefinitely | Stale immediately |
| **Nature** | "What it was" | "What we saw at time T" |

**Version/Snapshot sources:**
- Git: `branch`, `tag`, or `commit-sha` (static)
- JAR: Maven version from `pom.xml` or filename (static)
- GitHub: branch/tag/commit from URL (static)
- nREPL: UUIDv7 snapshot ID (temporal) - represents "a view into live state at this moment"

**Live system reality:**
- Runtime state changes unpredictably
- Updates are not transactional
- Snapshots may be internally inconsistent
- Some apps are predictable, others behave like strange attractors
- Best we can do: recognize imperfection, timestamp our observations

**Future possibilities:**
- Live monitoring/tracing of vars/atoms
- Change detection via watch callbacks
- Temporal queries ("show me state from 5 minutes ago")

---

## Decisions Made

### D1: Clean Slate Rewrite
- No incremental migration
- New namespace structure alongside old
- Switch over when ready, delete old code
- Freedom to redesign everything

### D2: Datalevin as State Backend (with Portable Interface)
- Server uses **Datalevin** for metadata (persistence, full-text search)
- Portable `IDatalogDB` protocol allows swapping Datascript for tests or future needs
- URI as entity `:db/id` - natural fit
- Datalog queries for relationships (deps, callers, cross-project)
- Export views as plain maps for atom-sync to browser
- Browser stays simple (Reagent atoms) initially

### D5: Separate Metadata from Content (Post-Review)
**All three reviewers (Gemini, GPT-5.2, Grok) strongly recommended this.**

- **Datascript/Datalevin: metadata only** - names, types, line numbers, file paths, relationships
- **Content store: separate** - source code strings, docs, examples in LRU cache or on-demand fetch
- **Rationale:** Datascript optimized for indexed metadata queries, not large blobs. Storing source strings would bloat DB, degrade query performance, explode memory.

```clojure
;; IN Datalog DB (metadata)
{:uri/string "dir://proj@v/ns/sym"
 :symbol/name "register!"
 :symbol/type :defn
 :symbol/line 42
 :symbol/file "src/proj/core.clj"  ; pointer, not content
 :symbol/deps [...refs...]}

;; SEPARATE (content store)
(def !content-cache (atom {}))  ; LRU or bounded cache

(defn get-source [uri]
  (or (get @!content-cache uri)
      (let [content (fetch-from-file-or-jar uri)]
        (swap! !content-cache assoc uri content)
        content)))
```

### D6: Layered Server + Feature Slices UI (Post-Review)
**Reviewers noted Feature Slices alone can lead to circular dependencies on server.**

- **Server: Layered architecture**
  ```
  Infrastructure (LSP, file IO, shell)
       ↓
  Domain (analysis, git, jar-reading)
       ↓
  Application (handlers, state, sync)
       ↓
  Presentation (atom-sync exports, event routing)
  ```

- **Client: Feature slices** - projects/, namespaces/, symbols/, detail/ components

### D7: Isolate Volatile Sources (Post-Review)
- Static sources (dir, jar, github): can share caches, persist across sessions
- Temporal sources (nREPL): isolated cache, never persisted, tagged with UUIDv7 snapshot
- UI shows "Live" badge for volatile sources to indicate links may rot

### D3: URI-Centric Design
- Every element addressable via URI
- URI is the primary key everywhere
- Navigation = "resolve URI and display"
- History/bookmarks = list of URIs

### D4: Static vs Temporal Sources
- Static (dir, jar, github): immutable versions, safe to cache
- Temporal (nrepl): UUIDv7 snapshots, best-effort consistency
- `@latest` pseudo-version for "re-fetch now"

## Client-Server Sync Options

### Option 1: atom-sync (Current Infrastructure)

Server exports views as plain maps, atom-sync diffs and pushes to browser.

```
Server Datascript → Export Views (maps) → atom-sync → Browser Atoms
```

```clojure
;; Server: export views on every DB change
(defn export-views [db]
  {:projects   (query-projects db)
   :namespaces (query-namespaces db current-project)
   :symbols    (query-symbols db current-ns)})

;; Browser: receives plain maps
@!synced-state  ; => {:projects [...] :namespaces [...] ...}
```

**Pros:**
- Already built and working
- Simple browser code (just atoms)
- No query logic on client
- Automatic diffing minimizes bandwidth

**Cons:**
- Server must pre-compute all views
- Client can't request custom queries
- Adding new views requires server changes
- All clients get same views (no personalization)

### Option 2: Query Protocol over Sente

Client subscribes to queries, server executes against Datascript and pushes results.

```
Browser ←→ Sente ←→ Server Datascript
  │                      │
  │ [:db/subscribe ...]  │
  │ ──────────────────►  │
  │                      │ execute query
  │ [:db/subscription ..] │
  │ ◄──────────────────  │
  │                      │
  │   (on data change)   │
  │ [:db/subscription ..] │
  │ ◄──────────────────  │
```

```clojure
;; Client: subscribe to named query
(def !namespaces (subscribe! :ns-list :namespaces-for @!current-project))

;; Server: predefined safe queries
(def queries
  {:projects       '[:find [(pull ?p [...]) ...] :where ...]
   :namespaces-for '[:find [...] :in $ ?project-uri :where ...]
   :symbols-for    '[:find [...] :in $ ?ns-uri :where ...]})

;; Server: on DB change, re-run affected subscriptions and push
```

**Pros:**
- Client queries exactly what it needs
- Server doesn't pre-compute unused views
- Easy to add new queries (just add to query map)
- Per-client subscriptions (different args)
- Natural path to client-side Datascript later

**Cons:**
- More complex client (subscription management)
- Server tracks per-client state
- Need to validate/sanitize queries
- Subscription lifecycle management

### Option 3: Hybrid (Recommended)

Use both: atom-sync for global state, query protocol for dynamic/personalized data.

```clojure
;; atom-sync for shared state (all clients see same)
:synced {:projects [...]}  ; list of available projects

;; Query protocol for selection-dependent data
(subscribe! :namespaces :namespaces-for @!selected-project)
(subscribe! :symbols :symbols-for @!selected-ns)
(subscribe! :detail :symbol-detail @!selected-symbol)
```

**Pros:**
- Best of both worlds
- Simple things stay simple (atom-sync)
- Complex queries when needed (subscriptions)
- Can migrate incrementally

**Cons:**
- Two sync mechanisms to understand
- Need clear guidelines on when to use which

### Comparison Matrix

| Aspect | atom-sync | Query Protocol | Hybrid |
|--------|-----------|----------------|--------|
| Browser complexity | Low | Medium | Medium |
| Server complexity | Medium | Medium | Medium |
| Flexibility | Low | High | High |
| Bandwidth efficiency | Medium | High | High |
| Custom queries | No | Yes | Yes |
| Existing infra | Yes | Build new | Partial |
| Migration effort | None | High | Low |

### Query Protocol: Security Considerations

If implementing query protocol, use **named queries only** (not arbitrary Datalog):

```clojure
;; SAFE: predefined query map
(def queries
  {:projects       '[:find ...]
   :namespaces-for '[:find ... :in $ ?project ...]
   :symbols-for    '[:find ... :in $ ?ns ...]})

(defn handle-query [{:keys [query args]}]
  (if-let [q (get queries query)]
    (apply d/q q @!db args)
    (throw (ex-info "Unknown query" {:query query}))))

;; UNSAFE: arbitrary Datalog from client
;; (d/q (:q request) @!db)  ; DON'T DO THIS
```

### Decision Pending

**Question:** Which approach for the clean slate rewrite?

- **Option 1 (atom-sync):** Simpler, proven, sufficient for current needs
- **Option 2 (Query Protocol):** More powerful, future-proof, more work
- **Option 3 (Hybrid):** Pragmatic middle ground

Current leaning: **Option 3 (Hybrid)** - keep atom-sync for now, add query protocol when needed.

---

## Datascript vs Datalevin

Both are Datalog databases with similar query languages. Key differences:

### Datascript

| Aspect | Details |
|--------|---------|
| **Storage** | In-memory only |
| **Persistence** | Manual (serialize to EDN/Transit, restore on startup) |
| **Size limit** | Must fit in JVM heap |
| **ClojureScript** | ✅ Yes - can run in browser |
| **Babashka** | ✅ Yes (via pod or built-in) |
| **Performance** | Very fast for small-medium datasets |
| **Maturity** | Battle-tested, widely used |
| **Full-text search** | ❌ No (would need external) |

### Datalevin

| Aspect | Details |
|--------|---------|
| **Storage** | LMDB-backed (memory-mapped files) |
| **Persistence** | Built-in, automatic |
| **Size limit** | Can exceed RAM (memory-mapped) |
| **ClojureScript** | ❌ No - JVM/Babashka only |
| **Babashka** | ✅ Yes (native support) |
| **Performance** | Fast reads, durable writes |
| **Maturity** | Newer but production-ready |
| **Full-text search** | ✅ Built-in |

### For Code Browser Use Case

| Requirement | Datascript | Datalevin | Notes |
|-------------|------------|-----------|-------|
| Babashka compatible | ✅ | ✅ | Both work |
| Browser-side option | ✅ | ❌ | If we want client Datalog later |
| Persistence | Manual | Automatic | Datalevin wins for "remember projects" |
| Large codebases | ⚠️ | ✅ | Datalevin handles bigger datasets |
| Full-text search | ❌ | ✅ | Useful for "find in all sources" |
| Simplicity | ✅ | ✅ | Both have clean APIs |

### Recommendation

**Datalevin** seems better suited for code browser because:
1. **Persistence** - Don't re-analyze projects on every server restart
2. **Size** - Can handle monorepos and many projects without heap pressure
3. **Full-text search** - "Find symbol by name across all projects" is a natural fit
4. **Babashka native** - No pod overhead

**Trade-off:** Lose browser-side Datalog option. But we're planning browser atoms + atom-sync anyway, so this is acceptable.

**Alternative:** Start with Datascript (simpler, in-memory), migrate to Datalevin if persistence/scale becomes important. APIs are similar enough.

### Decision: Datalevin with Portable Interface

**Decision:** Start with Datalevin, but keep Datalog interface portable.

Both libraries share nearly identical query APIs:

```clojure
;; These work identically in both Datascript and Datalevin:
(d/q '[:find ?e :where [?e :symbol/name "foo"]] db)
(d/pull db '[*] eid)
(d/transact conn [[:db/add eid :symbol/name "bar"]])
```

**Differences to abstract:**

| Operation | Datascript | Datalevin |
|-----------|------------|-----------|
| Create DB | `(d/create-conn schema)` | `(d/get-conn path schema)` |
| Get DB value | `@conn` | `(d/db conn)` |
| Close | N/A (GC) | `(d/close conn)` |
| Full-text | N/A | `(d/fulltext-datoms db ...)` |

**Portable interface:**

```clojure
(ns code-browser.db.protocol
  "Portable Datalog interface - works with Datascript or Datalevin")

(defprotocol IDatalogDB
  (q [this query] [this query args] "Execute Datalog query")
  (pull [this pattern eid] "Pull entity")
  (transact! [this tx-data] "Transact data")
  (db [this] "Get current DB value")
  (close! [this] "Close connection (no-op for Datascript)"))

;; Datalevin implementation
(defrecord DatalevinDB [conn]
  IDatalogDB
  (q [_ query] (d/q query (d/db conn)))
  (q [_ query args] (apply d/q query (d/db conn) args))
  (pull [_ pattern eid] (d/pull (d/db conn) pattern eid))
  (transact! [_ tx-data] (d/transact! conn tx-data))
  (db [_] (d/db conn))
  (close! [_] (d/close conn)))

;; Datascript implementation (if needed for testing/browser)
(defrecord DatascriptDB [conn]
  IDatalogDB
  (q [_ query] (d/q query @conn))
  (q [_ query args] (apply d/q query @conn args))
  (pull [_ pattern eid] (d/pull @conn pattern eid))
  (transact! [_ tx-data] (d/transact! conn tx-data))
  (db [_] @conn)
  (close! [_] nil))  ; no-op

;; Factory
(defn create-db
  [{:keys [backend path schema]}]
  (case backend
    :datalevin (->DatalevinDB (dl/get-conn path schema))
    :datascript (->DatascriptDB (ds/create-conn schema))))
```

**Benefits:**
- Start with Datalevin (persistence, full-text)
- Tests can use Datascript (faster, no disk)
- Future browser option remains open
- Easy to switch if needed

---

## Questions Still Open

1. **Sync approach:** atom-sync vs query protocol vs hybrid (leaning hybrid)

2. **Async handling:** Futures vs core.async vs promises?

3. **Event protocol:** Keep current keywords or formalize with specs?

4. **Testing strategy:** Unit tests per module? Integration tests? Browser tests?

5. **Schema approach:** Define upfront or let it emerge?

## Decisions Finalized

- **DB choice:** ✅ Datalevin with portable `IDatalogDB` interface

---

## New Architecture: Clean Slate Design

### Namespace Structure

```
modules/sente-browser/src/
├── code_browser/                    # NEW: Clean slate implementation
│   ├── uri.cljc                     # URI parsing/generation/validation
│   ├── schema.cljc                  # Datascript schema definition
│   ├── db.clj                       # Server Datascript instance + queries
│   ├── sync.clj                     # Export views for atom-sync
│   │
│   ├── sources/                     # Data source adapters
│   │   ├── protocol.clj             # IProjectSource protocol
│   │   ├── directory.clj            # Local directory (clj-kondo)
│   │   ├── jar.clj                  # JAR file analysis
│   │   ├── github.clj               # GitHub clone + analyze
│   │   └── nrepl.clj                # Live nREPL introspection
│   │
│   ├── handlers.clj                 # Event handlers (thin, delegate to db)
│   ├── server.clj                   # Public API, initialization
│   │
│   └── ui/                          # Browser components
│       ├── state.cljs               # Local UI state (panels, filters)
│       ├── events.cljs              # Event sending helpers
│       ├── components/
│       │   ├── list.cljs            # Generic selectable list
│       │   ├── projects.cljs        # Project list (uses list.cljs)
│       │   ├── namespaces.cljs      # Namespace list
│       │   ├── symbols.cljs         # Symbol list
│       │   ├── detail.cljs          # Detail panel (source/doc/deps/callers)
│       │   └── layout.cljs          # Main layout orchestration
│       └── main.cljs                # Entry point
│
├── sente_browser/
│   └── code_browser.clj             # OLD: Keep until new is ready
└── browser/
    └── code_browser.cljs            # OLD: Keep until new is ready
```

### Datascript Schema (Draft)

```clojure
(def schema
  {;; URI is the universal ID
   :uri/string      {:db/unique :db.unique/identity}
   :uri/source      {}  ; :dir | :jar | :github | :nrepl
   :uri/project     {}  ; project identifier
   :uri/version     {}  ; git SHA | maven ver | UUIDv7
   :uri/version-type {} ; :static | :temporal
   :uri/namespace   {}  ; namespace name (if applicable)
   :uri/symbol      {}  ; symbol name (if applicable)

   ;; Hierarchy via refs
   :uri/parent      {:db/valueType :db.type/ref}

   ;; Project attributes
   :project/root-path   {}
   :project/namespaces  {:db/valueType :db.type/ref
                         :db/cardinality :db.cardinality/many}

   ;; Namespace attributes
   :ns/name         {}
   :ns/file         {}
   :ns/symbols      {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :ns/aliases      {:db/cardinality :db.cardinality/many}  ; [{:alias x :ns y}]
   :ns/refers       {:db/cardinality :db.cardinality/many}

   ;; Symbol attributes (METADATA ONLY - no source code strings!)
   :symbol/name     {}
   :symbol/type     {}  ; :def | :defn | :defmacro | :defmulti | etc
   :symbol/file     {}  ; file path (content fetched on demand)
   :symbol/line     {}  ; start line
   :symbol/end-line {}  ; end line (for extracting source)
   :symbol/doc      {}  ; docstring (small, ok to store)
   :symbol/arglists {}
   :symbol/deps     {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :symbol/callers  {:db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many}})
```

### Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                           SERVER                                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │  directory   │    │     jar      │    │    github    │  Sources  │
│  │   source     │    │    source    │    │    source    │          │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘          │
│         │                   │                   │                   │
│         └───────────────────┼───────────────────┘                   │
│                             ▼                                       │
│                    ┌────────────────┐                               │
│                    │   Datascript   │  Single source of truth       │
│                    │      DB        │  URI-keyed entities           │
│                    └────────┬───────┘                               │
│                             │                                       │
│         ┌───────────────────┼───────────────────┐                   │
│         ▼                   ▼                   ▼                   │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐            │
│  │   projects   │   │  namespaces  │   │   symbols    │  Views     │
│  │     view     │   │     view     │   │     view     │  (maps)    │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘            │
│         │                  │                   │                    │
│         └──────────────────┼───────────────────┘                    │
│                            ▼                                        │
│                    ┌────────────────┐                               │
│                    │   atom-sync    │  Diffs, pushes to browser     │
│                    └────────┬───────┘                               │
│                             │                                       │
└─────────────────────────────┼───────────────────────────────────────┘
                              │ WebSocket
┌─────────────────────────────┼───────────────────────────────────────┐
│                             ▼                           BROWSER     │
│                    ┌────────────────┐                               │
│                    │  !synced-state │  Plain maps from server       │
│                    └────────┬───────┘                               │
│                             │                                       │
│         ┌───────────────────┼───────────────────┐                   │
│         ▼                   ▼                   ▼                   │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐            │
│  │   projects   │   │  namespaces  │   │   symbols    │  Components│
│  │    list      │   │    list      │   │    list      │            │
│  └──────────────┘   └──────────────┘   └──────────────┘            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Example Queries

```clojure
;; Get all namespaces for a project
(d/q '[:find [(pull ?ns [:ns/name :uri/string]) ...]
       :in $ ?project-uri
       :where [?p :uri/string ?project-uri]
              [?p :project/namespaces ?ns]]
     db project-uri)

;; Get all symbols that call a given symbol
(d/q '[:find [(pull ?caller [:symbol/name :uri/string]) ...]
       :in $ ?target-uri
       :where [?target :uri/string ?target-uri]
              [?caller :symbol/deps ?target]]
     db target-uri)

;; Find symbol by name across all projects
(d/q '[:find [(pull ?sym [:symbol/name :uri/string :symbol/type]) ...]
       :in $ ?name
       :where [?sym :symbol/name ?name]]
     db "register!")

;; Get navigation breadcrumb (project → ns → symbol)
(d/q '[:find (pull ?e [:uri/string :uri/project :uri/namespace :uri/symbol]) .
       :in $ ?uri
       :where [?e :uri/string ?uri]]
     db current-uri)
```

---

## Next Steps

1. [x] Discuss architecture options → Decision: Clean slate with Datascript
2. [ ] Prototype URI module (`code_browser/uri.cljc`)
3. [ ] Prototype Datascript schema + basic queries
4. [ ] Build one source adapter (directory) end-to-end
5. [ ] Wire up atom-sync export
6. [ ] Build browser list component
7. [ ] Iterate and expand

---

## Discussion Notes

### 2026-01-17: Initial Design Session

**Context:** Code browser has grown to ~3,500 lines (server + browser) across 20+ phases. Time to step back and redesign for modularity and future extensibility.

**Key insights from discussion:**

1. **URI-centric design** - Frank proposed using URIs as universal identifiers. This unifies navigation, history, bookmarks, and cross-references into a single concept.

2. **"Everything is a list"** - Projects, namespaces, and symbols all follow the same pattern: list → select → detail. This suggests a generic list component pattern.

3. **Static vs Temporal sources** - Important distinction:
   - Static (dirs, JARs, GitHub): have immutable versions (git SHA, Maven version)
   - Temporal (nREPL): use UUIDv7 snapshot IDs, best-effort consistency
   - "Live systems are strange attractors" - recognize the imperfection, timestamp observations

4. **Datascript as state backend** - Frank asked "would it help?" and the answer is yes:
   - URI as `:db/id` is natural fit
   - Datalog queries perfect for relationships (deps, callers)
   - Cross-project queries become trivial
   - Server uses Datascript, exports views to browser via atom-sync

5. **Clean slate approach** - No backwards compatibility needed. Build new architecture alongside old, switch when ready.

**Decisions made:**
- D1: Clean slate rewrite (not incremental)
- D2: Datascript as state backend (server-side)
- D3: URI-centric design (everything addressable)
- D4: Static vs Temporal source distinction

**What's ready for prototyping:**
- URI module (parsing, generation, validation)
- Datascript schema
- Directory source adapter

---

### 2026-01-17: External Review Feedback

**Reviewers:** Gemini 3 Pro, GPT-5.2 Codex, Grok

**Unanimous endorsements:**
- Clean slate rewrite ✅
- URI-centric design ✅ ("Excellent", "Brilliant")
- Datalog DB for relationships ✅
- Hybrid sync strategy ✅

**Critical unanimous recommendation: Separate metadata from content**
- All three reviewers flagged `symbol/source` in Datascript as risky
- Datascript/Datalevin optimized for indexed metadata, not large blobs
- Source strings would bloat DB, degrade performance, explode memory
- **Action:** Store metadata in DB, fetch content on-demand with LRU cache

**Architecture refinement:**
- Server: Layered architecture (to avoid circular dependencies between features)
- Client: Feature slices (natural for UI component tree)

**Additional recommendations incorporated:**
- D5: Separate metadata from content
- D6: Layered server + feature slices UI
- D7: Isolate volatile (nREPL) sources from static caches
- Source adapter protocol: `list-namespaces`, `get-symbols`, `fetch-source`
- UI "Live" badge for volatile sources

**New question raised:** Datascript vs Datalevin
- Datalevin offers persistence, larger datasets, full-text search
- Trade-off: No browser-side option (acceptable given atom-sync plan)
- **Decision:** Start with Datalevin, use portable `IDatalogDB` protocol
- Portable interface allows: Datascript for tests, future flexibility, same query code everywhere

