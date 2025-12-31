# Clojure Var Metadata Research

**Date:** 2025-12-30
**Status:** Complete
**Relevance:** Phase 0 introspection tools, nREPL module, clojure-lsp integration

---

## Overview

Research into how var metadata and docstrings work across Clojure variants, informing design decisions for introspection tools that need to work across JVM Clojure, Babashka, ClojureScript, and SCI/Scittle.

---

## Comparison Table

| Aspect | Clojure (JVM) | ClojureScript | SCI/Scittle |
|--------|---------------|---------------|-------------|
| Vars reified at runtime | ✅ Full | ❌ Compile-time only | Partial |
| `(meta #'user-fn)` | ✅ Full | ✅ Compile-time | ✅ Works |
| `(meta #'clojure.core/map)` | ✅ Full docstring | ⚠️ Limited | ❌ nil |
| `(doc map)` | ✅ Works | ✅ Works | ⚠️ Empty for built-ins |
| Built-in functions | Vars with metadata | Vars (compile-time) | Plain JS functions |

---

## SCI/Scittle Details

### Two Types of "Vars"

| Type | `var?` | `meta` | Examples |
|------|--------|--------|----------|
| User-defined | `true` | ✅ Full | `(def x 1)`, `(defn f [])` |
| Built-in | `false` | `nil` | `clojure.string/join`, `+`, `map` |

### Why Built-ins Have No Metadata

1. **Performance** - SCI compiles built-in functions as plain JavaScript functions
2. **Bundle size** - Stripping ~500 docstrings saves significant KB
3. **Macros are different** - `when`, `let`, `->` retain metadata because they need it for macro expansion

### clojure.core Stats in Scittle

- Total vars: 546
- With docs: 86 (16%)
- The 86 with docs are mostly macros (`->`, `cond`, `when`, `let`, `for`) and dynamic vars (`*ns*`, `*file*`)

### `doc` Macro in Scittle

```clojure
;; doc exists in clojure.repl
(keys (ns-publics 'clojure.repl))
;; => ["source-fn" "doc" "find-doc" "dir" "dir-fn" "source" "apropos"]

;; Works for user-defined vars
(defn my-fn "My docstring" [x] x)
(with-out-str (clojure.repl/doc my-fn))
;; => "-------------------------\nuser/my-fn\n([x])\n  My docstring\n"

;; Empty for built-ins (no metadata)
(with-out-str (clojure.repl/doc map))
;; => ""
```

---

## ClojureScript Details

### Compile-Time Only Vars

From David Nolen: "runtime metadata on functions would degrade advanced compilation"

- Vars are NOT reified at runtime
- The `var` special form emits a Var instance with compile-time metadata
- `def` returns the value, not the var (unlike Clojure)

### Implications

- `(meta #'user-fn)` works but reflects compile-time state
- Google Closure Compiler's advanced optimizations require this trade-off

---

## clojure-lsp Approach

**Key insight:** clojure-lsp uses static analysis, NOT runtime metadata.

```
Source                      Analysis Method
─────────────────────────   ─────────────────────────────
Your code (.clj/.cljs)   →  Parse AST directly (clj-kondo)
Dependencies (jars)      →  Parse source in jars
clojure.core built-ins   →  clojuredocs.org API (2025 update)
```

### Why This Matters

1. **Works identically for .clj and .cljs** - no runtime needed
2. **Can analyze code that doesn't compile** - pure static analysis
3. **Docstrings come from source** - never calls `(meta #'var)`
4. **clojuredocs.org integration** - crowd-sourced docs for built-ins

---

## Design Implications for bb-mcp-server

### nrepl-get-value Tool

Must handle SCI where `resolve` returns functions directly:

```clojure
;; Before (broken in SCI)
(let [val @v] ...)

;; After (SCI-compatible)
(let [val (if (var? v) @v v)
      var-meta (when (var? v) (meta v))]
  ...)
```

### Phase 0 Introspection Tools

All 4 tools work across JVM Clojure, Babashka, AND Scittle:

| Tool | User-defined vars | Built-in functions |
|------|-------------------|-------------------|
| `nrepl-loaded-namespaces` | ✅ | ✅ |
| `nrepl-introspect-ns` | ✅ | ✅ |
| `nrepl-var-meta` | ✅ Full metadata | ⚠️ nil (expected) |
| `nrepl-get-value` | ✅ | ✅ (returns fn) |

### Future Considerations

For Phase 0.5+ (source capture, static+live integration):

1. **Don't rely on runtime metadata for built-ins** - use clojure-lsp for static analysis
2. **User-defined vars are reliable** - full metadata available at runtime
3. **Consider hybrid approach** - clojure-lsp for static, nREPL for live state

---

## References

- [What's in a Var? - David Nolen](https://swannodette.github.io/2014/12/17/whats-in-a-var/)
- [ClojureScript Differences](https://clojurescript.org/about/differences)
- [clojure-lsp GitHub](https://github.com/clojure-lsp/clojure-lsp)
- [ClojureScript var meta issue](https://github.com/clojure/clojurescript-site/issues/171)
