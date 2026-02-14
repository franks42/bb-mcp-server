# clj-statecharts: Babashka, Scittle & SCI Compatibility

## Summary

**clj-statecharts** is fully compatible with Babashka. All 36 tests pass with 155 assertions, zero failures. This document captures the research and testing done to verify compatibility, and provides a roadmap for Scittle/SCI testing.

## Library Overview

- **Repository**: https://github.com/lucywang000/clj-statecharts
- **License**: EPL-1.0
- **Version tested**: 0.1.7 (latest as of Jan 2026)
- **Only dependency**: `metosin/malli` 0.8.9 (which is bb-compatible since 0.8.9)

### Why clj-statecharts over fulcrologic/statecharts?

| Aspect | clj-statecharts | fulcrologic/statecharts |
|--------|-----------------|------------------------|
| Approach | Functional, data-driven | SCXML-based, more imperative |
| Dependencies | Just malli | timbre, malli, guardrails, core.async |
| State representation | Flat map with `_state` key | Nested structure |
| API style | XState-inspired, simple | W3C SCXML algorithm |
| Integration | Re-frame built-in | Fulcro-specific |
| Learning curve | Lower | Higher |

## Babashka Compatibility - VERIFIED ✅

### Test Results

```
=== Running ALL clj-statecharts tests on Babashka ===
bb version: 1.12.214

Ran 36 tests containing 155 assertions.
0 failures, 0 errors.
```

### Required Dependencies

```clojure
;; bb.edn
{:paths ["src" "test"]
 :deps {metosin/malli {:mvn/version "0.8.9"}}}
```

If bb can't fetch deps (network issues), manually download:
- `metosin/malli` 0.8.9
- `borkdude/dynaload` 0.3.5 (malli dependency)
- `borkdude/edamame` 1.0.0 (malli dependency)

### Minimal Test Script

```clojure
#!/usr/bin/env bb
(require '[statecharts.core :as fsm])

(def machine
  (fsm/machine
    {:id :traffic-light
     :initial :red
     :states {:red {:on {:timer :green}}
              :green {:on {:timer :yellow}}
              :yellow {:on {:timer :red}}}}))

(def state (fsm/initialize machine))
(println "initial:" (:_state state))  ;; => :red

(def state2 (fsm/transition machine state {:type :timer}))
(println "after timer:" (:_state state2))  ;; => :green
```

### Test Execution

```bash
# With deps available
bb -cp "src:test" -e "
(require '[clojure.test :refer [run-tests]])
(require '[statecharts.impl-test])
(require '[statecharts.service-test])
(require '[statecharts.utils-test])
(run-tests 
  'statecharts.impl-test
  'statecharts.service-test
  'statecharts.utils-test)"
```

### Kaocha Stub Required for Tests

The test file `impl_test.cljc` requires `kaocha.stacktrace` for a test fixture. Create a stub:

```clojure
;; lib/kaocha/stacktrace.cljc
(ns kaocha.stacktrace)
(def ^:dynamic *stacktrace-filters* [])
```

This is only needed for running the library's own tests, not for using the library.

## Scittle/SCI Compatibility - TODO 🔄

### Expected to Work

The library uses:
- Pure CLJC code (no platform-specific magic)
- Standard Clojure constructs
- Malli for schema validation (malli has SCI support via `sci.configs`)
- Protocols and deftypes (supported in SCI)

### Testing Approach for Scittle

1. **Option A: Use sci.configs for malli**
   ```clojure
   ;; In Scittle, load malli's sci config
   (require '[sci.configs.metosin.malli :as malli-config])
   ```

2. **Option B: Inline the library**
   - Bundle statecharts source directly
   - May need to handle `deftype` for Clock/Scheduler

### Scittle Test Template

```html
<!DOCTYPE html>
<html>
<head>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.malli.js"></script>
</head>
<body>
  <div id="output"></div>
  <script type="application/x-scittle">
  ;; TODO: Test if statecharts can be loaded
  ;; May need to inline the source or create a scittle plugin
  
  (require '[malli.core :as m])
  
  ;; If statecharts source is inlined/bundled:
  ;; (require '[statecharts.core :as fsm])
  ;; 
  ;; (def machine
  ;;   (fsm/machine
  ;;     {:id :test
  ;;      :initial :a
  ;;      :states {:a {:on {:next :b}}
  ;;               :b {:on {:next :a}}}}))
  ;; 
  ;; (def state (fsm/initialize machine))
  ;; (js/console.log "State:" (pr-str (:_state state)))
  </script>
</body>
</html>
```

### Potential SCI Issues to Watch

1. **`deftype` with mutable fields** - `sim.cljc` uses `^:volatile-mutable`
   ```clojure
   (deftype SimulatedClock [events
                            ^:volatile-mutable id
                            ^:volatile-mutable now_]
     ...)
   ```
   SCI may not support mutable fields in deftype.

2. **Protocol implementations** - Should work but verify:
   - `Clock` protocol
   - `IScheduler` protocol
   - `IStore` protocol

3. **Dynamic vars** - Used for `*clock*` binding
   ```clojure
   (def ^:dynamic ^Clock *clock* nil)
   ```

### Workarounds if SCI Has Issues

If `deftype` with mutable fields fails, create SCI-compatible versions:

```clojure
;; Alternative using atoms instead of volatile-mutable
(defn make-simulated-clock []
  (let [state (atom {:id 0 :now 0 :events {}})]
    (reify Clock
      (getTimeMillis [_] (:now @state))
      (setTimeout [_ f delay]
        (let [id (swap! state update :id inc)]
          (swap! state assoc-in [:events id] 
                 {:f f :event-time (+ (:now @state) delay)})
          id))
      (clearTimeout [_ id]
        (swap! state update :events dissoc id)))))
```

## Architecture: bb Server + Scittle Client

### Recommended Pattern

```
┌─────────────────────────────────────────────────────────┐
│                    Babashka Server                       │
│                                                          │
│  ┌──────────────────┐    ┌─────────────────────────┐   │
│  │ State Machine    │    │ WebSocket/SSE Handler   │   │
│  │ (authoritative)  │───▶│ (broadcasts state)      │   │
│  └──────────────────┘    └─────────────────────────┘   │
│           │                          │                   │
│           ▼                          ▼                   │
│  ┌──────────────────┐    ┌─────────────────────────┐   │
│  │ Event Queue      │    │ State Serialization     │   │
│  │ (receives events)│    │ (EDN/Transit)           │   │
│  └──────────────────┘    └─────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                           │
                           │ WebSocket/SSE
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    Scittle Client                        │
│                                                          │
│  ┌──────────────────┐    ┌─────────────────────────┐   │
│  │ State Mirror     │◀───│ WebSocket Handler       │   │
│  │ (read-only)      │    │ (receives state)        │   │
│  └──────────────────┘    └─────────────────────────┘   │
│           │                          ▲                   │
│           ▼                          │                   │
│  ┌──────────────────┐    ┌─────────────────────────┐   │
│  │ UI Rendering     │    │ Event Sender            │   │
│  │ (based on state) │    │ (user actions)          │   │
│  └──────────────────┘    └─────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Key Points

1. **Server owns the state machine** - All transitions happen server-side
2. **Client receives state updates** - No local state machine needed (simplest approach)
3. **Or client mirrors state** - Run same machine client-side for optimistic updates
4. **Events are just data** - `{:type :timer}` serializes trivially

### Server-Side Example (bb)

```clojure
(ns myapp.server
  (:require [statecharts.core :as fsm]
            [statecharts.service :as service]))

(def machine
  (fsm/machine
    {:id :websocket-protocol
     :initial :disconnected
     :states {:disconnected {:on {:connect :connecting}}
              :connecting {:on {:connected :connected
                                :error :disconnected}}
              :connected {:on {:message :connected
                               :disconnect :disconnecting
                               :error :reconnecting}}
              :reconnecting {:on {:connected :connected
                                  :give-up :disconnected}}
              :disconnecting {:on {:disconnected :disconnected}}}}))

;; Per-connection state
(defn create-session []
  (let [svc (fsm/service machine)]
    (fsm/start svc)
    svc))

(defn handle-event [session event]
  (fsm/send session event)
  ;; Broadcast new state to client
  {:state (fsm/value session)
   :full-state (fsm/state session)})
```

### Client-Side Example (Scittle) - State Mirror Only

```clojure
;; Simplest approach: client just renders server state
(def current-state (atom nil))

(defn on-state-update [new-state]
  (reset! current-state new-state)
  (render-ui @current-state))

(defn send-event [event-type]
  ;; Send to server, don't update local state
  (ws-send {:type event-type}))
```

## Files in This Directory

```
clj-statecharts/
├── src/statecharts/
│   ├── core.cljc        # Main API
│   ├── impl.cljc        # Core implementation (33KB)
│   ├── service.cljc     # Service abstraction
│   ├── clock.cljc       # Clock protocol
│   ├── scheduler.cljc   # Delayed event scheduling
│   ├── delayed.cljc     # Delayed transitions
│   ├── sim.cljc         # Simulated clock for testing
│   ├── store.cljc       # State store protocol
│   ├── utils.cljc       # Utilities
│   └── macros.clj[s]    # Platform macros
├── test/statecharts/
│   ├── impl_test.cljc   # Main tests (42KB, 29 tests)
│   ├── service_test.cljc # Service tests (2 tests)
│   └── utils_test.cljc  # Utility tests (5 tests)
├── lib/
│   ├── malli-0.8.9.jar
│   ├── dynaload-0.3.5.jar
│   ├── edamame-1.0.0.jar
│   └── kaocha/stacktrace.cljc  # Stub for tests
├── bb.edn
├── tests.edn
└── statecharts-bb-scittle-sci.md  # This file
```

## Reagent Integration

Statecharts excel at managing UI component lifecycle state that goes beyond simple value changes. They prevent impossible states, make transitions explicit, and handle race conditions gracefully.

### Basic Pattern

```clojure
(ns myapp.ui
  (:require [reagent.core :as r]
            [statecharts.core :as fsm]))

;; Define the machine once
(def form-machine
  (fsm/machine
    {:id :form
     :initial :pristine
     :states {:pristine   {:on {:change :dirty}}
              :dirty      {:on {:submit :submitting
                                :reset :pristine}}
              :submitting {:on {:success :submitted
                                :error :dirty}}
              :submitted  {:on {:edit :dirty}}}}))

;; State atom
(defonce form-state (r/atom nil))

;; Initialize
(defn init-form! []
  (reset! form-state (fsm/initialize form-machine)))

;; Send events
(defn send! [event-type & [data]]
  (swap! form-state 
         #(fsm/transition form-machine % 
                          (merge {:type event-type} data))))

;; Component renders based on state
(defn my-form []
  (let [{:keys [_state] :as state} @form-state]
    (case _state
      :pristine   [:div "Ready to edit"
                   [:button {:on-click #(send! :change)} "Start"]]
      :dirty      [:div 
                   [:input {:value (:value state)
                            :on-change #(send! :change {:value (-> % .-target .-value)})}]
                   [:button {:on-click #(send! :submit)} "Submit"]
                   [:button {:on-click #(send! :reset)} "Reset"]]
      :submitting [:div "Submitting..." [:span.spinner]]
      :submitted  [:div "Success! " 
                   [:button {:on-click #(send! :edit)} "Edit again"]])))
```

### With Actions (Side Effects)

```clojure
(defn submit-form! [state _event]
  ;; Perform async submission
  (-> (js/fetch "/api/submit" 
                #js {:method "POST"
                     :body (js/JSON.stringify (clj->js (:data state)))})
      (.then #(send! :success))
      (.catch #(send! :error {:message (.-message %)})))
  state)  ;; Return state unchanged, async callback will transition

(def form-machine
  (fsm/machine
    {:id :form
     :initial :pristine
     :states {:pristine   {:on {:change :dirty}}
              :dirty      {:on {:submit {:target :submitting
                                         :actions submit-form!}
                                :reset :pristine}}
              :submitting {:on {:success :submitted
                                :error :dirty}}
              :submitted  {:on {:edit :dirty}}}}))
```

### With Context (Storing Data in State)

```clojure
(def form-machine
  (fsm/machine
    {:id :form
     :initial :pristine
     :context {:value ""        ;; Form value
               :error nil       ;; Error message
               :attempts 0}     ;; Retry counter
     :states 
     {:pristine   
      {:on {:change {:target :dirty
                     :actions (fsm/assign 
                               (fn [state event]
                                 (assoc state :value (:value event))))}}}
      :dirty      
      {:on {:submit :submitting
            :reset {:target :pristine
                    :actions (fsm/assign #(assoc % :value "" :error nil))}}}
      :submitting 
      {:on {:success :submitted
            :error {:target :dirty
                    :actions (fsm/assign 
                              (fn [state event]
                                (-> state
                                    (assoc :error (:message event))
                                    (update :attempts inc))))}}}
      :submitted  
      {:on {:edit :dirty}}}}))
```

### Common UI Patterns

**Async Data Loading**
```clojure
{:id :data-loader
 :initial :idle
 :context {:data nil :error nil}
 :states {:idle    {:on {:fetch :loading}}
          :loading {:on {:success {:target :ready
                                   :actions (fsm/assign #(assoc %1 :data (:data %2)))}
                         :error {:target :failed
                                 :actions (fsm/assign #(assoc %1 :error (:error %2)))}}}
          :ready   {:on {:refresh :loading
                         :invalidate :idle}}
          :failed  {:on {:retry :loading
                         :dismiss :idle}}}}
```

**Modal/Dialog Flows**
```clojure
{:id :delete-confirmation
 :initial :closed
 :context {:item-id nil}
 :states {:closed     {:on {:open {:target :confirming
                                   :actions (fsm/assign #(assoc %1 :item-id (:id %2)))}}}
          :confirming {:on {:confirm :deleting
                            :cancel :closed}}
          :deleting   {:on {:success :closed
                            :error :confirming}}}}
```

**Multi-step Wizard**
```clojure
{:id :wizard
 :initial :step1
 :context {:step1-data {} :step2-data {} :step3-data {}}
 :states {:step1 {:on {:next {:target :step2
                              :actions (fsm/assign #(assoc %1 :step1-data (:data %2)))}}}
          :step2 {:on {:next {:target :step3
                              :actions (fsm/assign #(assoc %1 :step2-data (:data %2)))}
                       :back :step1}}
          :step3 {:on {:submit :submitting
                       :back :step2}}
          :submitting {:on {:success :complete
                            :error :step3}}
          :complete {}}}
```

**Authentication Flow**
```clojure
{:id :auth
 :initial :checking
 :context {:user nil :error nil}
 :states {:checking     {:on {:authenticated {:target :logged-in
                                              :actions (fsm/assign #(assoc %1 :user (:user %2)))}
                              :unauthenticated :logged-out}}
          :logged-out   {:on {:login :authenticating}}
          :authenticating {:on {:success {:target :logged-in
                                          :actions (fsm/assign #(assoc %1 :user (:user %2) :error nil))}
                                :error {:target :logged-out
                                        :actions (fsm/assign #(assoc %1 :error (:error %2)))}}}
          :logged-in    {:on {:logout :logging-out}}
          :logging-out  {:on {:done :logged-out}}}}
```

### Why Statecharts over Ad-hoc Atoms

| Ad-hoc Atoms | Statecharts |
|--------------|-------------|
| `(reset! loading? true)` scattered everywhere | Single `(send! :fetch)` |
| Can be loading AND have error simultaneously | Impossible states are impossible |
| Race conditions from rapid clicks | Events in wrong state are ignored |
| State logic spread across handlers | All transitions in one place |
| "How did we get here?" debugging | State history is traceable |
| Documentation separate from code | The machine IS the documentation |

### Scittle/Reagent Considerations

For Scittle with Reagent, the pattern is identical. The only difference is how you load the libraries:

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.reagent.js"></script>
<!-- statecharts would need to be bundled or inlined -->

<script type="application/x-scittle">
(require '[reagent.core :as r])
(require '[reagent.dom :as rdom])
;; (require '[statecharts.core :as fsm])  ;; if bundled

;; Same patterns as above work identically
</script>
```

## Next Steps for Scittle Testing

1. **Create Scittle test page** with malli plugin loaded
2. **Bundle statecharts source** - inline or create scittle plugin
3. **Test core functionality**:
   - Machine creation
   - State initialization  
   - Transitions
   - Guards and actions
4. **Test potential problem areas**:
   - `deftype` with mutable fields (SimulatedClock)
   - Protocol implementations
   - Dynamic var binding
5. **If issues found**, create SCI-compatible alternatives
6. **Document findings** in this file

## References

- [clj-statecharts documentation](https://lucywang000.github.io/clj-statecharts/)
- [Malli bb compatibility](https://github.com/metosin/malli#babashka)
- [SCI configs for libraries](https://github.com/babashka/sci.configs)
- [Scittle](https://github.com/babashka/scittle)
- [Statecharts concept](https://statecharts.dev/)
