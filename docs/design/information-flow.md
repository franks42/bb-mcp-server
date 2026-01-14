# Code Browser Information Flow

> Design exploration: What information can we derive from selections?

## Core Entities

```
┌─────────────────────────────────────────────────────────────────┐
│                        ENTITY GRAPH                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Project ─────► Files ─────► Namespaces ─────► Symbols         │
│      │            │              │                 │             │
│      ▼            ▼              ▼                 ▼             │
│    Git         Source         Requires          Types           │
│   Branch        Code          Required-by       Docs            │
│   Commits      Lines          Aliases           Examples        │
│   Dirty?       Regions        Imports           Metadata        │
│                                                  Usages         │
│                                                  Deps           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Information Flow Paths

### From Project

```
Project
├── git/
│   ├── branch (current)
│   ├── branches (all)
│   ├── commits (recent)
│   ├── dirty-files
│   └── upstream status
├── files/
│   ├── source files (.clj, .cljs, .cljc)
│   ├── config files (deps.edn, project.clj)
│   ├── test files
│   └── resource files
├── namespaces/
│   ├── all ns in project
│   ├── ns by directory (src/, test/)
│   └── ns dependency graph
└── dependencies/
    ├── declared deps (deps.edn)
    ├── transitive deps
    └── classpath entries
```

### From Git

```
Git Branch
├── → files changed (vs main)
├── → commits on branch
├── → namespaces affected
└── → symbols modified

Git Commit
├── → files in commit
├── → diff (lines changed)
├── → namespaces touched
└── → symbols added/removed/modified

Git Dirty Status
├── → unstaged files
├── → staged files
└── → untracked files
```

### From File

```
File
├── → namespace(s) defined
├── → line count, byte size
├── → git status (modified?, staged?)
├── → last commit touching file
├── → symbols defined (all)
├── → symbols by type (fns, macros, etc.)
├── → top-level forms (side effects)
├── → requires (dependencies)
├── → imports (Java/JS)
└── → regions (comment blocks, sections)
```

### From Namespace

```
Namespace
├── identity/
│   ├── name (fully qualified)
│   ├── file(s) defining it
│   ├── docstring
│   └── metadata
├── symbols/
│   ├── public vars
│   ├── private vars
│   ├── macros
│   ├── multimethods
│   ├── protocols
│   ├── records/types
│   └── top-level forms
├── dependencies/
│   ├── requires (ns it depends on)
│   ├── required-by (ns that depend on it)
│   ├── imports (Java classes)
│   ├── aliases (short names)
│   └── refers (specific vars)
└── analysis/
    ├── symbol count by type
    ├── complexity metrics
    └── test coverage (if available)
```

### From Symbol/Var

```
Symbol
├── identity/
│   ├── name (simple)
│   ├── fqn (namespace/name)
│   ├── file + line range
│   └── kind/type
├── documentation/
│   ├── docstring
│   ├── arglists
│   ├── metadata (^:private, ^:deprecated, etc.)
│   └── specs (if defined)
├── source/
│   ├── source code
│   ├── line range
│   └── containing form (for nested defs)
├── examples/
│   ├── nearby (comment ...) blocks
│   ├── test cases using this symbol
│   └── REPL history (runtime)
├── relationships/
│   ├── dependencies (symbols this calls)
│   ├── dependents (symbols that call this)
│   ├── same-name in other ns
│   └── overloads/arities
└── type-specific/
    ├── [multimethod] → dispatch fn, methods
    ├── [defmethod] → parent multimethod
    ├── [protocol] → methods, implementations
    ├── [protocol-impl] → protocol, containing type
    ├── [defrecord] → fields, protocols implemented
    └── [deftype] → fields, protocols implemented
```

### From Type/Kind

```
Type (e.g., "macro", "multimethod", "protocol")
├── → all symbols of this type in ns
├── → all symbols of this type in project
├── → count by namespace
└── → common patterns/usage
```

---

## Navigation Graph (What leads to what?)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         NAVIGATION GRAPH                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                              ┌──────────┐                                │
│                    ┌────────►│ PROJECT  │◄────────┐                      │
│                    │         └────┬─────┘         │                      │
│                    │              │               │                      │
│              [switch]        [list files]    [git info]                  │
│                    │              │               │                      │
│                    │              ▼               ▼                      │
│               ┌────┴────┐   ┌─────────┐    ┌──────────┐                  │
│               │ PROJECT │   │  FILE   │◄───│   GIT    │                  │
│               │ SELECTOR│   └────┬────┘    │ BRANCH   │                  │
│               └─────────┘        │         └────┬─────┘                  │
│                                  │              │                        │
│                          [defines]        [commits]                      │
│                                  │              │                        │
│                                  ▼              ▼                        │
│                            ┌──────────┐   ┌──────────┐                   │
│               ┌───────────►│NAMESPACE │   │  COMMIT  │                   │
│               │            └────┬─────┘   └────┬─────┘                   │
│               │                 │              │                         │
│        [required-by]     [has symbols]    [changed]                      │
│               │                 │              │                         │
│               │                 ▼              │                         │
│               │           ┌──────────┐        │                          │
│               └───────────│  SYMBOL  │◄───────┘                          │
│                           └────┬─────┘                                   │
│                                │                                         │
│              ┌─────────────────┼─────────────────┐                       │
│              │                 │                 │                       │
│        [calls/uses]      [defined-by]     [implements]                   │
│              │                 │                 │                       │
│              ▼                 ▼                 ▼                       │
│         ┌────────┐       ┌──────────┐     ┌───────────┐                  │
│         │ SYMBOL │       │   TYPE   │     │ PROTOCOL/ │                  │
│         │ (other)│       │ (filter) │     │MULTIMETHOD│                  │
│         └────────┘       └──────────┘     └───────────┘                  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Navigation Paths

### Selection → Available Actions

| When viewing... | Can navigate to... |
|-----------------|-------------------|
| **Project** | Files, Namespaces, Git branches, Dependencies |
| **Git branch** | Commits, Changed files, Affected namespaces |
| **Git commit** | Changed files, Diff, Affected symbols |
| **File** | Namespace(s), All symbols, Git history |
| **Namespace** | Symbols, Required ns, Required-by ns, File |
| **Symbol list** | Filter by type, Sort by name/line/kind |
| **Symbol** | Source, Docs, Examples, Deps, Dependents |
| **Multimethod** | All defmethods, Dispatch function |
| **Defmethod** | Parent multimethod, Dispatch value |
| **Protocol** | Methods, All implementations |
| **Protocol impl** | Protocol definition, Containing type |
| **Defrecord/type** | Fields, Implemented protocols, Methods |
| **Type filter** | All symbols of that type (ns or project-wide) |

### Bidirectional Relationships

```
Namespace A ←─[requires]──→ Namespace B
            ←─[required-by]─→

Symbol X ←─[calls]──→ Symbol Y
         ←─[called-by]─→

Protocol P ←─[defines]──→ Method M
           ←─[implemented-by]──→ Type T

Multimethod M ←─[dispatches-to]──→ Method D
              ←─[method-of]──→
```

---

## UI/UX Considerations

### Current Foundation (3-panel layout)

```
┌────────────┬────────────┬──────────────────────┐
│ Namespaces │   Symbols  │       Source         │
│            │            │                      │
│ [filter]   │  [filter]  │   [highlighted]      │
│            │  [sort]    │                      │
│ • ns-a     │  • fn-1    │   (defn fn-1 ...)    │
│ • ns-b ◄── │  • fn-2 ◄──│                      │
│ • ns-c     │  • macro-1 │                      │
│            │            │                      │
│ [count]    │ [count]    │   [file:lines]       │
└────────────┴────────────┴──────────────────────┘
```

### Enhancement Options

#### Option A: Contextual Side Panel

```
┌────────────┬────────────┬──────────────────────┬───────────┐
│ Namespaces │   Symbols  │       Source         │  Context  │
│            │            │                      │           │
│            │            │                      │ [tabs]    │
│            │            │                      │ Docs      │
│            │            │                      │ Deps      │
│            │            │                      │ Usages    │
│            │            │                      │ Examples  │
└────────────┴────────────┴──────────────────────┴───────────┘
```

#### Option B: Expandable Symbol Detail

```
┌────────────┬────────────┬──────────────────────┐
│ Namespaces │   Symbols  │       Source         │
│            │            ├──────────────────────┤
│            │  • fn-1    │ (defn fn-1 ...)      │
│            │  ▼ fn-2    ├──────────────────────┤
│            │   ├─ Docs  │ Docstring here       │
│            │   ├─ Deps  │ calls: foo, bar      │
│            │   └─ Uses  │ used by: baz, qux    │
│            │  • fn-3    │                      │
└────────────┴────────────┴──────────────────────┘
```

#### Option C: Breadcrumb + Drill-down

```
┌─────────────────────────────────────────────────┐
│ project > src > my.namespace > my-fn            │  ← breadcrumb
├────────────┬────────────┬──────────────────────┤
│ [context]  │   Symbols  │       Source         │
│            │            │                      │
│ Requires:  │            │                      │
│ • clojure  │            │                      │
│ • other.ns │            │                      │
│            │            │                      │
│ Required   │            │                      │
│ by:        │            │                      │
│ • user.ns  │            │                      │
└────────────┴────────────┴──────────────────────┘
```

#### Option D: Graph View (for dependencies)

```
┌─────────────────────────────────────────────────┐
│                 Dependency Graph                 │
│                                                  │
│            ┌─────────┐                           │
│     ┌──────│ core.ns │──────┐                   │
│     │      └─────────┘      │                   │
│     ▼                       ▼                   │
│ ┌───────┐              ┌───────┐                │
│ │ ns-a  │              │ ns-b  │                │
│ └───┬───┘              └───┬───┘                │
│     │      ┌───────┐       │                    │
│     └─────►│ ns-c  │◄──────┘                    │
│            └───────┘                            │
└─────────────────────────────────────────────────┘
```

---

## Information Queries (What can we ask?)

### Project-level

- "What namespaces are in this project?"
- "What files have uncommitted changes?"
- "What are the external dependencies?"
- "Show test namespaces only"

### Namespace-level

- "What does this namespace require?"
- "What namespaces require this one?"
- "How many symbols of each type?"
- "What protocols are defined here?"
- "What multimethods are defined here?"

### Symbol-level

- "What is the source code?"
- "What is the docstring?"
- "What are the arglists?"
- "What symbols does this call?"
- "What symbols call this?"
- "Are there examples in comments?"
- "Are there tests for this?"

### Type-level

- "Show all macros in this namespace"
- "Show all protocol implementations"
- "Show all multimethods and their methods"
- "Show all private functions"
- "Show all deprecated symbols"

### Cross-cutting

- "Where is symbol X used across the project?"
- "What changed in the last commit?"
- "What symbols are defined but never used?"
- "What are the most-used symbols?"

---

## Symbol Properties & Annotations

### Semantic Properties (crucial for understanding)

```
Symbol Properties
├── purity/
│   ├── pure? (no side effects, deterministic)
│   ├── referentially-transparent?
│   ├── idempotent?
│   └── side-effects (I/O, state mutation, etc.)
├── safety/
│   ├── thread-safe?
│   ├── lazy? (lazy seq producer)
│   ├── realizes-lazy-seqs? (forces evaluation)
│   └── blocking? (I/O, locks, sleeps)
├── performance/
│   ├── complexity (O(1), O(n), O(n²), etc.)
│   ├── recursive? (direct, mutual, tail)
│   ├── memoized?
│   └── transducer-compatible?
└── lifecycle/
    ├── deprecated?
    ├── experimental?
    ├── internal? (impl detail)
    └── stable? (public API)
```

### Property Sources

| Property | How to Detect | Confidence |
|----------|---------------|------------|
| Pure | Static analysis (no I/O, no state) | Medium |
| Side effects | Calls to I/O fns, atoms, refs | High |
| Deprecated | ^:deprecated metadata | High |
| Private | ^:private or defn- | High |
| Lazy producer | Returns lazy-seq, map, filter, etc. | Medium |
| Blocking | Calls to Thread/sleep, I/O | Medium |
| Recursive | Self-reference in body | High |
| Tail recursive | recur in tail position | High |

### Following Purity Through Call Graph

```
                    PURITY PROPAGATION

    pure fn ────────► calls only pure fns ────────► pure result
         │
         └───► calls impure fn ────────► impure (side effects)


Example:
    (defn process [x]        ; pure? depends on children
      (-> x
          transform          ; pure ✓
          validate           ; pure ✓
          save!))            ; IMPURE (I/O) ✗

    Result: process is IMPURE (calls save!)
```

### UI for Properties

```
┌────────────────────────────────────────────────────────┐
│ process-data                                    fn     │
├────────────────────────────────────────────────────────┤
│ [pure ✓] [no-side-effects ✓] [O(n)] [thread-safe ✓]   │
├────────────────────────────────────────────────────────┤
│ Calls:                                                 │
│   • transform ────── pure ✓                            │
│   • validate ─────── pure ✓                            │
│   • save! ────────── IMPURE (I/O)                      │
│                                                        │
│ ⚠ This function has side effects via save!            │
└────────────────────────────────────────────────────────┘
```

### Property Queries

- "Show all pure functions in namespace"
- "What impure functions does this call?"
- "Is this function safe to memoize?"
- "What side effects does this function have?"
- "Show deprecated symbols"
- "Show all blocking operations"
- "Trace purity through call chain"

---

## Data Sources

| Information | Source | Static/Runtime |
|-------------|--------|----------------|
| File list | Filesystem | Static |
| Namespace list | clj-kondo / LSP | Static |
| Symbol list | clj-kondo | Static |
| Symbol types | clj-kondo :defined-by | Static |
| Docstrings | clj-kondo / source parse | Static |
| Dependencies | clj-kondo :var-usages | Static |
| Protocol impls | clj-kondo :protocol-impls | Static |
| Git status | git CLI | Static |
| Purity analysis | clj-kondo + call graph | Static |
| Side effect detection | Call graph analysis | Static |
| Runtime values | nREPL introspection | Runtime |
| Specs | nREPL (clojure.spec) | Runtime |
| Test results | nREPL (test runner) | Runtime |

---

## Implementation Priority

Based on value vs. complexity:

### High Value, Low Complexity (Do First)
1. Namespace requires/required-by
2. Symbol dependencies (what this calls)
3. Symbol dependents (what calls this)
4. Filter symbols by type
5. Multimethod ↔ defmethod navigation
6. Protocol ↔ implementation navigation

### High Value, Medium Complexity
7. Project-wide symbol search
8. Git-aware file/symbol status
9. Docstring/metadata display
10. Example extraction from comments

### Medium Value, Higher Complexity
11. Dependency graph visualization
12. Cross-project search
13. Test coverage integration
14. Runtime introspection (nREPL)

---

## Open Questions

1. **Panel layout**: How many panels can we have before it's overwhelming?
2. **Navigation history**: Should we have back/forward like a browser?
3. **Bookmarks**: Can users bookmark frequently-visited symbols?
4. **Search**: Global search vs. contextual search?
5. **Keyboard navigation**: What shortcuts make sense?
6. **Multiple selection**: Select multiple symbols to compare?

---

## Next Steps

1. Prototype namespace requires/required-by (simplest relationship)
2. Add symbol deps/dependents to inspector panel
3. Implement type filtering in symbols panel
4. Add protocol/multimethod navigation (Phase 1.5E.8)
5. Consider graph visualization for complex relationships

