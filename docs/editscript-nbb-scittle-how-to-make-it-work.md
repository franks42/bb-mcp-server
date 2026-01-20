# Editscript for nbb/Scittle: Port Guide

**Date:** 2026-01-14
**Editscript Version:** 0.7.0
**Status:** Working in both nbb and Scittle browser

---

## Overview

[Editscript](https://github.com/juji-io/editscript) is a Clojure library for diffing and patching data structures. Version 0.7.0 claims Babashka compatibility, but this works because Babashka uses `:clj` reader conditionals.

**nbb and Scittle use `:cljs` reader conditionals**, which requires patches to work around SCI (Small Clojure Interpreter) limitations.

This document describes the patches needed and procedures to use editscript in nbb/Scittle.

---

## Quick Start

### Loading into Scittle Browser

Files must be loaded in dependency order using the MCP tool (not `bb nrepl load-file` which silently fails for `.cljc`):

```bash
# 1. Load files in order
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/edit.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/util/common.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/util/pairing.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/util/index.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/diff/a_star.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/diff/quick.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/patch.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/path/to/editscript/core.cljc","connection":"browser-N","timeout":30000}' --mcp <nickname>

# 2. Use it (must use full namespace, aliases don't persist across evals)
bb nrepl eval "(editscript.core/diff [1 2 3] [1 2 4])" --connection browser-N --mcp <nickname>
# => [[[2] "r" 4]]

bb nrepl eval "(let [a {:x 1} b {:x 2 :y 3} d (editscript.core/diff a b)] (editscript.core/patch a d))" --connection browser-N --mcp <nickname>
# => {:x 2, :y 3}
```

### Loading into nbb

```bash
cd /path/to/editscript-nbb
nbb -cp src -e "(require '[editscript.core :as e]) (println (e/diff [1 2 3] [1 2 4]))"
```

---

## SCI Limitations & Workarounds

### 1. `^:unsynchronized-mutable` → `^:mutable`

**Problem:** SCI doesn't support `^:unsynchronized-mutable` annotation on deftype fields.

**Error:** `Invalid assignment target`

**Solution:** Use `^:mutable` for CLJS branch.

**Files affected:** `edit.cljc`, `util/index.cljc`, `util/pairing.cljc`, `diff/a_star.cljc`

```clojure
;; Before (CLJ only)
(deftype Foo [^:unsynchronized-mutable x] ...)

;; After (with CLJS branch)
#?(:clj
   (deftype Foo [^:unsynchronized-mutable x] ...)
   :cljs
   (deftype Foo [^:mutable x] ...))
```

### 2. CLJS Protocols Not Exposed for deftype

**Problem:** SCI doesn't expose CLJS core protocols (IHash, IEquiv, ISeqable, ICollection, IStack, IAssociative, IMap) for deftype implementation.

**Error:** `Protocol not found: IHash` (or ISeqable, IStack, etc.)

**Solution:**
- Use `defrecord` instead of `deftype` when hash/equality is needed
- Create wrapper functions for protocol operations
- Use map-based implementations

**Example - Coord (a_star.cljc):**

```clojure
;; Before: deftype implementing IHash, IEquiv, IComparable
(deftype Coord [^long a ^long b]
  IHash (hash [_] ...)
  IEquiv (equals [_ o] ...)
  IComparable (compareTo [_ o] ...))

;; After: defrecord for CLJS (records auto-implement hash/equality)
#?(:clj
   (deftype Coord [^long a ^long b] ...)
   :cljs
   (defrecord Coord [a b]))
```

**Example - PriorityMap (pairing.cljc):**

```clojure
;; Before: deftype implementing ISeqable, ICollection, IStack, etc.
(deftype PriorityMap [heap map]
  IPersistentStack
  (peek [_] ...)
  (pop [this] ...))

;; After: Map-based implementation with wrapper functions
#?(:cljs
   (do
     (defn pq-peek [pm]
       (when-let [[pair _] (first (:sorted pm))]
         [(second pair) (first pair)]))

     (defn pq-pop [pm]
       (if-let [[pair _] (first (:sorted pm))]
         (let [item (second pair)]
           {:items (dissoc (:items pm) item)
            :sorted (dissoc (:sorted pm) pair)})
         pm))

     (defn pq-assoc [pm k v]
       (let [old-v (get (:items pm) k)]
         {:items (assoc (:items pm) k v)
          :sorted (cond-> (:sorted pm)
                    old-v (dissoc [old-v k])
                    true  (assoc [v k] true))}))

     (defn pq-empty? [pm]
       (empty? (:items pm)))))

;; priority-map constructor returns simple map
#?(:cljs
   (defn priority-map
     ([] {:items {} :sorted (sorted-map)})
     ([& keyvals]
      (reduce (fn [pm [k v]] (pq-assoc pm k v))
              (priority-map)
              (partition 2 keyvals)))))
```

### 3. `.-field` Access Returns nil

**Problem:** Direct field access on deftypes via `.-field` returns `nil` in SCI.

**Error:** Silent - returns `nil` instead of the value.

**Solution:** Use keyword access on defrecords, or create accessor functions.

```clojure
;; Before
(.-a coord)

;; After - accessor functions
(defn coord-a [c]
  #?(:clj (.-a ^Coord c)
     :cljs (:a c)))

(defn coord-b [c]
  #?(:clj (.-b ^Coord c)
     :cljs (:b c)))
```

### 4. `goog.math.Long` Not Available

**Problem:** nbb/Scittle don't include Google Closure's math library.

**Error:** `Could not resolve symbol: goog.math.Long`

**Solution:** Use `js/Number.MAX_SAFE_INTEGER` instead.

```clojure
;; Before
(:require [goog.math.Long :refer [getMaxValue]])
(def ^:const ^long ^:private +INFINITY+ (getMaxValue))

;; After
#?(:clj (def ^:const ^long ^:private +INFINITY+ Long/MAX_VALUE)
   :cljs (def ^:private +INFINITY+ js/Number.MAX_SAFE_INTEGER))
```

### 5. `clojure.data.priority-map` Not in nbb

**Problem:** Babashka includes `clojure.data.priority-map`, but nbb doesn't.

**Solution:** Use `:bb` reader conditional for BB, custom implementation for `:cljs`.

```clojure
#?(:bb (def priority-map clojure.data.priority-map/priority-map)
   :clj (defn priority-map [...] ...)  ;; Custom pairing heap
   :cljs (defn priority-map [...] ...)) ;; Map-based implementation
```

---

## Complete List of Changed Files

### 1. `editscript/edit.cljc`

- Added `:cljs` branch for `EditScript` deftype with `^:mutable`
- Added `IPrintWithWriter` extension for CLJS printing

### 2. `editscript/util/index.cljc`

- Removed `goog.math.Long` import
- Added `:cljs` branch for `Node` deftype with `^:mutable`

### 3. `editscript/util/pairing.cljc`

- Added `:cljs` branch for `HeapNode` deftype with `^:mutable`
- Replaced `PriorityMap` deftype with map-based implementation for CLJS
- Added wrapper functions: `pq-peek`, `pq-pop`, `pq-assoc`, `pq-empty?`
- Added `:bb` reader conditional for `clojure.data.priority-map`

### 4. `editscript/diff/a_star.cljc`

- Removed `goog.math.Long` import, use `js/Number.MAX_SAFE_INTEGER`
- Added `:cljs` branch for `State` deftype with `^:mutable`
- Changed `Coord` from deftype to defrecord for CLJS
- Added accessor functions: `coord-a`, `coord-b`
- Added map operation helpers: `came-assoc!`, `came-assoc`, `came-get`, `g-assoc!`, `g-get`
- Added `coord-key` function for using Coords as map keys in CLJS
- Updated all `(.-a coord)` to `(coord-a coord)` pattern

---

## Detailed Patches

### a_star.cljc - Coord Changes

The `Coord` type is used as a key in maps for the A* algorithm. In JVM Clojure, deftype with IHash/IEquiv works. In SCI, we use defrecord which auto-implements these.

```clojure
;; Coord definition
#?(:clj
   (deftype Coord [^long a ^long b]
     Object
     (hashCode [_] (hash-combine (hash a) (hash b)))
     (equals [_ o] (and (instance? Coord o)
                        (= a (.-a ^Coord o))
                        (= b (.-b ^Coord o))))
     Comparable
     (compareTo [_ o]
       (let [c (compare a (.-a ^Coord o))]
         (if (zero? c)
           (compare b (.-b ^Coord o))
           c))))
   :cljs
   (defrecord Coord [a b]))

;; Accessor functions (used throughout)
(defn coord-a [c]
  #?(:clj (.-a ^Coord c)
     :cljs (:a c)))

(defn coord-b [c]
  #?(:clj (.-b ^Coord c)
     :cljs (:b c)))

;; For map keys in CLJS, use a vector key instead of the record directly
#?(:cljs
   (defn coord-key [c]
     [(get-order (coord-a c)) (get-order (coord-b c))]))

;; Map operation helpers
(defn came-assoc! [m coord val]
  #?(:clj (assoc! m coord val)
     :cljs (assoc! m (coord-key coord) val)))

(defn came-get [m coord]
  #?(:clj (get m coord)
     :cljs (get m (coord-key coord))))

;; etc.
```

### pairing.cljc - Priority Map

The original uses a pairing heap with deftype implementing `IPersistentStack`. For CLJS, we use a simple map-based implementation:

```clojure
;; CLJS priority-map structure:
;; {:items {item priority, ...}
;;  :sorted (sorted-map [priority item] true, ...)}

;; Operations:
;; - pq-peek: Get [item priority] with lowest priority
;; - pq-pop: Remove lowest priority item
;; - pq-assoc: Add/update item with priority
;; - pq-empty?: Check if empty
```

---

## Testing

### Test in nbb

```bash
cd /tmp/editscript-nbb
nbb -cp src -e "
(require '[editscript.core :as e])

(println \"Test 1: Vector diff\")
(println (e/get-edits (e/diff [1 2 3] [1 2 4])))

(println \"Test 2: Map diff\")
(println (e/get-edits (e/diff {:a 1 :b 2} {:a 1 :b 3 :c 4})))

(println \"Test 3: Patch\")
(let [a {:x 1 :y 2}
      b {:x 1 :y 3 :z 4}
      d (e/diff a b)]
  (println (e/patch a d)))

(println \"All tests passed!\")
"
```

### Test in Scittle Browser

After loading all files:

```clojure
;; In browser via nREPL
(editscript.core/diff [1 2 3] [1 2 4])
;; => [[[2] "r" 4]]

(let [a {:x 1 :y 2}
      b {:x 1 :y 3 :z 4}
      d (editscript.core/diff a b)]
  (editscript.core/patch a d))
;; => {:x 1, :y 3, :z 4}

(editscript.core/edit-distance (editscript.core/diff [1 2 3 4 5] [1 3 4 5 6]))
;; => 2
```

---

## Future Work

1. **Upstream PR:** Consider submitting patches to editscript upstream with proper `:cljs` branches for SCI compatibility.

2. **Fix `bb nrepl load-file`:** The CLI command silently fails for `.cljc` files in Scittle. Tracked in IMPLEMENTATION_PLAN.md Task 18.1.

3. **Package as module:** Could create an `editscript-scittle` module that bundles the patched files and loads them automatically.

4. **Performance testing:** The map-based priority queue may be slower than the pairing heap for large diffs. Benchmark if needed.

---

## Related Issues

- [babashka/nbb#404](https://github.com/babashka/nbb/issues/404) - Request to expose more CLJS types to nbb
- [babashka/sci#1017](https://github.com/babashka/sci/pull/1017) - hashCode support on deftype (merged 2026-01-13)
- [babashka/sci#345](https://github.com/babashka/sci/issues/345) - Main deftype tracking issue
- [babashka/scittle#80](https://github.com/babashka/scittle/issues/80) - Confirms `.-field` doesn't work, use defrecord

---

## Location of Patched Files

The patched editscript is at: `/tmp/editscript-nbb/`

```
/tmp/editscript-nbb/
├── src/
│   └── editscript/
│       ├── core.cljc
│       ├── edit.cljc
│       ├── patch.cljc
│       ├── diff/
│       │   ├── a_star.cljc
│       │   └── quick.cljc
│       └── util/
│           ├── common.cljc
│           ├── index.cljc
│           └── pairing.cljc
└── deps.edn
```

---

*Last Updated: 2026-01-14*
