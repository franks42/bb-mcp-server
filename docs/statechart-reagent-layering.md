# Statechart + Reagent Layering Pattern

> Using clj-statecharts as a write gate for Reagent atoms.
> Applicable to any Reagent/re-frame UI with complex state lifecycles.

---

## The Problem

Reagent gives you reactive rendering but is completely silent about **which state transitions are valid**. An `r/atom` can be swapped to anything at any time. There's no schema, no transition rules, no way to ask "what can happen from here?"

This leads to:
- Bugs where state combinations that "shouldn't happen" silently do (stale data, impossible UI states)
- Mutation logic scattered across many functions with no single source of truth
- No way to test state behavior without running the full UI
- Debugging requires tracing through code to reconstruct the sequence of swaps

Statecharts solve the state definition problem. Reagent solves the rendering problem. They compose cleanly.

---

## The 4-Layer Architecture

| Layer | Responsibility | Tool | Changes? |
|-------|---------------|------|----------|
| **State definition** | What states exist, what transitions are valid | Statechart machine | New |
| **State mutation** | Apply transitions, update context | `fsm/transition` inside `swap!` | New |
| **State storage** | Hold the current value, trigger reactivity | `r/atom` | Unchanged |
| **State rendering** | Turn state into DOM | Reagent components | Minimal |

Reagent handles the bottom two layers. Statecharts handle the top two. The boundary is the atom — the statechart writes it, Reagent reads it.

---

## Core Pattern: Statechart as Write Gate

The statechart becomes the **only writer** to the atom. Raw `swap!` calls that change state/status are replaced by transition calls that enforce valid moves.

### Before (scattered mutation helpers)

```clojure
(defn- set-widget-loading! [widget-id]
  (swap! !widgets update widget-id
         (fn [w]
           (if (:data w)
             (assoc w :status :refreshing)
             (assoc w :status :loading)))))

(defn- set-widget-ready! [widget-id data]
  (swap! !widgets update widget-id
         assoc :data data :status :ready :error nil))

(defn- set-widget-error! [widget-id error-msg]
  (swap! !widgets update widget-id
         assoc :status :error :error error-msg))

;; 5 mutation helpers, each doing ad-hoc assoc on status
;; Nothing prevents calling set-widget-ready! on a :closed widget
```

### After (single write gate)

```clojure
(def widget-lifecycle-statechart-compiled
  (fsm/machine widget-lifecycle-statechart))

(defn transition-widget! [widget-id event]
  (swap! !widgets update widget-id
         (fn [w]
           (when w
             (fsm/transition widget-lifecycle-statechart-compiled w event)))))

;; One function replaces 5. Invalid transitions throw.
;; All context updates happen in assign actions (pure fns).
```

**Callers change minimally:**

```clojure
;; Before
(set-widget-loading! wid)

;; After — the machine decides loading vs refreshing based on context
(transition-widget! wid {:type :fetch-start})

;; Before
(set-widget-ready! wid data)

;; After
(transition-widget! wid {:type :fetch-success :data data :version-hash new-hash})
```

---

## Multi-Instance Pattern

When multiple independent instances share a single atom (e.g., N widgets in one `!widgets` map), each instance is a separate state machine instance stored as a value in the map.

```clojure
;; One compiled machine, shared by all instances
(def widget-lifecycle-statechart-compiled
  (fsm/machine widget-lifecycle-statechart))

;; Per-instance initialization
(defn init-widget [widget-id widget-type uri]
  (-> (fsm/initialize widget-lifecycle-statechart-compiled {:exec false})
      (assoc :widget-id widget-id
             :widget-type widget-type
             :uri uri)))

;; Per-instance transition (the write gate)
(defn transition-widget! [widget-id event]
  (swap! !widgets update widget-id
         (fn [w]
           (when w
             (fsm/transition widget-lifecycle-statechart-compiled w event)))))

;; Open: add initialized instance to atom
(defn open-widget! [widget-id widget-type uri]
  (swap! !widgets assoc widget-id
         (init-widget widget-id widget-type uri))
  (transition-widget! widget-id {:type :fetch-start}))

;; Close: transition to terminal, then remove
(defn close-widget! [widget-id]
  (transition-widget! widget-id {:type :close})
  (swap! !widgets dissoc widget-id))
```

The atom holds `{:w0 {state-map} :w1 {state-map} ...}` where each state-map has `:_state` (the current state keyword) plus context fields. Reagent cursors and subscriptions work unchanged.

---

## Rendering Layer

Views read `:_state` instead of a manual `:status` field. Everything else stays the same.

```clojure
(defn widget-component [widget-id]
  (let [w (get @!widgets widget-id)]
    (case (:_state w)
      :loading    [loading-spinner]
      :ready      [render-data (:data w)]
      :refreshing [render-data (:data w) {:stale? true}]
      :error      [error-display (:error w) {:on-retry #(transition-widget! widget-id {:type :retry})}]
      :closed     nil)))
```

Event handlers in the view call `transition-widget!` directly:

```clojure
;; Button that's only enabled when the transition is valid
(defn refresh-button [widget-id]
  (let [w (get @!widgets widget-id)
        can-refresh? (= :ready (:_state w))]
    [:button {:disabled (not can-refresh?)
              :on-click #(transition-widget! widget-id {:type :invalidate :new-version-hash (current-hash)})}
     "Refresh"]))
```

---

## Assign Actions (Pure Context Updaters)

Each assign action is a named, public function with a docstring. They replace the ad-hoc `assoc` calls that were inside mutation helpers.

```clojure
(defn assign-data
  "Store fetched data and version-hash, clear error."
  [ctx event]
  (assoc ctx :data (:data event) :error nil
         :version-hash (:version-hash event)))

(defn assign-error
  "Store error message. Does NOT clear :data (preserves stale data)."
  [ctx event]
  (assoc ctx :error (:error event)))

(defn assign-invalidate
  "Update version-hash when project data changes."
  [ctx event]
  (assoc ctx :version-hash (:new-version-hash event)))
```

These are independently testable without any UI or atom:

```clojure
(deftest assign-error-preserves-data-test
  (let [ctx {:data [{:name "stale"}] :error nil}
        result (assign-error ctx {:error "server down"})]
    (is (= "server down" (:error result)))
    (is (= [{:name "stale"}] (:data result)))))
```

---

## Two-Phase Adoption

### Phase 1: Documentation Statechart (no code changes)

Create the machine definition and tests in a `.cljc` file. Run against the static validator. Use pure transition tests to find bugs. The machine documents the lifecycle without touching runtime code.

**Files:**
- `src/statecharts/machines/widget_lifecycle.cljc` — machine config + compiled machine
- `test/statecharts/machines/widget_lifecycle_test.clj` — pure transition tests

**Value:** Bug discovery, living documentation, test specifications.

### Phase 2: Write Gate Integration (targeted refactoring)

Wire the machine as the write gate. Replace mutation helpers with `transition-widget!`. Update view reads from `:status` to `:_state`.

**Changes:**
- Replace 5 mutation helpers with 1 `transition-widget!` function
- Update callers to pass event maps instead of calling specific helpers
- Change `:status` reads to `:_state` in view components
- Pure transition tests from Phase 1 become regression tests

**Value:** Invalid transitions become impossible. Every state change has a name. Context updates are centralized in assign actions.

Phase 1 is the hard part (designing the machine). Phase 2 is mechanical.

---

## What This Pattern Is NOT

- **Not a replacement for Reagent** — Reagent's reactivity model stays unchanged
- **Not re-frame** — no subscription layer, no event queue, no middleware chain. Just a write discipline on `swap!`
- **Not for every atom** — simple state (filter text, toggle flags, cursor position) doesn't need this. Apply only where lifecycle complexity exists
- **Not an all-or-nothing refactoring** — adopt per-component as pain demands it

---

## When to Use This Pattern

**Good candidates:**
- Components with 3+ named states and transitions between them
- State spread across multiple mutation functions
- Bugs caused by "impossible" state combinations that happen anyway
- Async operations (fetch, save, connect) with loading/success/error cycles
- Multi-instance lifecycles (widget managers, connection pools, form wizards)

**Skip it for:**
- Simple boolean toggles (`expanded?`, `visible?`)
- One-shot operations with no lifecycle
- State that's purely derived from other state (use Reagent reactions instead)

---

## Relationship to Other Patterns

| Pattern | Scope | Pairs with statecharts? |
|---------|-------|------------------------|
| **Nexus (action-dispatch)** | Separates WHAT from HOW | Yes — statecharts declare valid transitions, Nexus handlers declare effects |
| **re-frame** | Full event loop with subscriptions | Yes — statechart can live inside re-frame event handlers |
| **Reagent raw atoms** | Direct reactive state | Yes — this document describes exactly this pairing |
| **Component/Mount** | System lifecycle | Separate concern — statecharts for runtime state, not startup wiring |

---

## Reference Implementation

- **Machine definition:** `src/statecharts/machines/widget_lifecycle.cljc`
- **Pure transition tests:** `test/statecharts/machines/widget_lifecycle_test.clj`
- **Runtime integration (server-side example):** `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj`
- **Statechart library:** `../clj-statecharts-bb-scittle` (BB + Scittle compatible fork)
- **Static validator:** `src/statecharts/validate.cljc`
- **Naming conventions:** `docs/statecharts-naming-coding-conventions.md`
