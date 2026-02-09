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

| Rank | Candidate | States | File | Status |
|------|-----------|--------|------|--------|
| **1** | **Local nREPL Server** | 5 (stopped/starting/running/stopping/error) | `mcp_nrepl/state/local_nrepl_server.clj` | **Done** (v1.21.0) — validated: 0 errors, 0 warnings, 0 conventions |
| 2 | Datalevin Pod | 3 (unloaded/loaded/connected) | `datalevin_pod/core.clj` | Pending — very simple, 2 atoms, single file |
| 3 | Module System | 4 (stopped/starting/running/stopping) | `module/system.clj` | Pending — clean state atom, widespread side effects |
| 4 | Browser Connection | 3 (pending-validation/validated/disconnected) | `sente_browser/server.clj` | Pending — per-connection template |
| 5 | Widget Lifecycle | 5 (created/loading/loaded/error/closed) | `code_browser_v2.cljs` | Pending — browser-side, boolean flags |
| 6 | Claude Manager | 7 (spawned/init/idle/waiting/accumulating/completed/dead) | `claude_manager/*.clj` | Pending — most complex, most benefit |

### Local nREPL Server — First Integration (v1.21.0)

Implemented in `mcp_nrepl/state/local_nrepl_server.clj`. Key patterns:

- **Named assign functions** — `assign-config`, `assign-server-info`, `assign-error`, `assign-stopped` — instead of inline anonymous fns, for navigability and debugging
- **Pure transition tests** — 70 tests with `{:exec false}`, no I/O, no mocking
- **Effect separation** — `start-server!`/`stop-server!` do transitions + effects in sequence: transition → try effect → transition (success or failure)
- **Static validation** — `bb statechart:validate` confirms 5 states, 8 edges, 0 issues

```clojure
;; Named assign action (preferred convention)
(defn assign-config [ctx event]
  (assoc ctx :config (:config event) :error nil))

;; Machine definition with named vars
(def nrepl-server-machine
  (fsm/machine
    {:id      :nrepl-server
     :initial :stopped
     :context {:server-map nil :host "localhost" :port nil ...}
     :states
     {:stopped  {:on {:start {:target  :starting
                              :actions [(fsm/assign assign-config)]}}}
      :starting {:on {:started {:target  :running
                                :actions [(fsm/assign assign-server-info)]}
                      :failed  {:target  :error
                                :actions [(fsm/assign assign-error)]}}}
      :running  {:on {:stop :stopping}}
      :stopping {:on {:stopped {:target  :stopped
                                :actions [(fsm/assign assign-stopped)]}
                      :failed  {:target  :error
                                :actions [(fsm/assign assign-error)]}}}
      :error    {:on {:start {:target  :starting
                              :actions [(fsm/assign assign-config)]}
                      :reset :stopped}}}}))
```

---

## Static Analyzer ("statechart-kondo")

`src/statecharts/validate.cljc` — a reusable static analyzer for normalized machines. Pure functions, `.cljc` for BB + Scittle.

### Usage

```bash
# CLI validation with graph output
bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-machine

# Run analyzer tests
bb test:statecharts    # 19 tests, 69 assertions
```

### Structural Checks

These catch graph-level bugs that `fsm/machine` doesn't validate:

| Check | Severity | Description |
|-------|----------|-------------|
| Unreachable states | **Error** | States that cannot be reached from the initial state via any transition path |
| Dead-end states | Warning | States with no outgoing transitions (`:on`, `:always`, `:after`) — may be intentional final states |
| Non-deterministic events | Warning | Same event has multiple transitions without guards (first-match wins, but likely a bug) |
| Orphan states | Warning | States with no incoming transitions except the initial state |
| Self-transition-only | Info | States whose only transitions point back to themselves |

### Convention Checks

These enforce our project's statechart conventions:

| Check | Description | Fix |
|-------|-------------|-----|
| Missing `:id` | Machine has no `:id` keyword | Add `:id :my-machine` — needed for CLI, logging, browser viz |
| Missing `:context` | Machine has no `:context` map | Add `:context {}` — needed for debugging and state inspection |
| Error without recovery | State with "error" in name has no outgoing transitions | Add `:start`, `:reset`, or `:retry` transition from error state |
| No return to initial | State has no path back to the initial state | Ensure every reachable state can eventually cycle back to initial |

### Conventions Explained

**Every machine should have `:id`** — The CLI tool (`bb statechart:validate`) prints it in output. The browser viz will use it for titles. Logging references it. Without it, the machine is anonymous.

**Every machine should have `:context`** — Even if empty `{}`. The context map is the extended state — port numbers, error messages, timestamps. Tools like `get-full-state` dump it for debugging. Without `:context`, you lose inspectability.

**Error states need recovery paths** — A state named `:error` or `:connection-error` with no outgoing transitions is a black hole. The system enters it and never leaves. Always provide at least one escape: `:reset` (back to initial), `:retry` (try again), or `:start` (fresh attempt).

**Return path to initial** — If state `:c` can never cycle back to the initial state `:a` (even indirectly), the machine can only move forward. This is fine for finite workflows (`:pending` → `:approved` → `:archived`) but a red flag for lifecycle machines that should be restartable.

### Programmatic API

```clojure
(require '[statecharts.validate :as v])

;; Full validation (structural + conventions)
(v/validate my-machine)
;; => {:errors [...] :warnings [...] :info [...] :conventions [...] :graph {...} :summary {...}}

;; Individual checks
(v/find-unreachable my-machine)   ;; => seq of issue maps
(v/find-dead-ends my-machine)
(v/find-non-deterministic my-machine)
(v/find-orphans my-machine)
(v/find-self-only my-machine)

;; Convention checks
(v/check-has-id my-machine)
(v/check-has-context my-machine)
(v/check-error-recovery my-machine)
(v/check-initial-return-path my-machine)
(v/check-conventions my-machine)  ;; all conventions

;; Graph extraction (for visualization)
(v/machine->graph my-machine)
;; => {:states #{:stopped :starting ...} :edges [...] :initial :stopped :id :nrepl-server}
```

### Integration with Tests

Every file that defines a statechart should include a validation test:

```clojure
(require '[statecharts.validate :as validate])

(deftest machine-validation-test
  (testing "my-machine passes static validation"
    (let [result (validate/validate my-machine)]
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))
      (is (empty? (:conventions result))))))
```

### CLI Output Example

```
Validating: mcp-nrepl.state.local-nrepl-server/nrepl-server-machine
Machine: :nrepl-server (5 states, 8 edges)

Graph:
  :error       --:reset      --> :stopped
  :error       --:start      --> :starting
  :running     --:stop       --> :stopping
  :starting    --:failed     --> :error
  :starting    --:started    --> :running
  :stopped     --:start      --> :starting
  :stopping    --:failed     --> :error
  :stopping    --:stopped    --> :stopped

Validation: PASS (0 errors, 0 warnings, 0 conventions)
```

### Browser Integration (Future)

The analyzer is `.cljc`, so it already works in Scittle. Integration path:

1. Serve `validate.cljc` via existing `/cljc/` route in bootstrap.clj
2. Add `:statechart-validation` widget type to Code Browser v2
3. When browsing a symbol that contains `fsm/machine`, offer a "Validate" button
4. Run `validate` in-browser on the machine data, display results in widget
5. Use `machine->mermaid` from `viz.cljc` to render a state diagram (Mermaid.js)

---

## When NOT to Use Statecharts

- **Simple CRUD** — if it's just `(swap! atom assoc k v)`, don't wrap it
- **Linear sequences** — if there's only one path (A → B → C), a statechart adds ceremony without benefit
- **Existing working code** — don't retrofit unless pain demands it (same advice as Nexus)
- **Performance-critical hot paths** — the transition function has overhead (configuration computation, exit/entry sets)

---

## Reference

- **Static analyzer:** `src/statecharts/validate.cljc` (.cljc, BB + Scittle)
- **Analyzer tests:** `test/statecharts/validate_test.clj` (19 tests, 69 assertions)
- **CLI script:** `scripts/statechart_validate.clj` (colored output, graph printing)
- **First integration:** `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj`
- **Fork source:** `../clj-statecharts-bb-scittle/src/statecharts/` (9 files, ~1,650 LOC)
- **Core engine:** `impl.cljc` (854 lines — machine creation + transition algorithm)
- **Scittle bundle:** `../clj-statecharts-bb-scittle/dist/statecharts-bundle.cljc`
- **Upstream docs:** `../clj-statecharts-bb-scittle/docs/content/docs/`
- **XState visualizer:** https://stately.ai/viz (structural similarity, not direct export)

---

*Added: 2026-02-08 — Reviewed as formalized state machine approach for bb-mcp-server lifecycles.*
*Updated: 2026-02-08 — Added static analyzer ("statechart-kondo") with convention checks.*
