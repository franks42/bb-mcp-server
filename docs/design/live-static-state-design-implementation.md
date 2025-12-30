# Static + Live State Integration Design

**Status:** Design Phase (Reviewed)
**Created:** 2025-12-29
**Author:** Claude Code Session
**Reviewed:** Gemini (Cascade) - see `live-static-state-design-implementation-review.md`

---

## Problem Statement

Clojure development involves two distinct views of application state:

1. **Static State** (clojure-lsp) - What's on disk
   - Source files, namespaces, function definitions
   - Call graphs, references, dependencies
   - Analyzed via AST parsing

2. **Live State** (nREPL) - What's in the running JVM
   - Currently loaded namespaces and vars
   - Runtime values, dynamically defined functions
   - State modified by REPL evaluation

**The Gap:** When working interactively, these views diverge:

```clojure
;; In REPL (not saved to file):
(def temp-helper (fn [x] (* x 2)))
(defn process [data] (map temp-helper data))

;; clojure-lsp: "temp-helper is undefined"
;; nREPL: temp-helper exists and works
```

Neither tool alone provides a complete picture of the application's current state.

---

## State Change Sources

Application state can change through multiple channels:

| Source | Static (LSP) Sees | Live (nREPL) Sees |
|--------|-------------------|-------------------|
| File save | ✅ (via watch) | ❌ (until loaded) |
| REPL eval | ❌ | ✅ |
| load-file | ❌ | ✅ |
| require/use | ❌ | ✅ |
| alter-var-root | ❌ | ✅ |
| defmethod | ❌ | ✅ |
| Module hot-reload | ✅ (if saved) | ✅ |
| Environment vars | ❌ | ✅ |

---

## Current Capabilities

### What We Have Today

**clojure-lsp (18 CLI commands):**
- `find-symbol` - Find symbols by name across project
- `definition`, `references` - Navigate code
- `call-hierarchy` - Callers/callees
- `watch` - Track file changes, update index

**nREPL (via nrepl module):**
- `nrepl-eval` - Evaluate arbitrary code
- `nrepl-load-file` - Load files into runtime
- `nrepl-connection` - Manage connections

### Introspection via nREPL

Runtime state can be queried directly:

```clojure
;; List all loaded namespaces
(all-ns)

;; Get vars in a namespace
(ns-publics 'my.ns)
(ns-interns 'my.ns)  ; includes private vars

;; Get var metadata
(meta #'my.ns/some-fn)

;; Check if var exists at runtime
(resolve 'my.ns/some-fn)

;; Get current value
@#'my.ns/some-var

;; List all methods of a multimethod
(methods my.ns/my-multi)
```

---

## Proposed Architecture

### Unified Query Interface

```
┌─────────────────────────────────────────────────────────────┐
│                    Unified Query API                         │
│                                                              │
│   bb state query "my.ns/some-fn"                            │
│   bb state diff                                              │
│   bb state sync                                              │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│    Static Layer          │   │    Live Layer            │
│    (clojure-lsp)         │   │    (nREPL)               │
│                          │   │                          │
│  • AST analysis          │   │  • Runtime introspection │
│  • File-based            │   │  • JVM state             │
│  • References/calls      │   │  • Current values        │
│  • Refactoring           │   │  • Dynamic definitions   │
└─────────────────────────┘   └─────────────────────────┘
```

### Query Types

**1. Symbol Query** - Get complete picture of a symbol:

```clojure
(query-symbol "my.ns/process")
;; =>
{:name "process"
 :ns "my.ns"
 :static {:file "src/my/ns.clj"
          :line 42
          :arglists '([data])
          :doc "Process the data"}
 :live {:defined? true
        :value #function[my.ns/process]
        :arglists '([data])
        :meta {:added "1.0"}}
 :diverged? false}  ; true if static != live
```

**2. Namespace Query** - Compare static vs live:

```clojure
(query-namespace "my.ns")
;; =>
{:ns "my.ns"
 :static-only #{:removed-fn}       ; In file but not loaded
 :live-only #{:temp-helper}        ; REPL-defined, not in file
 :both #{:process :main}           ; In both
 :diverged #{:config}}             ; Different signatures
```

**3. State Diff** - What's different?

```clojure
(state-diff)
;; =>
{:namespaces-not-loaded #{"my.new-ns"}
 :repl-only-vars {"my.ns" #{:temp-helper :debug-fn}}
 :stale-vars {"my.ns" #{:old-fn}}  ; file changed, not reloaded
 :value-changes {"my.ns/config" {:file-value {:port 3000}
                                 :runtime-value {:port 8080}}}}
```

---

## Architecture (per Gemini Review)

**Key recommendation:** Create a dedicated `state-monitor` module rather than putting unification logic in `clojure-lsp` or `nrepl`.

```
┌─────────────────────────────────────────────────────────────┐
│                    state-monitor module                      │
│                                                              │
│   Unified Query API, Normalization, CLI                     │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│    clojure-lsp module    │   │    nrepl module          │
│                          │   │                          │
│  • Static analysis       │   │  • Runtime introspection │
│  • File-based index      │   │  • JVM state             │
│  • References/calls      │   │  • Current values        │
└─────────────────────────┘   └─────────────────────────┘
```

**Why separate module?**
- `clojure-lsp` shouldn't depend on `nrepl`
- `nrepl` shouldn't depend on `clojure-lsp`
- `state-monitor` depends on both → clean dependency graph
- Unification logic can evolve independently

---

## Implementation Phases

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | nREPL Introspection Tools | Planned |
| 0.5 | REPL Source Capture | Planned |
| 1 | Namespace-Focused Query | Planned |
| 2 | State Monitor Module | Planned |
| 3 | Full Unification & CLI | Planned |

---

## Phase 0: nREPL Introspection Tools (Immediate Wins)

Add basic introspection tools to existing `nrepl` module. No unification logic needed - provides immediate value for AI agents to verify assumptions.

**Key insight:** These work with vanilla `clojure.core` - no cider-nrepl required.

### Tool: `nrepl-loaded-namespaces`

**Purpose:** List all namespaces currently loaded in the JVM.

**Input:** None (or optional filter pattern)

**Output:**
```clojure
{:namespaces ["clojure.core" "clojure.string" "my.app.core" "my.app.handlers" ...]
 :count 47}
```

**Use case:** Agent asks "What's actually loaded right now?" before assuming a namespace exists.

**Implementation:** `(all-ns)`

---

### Tool: `nrepl-introspect-ns`

**Purpose:** List all vars defined in a specific namespace.

**Input:** `{:ns "my.app.core"}`

**Output:**
```clojure
{:ns "my.app.core"
 :publics [:start! :stop! :handler :config]
 :interns [:start! :stop! :handler :config :helper-fn]
 :aliases {:str "clojure.string", :log "taoensso.trove"}
 :refers {:keys "clojure.core", :vals "clojure.core"}}
```

**Use case:** Agent verifies "Does `my.app.core/process` exist at runtime?" - especially after REPL eval that wasn't saved to file.

**Implementation:** `(ns-publics 'ns)` + `(ns-interns 'ns)` + `(ns-aliases 'ns)`

---

### Tool: `nrepl-var-meta`

**Purpose:** Get metadata for a specific var (arglists, docstring, file, line, etc.)

**Input:** `{:symbol "my.app.core/handler"}`

**Output:**
```clojure
{:name handler
 :ns my.app.core
 :arglists ([request])
 :doc "Handle incoming HTTP request"
 :file "src/my/app/core.clj"
 :line 42
 :added "1.0"
 :private false}
```

**Use case:** Agent needs function signature or doc without reading the file. Works for REPL-defined functions too (`:file` will be nil).

**Implementation:** `(meta #'ns/var)`

---

### Tool: `nrepl-get-value`

**Purpose:** Get the current runtime value of a var (dereferenced).

**Input:** `{:symbol "my.app.config/settings"}`

**Output:**
```clojure
{:symbol "my.app.config/settings"
 :value {:port 8080 :host "localhost" :debug true}
 :type "clojure.lang.PersistentArrayMap"}
```

**Use case:** The **killer feature** per Gemini review. Agent can see actual config values, atom contents, cached state. Static analysis can never show this.

**Example scenario:**
```
File says:     (def settings (load-config "config.edn"))
Agent asks:    "What IS settings right now?"
Tool returns:  {:port 8080, :host "0.0.0.0"}  ; actual loaded values
```

**Implementation:** `@(resolve 'ns/var)` with `pr-str`

---

## Phase 0.5: REPL Source Capture

**Problem:** When code is evaluated at the REPL (not loaded from file), the source is lost:

```clojure
;; REPL eval:
(defn my-helper [x] (* x 2))

;; Var metadata shows:
(meta #'my-helper)
;; => {:file "NO_SOURCE_FILE", :line 1, ...}

;; clojure.repl/source fails:
(source my-helper)
;; => Source not found
```

**What's preserved:** name, namespace, arglists, doc
**What's lost:** the actual source code

### Solution: Capture at Eval Time

Since we control the `nrepl-eval` entry point, intercept and store source:

```clojure
;; Agent calls:
(nrepl-eval {:code "(defn my-helper [x] (* x 2))"})

;; We intercept and store:
{:eval/id #uuid "..."
 :eval/code "(defn my-helper [x] (* x 2))"
 :eval/vars ["user/my-helper"]
 :eval/ns "user"
 :eval/timestamp #inst "2025-12-29T..."}
```

### Storage Strategy (Hybrid)

1. **Datalevin** (persistent) - Query eval history across sessions
2. **Var metadata** (ephemeral) - Attach `:source` to var itself as backup

```clojure
;; When eval defines a var, also do:
(alter-meta! (resolve 'my-helper) assoc
             :source "(defn my-helper [x] (* x 2))"
             :eval-timestamp #inst "...")
```

### Tool: `nrepl-var-source`

**Purpose:** Retrieve source code for a var, whether from file or REPL eval.

**Input:** `{:symbol "user/my-helper"}`

**Output:**
```clojure
{:symbol "user/my-helper"
 :source "(defn my-helper [x] (* x 2))"
 :origin :repl-eval    ; or :file
 :file nil             ; nil for REPL, path for file
 :timestamp #inst "2025-12-29T..."
 :persisted true}      ; true if in Datalevin
```

**Lookup order:**
1. Check var metadata for `:source`
2. Query Datalevin for eval history
3. Try `clojure.repl/source-fn` (file-based)
4. Return `{:source nil :reason "not-found"}`

### Tool: `nrepl-eval-history`

**Purpose:** List recent REPL evaluations with their effects.

**Input:** `{:ns "user" :limit 10}` (optional filters)

**Output:**
```clojure
{:history
 [{:id #uuid "..."
   :code "(defn my-helper [x] (* x 2))"
   :vars ["user/my-helper"]
   :ns "user"
   :timestamp #inst "2025-12-29T23:00:00"}
  {:id #uuid "..."
   :code "(def config {:port 8080})"
   :vars ["user/config"]
   :ns "user"
   :timestamp #inst "2025-12-29T22:55:00"}
  ...]}
```

**Use case:** Agent asks "What did I eval in this session?" before ending work.

### Implementation Notes

**Parsing def forms:**
```clojure
(defn extract-defined-vars [code-str]
  "Parse code and extract vars that would be defined."
  (let [form (read-string code-str)]
    (when (and (list? form)
               (#{'def 'defn 'defn- 'defmacro 'defonce 'defmulti 'defmethod}
                (first form)))
      [(str *ns* "/" (second form))])))
```

**Datalevin schema:**
```clojure
{:eval/id {:db/unique :db.unique/identity}
 :eval/code {:db/valueType :db.type/string}
 :eval/vars {:db/valueType :db.type/tuple
             :db/tupleType :db.type/string}
 :eval/ns {:db/valueType :db.type/string}
 :eval/timestamp {:db/valueType :db.type/instant}
 :eval/session {:db/valueType :db.type/string}}
```

### Open Questions

1. **Scope:** Track all evals or just def forms?
2. **Overwrites:** When var redefined, keep history or just latest?
3. **Cleanup:** Prune old history? Per-session vs global retention?
4. **load-file:** Store file content when loading via nREPL?
5. **Multi-form:** Handle `(do (defn a ...) (defn b ...))` ?

---

## Phase 1: Namespace-Focused Query

**Recommendation from Gemini:** Prioritize focused queries over global diff.

**Why focused > global:**
- Global diff is slow (network round-trips to nREPL)
- Global diff is noisy (many libraries differ slightly in runtime vs source)
- Agent works primarily in one file/namespace at a time

### Tool: `query-namespace`

**Purpose:** Compare what's in the file vs what's in the JVM for a single namespace.

**Input:** `{:ns "my.app.core"}`

**Output:**
```clojure
{:ns "my.app.core"
 :file "src/my/app/core.clj"

 ;; In file but NOT loaded (file changed, not reloaded)
 :static-only [:new-feature :updated-handler]

 ;; Loaded but NOT in file (REPL-defined)
 :live-only [:debug-fn :temp-helper]

 ;; In both
 :both [:start! :stop! :handler :config]

 ;; In both but signatures differ
 :diverged [{:name :handler
             :static-arglists ([req])
             :live-arglists ([req opts])}]}
```

**Use case:** Agent asks "Is what I'm seeing in the file actually what's running?" Single most useful query for interactive development.

---

### Tool: `inspect-value`

**Purpose:** Two-step verification: (1) LSP confirms symbol exists in code, (2) nREPL fetches runtime value.

**Input:** `{:symbol "my.app.core/config"}`

**Output:**
```clojure
{:symbol "my.app.core/config"
 :static {:file "src/my/app/core.clj"
          :line 15
          :defined true}
 :live {:loaded true
        :value {:env :production :port 3000}
        :type "clojure.lang.PersistentHashMap"}
 :in-sync true}
```

**Use case:** Agent wants to debug a value but first confirms it's a real symbol (not a typo). Combines code navigation with runtime inspection.

---

## Phase 2: State Monitor Module

Create new `modules/state-monitor/` as orchestrator that consumes both modules.

### Module Structure

```
state-monitor/
├── module.edn           # declares deps: [clojure-lsp, nrepl]
└── src/state_monitor/
    ├── core.clj         # MCP tool registration, lifecycle
    ├── query.clj        # Unified query logic
    └── normalize.clj    # Static↔Live data normalization
```

### Normalization Challenges

Static analysis sees **text**, runtime introspection sees **data**. Must normalize for comparison:

| Static (text) | Live (data) | Normalized |
|---------------|-------------|------------|
| `(defn foo [x y] ...)` | `{:arglists '([x y])}` | `{:arity 2, :args [x y]}` |
| `my.app.core/handler` | `#'my.app.core/handler` | `"my.app.core/handler"` |
| line 42, col 3 | nil | (ignore for comparison) |

**Strategy:**
- Ignore metadata differences that don't affect behavior (line numbers)
- Normalize fully qualified symbols vs aliases
- Handle macro expansions which might obscure original definition

---

## Phase 3: Full Unification & CLI

### Tool: `state-query-symbol`

**Purpose:** Everything about a symbol - static analysis + runtime state.

**Input:** `{:symbol "my.app.core/handler"}`

**Output:**
```clojure
{:symbol "my.app.core/handler"
 :static {:file "src/my/app/core.clj"
          :line 42
          :arglists ([request])
          :doc "Handle HTTP request"
          :references [{:file "src/my/app/routes.clj" :line 15}
                       {:file "src/my/app/middleware.clj" :line 8}]
          :callers [:my.app.routes/app :my.app.middleware/wrap-handler]}
 :live {:defined true
        :arglists ([request])
        :value #function[my.app.core/handler]}
 :diverged false}
```

**Use case:** Complete picture. Agent gets LSP navigation (references, callers) plus runtime confirmation.

---

### Tool: `state-diff-namespace`

**Purpose:** Full divergence report for a namespace.

**Input:** `{:ns "my.app.core"}`

**Output:**
```clojure
{:ns "my.app.core"
 :summary {:total-vars 12
           :in-sync 9
           :static-only 1
           :live-only 1
           :diverged 1}
 :details {:static-only [{:name :new-feature
                          :reason "File changed, not reloaded"}]
           :live-only [{:name :debug-fn
                        :reason "REPL-defined, not in file"}]
           :diverged [{:name :handler
                       :static {:arglists ([req])}
                       :live {:arglists ([req opts])}
                       :reason "Signature mismatch"}]}}
```

**Use case:** Before committing or ending session, agent checks "Did I forget to save anything? Is there stale code?"

---

### Tool: `state-sync-namespace`

**Purpose:** Reload namespace from file to bring runtime in sync with disk.

**Input:** `{:ns "my.app.core"}`

**Action:** Evaluates `(require 'my.app.core :reload)` via nREPL

**Output:**
```clojure
{:ns "my.app.core"
 :action :reloaded
 :before {:static-only 1 :live-only 1 :diverged 1}
 :after {:static-only 0 :live-only 0 :diverged 0}
 :warning "REPL-defined vars lost: [:debug-fn]"}
```

**Use case:** Agent fixes divergence automatically. Warning about losing REPL-only definitions.

---

### Change Tracking (Future)

Track state changes over time:

```
Timeline:
─────────────────────────────────────────────────────────────────
t₀: Server start
    └─ Static: {} Live: {}

t₁: Load project
    └─ Static: {my.ns/foo, my.ns/bar} Live: {}

t₂: (require 'my.ns)
    └─ Static: {my.ns/foo, my.ns/bar} Live: {my.ns/foo, my.ns/bar}

t₃: (def temp-fn ...)  ; at REPL
    └─ Static: {my.ns/foo, my.ns/bar} Live: {my.ns/foo, my.ns/bar, my.ns/temp-fn}
    └─ Divergence detected!

t₄: Edit my/ns.clj, save
    └─ Static: {my.ns/foo, my.ns/bar, my.ns/baz} Live: {my.ns/foo, my.ns/bar, my.ns/temp-fn}
    └─ Static has baz, Live missing baz, Live has temp-fn not in static

t₅: (require 'my.ns :reload)
    └─ Static: {my.ns/foo, my.ns/bar, my.ns/baz} Live: {my.ns/foo, my.ns/bar, my.ns/baz}
    └─ temp-fn gone (namespace reloaded)
```

---

## CLI Interface

```bash
# Query a specific symbol
bb state query my.ns/some-fn
# => Shows static definition + runtime state

# Show divergence between file and runtime
bb state diff
# => Lists REPL-only vars, stale vars, etc.

# Sync runtime to match files
bb state sync my.ns
bb state sync --all

# Watch for divergence
bb state watch
# => Alerts when static/live diverge

# Timeline view
bb state history
# => Shows state changes over session
```

---

## cider-nrepl Integration

The [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl) middleware provides enhanced introspection:

| Op | Purpose |
|----|---------|
| `info` | Var metadata, arglists, docs |
| `ns-list` | List all namespaces |
| `ns-vars` | List vars in namespace |
| `ns-path` | Get file path for namespace |
| `resource` | Find resource on classpath |
| `complete` | Runtime-aware completion |
| `eldoc` | Get function signature |

**Integration approach:**
1. Check if cider-nrepl is available
2. Use enhanced ops when present
3. Fall back to basic nREPL introspection otherwise

---

## Use Cases

### 1. REPL-Driven Development

Developer evaluates code at REPL without saving:
- State tracker shows "3 REPL-only vars in my.ns"
- Before ending session, can export REPL definitions to file

### 2. Debugging Stale Code

"Why isn't my change working?"
- `bb state diff my.ns` shows file changed but not reloaded
- `bb state sync my.ns` reloads from disk

### 3. Understanding Current State

"What's actually running right now?"
- `bb state query my.ns/config` shows runtime value differs from file
- Reveals that env override is active

### 4. Code Review / Handoff

"What did I change in this session?"
- `bb state history` shows all REPL modifications
- Can identify experimental code that needs saving or discarding

---

## Technical Considerations

### Performance

- Static queries: Fast (clojure-lsp maintains index)
- Live queries: Network round-trip to nREPL
- Caching: Cache live state with TTL, invalidate on known mutations

### Consistency

- Point-in-time snapshots for diff operations
- Handle race between file save and index update
- Track "last known state" per namespace

### Multi-REPL

- Support multiple nREPL connections (e.g., CLJ + CLJS)
- Per-runtime state tracking
- Cross-runtime symbol resolution

---

## Gemini Review Recommendations

From `live-static-state-design-implementation-review.md`:

### Architecture
- ✅ **Dedicated module** - Create `state-monitor` rather than putting logic in clojure-lsp or nrepl
- ✅ **Dependency direction** - state-monitor consumes both lower modules

### Implementation Priorities
1. **Phase 0 first** - Ship basic nREPL introspection tools immediately (provides value without full merger)
2. **Focused > Global** - Prioritize `query-namespace` over global `state-diff` (global is slow/noisy)
3. **Value inspection is killer feature** - Elevate `nrepl-get-value` as first-class use case

### Fallback Strategy

**Critical:** Core introspection must work with vanilla `clojure.core`:
```clojure
;; These work everywhere - no middleware needed
(all-ns)              ; list namespaces
(ns-publics 'my.ns)   ; public vars
(ns-interns 'my.ns)   ; all vars including private
(meta #'my.ns/foo)    ; var metadata
(resolve 'my.ns/foo)  ; check if exists
@#'my.ns/foo          ; get value
```

Enhanced ops when `cider-nrepl` available:
- `info` - richer metadata
- `eldoc` - function signatures
- `ns-path` - file locations

### Divergence Detection Caveats

Be cautious with signature comparisons:
- Static sees literal text: `(defn foo [x] ...)`
- Runtime sees evaluated data: `{:arglists '([x])}`
- Need normalization layer that:
  - Ignores line numbers and other irrelevant metadata
  - Normalizes fully qualified symbols vs aliases
  - Handles macro expansions

### Testing Recommendations

1. **E2E refactoring flow** - Test `clj-code-actions` → `clj-execute-command` chain
2. **Watcher robustness** - Verify recovery if fswatcher pod terminates unexpectedly
3. **Troubleshooting docs** - Add common issues section (project root not found, binary not in PATH)

---

## Open Questions

1. **Scope of tracking:** Track all namespaces or only project namespaces?
2. **Persistence:** Store state history across sessions?
3. **Conflict resolution:** When static and live diverge, which is "correct"?
4. **Performance budget:** How often to poll live state?

---

## References

- [clojure-lsp](https://clojure-lsp.io/) - Static analysis
- [nREPL](https://nrepl.org/) - Runtime connection
- [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl) - Enhanced middleware
- [tools.namespace](https://github.com/clojure/tools.namespace) - Namespace reloading

---

*Last Updated: 2025-12-29*
