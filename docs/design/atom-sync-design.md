# Atom Sync Design

**Status:** Design Draft
**Created:** 2026-01-11
**Module:** `modules/atom-sync/`

---

## Vision

Synchronize Clojure atoms between bb-mcp-server and browser (Scittle) over sente-lite WebSocket. Enables reactive UI patterns where server-side state changes automatically reflect in browser.

**Phase 1 Goal:** One-way sync (server → browser). Server owns state, browser observes.

**Future:** Bidirectional sync with conflict resolution.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         bb-mcp-server                                │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  atom-sync registry                                          │    │
│  │  !synced-atoms = {:layout     (atom {...})                   │    │
│  │                   :namespaces (atom [...])                   │    │
│  │                   :source     (atom nil)}                    │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                    add-watch on each atom                            │
│                              │                                       │
│                              ▼                                       │
│               [:sync/atom {:key :layout :value {...}}]               │
│                              │                                       │
│               push via sente-lite (to all or specific clients)       │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────┬───────────────────────────────────────┐
│                         Browser                                       │
│                              │                                       │
│                    receive [:sync/atom ...]                          │
│                              │                                       │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  !synced-atoms = {:layout     (r/atom {...})    ◄── Reagent  │    │
│  │                   :namespaces (r/atom [...])                  │    │
│  │                   :source     (r/atom nil)}                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                    Reagent re-renders on change                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Design Decisions

### 1. Sync Direction (Phase 1)

**Decision:** One-way sync (server → browser)

Server owns the atoms. Browser receives updates but cannot modify server state through sync mechanism. Browser actions trigger events (`:code-browser/select-ns`) that server handles and updates atoms accordingly.

```
Browser action → Event → Server handler → Update atom → Sync to browser
```

### 2. Sync Scope

**Decision:** Shared atoms (all browsers see same value)

For Phase 1, a synced atom has one value that all connected browsers receive. Per-client atoms can be added later if needed.

**Rationale:** Simpler implementation, sufficient for code-browser use case where all browsers view same project.

### 3. Sync Granularity

**Decision:** Full state sync (no delta)

On every atom change, send the complete value. No diffing or patch generation.

**Rationale:**
- Simpler implementation
- Avoids merge conflicts
- Atom values are typically small-to-medium maps
- Can optimize later if performance requires

### 4. Message Protocol

**Decision:** Map-primitive-based operations (assoc-in/dissoc-in)

Modeling the protocol after Clojure's map primitives allows:
- Full state sync by assoc-in at root path `[]`
- Granular updates by assoc-in at nested paths `[:a :b :c]`
- Same message format scales from simple to optimized

```clojure
;; Server → Browser
[:sync/op {:key   :my-atom          ; atom identifier
           :op    :assoc-in         ; :assoc-in | :dissoc-in
           :path  []                ; [] = root (full replace), [:a :b] = nested
           :value {...}             ; value to set (omitted for dissoc-in)
           :ts    1736654400000}]   ; optional: epoch-ms for debugging

;; Examples:
;; Full state sync (Phase 1 default)
[:sync/op {:key :code-browser :op :assoc-in :path [] :value {:selected-ns "user" :symbols [...]}}]

;; Granular update (future optimization)
[:sync/op {:key :code-browser :op :assoc-in :path [:selected-ns] :value "clojure.core"}]

;; Remove key
[:sync/op {:key :code-browser :op :dissoc-in :path [:error]}]

;; Browser → Server (future Phase 2)
[:sync/op {:key   :my-atom
           :op    :assoc-in
           :path  [:user-input]
           :value "..."
           :ts    1736654400001}]
```

**Browser Handler:**
```clojure
(defn apply-sync-op [{:keys [key op path value]}]
  (when-let [a (get @!synced-atoms key)]
    (case op
      :assoc-in  (swap! a assoc-in path value)
      :dissoc-in (swap! a update-in (butlast path) dissoc (last path))
      nil)))
```

**Why this approach:**
- Familiar Clojure semantics
- Phase 1: Just use `path []` for full sync
- Future: Optimize with granular paths, no protocol change needed
- Simple to implement and debug

### 5. Sync Reliability & State Verification

**Decision:** Sequence numbers for ordering and gap detection

Each synced atom maintains a monotonic sequence number in the **registry** (not in the atom value itself). This keeps the atom interface clean - users work with their data normally.

**Why not store seq in atom value:**
```clojure
;; BAD: Pollutes atom interface
(def !state (atom {:seq 42 :value {:counter 0}}))
(swap! !state update :value assoc :counter 1)  ; awkward!

;; GOOD: Clean atom, seq in registry
(def !state (atom {:counter 0}))
(swap! !state assoc :counter 1)  ; natural!
;; Registry tracks: {:my-state {:atom !state :seq 42}}
```

**Server-side registry structure:**
```clojure
;; Registry maintains seq separate from atom value
(defonce !synced-atoms
  (atom {}))  ; {:key {:atom atom-ref :seq n :push-on-change? bool}}

;; On atom change, increment seq and include in message
(defn push-atom! [key]
  (let [{:keys [atom seq]} (get @!synced-atoms key)
        new-seq (inc seq)]
    (swap! !synced-atoms assoc-in [key :seq] new-seq)
    (broadcast! [:sync/op {:key key
                           :seq new-seq
                           :op :assoc-in
                           :path []
                           :value @atom}])))
```

**Browser-side tracking:**
```clojure
;; Browser tracks last-seen seq per atom
(defonce !sync-state
  (atom {}))  ; {:my-atom {:seq 42 :value (r/atom {...})}}

(defn apply-sync-op [{:keys [key seq op path value]}]
  (let [{:keys [seq last-seq]} (get @!sync-state key)
        expected-seq (inc (or last-seq 0))]
    (cond
      ;; Normal case: sequential update
      (= seq expected-seq)
      (do (swap! (get-synced-atom key) assoc-in path value)
          (swap! !sync-state assoc-in [key :seq] seq))

      ;; Gap detected: missed updates, request full resync
      (> seq expected-seq)
      (do (js/console.warn "Sync gap detected, requesting resync" key)
          (request-full-sync! key))

      ;; Stale/duplicate: ignore
      (< seq expected-seq)
      (js/console.debug "Ignoring stale sync message" key seq))))
```

**Guarantees:**
- ✓ Ordering: seq ensures updates applied in order
- ✓ Gap detection: missing seq triggers resync
- ✓ Duplicate handling: stale messages ignored
- ✓ Clean interface: users work with plain atom values
- ✓ Introspectable: can query current seq on both sides

**Future: Content hash verification**
```clojure
;; Optional hash for periodic integrity check
[:sync/op {:key :my-atom :seq 100 :op :assoc-in :path []
           :value {...}
           :hash "sha256:abc123"}]  ; hash of serialized value

;; Browser verifies after applying
;; Mismatch → request full resync
```

### 6. Initial State on Connect

**Decision:** Push all registered atoms on `:server/ready`

When browser completes handshake and receives `:server/ready`, server immediately pushes current value of all registered synced atoms with `seq 1` (or current seq if reconnecting).

### 7. Reconnection Handling

**Decision:** Re-push full state on reconnect

Session identity persists (via `session-id`), but on WebSocket reconnect, server re-pushes all synced atoms to ensure consistency. Browser resets its seq tracking to match.

---

## Existing Code (Browser-Side)

Already in `modules/sente-browser/src/sente_browser/bootstrap.clj` (embedded in HTML):

```clojure
;; Registry of synced atoms
(defonce !synced-atoms (atom {}))
(defonce !sync-watchers-installed (atom #{}))

;; Get or create a synced Reagent atom by key
(defn get-synced-atom [key]
  (or (get @!synced-atoms key)
      (let [a (r/atom nil)]
        (swap! !synced-atoms assoc key a)
        a)))

;; Handle :sync/atom message from server
(defn on-sync-message [{:keys [key value]}]
  (when-let [a (get @!synced-atoms key)]
    (reset! a value)))

;; Install watcher to push changes back to server (future Phase 2)
(defn install-sync-watcher! [key client-id]
  (when-not (contains? @!sync-watchers-installed key)
    (when-let [a (get @!synced-atoms key)]
      (add-watch a ::sync-to-server
        (fn [_ _ old-val new-val]
          (when (not= old-val new-val)
            (client/send! client-id [:sync/atom-update {:key key :value new-val}]))))
      (swap! !sync-watchers-installed conj key))))
```

Handler wired up in `on-message`:
```clojure
:sync/atom
(on-sync-message data)
```

**Status:** Code exists but is **unused**. No server-side implementation.

---

## Existing Code (Server-Side)

In `modules/sente-browser/src/sente_browser/server.clj`:

```clojure
(defn send-to-browser! [sente-conn-id event]
  (sente-server/send-event-to-connection! sente-conn-id event))

(defn broadcast-to-browsers! [event]
  (let [conn-ids (keys @!browser-connections)]
    (doseq [conn-id conn-ids]
      (send-to-browser! conn-id event))
    (count conn-ids)))
```

**Status:** Transport exists. No synced-atom registry or watchers.

---

## Proposed Server-Side API

```clojure
(ns atom-sync.server
  "Server-side atom synchronization over sente-lite.")

;; =============================================================================
;; Registry
;; =============================================================================

;; Map of key -> {:atom atom-ref, :seq n, :push-on-change? bool}
;; Seq is maintained in registry, NOT in atom value (keeps atom interface clean)
(defonce !synced-atoms (atom {}))

(defn register-synced-atom!
  "Register an atom for sync to browsers.

   Options:
   - :push-on-change? (default true) - auto-push on atom change

   Example:
     (def !layout (atom {:ns-width \"25%\"}))
     (register-synced-atom! :layout !layout)
     ;; Users work with atom normally:
     (swap! !layout assoc :ns-width \"30%\")  ; auto-syncs to browsers"
  [key atom-ref & {:keys [push-on-change?]
                   :or {push-on-change? true}}]
  (swap! !synced-atoms assoc key
         {:atom atom-ref
          :seq 0                       ; starts at 0, first push will be seq 1
          :push-on-change? push-on-change?})
  (when push-on-change?
    (add-watch atom-ref [::sync key]
      (fn [_ _ old-val new-val]
        (when (not= old-val new-val)
          (push-atom! key)))))
  key)

(defn unregister-synced-atom!
  "Remove an atom from sync registry."
  [key]
  (when-let [{:keys [atom]} (get @!synced-atoms key)]
    (remove-watch atom [::sync key]))
  (swap! !synced-atoms dissoc key))

;; =============================================================================
;; Push Functions
;; =============================================================================

(defn push-atom!
  "Push current value of atom to all connected browsers.
   Increments seq and includes in message for ordering/gap detection."
  [key]
  (when-let [{:keys [atom seq]} (get @!synced-atoms key)]
    (let [new-seq (inc seq)
          value @atom]
      ;; Update seq in registry
      (swap! !synced-atoms assoc-in [key :seq] new-seq)
      ;; Broadcast with seq for reliability
      (sente-browser.server/broadcast-to-browsers!
        [:sync/op {:key   key
                   :seq   new-seq
                   :op    :assoc-in
                   :path  []
                   :value value}]))))

(defn push-atom-to-client!
  "Push atom value to a specific browser connection.
   Uses current seq (doesn't increment - this is a catch-up push)."
  [sente-conn-id key]
  (when-let [{:keys [atom seq]} (get @!synced-atoms key)]
    (sente-browser.server/send-to-browser! sente-conn-id
      [:sync/op {:key   key
                 :seq   seq           ; current seq, not incremented
                 :op    :assoc-in
                 :path  []
                 :value @atom}])))

(defn push-all-atoms!
  "Push all registered atoms to all browsers."
  []
  (doseq [key (keys @!synced-atoms)]
    (push-atom! key)))

(defn get-sync-status
  "Get current sync status for debugging/introspection.
   Returns map of atom keys to their current seq numbers."
  []
  (into {}
    (map (fn [[k {:keys [seq]}]] [k seq]))
    @!synced-atoms))

(defn push-all-atoms-to-client!
  "Push all registered atoms to a specific browser."
  [sente-conn-id]
  (doseq [key (keys @!synced-atoms)]
    (push-atom-to-client! sente-conn-id key)))

;; =============================================================================
;; Integration Hook
;; =============================================================================

(defn on-browser-connected!
  "Called when a browser completes handshake.
   Pushes all synced atoms to the new client."
  [sente-conn-id]
  (push-all-atoms-to-client! sente-conn-id))
```

---

## Proposed Browser-Side API

Existing code in bootstrap.clj is sufficient. Key functions:

```clojure
;; Get a synced atom (auto-created if not exists)
(get-synced-atom :my-key)  ; => reagent atom

;; Usage in component
(defn my-component []
  (let [data @(get-synced-atom :my-data)]
    [:div (pr-str data)]))
```

---

## Integration Points

### 1. Hook into Browser Connection

In `sente-browser.server/promote-to-validated!`, after sending `:server/ready`:

```clojure
;; After handshake complete, push synced atoms
(atom-sync.server/on-browser-connected! sente-conn-id)
```

### 2. Feature Registration

Features (like code-browser) register their atoms at startup:

```clojure
(ns code-browser.server
  (:require [atom-sync.server :as sync]))

(def !browser-state
  (atom {:namespaces []
         :selected-ns nil
         :symbols []
         :source nil}))

(defn enable! []
  (sync/register-synced-atom! :code-browser !browser-state))

(defn disable! []
  (sync/unregister-synced-atom! :code-browser))
```

### 3. Server Updates State, Sync Happens Automatically

```clojure
;; Server handler for browser action
(defn handle-select-ns [{:keys [ns]}]
  (let [symbols (fetch-symbols-for-ns ns)]
    ;; Update atom - watcher auto-syncs to browsers
    (swap! !browser-state assoc
           :selected-ns ns
           :symbols symbols
           :source nil)))
```

---

## Module Structure

```
modules/atom-sync/
├── src/atom_sync/
│   ├── core.clj        ; Transport-independent sync logic
│   └── server.clj      ; sente-lite integration (thin wrapper)
├── test/atom_sync/
│   ├── core_test.clj   ; Local sync tests (no network)
│   └── server_test.clj ; Integration tests
└── module.edn
```

Browser-side code remains in `sente-browser/bootstrap.clj` (already exists).

### Layered Architecture

**Core layer** (`core.clj`) - Transport-independent:
- `deep-diff->ops` - generate sync ops from value changes
- `apply-sync-op` - apply op to target atom
- `register-synced-atom!` / `unregister-synced-atom!`
- `subscribe!` / `unsubscribe!` - callback-based push
- Seq tracking, gap detection

**Transport layer** (`server.clj`) - Thin wrapper:
- Subscribes to core with sente-lite broadcast callback
- Handles `:sync/resync-request` from browser
- Wires into browser connection lifecycle

**Benefits:**
- Core logic testable without WebSocket (two atoms, same process)
- Transport layer is ~20 lines
- Easy to add other transports (SSE, polling) if needed
- Clear separation of concerns

---

## Implementation Phases

### Phase 1A: Server-Side Core (One-Way Sync)

| Task | Description |
|------|-------------|
| 1A.1 | Create `modules/atom-sync/` module structure |
| 1A.2 | Implement `register-synced-atom!` with watch |
| 1A.3 | Implement `push-atom!` and `push-all-atoms!` |
| 1A.4 | Implement `push-atom-to-client!` for single browser |
| 1A.5 | Add `on-browser-connected!` hook |

### Phase 1B: Integration

| Task | Description |
|------|-------------|
| 1B.1 | Hook into `sente-browser.server/promote-to-validated!` |
| 1B.2 | Verify browser receives `:sync/atom` on connect |
| 1B.3 | Test: change atom on server, browser updates |

### Phase 1C: Testing

| Task | Description |
|------|-------------|
| 1C.1 | Unit tests for registry functions |
| 1C.2 | Integration test: register → push → browser receives |
| 1C.3 | Reconnection test: browser reconnects, gets fresh state |

### Phase 2: Bidirectional (Future)

| Task | Description |
|------|-------------|
| 2.1 | Server handler for `:sync/atom-update` |
| 2.2 | Conflict resolution (last-write-wins) |
| 2.3 | Browser-writable flag per atom |

---

## Future Optimization: Delta Sync

### Editscript Analysis (Investigated, NOT Compatible)

[Editscript](https://github.com/juji-io/editscript) by Huahai Yang (Datalevin author) was investigated for delta sync optimization.

**Findings from code review:**
- **9 `deftype` declarations** across the codebase
- Core `EditScript` type uses `^:unsynchronized-mutable` fields and `locking` macro
- `extend-protocol IType` to Java-specific types (`IPersistentVector`, `IPersistentMap`, etc.)

**Verdict: NOT compatible with Babashka/Scittle** - SCI lacks `deftype` support.

### Clerk's Approach (For Reference)

[Clerk](https://github.com/nextjournal/clerk) uses editscript for atom syncing, but with important caveats:

**Server-side (webserver.clj):**
```clojure
;; Conditional load - Babashka only gets alias!
(u/if-bb
  (require '[editscript.core :as-alias editscript])  ; no actual editscript
  (require '[editscript.core :as editscript]))

;; Delta sync (JVM only)
{:type :patch-state!
 :patch (editscript/get-edits (editscript/diff old new {:algo :quick}))}

;; Browser → Server sync throws in Babashka!
:sync! (u/if-bb
         (throw (ex-info "Not implemented" {}))
         (swap! @var editscript/patch ...))
```

**Browser-side (render.cljs):**
```clojure
;; Clerk uses compiled ClojureScript (shadow-cljs), NOT Scittle
[editscript.core :as editscript]

(defn atom-changed [var-name _atom old-state new-state]
  (ws-send! {:type :sync!
             :var-name var-name
             :patch (editscript/get-edits
                      (editscript/diff old-state new-state {:algo :quick}))}))
```

**Why Clerk can use editscript but we cannot:**
| Aspect | Clerk | bb-mcp-server |
|--------|-------|---------------|
| Browser runtime | Compiled ClojureScript | Scittle (SCI interpreter) |
| Server runtime | JVM Clojure (primary) | Babashka (primary) |
| deftype support | ✓ Yes | ✗ No |
| Bidirectional sync | JVM only | Needed on bb |

**Editscript edits format (useful reference):**
```clojure
[[path :+ value]   ; add
 [path :-]         ; delete
 [path :r value]]  ; replace
```

Our map-primitive protocol achieves equivalent semantics with SCI-compatible pure functions.

### `differ` Library (Investigated, SCI-Compatible ✓)

[differ](https://github.com/robinheghan/differ) is a pure-function diff/patch library for Clojure/ClojureScript.

**Source code analysis:**
- ❌ No `deftype`, `defrecord`, or `defprotocol`
- ✓ Pure functions only (~8KB total)
- ✓ `.cljc` files (cross-platform)
- ✓ MIT license

**Babashka compatibility: VERIFIED**
```clojure
;; All tests pass in Babashka
(require '[differ.core :as differ])
(differ/diff {:a {:b 1}} {:a {:b 2}})  ; => [{:a {:b 2}} {}]
(differ/patch {:a {:b 1}} [{:a {:b 2}} {}])  ; => {:a {:b 2}}
```

**Original vs Fork:**

| Test Case | Original (0.3.3) | Fork (0.4.0-alpha) |
|-----------|------------------|-------------------|
| Simple map | ✓ | ✓ |
| Nested map | ✓ | ✓ |
| Vector→String | ✓ | ✓ |
| Empty set | ✓ | ✓ |
| **Falsey keys** (`nil`, `false`) | **✗ FAILS** | ✓ |
| Records | ✗ | ✓ |

**Fork: [jeremyrsellars/differ](https://github.com/jeremyrsellars/differ)**
- Fixes falsey keys bug, adds record support
- Adds generative/round-trip tests
- Active (Nov 2023), while original unmaintained (2017-2019)

```clojure
;; deps.edn - use the fork
{:deps {io.github.jeremyrsellars/differ
        {:git/tag "v0.4.0-alpha"
         :git/sha "17e0b343bb636d8a397673cc3fbb5e70e01a5fd7"}}}
```

**Code quality assessment:**
- **Strengths:** Clean architecture, type preservation, transient optimization
- **Concerns:** Dense vector logic, limited docs, magic keywords (`:+` for additions)
- **Original repo:** 7+ years old, 2 open issues ignored, PR from fork author unmerged
- **Verdict:** Use the fork if adopting; or use our simpler `deep-diff->ops` (~25 lines)

**Scittle compatibility:**
- Should work (pure functions, no SCI-incompatible constructs)
- Requires bundling source (~8KB) since Scittle can't load Maven deps

**Recommendation:** For Phase 1, our custom `deep-diff->ops` is simpler and sufficient. Consider `differ` fork only if we need vector diffing or more battle-tested edge case handling.

### Vector Diffing Analysis

**Question:** Our maps contain vectors as values - does this complicate diffing?

**Code-browser state example:**
```clojure
{:namespaces ["user" "clojure.core" "my.ns"]   ; vector of strings
 :symbols [{:name "foo" :type :fn} ...]         ; vector of maps
 :selected-ns "user"
 :source "..."}
```

**How vectors change in our use case:**

| Field | Update Pattern | Incremental diff useful? |
|-------|---------------|--------------------------|
| `:namespaces` | Wholesale replace on project load | ❌ No |
| `:symbols` | Wholesale replace on ns selection | ❌ No |
| `:source` | Wholesale replace on symbol select | N/A (string) |

**Verdict:** Our vectors are replaced entirely, not edited incrementally. Simple replacement is optimal.

**Protocol limitation with vectors:**
```clojure
;; Our assoc-in/dissoc-in protocol is map-oriented:
(assoc-in {:v [1 2 3]} [:v 1] 99)   ; => {:v [1 99 3]} ✓ update works
(update {:v [1 2 3]} :v dissoc 1)   ; => ERROR! vectors don't support dissoc

;; Vector operations need different semantics:
;; - insert at index, remove at index, append
;; - Would require new ops: :vec-insert, :vec-remove, :vec-append
```

**Future: If we need incremental vector sync**, options are:
1. Add vector-specific ops to our protocol
2. Use `differ` which has its own patch format for vectors
3. Identify vector elements by ID (for vectors of maps with `:id` keys)

**For Phase 1:** Vectors as map values are handled by replacement at their path:
```clojure
;; deep-diff->ops treats vectors as atomic values
old: {:items [1 2 3]}
new: {:items [1 2 3 4]}
ops: [[:sync/op {:op :assoc-in :path [:items] :value [1 2 3 4]}]]
```

This is correct, simple, and sufficient for wholesale-replacement patterns.

### Could We Add Vector Diffing Ourselves?

**Yes, but it requires protocol changes.** The differ library is small (~95 lines), but adapting its vector logic to our protocol isn't trivial:

**Format mismatch:**
```clojure
;; differ's output (compact, custom format):
[{:items [:+ 4]} {:items [1]}]  ; [alterations, removals]

;; Our protocol (assoc-in/dissoc-in based):
[[:sync/op {:op :assoc-in :path [:items 3] :value 4}]]
```

**Index-based paths partially work:**
```clojure
;; Update at index - works!
(assoc-in {:v [1 2 3]} [:v 1] 99)  ; => {:v [1 99 3]}
[:sync/op {:op :assoc-in :path [:items 1] :value 99}]

;; Remove at index - doesn't work!
;; Vectors don't support dissoc, need splice/subvec operations
[:sync/op {:op :dissoc-in :path [:items 1]}]  ; ✗ can't apply
```

**To support full vector diffing, we'd need new ops:**
```clojure
[:sync/op {:op :vec-append :path [:items] :value 4}]
[:sync/op {:op :vec-splice :path [:items] :start 1 :delete 2 :insert [99]}]
```

**Implementation complexity comparison:**

| Approach | Lines | Capabilities | Notes |
|----------|-------|--------------|-------|
| Our `deep-diff->ops` | ~25 | Maps only, vectors replaced | Current |
| + vector update | ~35 | + update at index | Uses assoc-in with int keys |
| + vector append | ~45 | + detect appends | Common case optimization |
| + full vector splice | ~65 | Full vector diff | Needs new protocol ops |
| Use `differ` fork | 0 | Full diff/patch | External dep, own format |

**Recommendation:**
- Phase 1: Simple replacement (current)
- If needed: Add append detection (~10 lines) - covers common case
- If complex edits needed: Either extend protocol or use `differ` fork

The differ fork is MIT licensed and ~95 lines - small enough to vendor if we want to avoid git dependency. But for our wholesale-replacement use case, this complexity isn't needed.

### Diff-Based Delta Sync Options

For future granular sync, we can compute diffs from old/new values without intercepting operations. All options below are **SCI-compatible** (work in Babashka and Scittle).

#### Option 1: `clojure.data/diff` (Shallow)

Standard library, simple, but only works for flat maps:

```clojure
(require '[clojure.data :as data])

(data/diff {:a 1 :b 2 :c 3}      ; old
           {:a 1 :b 99 :d 4})    ; new

;; => [{:b 2 :c 3}    ; only in old (removed/changed-from)
;;     {:b 99 :d 4}   ; only in new (added/changed-to)
;;     {:a 1}]        ; unchanged

(defn shallow-diff->ops
  "Translate clojure.data/diff to sync ops. Shallow - top-level keys only."
  [key old-val new-val]
  (let [[removed added _unchanged] (data/diff old-val new-val)
        removed-keys (set (keys removed))
        added-keys (set (keys added))]
    (concat
      ;; Truly removed (in old, not in new)
      (for [k removed-keys
            :when (not (contains? added-keys k))]
        [:sync/op {:key key :op :dissoc-in :path [k]}])
      ;; Added or changed (in new)
      (for [[k v] added]
        [:sync/op {:key key :op :assoc-in :path [k] :value v}]))))

;; Example:
(shallow-diff->ops :state {:a 1 :b 2 :c 3} {:a 1 :b 99 :d 4})
;; => ([:sync/op {:key :state :op :dissoc-in :path [:c]}]
;;     [:sync/op {:key :state :op :assoc-in :path [:b] :value 99}]
;;     [:sync/op {:key :state :op :assoc-in :path [:d] :value 4}])
```

**Limitation:** Nested changes replace entire subtree:
```clojure
(data/diff {:user {:name "old" :age 30}}
           {:user {:name "new" :age 30}})
;; => [{:user {:name "old"}}   ; whole :user marked different
;;     {:user {:name "new"}}   ; not path [:user :name]
;;     nil]
```

#### Option 2: Deep Recursive Diff (Nested Paths)

Pure Clojure, ~25 lines, handles nested structures:

```clojure
(defn deep-diff->ops
  "Recursively diff two values, return assoc-in/dissoc-in ops with full paths.
   Works with nested maps. SCI-compatible (no deftype/defprotocol)."
  ([key old-val new-val]
   (deep-diff->ops key [] old-val new-val))
  ([key path old-val new-val]
   (cond
     ;; Same value - no ops needed
     (= old-val new-val)
     []

     ;; Both maps - recurse into each key
     (and (map? old-val) (map? new-val))
     (let [all-keys (into (set (keys old-val)) (keys new-val))]
       (mapcat
         (fn [k]
           (let [ov (get old-val k ::missing)
                 nv (get new-val k ::missing)
                 path' (conj path k)]
             (cond
               (= ov nv) []
               (= nv ::missing) [[:sync/op {:key key :op :dissoc-in :path path'}]]
               (= ov ::missing) [[:sync/op {:key key :op :assoc-in :path path' :value nv}]]
               :else (deep-diff->ops key path' ov nv))))
         all-keys))

     ;; Different values or types - replace at current path
     :else
     [[:sync/op {:key key :op :assoc-in :path path :value new-val}]])))

;; Example - nested change:
(deep-diff->ops :state
  {:user {:name "old" :age 30} :count 1}
  {:user {:name "new" :age 30} :count 2})
;; => ([:sync/op {:key :state :op :assoc-in :path [:user :name] :value "new"}]
;;     [:sync/op {:key :state :op :assoc-in :path [:count] :value 2}])

;; Example - removal:
(deep-diff->ops :state
  {:a 1 :b {:x 1 :y 2}}
  {:a 1 :b {:x 1}})
;; => ([:sync/op {:key :state :op :dissoc-in :path [:b :y]}])
```

#### Option 3: Batched Ops (Optimization)

Send multiple ops in single message to reduce overhead:

```clojure
;; Instead of multiple messages:
[:sync/op {:key :state :op :assoc-in :path [:a] :value 1}]
[:sync/op {:key :state :op :assoc-in :path [:b] :value 2}]

;; Single batched message:
[:sync/ops {:key :state
            :seq 42
            :ops [{:op :assoc-in :path [:a] :value 1}
                  {:op :assoc-in :path [:b] :value 2}
                  {:op :dissoc-in :path [:c]}]}]
```

#### Comparison Table

| Approach | Nested Paths | Vectors | SCI-Compatible | Best For |
|----------|--------------|---------|----------------|----------|
| Full sync (Phase 1) | N/A | N/A | ✓ | Small atoms, simplicity |
| `clojure.data/diff` | ❌ Shallow | ❌ | ✓ | Flat maps |
| `deep-diff->ops` | ✓ Yes | ❌ | ✓ | Nested maps (our use case) |
| `differ` (fork) | ✓ Yes | ✓ | ✓ | Maps + vectors, battle-tested |
| editscript | ✓ Yes | ✓ | ❌ | N/A (requires deftype) |
| schism (CRDT) | ✓ Yes | ✓ | ❌ | N/A (requires deftype) |

#### Integration with Watcher

```clojure
;; In push-atom! - choose strategy based on value size or config
(defn push-atom! [key]
  (when-let [{:keys [atom seq last-value]} (get @!synced-atoms key)]
    (let [new-seq (inc seq)
          new-value @atom
          ;; Choose sync strategy
          ops (if (should-use-delta? key last-value new-value)
                (deep-diff->ops key last-value new-value)
                [[:sync/op {:key key :op :assoc-in :path [] :value new-value}]])]
      ;; Update registry
      (swap! !synced-atoms update key assoc :seq new-seq :last-value new-value)
      ;; Send ops (with seq for each)
      (doseq [op ops]
        (broadcast! (update-in op [1] assoc :seq new-seq))))))

(defn should-use-delta?
  "Heuristic: use delta for large maps, full sync for small."
  [key old-val new-val]
  (and old-val
       (map? old-val)
       (map? new-val)
       (> (count old-val) 10)))  ; tune threshold
```

#### Recommendation

| Phase | Strategy | Rationale |
|-------|----------|-----------|
| **Phase 1** | Full sync (`path []`) | Simple, reliable, sufficient for small-medium atoms |
| **Phase 1+** | Shallow diff | Easy optimization for flat state maps |
| **Phase 2** | Deep diff | For large nested structures if needed |
| **Future** | Batched ops | Reduce message overhead |

---

## Open Questions

### Resolved

1. ~~**Sync reliability:**~~ → Sequence numbers with gap detection (see §5)
2. ~~**Metadata storage:**~~ → Seq stored in registry, not atom value (keeps interface clean)
3. ~~**Editscript compatibility:**~~ → NOT compatible with bb/Scittle (uses deftype)
4. ~~**Schism CRDT:**~~ → NOT compatible with bb/Scittle (uses deftype)
5. ~~**Existing diff libraries:**~~ → `differ` fork works, but our `deep-diff->ops` is simpler for maps-only use case

### Open

1. **Serialization:** EDN should be sufficient. Do we need transit for performance?

2. **Large values:** Should we add size limits or compression for large atoms?

3. **Error handling:** What if push fails (browser disconnected mid-send)?
   - Current: sente-lite handles reconnection, browser requests resync on gap

4. **Hash verification:** When to add content hash for integrity checking?
   - Option A: Every N updates include hash
   - Option B: Only on full resync
   - Option C: On-demand via debug command

5. **Seq overflow:** Long-running servers could overflow. Use BigInt or reset on reconnect?
   - Likely not an issue in practice (2^53 messages is a lot)

---

## References

- Original design: `docs/design/bb-scittle-code-browser-design.md` (Bidirectional Atom Sync section)
- Implementation plan: `IMPLEMENTATION_PLAN.md` (Phase 1.4)
- sente-browser module: `modules/sente-browser/`

---

*Last Updated: 2026-01-11 (differ research, vector analysis, implementation options)*
