# Statecharts Reference

> Formalized state machines for lifecycle management.
> Based on [clj-statecharts](https://github.com/lucywang000/clj-statecharts) v0.1.7, BB/Scittle-compatible fork at `../clj-statecharts-bb-scittle`.

---

## Why Statecharts

Our codebase has many implicit state machines — atoms with `:status` fields, `case` statements on state values, guards checking `running?` before transitions. They work, but:

- **States and transitions aren't declared** — you must read the whole file to understand the lifecycle
- **Invalid transitions are possible** — nothing prevents `stop-server!` when already `:stopped`
- **Testing requires mocking I/O** — business logic is tangled with side effects
- **Composition is fragile** — when multiple state machines interact, bugs hide in the gaps

Statecharts formalize this: declare states and transitions as data, get validation and testability for free.

---

## The Library

clj-statecharts (~1,650 LOC) implements the SCXML/XState statecharts spec in idiomatic Clojure:

| Feature | Supported |
|---------|-----------|
| Flat states | Yes |
| Hierarchical (nested) states | Yes |
| Parallel (orthogonal) regions | Yes |
| Guards (conditional transitions) | Yes |
| Entry/exit actions | Yes |
| Transition actions | Yes |
| Context (extended state) | Yes — `assign` |
| Delayed transitions (timeouts) | CLJS/Scittle only (CLJ `setTimeout` not implemented) |
| Eventless transitions (`:always`) | Yes |
| Pure transition function | Yes — `{:exec false}` |
| Service abstraction (atom-backed) | Yes |
| XState visualizer compatibility | Structural similarity (not export) |

### BB/Scittle Fork

The fork at `../clj-statecharts-bb-scittle` made two changes from upstream:

1. **Removed Malli dependency** — replaced with hand-written `normalize-machine` functions (same transforms, zero deps)
2. **Fixed `(.-v retval)` to `(:v retval)`** — Scittle's SCI returns `nil` for `.-field` on defrecords

Everything else worked as-is, including `deftype` with `^:volatile-mutable` (confirmed in Scittle 0.7.30).

**Test results:** 36 JVM/SCI tests (155 assertions) + 37 Scittle browser tests passing.

---

## Core Concepts

### Machine = Data (Immutable Blueprint)

```clojure
(require '[statecharts.core :as fsm :refer [assign]])

(def server-lifecycle
  (fsm/machine
    {:id      :nrepl-server
     :initial :stopped
     :context {:port nil :error nil}
     :states
     {:stopped  {:on {:start :starting}}
      :starting {:on {:started {:target  :running
                                :actions (assign (fn [ctx event]
                                                   (assoc ctx :port (:port event))))}
                      :failed  {:target  :error
                                :actions (assign (fn [ctx event]
                                                   (assoc ctx :error (:msg event))))}}}
      :running  {:on {:stop :stopping}}
      :stopping {:on {:stopped :stopped
                      :failed  :error}}
      :error    {:on {:reset :stopped}}}}))
```

### State = Plain Map

```clojure
{:_state :running, :port 7888, :error nil}
```

No opaque objects. Serializable. Printable. Storable.

### Transition = Pure Function

```clojure
;; Pure — no side effects, returns new state + collected actions
(fsm/transition server-lifecycle
                {:_state :stopped :port nil :error nil}
                :start
                {:exec false})
;; => {:_state :starting, :port nil, :error nil, :_actions [...]}

;; With execution — runs entry/exit/transition actions
(fsm/transition server-lifecycle state :start)
;; => {:_state :starting, :port nil, :error nil}
```

### Guards = Conditional Transitions

```clojure
{:on {:connect [{:target :authenticated
                 :guard  (fn [ctx _] (:has-token? ctx))}
                {:target :anonymous}]}}
```

First matching guard wins. Last transition (no guard) is the fallback.

### Assign = Context Updates

```clojure
;; assign wraps a function so the engine knows to use its return value
(assign (fn [ctx event]
          (assoc ctx :port (:port event))))
```

Without `assign`, action return values are ignored (for side-effect-only actions).

### Nested States

```clojure
{:states
 {:connected {:initial :idle
              :on {:disconnect :disconnected}  ;; handled by any child state
              :states
              {:idle    {:on {:request :loading}}
               :loading {:on {:response :idle
                              :error    :error}}
               :error   {:on {:retry :loading}}}}
  :disconnected {}}}
```

Events bubble up: if `:loading` doesn't handle `:disconnect`, the parent `:connected` does.

### Parallel States

```clojure
{:type    :parallel
 :regions
 {:connection {:initial :disconnected
               :states  {:disconnected {} :connected {}}}
  :ui         {:initial :collapsed
               :states  {:collapsed {} :expanded {}}}}}

;; State: {:connection :disconnected, :ui :collapsed}
```

---

## Testing

The killer feature: `{:exec false}` makes transitions pure.

```clojure
(deftest server-lifecycle-test
  (let [machine server-lifecycle
        init (fsm/initialize machine {:exec false})]

    (testing "starts in :stopped"
      (is (= :stopped (:_state init))))

    (testing "start transitions to :starting"
      (let [s (fsm/transition machine init :start {:exec false})]
        (is (= :starting (:_state s)))))

    (testing "cannot stop when stopped (unknown event)"
      (is (thrown? Exception
            (fsm/transition machine init :stop))))

    (testing "started event carries port in context"
      (let [starting (fsm/transition machine init :start {:exec false})
            running (fsm/transition machine starting
                      {:type :started :port 7888}
                      {:exec false})]
        (is (= :running (:_state running)))
        (is (= 7888 (:port running)))))))
```

No atoms, no server, no mocking. Just data.

---

## Integration Approach for bb-mcp-server

### Pattern: State Machine + Effects (Nexus-compatible)

Combine statecharts (WHAT transitions are valid) with the Nexus pattern (WHAT effects each transition produces):

```clojure
;; 1. Define the machine (pure data)
(def machine (fsm/machine {...}))

;; 2. Define effect handlers per transition (impure)
(defmulti execute-effect first)
(defmethod execute-effect :server/start [_ ctx]
  (nrepl-server/start-server! (:config ctx)))

;; 3. Transition + execute pattern
(defn handle-event! [state-atom machine event]
  (let [old-state @state-atom
        new-state (fsm/transition machine old-state event)]
    (reset! state-atom new-state)
    new-state))
```

### For Scittle Browser

The fork provides `dist/statecharts-bundle.cljc` — serve via a route in `bootstrap.clj`:

```clojure
;; In bootstrap.clj routes
["/cljc/statecharts-bundle.cljc"
 {:get (fn [_] {:status 200
                :headers {"Content-Type" "application/x-scittle"}
                :body (slurp "path/to/statecharts-bundle.cljc")})}]
```

Then in browser code:
```clojure
(ns my-widget (:require [statecharts.core :as fsm]))
```

---

## Candidate State Machines in Our Codebase

Ranked by suitability as first implementation target:

| Rank | Candidate | States | File | Why |
|------|-----------|--------|------|-----|
| **1** | **Local nREPL Server** | 5 (stopped/starting/running/stopping/error) | `mcp_nrepl/state/local_nrepl_server.clj` | Already has explicit named states, single atom, single file, clean guards |
| 2 | Datalevin Pod | 3 (unloaded/loaded/connected) | `datalevin_pod/core.clj` | Very simple, 2 atoms, single file |
| 3 | Module System | 4 (stopped/starting/running/stopping) | `module/system.clj` | Clean state atom, but widespread side effects |
| 4 | Browser Connection | 3 (pending-validation/validated/disconnected) | `sente_browser/server.clj` | Per-connection template |
| 5 | Widget Lifecycle | 5 (created/loading/loaded/error/closed) | `code_browser_v2.cljs` | Browser-side, boolean flags instead of status keyword |
| 6 | Claude Manager | 7 (spawned/init/idle/waiting/accumulating/completed/dead) | `claude_manager/*.clj` | Most complex, 3 files, most benefit from formalization |

### Why Local nREPL Server is the Best First Target

The existing code already has:
- Explicit status values: `:stopped`, `:starting`, `:running`, `:stopping`, `:error`
- Guard checks: `(when (running?) (throw ...))`, `(when-not (running?) (throw ...))`
- Clean transition functions: `start-server!`, `stop-server!`, `restart-server!`
- A `reset-state!` function for testing
- A single atom in a single file

The statechart would be a near 1:1 mapping. Here's what it would look like:

```clojure
(def nrepl-server-machine
  (fsm/machine
    {:id      :nrepl-server
     :initial :stopped
     :context {:server-map nil :host "localhost" :port nil
               :connection nil :started-at nil :stopped-at nil
               :config {} :error nil}
     :states
     {:stopped  {:on {:start {:target  :starting
                              :actions (assign (fn [ctx event]
                                                (assoc ctx :config (:config event)
                                                       :error nil)))}}}
      :starting {:on {:started {:target  :running
                                :actions (assign (fn [ctx event]
                                                  (merge ctx (select-keys event
                                                    [:server-map :host :port
                                                     :connection :started-at]))))}
                      :failed  {:target  :error
                                :actions (assign (fn [ctx event]
                                                  (assoc ctx :error (:error event))))}}}
      :running  {:on {:stop :stopping}}
      :stopping {:on {:stopped {:target  :stopped
                                :actions (assign (fn [ctx event]
                                                  (assoc ctx :server-map nil
                                                         :stopped-at (:stopped-at event)
                                                         :error nil)))}
                      :failed  {:target  :error
                                :actions (assign (fn [ctx event]
                                                  (assoc ctx :error (:error event))))}}}
      :error    {:on {:start {:target  :starting
                              :actions (assign (fn [ctx event]
                                                (assoc ctx :config (:config event)
                                                       :error nil)))}
                      :reset :stopped}}}}))
```

---

## When NOT to Use Statecharts

- **Simple CRUD** — if it's just `(swap! atom assoc k v)`, don't wrap it
- **Linear sequences** — if there's only one path (A → B → C), a statechart adds ceremony without benefit
- **Existing working code** — don't retrofit unless pain demands it (same advice as Nexus)
- **Performance-critical hot paths** — the transition function has overhead (configuration computation, exit/entry sets)

---

## Reference

- **Fork source:** `../clj-statecharts-bb-scittle/src/statecharts/` (9 files, ~1,650 LOC)
- **Core engine:** `impl.cljc` (854 lines — machine creation + transition algorithm)
- **Scittle bundle:** `../clj-statecharts-bb-scittle/dist/statecharts-bundle.cljc`
- **SCI test script:** `../clj-statecharts-bb-scittle/scripts/test-sci.bb`
- **Browser tests:** `../clj-statecharts-bb-scittle/test-scittle/scittle-tests.cljs`
- **Upstream docs:** `../clj-statecharts-bb-scittle/docs/content/docs/`
- **Scittle compat notes:** `../clj-statecharts-bb-scittle/docs/scittle-compatibility.md`
- **XState visualizer:** https://stately.ai/viz (structural similarity, not direct export)

---

*Added: 2026-02-08 — Reviewed as formalized state machine approach for bb-mcp-server lifecycles.*
