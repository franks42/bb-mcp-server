# Static + Live State Integration Design

**Status:** Design Phase
**Created:** 2025-12-29
**Author:** Claude Code Session

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

## Implementation Phases

### Phase 1: nREPL Introspection Tools

Expose runtime introspection via MCP tools:

```clojure
;; New tools for nrepl module
(defn introspect-ns
  "Get all vars and their metadata in a namespace."
  [{:keys [ns]}]
  ...)

(defn introspect-var
  "Get complete runtime info about a var."
  [{:keys [symbol]}]
  ...)

(defn list-loaded-namespaces
  "List all currently loaded namespaces."
  []
  ...)

(defn compare-to-file
  "Compare runtime state of ns to its source file."
  [{:keys [ns]}]
  ...)
```

### Phase 2: State Comparison

Combine clojure-lsp and nREPL results:

```clojure
(defn unified-query
  "Query both static and live state for a symbol."
  [{:keys [symbol]}]
  (let [static (clojure-lsp/find-symbol {:query symbol})
        live (nrepl/introspect-var {:symbol symbol})]
    (merge-views static live)))
```

### Phase 3: State Sync Helpers

Utility to bring live state in sync with files:

```clojure
(defn sync-namespace
  "Reload namespace from file to sync runtime with disk."
  [{:keys [ns]}]
  (nrepl/eval! (format "(require '%s :reload)" ns)))

(defn sync-all-stale
  "Reload all namespaces where file is newer than load time."
  []
  ...)
```

### Phase 4: Change Tracking

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
