# Atom Sync Design

**Status:** Implemented (Phase 1 Complete)
**Created:** 2026-01-11
**Updated:** 2026-01-12
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
  (atom {}))  ; {:key {:atom atom-ref :seq n :last-value v}}

;; On atom change, generate ops and assign incrementing seq to each
;; IMPORTANT: Each op needs unique seq (not one per swap!)
;; Otherwise browser rejects subsequent ops in multi-key swaps as "stale"
(defn on-atom-change [key _ref old-val new-val]
  (when (not= old-val new-val)
    (let [base-ops (deep-diff->ops key old-val new-val)
          start-seq (get-in @!synced-atoms [key :seq] 0)
          ;; Each op gets its own seq: start+1, start+2, etc.
          ops (map-indexed
               (fn [idx [op-type op-data]]
                 [op-type (assoc op-data :seq (+ start-seq idx 1))])
               base-ops)
          final-seq (+ start-seq (count base-ops))]
      (swap! !synced-atoms update key assoc :seq final-seq :last-value new-val)
      (notify-subscribers! ops))))
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

## Browser-Side Implementation

In `modules/sente-browser/src/sente_browser/bootstrap.clj` (embedded in HTML):

```clojure
;; Registry of synced Reagent atoms: {:key (r/atom value)}
(defonce !synced-atoms (atom {}))

;; Sync state tracking: {:key {:seq n}} for gap detection
(defonce !sync-state (atom {}))

;; Client ID for sending messages (set by init!)
(defonce !client-id (atom nil))

(defn get-synced-atom
  "Get or create a synced Reagent atom by key."
  [key]
  (or (get @!synced-atoms key)
      (let [a (r/atom nil)]
        (swap! !synced-atoms assoc key a)
        a)))

(defn request-resync!
  "Request full resync from server for an atom.
   Called when gap detected in seq numbers."
  [key]
  (log/log! {:level :warn :id ::resync-requested
             :msg "Requesting resync from server" :data {:key key}})
  (when @!client-id
    (client/send! @!client-id [:sync/resync-request {:key key}])))

(defn apply-sync-op
  "Apply a sync operation with seq validation.
   Returns :applied, :stale, or :gap."
  [{:keys [key seq op path value]}]
  (let [current-seq (get-in @!sync-state [key :seq] 0)
        expected-seq (inc current-seq)]
    (cond
      ;; Normal case: sequential update
      (= seq expected-seq)
      (do
        (when-let [a (get-synced-atom key)]
          (if (= path [])
            (reset! a value)
            (swap! a assoc-in path value)))
        (swap! !sync-state assoc-in [key :seq] seq)
        :applied)

      ;; First sync (seq 1, no prior state)
      (and (= seq 1) (= current-seq 0))
      (do
        (when-let [a (get-synced-atom key)]
          (if (= path []) (reset! a value) (swap! a assoc-in path value)))
        (swap! !sync-state assoc-in [key :seq] seq)
        :applied)

      ;; Gap detected: missed updates
      (> seq expected-seq)
      (do
        (request-resync! key)
        :gap)

      ;; Stale/duplicate: ignore
      :else :stale)))

(defn handle-resync-response
  "Handle resync response from server."
  [{:keys [key ops error]}]
  (if error
    (log/log! {:level :error :id ::resync-error :data {:key key :error error}})
    (do
      (swap! !sync-state assoc-in [key :seq] 0)
      (doseq [op ops]
        (let [[_ op-data] op]
          (apply-sync-op op-data))))))

(defn get-sync-status
  "Get current sync status for debugging."
  []
  {:atoms (keys @!synced-atoms)
   :state @!sync-state})
```

Handler wired up in `on-message`:
```clojure
:sync/op
(apply-sync-op data)
:sync/resync-response
(handle-resync-response data)
```

**Status:** Fully implemented with seq validation and gap detection.

---

## Server-Side Implementation

### Core Module (`atom-sync.core`)

Transport-independent sync logic:

```clojure
;; Register an atom for sync
(register-synced-atom! :my-state !my-atom)

;; Subscribe to receive ops (for transport layer)
(subscribe! :my-transport
  (fn [ops]
    (broadcast! ops)))

;; Generate ops for new clients
(generate-all-full-sync-ops)

;; Handle heartbeat from client
(handle-heartbeat :my-state client-seq)
```

### Transport Layer (`atom-sync.server`)

Thin wrapper wiring core to sente-lite:

```clojure
;; Initialize with transport functions
(init! broadcast-to-browsers-fn send-to-browser-fn)

;; Called when browser connects
(on-browser-connected! sente-conn-id)

;; Handle browser events
(dispatch-event :sync/resync-request {:key k})
(dispatch-event :sync/heartbeat {:key k :seq n})
```

**Status:** Fully implemented. Wired into sente-browser.server.

---

## Usage

### Basic Server-Side Usage

```clojure
(require '[atom-sync.core :as sync])

;; Register an atom for sync
(def !state (atom {:count 0 :message "Hello"}))
(sync/register-synced-atom! :my-state !state)

;; Any changes to the atom are automatically synced to browsers
(swap! !state assoc :count 1)  ; → Browser receives update

;; Unregister when done
(sync/unregister-synced-atom! :my-state)
```

### Basic Browser-Side Usage

```clojure
;; In Scittle (browser)

;; Get synced atom (auto-created as Reagent atom)
(def state (get-synced-atom :my-state))

;; Use in Reagent component
(defn my-component []
  [:div
   [:p "Count: " (:count @state)]
   [:p "Message: " (:message @state)]])

;; Check sync status (debugging)
(get-sync-status)
;; => {:atoms [:my-state] :state {:my-state {:seq 5}}}
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

## Implementation Status

### Phase 1: One-Way Sync ✓ COMPLETE

| Phase | Status | Description |
|-------|--------|-------------|
| **1.4A: Core** | ✓ Complete | `deep-diff->ops`, `apply-sync-op`, registry, subscribers, heartbeat |
| **1.4B: Server** | ✓ Complete | Transport wrapper, `init!`, `on-browser-connected!`, `dispatch-event` |
| **1.4C: Browser** | ✓ Complete | Seq validation, gap detection, resync, trove logging |
| **1.4D: Integration** | ✓ Complete | Wired into sente-browser.server |

**Test Coverage:** 29 tests, 130 assertions (all passing)

### Phase 2: Bidirectional (Future)

| Task | Description |
|------|-------------|
| 2.1 | Server handler for `:sync/atom-update` from browser |
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

## Future Enhancements

### Phase 1.6: Live File Watching

**Goal:** When source files change on disk, automatically update synced atoms and push to browsers.

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Live File Watching Architecture                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  File System                                                        │
│      │                                                              │
│      ▼                                                              │
│  ┌─────────────────────┐    workspace/didChangeWatchedFiles        │
│  │ watcher.clj         │──────────────────────────────────────┐    │
│  │ (pod-fswatcher)     │                                      │    │
│  └─────────────────────┘                                      ▼    │
│                                                         ┌──────────┐│
│                                                         │clojure-  ││
│                                                         │lsp       ││
│                                                         │subprocess││
│                                                         └────┬─────┘│
│                                                              │      │
│                              textDocument/publishDiagnostics │      │
│                                                              ▼      │
│  ┌─────────────────────┐                              ┌───────────┐ │
│  │ handle-notification!│◄─────────────────────────────│reader loop│ │
│  │ (client.clj)        │                              └───────────┘ │
│  └─────────┬───────────┘                                            │
│            │                                                        │
│            ▼                                                        │
│  ┌─────────────────────┐                                            │
│  │ notification        │ NEW: callback registry for file changes    │
│  │ callbacks           │                                            │
│  └─────────┬───────────┘                                            │
│            │                                                        │
│            ▼                                                        │
│  ┌─────────────────────┐                                            │
│  │ code-browser        │ Re-query affected ns/vars/source           │
│  │ on-file-change      │                                            │
│  └─────────┬───────────┘                                            │
│            │                                                        │
│            ▼                                                        │
│  ┌─────────────────────┐                                            │
│  │ !code-browser-state │ swap! with new data                        │
│  │ synced atom         │                                            │
│  └─────────┬───────────┘                                            │
│            │                                                        │
│            ▼                                                        │
│       atom-sync ──────────────────────────────────────► browser     │
│                      auto-push via [:sync/op ...]                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Extension Points:**

```clojure
;; In client.clj - add notification callback registry
(defonce !notification-callbacks (atom {}))

(defn on-notification! [key callback-fn]
  (swap! !notification-callbacks assoc key callback-fn))

(defn- handle-notification!
  [{:keys [method params] :as notification}]
  (case method
    "textDocument/publishDiagnostics"
    (do
      (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
      ;; NEW: Notify all callbacks
      (doseq [[_ cb] @!notification-callbacks]
        (cb notification)))
    nil))

;; In code_browser.clj - subscribe to file changes
(defn enable! []
  (client/on-notification! :code-browser
    (fn [{:keys [method params]}]
      (when (= method "textDocument/publishDiagnostics")
        (let [uri (:uri params)
              affected-ns (uri->namespace uri)]
          ;; Invalidate and refresh affected namespace
          (when (get-in @!code-browser-state [:symbols-by-ns affected-ns])
            (refresh-namespace! affected-ns)))))))
```

**Existing Infrastructure:**
- File watcher: `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/watcher.clj`
- Notification handler: `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/client.clj` (lines 112-123)
- clojure-lsp sends `textDocument/publishDiagnostics` when files change

---

### Phase 1.7: Accumulated State Structure

**Goal:** Retain previously fetched data instead of replacing it. Browser automatically has all browsed content cached via synced atom.

**Current Structure (Replace):**

```clojure
;; Each selection REPLACES the previous data
{:namespaces ["ns.a" "ns.b" ...]
 :selected-ns "ns.a"
 :symbols [...]            ;; Replaced when selecting new ns
 :selected-symbol "foo"
 :source {...}             ;; Replaced when selecting new var
 :loading? false
 :error nil}
```

**Proposed Structure (Accumulate):**

```clojure
;; Data ACCUMULATES as user browses
{:namespaces ["ns.a" "ns.b" ...]
 :selected-ns "ns.a"
 :symbols-by-ns {"ns.a" [{:name "foo" ...} {:name "bar" ...}]
                 "ns.b" [{:name "baz" ...}]}  ;; Accumulated
 :selected-symbol "foo"
 :source-by-var {"ns.a/foo" {:code "..." :file "..." ...}
                 "ns.a/bar" {:code "..." :file "..." ...}
                 "ns.b/baz" {:code "..." :file "..." ...}} ;; Accumulated
 :loading? false
 :error nil}
```

**Benefits:**

1. **Instant back-navigation** - Click ns.a → ns.b → ns.a: no refetch needed
2. **Progressive loading** - Browse around, synced atom grows with cached data
3. **File watcher friendly** - Invalidate specific `[:symbols-by-ns "changed.ns"]`
4. **Natural prefetch path** - Can bulk-load all symbols into same structure
5. **Zero extra transfer** - We already fetch this data, we just stop discarding it

**Server Changes:**

```clojure
;; Before: replace
(swap! !code-browser-state assoc
       :selected-ns ns
       :symbols ns-symbols)

;; After: accumulate
(swap! !code-browser-state
       (fn [state]
         (-> state
             (assoc :selected-ns ns)
             (assoc-in [:symbols-by-ns ns] ns-symbols))))
```

**Browser Changes:**

```clojure
;; Before: read flat
(let [symbols (:symbols @(get-server-state))]
  ...)

;; After: read by selected ns
(let [state @(get-server-state)
      symbols (get-in state [:symbols-by-ns (:selected-ns state)])]
  ...)
```

**atom-sync impact:** None! The module just syncs whatever is in the atom. Accumulated maps sync the same as replaced maps.

**Data size estimate:**
- 39 namespaces × ~10 vars each × ~3KB source = ~1.2MB fully loaded
- Progressive: only ~125KB for typical browsing session (measured)
- Acceptable for localhost development tool

---

## References

- Original design: `docs/design/bb-scittle-code-browser-design.md` (Bidirectional Atom Sync section)
- Implementation plan: `IMPLEMENTATION_PLAN.md` (Phase 1.4)
- sente-browser module: `modules/sente-browser/`
- clojure-lsp file watcher: `modules/clojure-lsp/src/bb_mcp_server/modules/clojure_lsp/watcher.clj`

---

*Last Updated: 2026-01-12 (Added Phase 1.6 File Watching, Phase 1.7 Accumulated State)*
