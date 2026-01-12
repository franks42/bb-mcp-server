# Static Code Analysis for Code Browser

**Date:** 2026-01-11
**Status:** Research/Investigation

---

## Problem

The code browser needs two capabilities:

### 1. Classify Definitions in a Namespace

Label different types of Clojure definitions:
- functions (`defn`, `defn-`)
- macros (`defmacro`)
- multimethods (`defmulti`, `defmethod`)
- protocols (`defprotocol`)
- protocol functions
- records (`defrecord`)
- types (`deftype`)
- variables (`def`, `defonce`)
- forward declarations (`declare`)
- tests (`deftest`)

### 2. Symbol-at-Point Information (Interactive Code Exploration)

When viewing source in the editor, **clicking on any symbol** should open a view for that symbol:

**For special forms** (`if`, `let`, `def`, `fn`, etc.):
- Middle panel: Show symbol type = "Special Form" (no namespace)
- Right panel: "Source unavailable - Special forms are primitives that bootstrap the language. They are built into the compiler, cannot be redefined, and have no Clojure source code."
- Include: documentation, usage examples from ClojureDocs

**For macros/functions** (`defn`, `map`, `filter`, etc.):
- Middle panel: Show as "Macro - clojure.core/defn" or "Function - clojure.core/map"
- Right panel: Source code (when available)
- Include: arglists, documentation, examples, see-also links

**For project vars** (`my-fn`, `my-var`):
- Middle panel: Show as "Function - my.ns/my-fn" with location
- Right panel: Source code from project
- Include: arglists, docstring

**Examples:**
- Click `if` → Special Form view, no source, compiler primitive explanation
- Click `let` → Special Form view (note: user-facing `let` is macro wrapper around `let*`)
- Click `map` → Function view with source from clojure.core
- Click `defn` → Macro view with source
- Click `my-fn` → Project function view with local source

This requires **resolving symbols in context** - determining what a symbol refers to at that position in the code.

---

Currently using clojure-lsp `workspace/symbol` which only provides 3 generic LSP kinds.

---

## LSP vs Kondo: What Each Provides

clojure-lsp uses clj-kondo internally, but doesn't expose all kondo data through LSP protocol.

### LSP Hover Results by Symbol Type

| Symbol Type | LSP Hover Result |
|-------------|------------------|
| Function (`atom`) | ✅ Full docs + arglists + ClojureDocs examples + see-also |
| Macro (`defn`) | ✅ Full docs + arglists + examples (no "Macro" label) |
| Special form (`if`) | ❌ Just name, no docs, no arglists |
| Project var | ✅ Docstring + arglists + source location |

### What We Need Each Tool For

| Capability | Tool | Why |
|------------|------|-----|
| Namespace list | LSP `workspace/symbol` | Fast, cached, workspace-wide |
| Var classification | **Kondo directly** | LSP only gives 3 kinds; kondo gives `:defined-by` |
| Symbol-at-point docs | LSP `textDocument/hover` | ClojureDocs examples, see-also links |
| Special form detection | **Hard-coded lists** | LSP gives no useful info for special forms |
| Special form docs | **ClojureDocs fetch or cache** | LSP doesn't provide them |

### Conclusion

We need a **hybrid approach**:
1. **LSP** - namespace list (cached), hover for docs/examples on functions/macros
2. **Kondo** - var type classification (defn vs defmacro vs defmulti vs defprotocol)
3. **Hard-coded** - special form detection per platform (CLJ/CLJS/SCI)
4. **External docs** - special form documentation from ClojureDocs or static cache

---

## Analysis Options Compared

### 1. clojure-lsp `workspace/symbol`

**Current approach.**

| LSP Kind | Meaning | Clojure Constructs |
|----------|---------|-------------------|
| 3 | namespace | `(ns ...)` |
| 12 | function | defn, defn-, defmacro, defmulti, defmethod, defprotocol, etc. |
| 13 | variable | def, defonce, declare |

**Pros:**
- Single call gets all symbols in workspace
- Fast, already integrated

**Cons:**
- No distinction between macro/function/multimethod/protocol
- All callable things are "function"

---

### 2. clojure-lsp `textDocument/documentSymbol`

**Per-file call with slightly richer kinds.**

| LSP Kind | Meaning |
|----------|---------|
| 3 | namespace |
| 11 | interface (used for defmulti!) |
| 12 | function |
| 13 | variable |

**Pros:**
- Distinguishes multimethods (kind 11) from functions (kind 12)

**Cons:**
- Per-file call (latency when selecting namespace)
- Still doesn't distinguish macro/protocol/deftype/defrecord

---

### 3. clj-kondo Analysis (Recommended)

**Rich static analysis with `:defined-by` field.**

```bash
clj-kondo --lint <file> --config '{:output {:analysis {:var-definitions true} :format :edn}}'
```

**`:defined-by` values detected:**

| `:defined-by` | Label |
|---------------|-------|
| `clojure.core/defn` | function |
| `clojure.core/defn-` | private-fn |
| `clojure.core/def` | variable |
| `clojure.core/defonce` | defonce |
| `clojure.core/declare` | declare |
| `clojure.core/defmacro` | macro |
| `clojure.core/defmulti` | multimethod |
| `clojure.core/defmethod` | method |
| `clojure.core/defprotocol` | protocol |
| `clojure.core/deftype` | deftype |
| `clojure.core/defrecord` | defrecord |
| `clojure.test/deftest` | test |

**Additional fields available:**
- `:macro true` - for macros
- `:private true` - for private vars
- `:protocol-ns`, `:protocol-name` - for protocol methods
- `:arglists` / `:arglist-strs` - function signatures
- `:doc` - docstrings

**Pros:**
- Distinguishes ALL defining forms
- Information comes directly from source code
- No runtime required
- clj-kondo is already a dependency (bundled in clojure-lsp)

**Cons:**
- Per-file call
- Subprocess invocation overhead

---

### 4. Runtime Introspection (nREPL)

**clj-ns-browser approach - requires loaded namespaces.**

```clojure
;; From clj-ns-browser/utils.clj
(defn macro? [v]
  (and (var? v) (:macro (meta v))))

(defn multimethod? [o]
  (isa? (type o) clojure.lang.MultiFn))

(defn protocol? [v]
  (and (var? v) (:on-interface @v)))

(defn protocol-fn? [v]
  (and (var? v) (:protocol (meta v))))

(defn deftype? [o]
  (isa? o clojure.lang.IType))

(defn defrecord? [o]
  (isa? o clojure.lang.IRecord))
```

**Pros:**
- Can inspect actual runtime values
- Can see REPL-defined vars not in source
- Can check current var values

**Cons:**
- Only works for loaded namespaces
- Requires `@v` (deref) which needs var to be bound

---

## Recommendation

**Use clj-kondo analysis for static browsing:**

1. When user selects a namespace, run kondo on that file
2. Parse the `:var-definitions` from analysis output
3. Map `:defined-by` to human-readable labels
4. Cache results per file (invalidate on file change)

**Hybrid approach for future:**

- Static mode: kondo analysis (works on any file)
- Runtime mode: nREPL introspection (richer for loaded namespaces)
- Show indicator of which mode is active
- Allow toggle between modes

---

## Edge Cases

### Multiple Namespaces in One File

Kondo correctly tracks which namespace each var belongs to via the `:ns` field:

```clojure
;; file with two namespaces
(ns foo.one)
(defn hello [] ...)  ; :ns foo.one

(ns foo.two)
(defn world [] ...)  ; :ns foo.two
```

**Solution:** Filter var-definitions by `:ns` field, not just filename.

### Split Namespace (One Namespace Across Multiple Files)

Some namespaces are split across files using `in-ns`:

```clojure
;; core.clj
(ns my.stuff)
(defn from-core [] ...)

;; extensions.clj
(in-ns 'my.stuff)
(defn from-extensions [] ...)
```

Per-file kondo analysis only returns vars from that file. To get complete namespace contents:

| Approach | Pros | Cons |
|----------|------|------|
| Lint entire project once | Complete, accurate | Slower startup |
| Lint per-file on demand | Fast | Incomplete for split namespaces |
| LSP for discovery + kondo per-file | Fast for common case | Extra complexity |

**Recommendation:** Start with per-file (option 2). Most codebases follow one-ns-per-file convention. Split namespaces are rare (notable exception: `clojure.core` itself).

**Future enhancement:** Detect split namespaces by checking if multiple files define vars with same `:ns`, then lint all contributing files.

---

## Implementation Notes

### Running kondo from Babashka

```clojure
(require '[clojure.java.shell :refer [sh]]
         '[clojure.edn :as edn])

(defn analyze-file [file-path]
  (let [result (sh "clj-kondo" "--lint" file-path
                   "--config" "{:output {:analysis {:var-definitions true} :format :edn}}")
        analysis (edn/read-string (:out result))]
    (:var-definitions (:analysis analysis))))
```

### Mapping `:defined-by` to labels

```clojure
(def defined-by->label
  {'clojure.core/defn        :function
   'clojure.core/defn-       :private-fn
   'clojure.core/def         :variable
   'clojure.core/defonce     :defonce
   'clojure.core/declare     :declare
   'clojure.core/defmacro    :macro
   'clojure.core/defmulti    :multimethod
   'clojure.core/defmethod   :method
   'clojure.core/defprotocol :protocol
   'clojure.core/deftype     :deftype
   'clojure.core/defrecord   :defrecord
   'clojure.test/deftest     :test})
```

---

## Special Forms

Special forms are primitives built into the Clojure compiler. They are often confused with macros and can trip up even experienced programmers.

**Special forms differ by platform:**

| Special Form | Clojure (JVM) | ClojureScript | SCI/Babashka |
|--------------|:-------------:|:-------------:|:------------:|
| `&` | ✓ | ✓ | ✓ |
| `.` | ✓ | ✓ | ✓ |
| `case*` | ✓ | ✓ | ✓ |
| `catch` | ✓ | ✓ | ✓ |
| `def` | ✓ | ✓ | ✓ |
| `deftype*` | ✓ | ✓ | limited |
| `defrecord*` | - | ✓ | - |
| `do` | ✓ | ✓ | ✓ |
| `finally` | ✓ | ✓ | ✓ |
| `fn` / `fn*` | ✓ | ✓ | ✓ |
| `if` | ✓ | ✓ | ✓ |
| `import*` | ✓ | - | - |
| `js*` | - | ✓ | - |
| `let` / `let*` | ✓ | ✓ | ✓ |
| `letfn` / `letfn*` | ✓ | ✓ | ✓ |
| `loop` / `loop*` | ✓ | ✓ | ✓ |
| `monitor-enter` | ✓ | **-** | ✓ |
| `monitor-exit` | ✓ | **-** | ✓ |
| `new` | ✓ | ✓ | ✓ |
| `ns` | - | ✓ | - |
| `quote` | ✓ | ✓ | ✓ |
| `recur` | ✓ | ✓ | ✓ |
| `reify*` | ✓ | ✓ | limited |
| `set!` | ✓ | ✓ | ✓ |
| `throw` | ✓ | ✓ | ✓ |
| `try` | ✓ | ✓ | ✓ |
| `var` | ✓ | ✓ | ✓ |

**Key differences:**
- **ClojureScript**: No `monitor-enter`/`monitor-exit` (single-threaded), adds `js*`, `ns`, `defrecord*`
- **SCI/Babashka**: Limited `deftype*`/`reify*`, no `definterface`
- **`*` forms**: Internal versions (`fn*`, `let*`, `loop*`) - user-facing ones are macros wrapping these

### Detection Methods

| Method | How |
|--------|-----|
| **Runtime** | `(keys (. clojure.lang.Compiler specials))` |
| **Runtime** | Check `:special-form` in var metadata |
| **Static** | Hard-code the list (stable per Clojure version) |
| **kondo** | Not explicitly marked - appears as `:var-usages` to `clojure.core` |

### Synthetic `**special-forms**` Namespace

For discoverability, create a pseudo-namespace that lists all special forms:

**Namespace panel:**
- Show `**special-forms**` as a distinct entry (e.g., sorted first with `**` prefix)
- Visually distinct styling (italic, different color)

**Vars panel (when `**special-forms**` selected):**
- List all special forms for current platform
- Kind = "special-form"
- Sorted alphabetically

**Source panel (when special form selected):**
```
Special Form: if

No source available - special forms are primitives that bootstrap
the language. They are built into the compiler and have no Clojure
source code.

────────────────────────────────────────────────────────────────────

(if test then else?)

Evaluates test. If truthy, evaluates and yields then, otherwise
evaluates and yields else. If else is not supplied, returns nil.

Examples:
  (if true :yes :no)   ;=> :yes
  (if false :yes :no)  ;=> :no
  (if nil :yes)        ;=> nil

See also: when, cond, if-let, if-not
```

**Implementation:**
1. Hard-coded special form list per platform
2. Docs fetched from ClojureDocs or pre-cached at build time
3. Symbol-at-point (Phase 3) reuses this data

### Implementation for Static Analysis

```clojure
(def clj-special-forms
  "Special forms for Clojure JVM (from clojure.lang.Compiler/specials)"
  #{"&" "." "case*" "catch" "def" "deftype*" "do" "finally"
    "fn" "fn*" "if" "import*" "let" "let*" "letfn" "letfn*"
    "loop" "loop*" "monitor-enter" "monitor-exit" "new"
    "quote" "recur" "reify*" "set!" "throw" "try" "var"})

(def cljs-special-forms
  "Special forms for ClojureScript (from cljs.analyzer)"
  #{"&" "." "case*" "catch" "def" "defrecord*" "deftype*" "do"
    "finally" "fn" "fn*" "if" "js*" "let" "let*" "letfn" "letfn*"
    "loop" "loop*" "new" "ns" "quote" "recur" "reify*" "set!"
    "throw" "try" "var"})

(def sci-special-forms
  "Special forms for SCI/Babashka"
  #{"&" "." "case*" "catch" "def" "do" "finally" "fn" "fn*"
    "if" "let" "let*" "letfn" "letfn*" "loop" "loop*"
    "monitor-enter" "monitor-exit" "new" "quote" "recur"
    "set!" "throw" "try" "var"})

(defn special-form? [sym-name platform]
  (let [forms (case platform
                :clj  clj-special-forms
                :cljs cljs-special-forms
                :sci  sci-special-forms
                clj-special-forms)]
    (contains? forms (name sym-name))))
```

---

## References

- [clj-kondo Analysis Data](https://cljdoc.org/d/clj-kondo/clj-kondo/2025.12.23/doc/analysis-data)
- [clj-kondo Hooks](https://cljdoc.org/d/clj-kondo/clj-kondo/2025.12.23/doc/hooks)
- [clj-ns-browser utils.clj](../clj-ns-browser/src/clj_ns_browser/utils.clj) - runtime introspection patterns
- [LSP Symbol Kinds](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#symbolKind)
- [ClojureScript Differences from Clojure](https://clojurescript.org/about/differences)
- [ClojureScript Analyzer Source](https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/analyzer.cljc)
- [SCI Analyzer Source](https://github.com/babashka/sci/blob/master/src/sci/impl/analyzer.cljc)

---

*Last Updated: 2026-01-11*
