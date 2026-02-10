# Statecharts Naming & Coding Conventions

> Conventions for integrating clj-statecharts into bb-mcp-server modules.
> Reference implementation: `modules/mcp-nrepl/src/mcp_nrepl/state/local_nrepl_server.clj`

---

## Core Principle: Named Vars Over Anonymous Functions

All actions and guards use **named, public vars** with docstrings. Never use anonymous functions in machine definitions. This enables:

- Code Browser can link action/guard names to their definitions
- Each action is independently testable
- Docstrings provide inline documentation
- `grep` / `find-references` works across the codebase

```clojure
;; GOOD: Named var with docstring
(defn assign-config
  "Store server config and clear error on start."
  [ctx event]
  (assoc ctx :config (:config event) :error nil))

;; BAD: Anonymous function in machine definition
{:actions [(fsm/assign (fn [ctx event] (assoc ctx :config (:config event))))]}
```

---

## Naming Conventions

### Context Assignment Actions: `assign-*`

Functions wrapped with `fsm/assign` that update the state machine context.

| Pattern | Purpose | Example |
|---------|---------|---------|
| `assign-config` | Store configuration data | `(assoc ctx :config (:config event))` |
| `assign-server-info` | Store operational data after success | `(merge ctx (select-keys event [...]))` |
| `assign-error` | Store error information | `(assoc ctx :error (:error event))` |
| `assign-stopped` | Clean up and record stop | `(assoc ctx :server-map nil ...)` |

**Signature:** `(fn [ctx event] -> updated-ctx)`

```clojure
(defn assign-config
  "Store server config and clear error on start."
  [ctx event]
  (assoc ctx :config (:config event) :error nil))
```

**Usage in machine:**
```clojure
{:on {:start {:target  :starting
              :actions [(fsm/assign assign-config)]}}}
```

### Guard Predicates: `guard-*`

Functions that determine whether a transition is allowed.

| Pattern | Purpose | Example |
|---------|---------|---------|
| `guard-can-retry?` | Check retry limit | `(< (:retries ctx 0) 3)` |
| `guard-has-config?` | Validate required data | `(some? (:config event))` |

**Signature:** `(fn [ctx event] -> boolean)`

```clojure
(defn guard-can-retry?
  "Allow retry only if under the retry limit."
  [ctx _event]
  (< (:retries ctx 0) 3))
```

**Usage in machine:**
```clojure
{:on {:retry [{:target :starting
               :guard  guard-can-retry?}
              {:target :failed}]}}
```

### Entry/Exit Actions: `on-enter-*` / `on-exit-*`

Side-effect actions triggered on state entry or exit. Use sparingly.

| Pattern | Purpose | Example |
|---------|---------|---------|
| `on-enter-running` | Log or notify on state entry | Telemetry event |
| `on-exit-running` | Cleanup on state exit | Release resources |

### State Machine Var: `*-statechart` / `*-statechart-compiled`

The statechart config is a top-level `def` with a descriptive docstring including the state diagram. The compiled version is derived from it.

```clojure
(def nrepl-server-statechart
  "Statechart config for local nREPL server lifecycle.

   States: stopped -> starting -> running -> stopping -> stopped
                         |                      |
                       error                  error
   ..."
  (types/map->Statechart {...}))

(def nrepl-server-statechart-compiled
  "Compiled statechart for local nREPL server lifecycle."
  (fsm/machine nrepl-server-statechart))
```

### State Atom: `!state`

The mutable state atom. Prefixed with `!` per Clojure convention for mutable references.

```clojure
(def !state
  "State atom initialized from the nrepl-server-statechart-compiled."
  (atom (fsm/initialize nrepl-server-statechart-compiled {:exec false})))
```

---

## File Organization

### One Machine Per File

Each file contains exactly one state machine. The file name matches the domain concept.

```
local_nrepl_server.clj    -> nrepl-server-statechart / nrepl-server-statechart-compiled
widget_lifecycle.cljc      -> widget-lifecycle-statechart / widget-lifecycle-statechart-compiled
datalevin_pod.clj          -> pod-statechart / pod-statechart-compiled
```

### Section Order

```clojure
(ns ...)

;; 1. Context Assignment Actions
(defn assign-config ...)
(defn assign-server-info ...)
(defn assign-error ...)

;; 2. Guard Predicates (if any)
(defn guard-can-retry? ...)

;; 3. State Machine Definition
(def *-machine (fsm/machine {...}))

;; 4. State Atom
(def !state (atom ...))

;; 5. Utility Functions (port extraction, formatting, etc.)
(defn extract-server-port ...)

;; 6. Transition Helper (private)
(defn- transition! ...)

;; 7. Query Functions (public, read-only)
(defn running? ...)
(defn get-status ...)
(defn get-connection-info ...)

;; 8. Command Functions (public, mutating)
(defn start-server! ...)
(defn stop-server! ...)

;; 9. Debug/Test Support
(defn get-full-state ...)
(defn reset-state! ...)
```

---

## The Transition-Effect-Transition Pattern

Imperative functions follow a three-step pattern:

```clojure
(defn start-server! [config]
  ;; 1. TRANSITION (pure): validate and move to intermediate state
  (transition! {:type :start :config config})
  (try
    ;; 2. EFFECT (impure): perform the actual I/O
    (let [result (nrepl-server/start-server! config)]
      ;; 3a. TRANSITION (pure): move to success state
      (transition! {:type :started :result result})
      result)
    (catch Exception e
      ;; 3b. TRANSITION (pure): move to error state
      (transition! {:type :failed :error (.getMessage e)})
      (throw e))))
```

**Why this pattern:**
1. Invalid transitions throw before I/O happens (fail fast)
2. State updates are atomic (`swap!` + `fsm/transition`)
3. Effects remain synchronous (no async dispatch)
4. Error recovery is explicit (`:error` state, not nil-checking)

---

## Testing Conventions

### Pure Transition Tests

Test all state transitions without I/O using `{:exec false}` for initialization and normal `fsm/transition` for transitions (actions execute to update context):

```clojure
(defn- transition [state event]
  (fsm/transition machine state event))

(defn- init-state []
  (fsm/initialize machine {:exec false}))

(deftest start-from-stopped-test
  (testing ":start from :stopped -> :starting"
    (let [state (init-state)
          next-state (transition state {:type :start :config {:port 7888}})]
      (is (= :starting (:_state next-state)))
      (is (= {:port 7888} (:config next-state))))))
```

### Test Categories

| Category | What to test | I/O needed? |
|----------|-------------|-------------|
| Valid transitions | Every edge in the state diagram | No |
| Invalid transitions | Every non-edge (should throw) | No |
| Full lifecycle | Complete cycle through all states | No |
| Error recovery | `:error` -> `:start` -> `:running` | No |
| Assign actions | Each `assign-*` function in isolation | No |
| Integration | Real server start/stop via public API | Yes |

### Test File Location

```
modules/<module>/test/<module>/state/<name>_test.clj
```

---

## API Compatibility

The public API signatures remain unchanged when adding statecharts. External callers (tool wrappers, other modules) should not need to change.

| Before (manual) | After (statecharts) | Change? |
|-----------------|-------------------|---------|
| `(running?)` | `(running?)` | No |
| `(get-status)` returns `:running` | `(get-status)` returns `:running` | No |
| `(start-server! config)` returns map | `(start-server! config)` returns map | No |
| `(stop-server!)` throws if not running | `(stop-server!)` throws if not running | No |

The key difference: invalid transitions now throw with structured ex-info from clj-statecharts ("fsm :nrepl-server got unknown event :stop when in state :stopped") instead of manual guard checks.

---

## Reuse Patterns

### Shared Assign Actions

`assign-error` is reused across multiple transitions:

```clojure
:starting {:on {:failed {:target :error
                         :actions [(fsm/assign assign-error)]}}}
:stopping {:on {:failed {:target :error
                         :actions [(fsm/assign assign-error)]}}}
```

Named vars make this DRY. Anonymous functions would duplicate the logic.

### Common Context Shape

All state machines should include at minimum:

```clojure
:context {:error nil        ;; Last error message
          :config {}        ;; Configuration used
          :started-at nil   ;; Timestamp
          :stopped-at nil}  ;; Timestamp
```

---

## When NOT to Use Statecharts

- Simple boolean flags (`enabled?`, `initialized?`)
- One-shot operations with no lifecycle
- When the current code works fine and there's no pain

**Rule:** Don't retrofit existing patterns unless there's a specific problem to solve. Apply statecharts to new code or when reworking a file that has state management bugs.
