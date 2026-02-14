# Testing clj-statecharts on Babashka and Scittle

## Why Two Separate Test Plans?

Babashka and Scittle both build on SCI, but they execute Clojure code through fundamentally different paths. Testing one does not validate the other.

**Babashka** loads `.jar` files and `.cljc` sources via its classpath, compiling them through SCI's analyzer but with access to a large set of built-in Java classes, namespaces, and features that SCI alone doesn't provide. When you run `bb -cp "src" -e '(require ...)'`, the code is loaded natively — it goes through bb's classloader, not through `sci.core/eval-string`. This is why 36 passing tests on bb tells you the library works on bb, but tells you nothing about raw SCI interpretation.

**Scittle** runs SCI in the browser via ClojureScript. Code is loaded as text and interpreted at runtime by the SCI interpreter compiled to JavaScript. There is no classpath, no JARs, no Java interop. Libraries must be either pre-compiled into SCI's context as plugins (like `scittle.malli.js`) or inlined as source text. The execution environment is more constrained in ways that matter.

Key differences that affect compatibility:

| Capability | Babashka | Scittle/SCI |
|---|---|---|
| Load JARs from classpath | Yes | No |
| Java interop | Extensive built-in set | None |
| `deftype` with mutable fields | Yes | No (`^:volatile-mutable` unsupported) |
| `deftype` basic | Yes | Yes (limited) |
| `reify` | Yes | Yes |
| Protocols | Yes | Yes |
| Dynamic vars (`^:dynamic`) | Yes | Yes |
| Macros from `.clj` files | Loaded at compile time | Must be pre-compiled into SCI context |
| `clojure.java.io` | Yes | No |
| `System/currentTimeMillis` | Yes | No (use `js/Date.now`) |
| `Thread/sleep` | Yes | No (use `js/setTimeout`) |
| Atoms, refs, agents | All three | Atoms only |
| Reader conditionals | `:bb` and `:clj` | `:cljs` (Scittle is CLJS-based SCI) |

---

## Phase 1: Babashka Testing (Classpath Loading)

This is the straightforward path. You're verifying that the library works when bb loads it as a normal dependency.

### Setup

```
clj-statecharts-test/
├── bb.edn
├── src/statecharts/          # Library source (copy from clj-statecharts repo)
│   ├── core.cljc
│   ├── impl.cljc
│   ├── service.cljc
│   ├── clock.cljc
│   ├── scheduler.cljc
│   ├── delayed.cljc
│   ├── sim.cljc
│   ├── store.cljc
│   ├── utils.cljc
│   └── macros.clj
├── test/statecharts/
│   ├── impl_test.cljc
│   ├── service_test.cljc
│   └── utils_test.cljc
└── lib/kaocha/stacktrace.cljc  # Stub (see below)
```

```clojure
;; bb.edn
{:paths ["src" "test" "lib"]
 :deps {metosin/malli {:mvn/version "0.8.9"}}}
```

```clojure
;; lib/kaocha/stacktrace.cljc — stub needed because impl_test.cljc requires it
(ns kaocha.stacktrace)
(def ^:dynamic *stacktrace-filters* [])
```

### Run the Full Test Suite

```bash
bb -cp "src:test:lib" -e "
(require '[clojure.test :refer [run-tests]])
(require '[statecharts.impl-test])
(require '[statecharts.service-test])
(require '[statecharts.utils-test])
(let [result (run-tests
               'statecharts.impl-test
               'statecharts.service-test
               'statecharts.utils-test)]
  (System/exit (if (and (zero? (:fail result))
                        (zero? (:error result)))
                 0 1)))"
```

### What This Validates

- Machine creation with all configuration options
- State initialization and transitions
- Guards, actions, and context (via `fsm/assign`)
- Nested/hierarchical states
- Parallel states
- History states (shallow and deep)
- Delayed transitions (via `SimulatedClock`)
- Service abstraction (`service/start`, `service/send`)
- Internal malli schema validation of machine definitions

### What This Does NOT Validate

- SCI interpreter compatibility (bb uses its own loader)
- Browser runtime behavior
- Anything involving `js/` interop
- Whether the source can be evaluated as text by SCI

---

## Phase 2: SCI Interpreter Testing (via bb)

This is the critical middle step most people skip. You use bb as a host to run SCI's interpreter directly, feeding it the library source as text. This simulates what Scittle does without needing a browser.

### Why This Step Matters

When Scittle loads your code, it does roughly this:

```
source text → SCI reader → SCI analyzer → SCI interpreter
```

When bb loads your code via classpath, it does:

```
source file → bb classloader → SCI analyzer (with bb extensions) → execution
```

The bb classloader path has access to Java classes, additional built-in functions, and relaxed restrictions that the raw SCI interpreter path does not. Phase 2 tests the SCI interpreter path.

### Setup

Create a test script that builds an SCI context with the necessary configs and evaluates the library source through it:

```clojure
#!/usr/bin/env bb
;; test-sci-compat.bb
;; Tests clj-statecharts through raw SCI interpretation

(require '[sci.core :as sci])

;; You need malli's SCI config. If using sci.configs:
;; (require '[sci.configs.metosin.malli :as malli-sci])
;; If sci.configs isn't available, you may need to provide
;; malli namespaces manually or test without malli first.

(def base-ctx
  (sci/init
    {:namespaces
     {'clojure.core (sci/create-ns 'clojure.core)}
     ;; Add malli SCI config here if available
     }))

;; Helper to load a file through SCI
(defn sci-load! [ctx path]
  (println (str "  Loading: " path))
  (try
    (sci/eval-string* ctx (slurp path))
    (println (str "  ✅ OK"))
    :ok
    (catch Exception e
      (println (str "  ❌ FAILED: " (.getMessage e)))
      {:error path :message (.getMessage e)})))

(println "=== SCI Interpreter Compatibility Test ===\n")

;; Load files in dependency order
(println "Step 1: Load utility namespaces")
(sci-load! base-ctx "src/statecharts/utils.cljc")

(println "\nStep 2: Load protocol definitions")
(sci-load! base-ctx "src/statecharts/clock.cljc")
(sci-load! base-ctx "src/statecharts/store.cljc")
(sci-load! base-ctx "src/statecharts/scheduler.cljc")

(println "\nStep 3: Load core implementation")
(sci-load! base-ctx "src/statecharts/impl.cljc")

(println "\nStep 4: Load delayed transitions")
(sci-load! base-ctx "src/statecharts/delayed.cljc")

(println "\nStep 5: Load sim (expect failure - volatile-mutable)")
(sci-load! base-ctx "src/statecharts/sim.cljc")

(println "\nStep 6: Load service")
(sci-load! base-ctx "src/statecharts/service.cljc")

(println "\nStep 7: Load public API")
(sci-load! base-ctx "src/statecharts/core.cljc")
```

### Dealing with Macros

clj-statecharts has a `macros.clj` (JVM) and possibly `macros.cljs` file. SCI handles macros differently from normal Clojure — macros must either be:

1. Pre-registered in the SCI context via `:namespaces`
2. Defined inline in interpreted code using `defmacro` (SCI supports this)
3. Loaded from a `.cljc` file that SCI can interpret

Check what `macros.clj` contains. If it's simple (e.g., `if-cljs` type platform detection), you may need to provide an SCI-compatible version:

```clojure
;; If macros.clj has platform-detection macros, provide CLJS variants
;; since Scittle's SCI runs in a CLJS-like environment
(def macro-ns
  {'statecharts.macros
   {'if-cljs (sci/copy-var
               (fn [_&form _&env then _else] then)  ;; Always take CLJS branch
               (sci/create-ns 'statecharts.macros))}})
```

### Expected Failures and Workarounds

**`^:volatile-mutable` in `deftype` (sim.cljc)**

This will fail. SCI does not support mutable fields in `deftype`. However, `sim.cljc` contains `SimulatedClock` which is a testing utility — it's not needed for production use. You can skip this file entirely for Scittle or provide an atom-based alternative:

```clojure
;; SCI-compatible SimulatedClock replacement
(defn make-simulated-clock []
  (let [state (atom {:id 0 :now 0 :events {}})]
    (reify
      statecharts.clock/Clock
      (getTimeMillis [_] (:now @state))
      (setTimeout [_ f delay]
        (let [new-id (:id (swap! state update :id inc))]
          (swap! state assoc-in [:events new-id]
                 {:f f :event-time (+ (:now @state) delay)})
          new-id))
      (clearTimeout [_ id]
        (swap! state update :events dissoc id)))))
```

**`System/currentTimeMillis` (clock.cljc)**

The default clock implementation likely calls `System/currentTimeMillis`. In Scittle, this needs to be `(js/Date.now)`. Check whether `clock.cljc` uses reader conditionals to handle this — if not, you'll need a shim:

```clojure
;; Provide in SCI context
(def browser-clock
  (reify statecharts.clock/Clock
    (getTimeMillis [_] (js/Date.now))
    (setTimeout [_ f delay] (js/setTimeout f delay))
    (clearTimeout [_ id] (js/clearTimeout id))))
```

**Malli integration**

clj-statecharts uses malli internally to validate machine definitions. For this to work in SCI, the malli namespaces must be available in the SCI context. In Scittle this means the `scittle.malli.js` plugin must be loaded. In the Phase 2 bb test, you need `sci.configs.metosin.malli` — install it via:

```clojure
;; bb.edn for Phase 2 testing
{:deps {metosin/malli {:mvn/version "0.8.9"}
        org.babashka/sci.configs {:git/url "https://github.com/babashka/sci.configs"
                                  :git/sha "..."}}}
```

If wiring malli into the SCI context proves difficult, test in two stages: first without malli (comment out the malli require in the source and the schema validation calls) to verify the core logic works, then tackle the malli wiring separately.

### Functional Test Through SCI

Once the namespaces load, run an actual state machine through the SCI interpreter:

```clojure
(sci/eval-string* base-ctx "
  (require '[statecharts.core :as fsm])

  (def m (fsm/machine
           {:id :test
            :initial :a
            :states {:a {:on {:go :b}}
                     :b {:on {:go :c}}
                     :c {:on {:go :a}}}}))

  (def s0 (fsm/initialize m))
  (assert (= :a (:_state s0)) \"initial state should be :a\")

  (def s1 (fsm/transition m s0 {:type :go}))
  (assert (= :b (:_state s1)) \"after :go should be :b\")

  (def s2 (fsm/transition m s1 {:type :go}))
  (assert (= :c (:_state s2)) \"after second :go should be :c\")

  (println \"All SCI interpreter tests passed.\")
")
```

Then progressively test more features:

```clojure
;; Guards
(sci/eval-string* base-ctx "
  (def guarded (fsm/machine
    {:id :guarded
     :initial :locked
     :context {:pin nil}
     :states {:locked {:on {:unlock {:target :unlocked
                                     :guard (fn [state event]
                                              (= (:pin event) \"1234\"))}}}
              :unlocked {:on {:lock :locked}}}}))

  (let [s (fsm/initialize guarded)
        ;; Wrong pin — should stay locked
        s1 (fsm/transition guarded s {:type :unlock :pin \"0000\"})
        _ (assert (= :locked (:_state s1)) \"wrong pin should stay locked\")
        ;; Right pin — should unlock
        s2 (fsm/transition guarded s {:type :unlock :pin \"1234\"})
        _ (assert (= :unlocked (:_state s2)) \"right pin should unlock\")]
    (println \"Guard tests passed.\"))
")
```

```clojure
;; Context updates via assign
(sci/eval-string* base-ctx "
  (def counter (fsm/machine
    {:id :counter
     :initial :active
     :context {:count 0}
     :states {:active {:on {:inc {:target :active
                                   :actions (fsm/assign
                                              (fn [state _event]
                                                (update state :count inc)))}}}}}))

  (let [s0 (fsm/initialize counter)
        s1 (fsm/transition counter s0 {:type :inc})
        s2 (fsm/transition counter s1 {:type :inc})]
    (assert (= 0 (:count s0)))
    (assert (= 1 (:count s1)))
    (assert (= 2 (:count s2)))
    (println \"Context/assign tests passed.\"))
")
```

```clojure
;; Nested states
(sci/eval-string* base-ctx "
  (def nested (fsm/machine
    {:id :nested
     :initial :on
     :states {:off {:on {:toggle :on}}
              :on {:initial :idle
                   :on {:toggle :off}
                   :states {:idle {:on {:start :running}}
                            :running {:on {:stop :idle}}}}}}))

  (let [s0 (fsm/initialize nested)]
    ;; Initial state should be the nested initial
    (assert (= [:on :idle] (:_state s0))
            (str \"expected [:on :idle], got \" (:_state s0)))
    (println \"Nested state tests passed.\"))
")
```

---

## Phase 3: Scittle Browser Testing

Only proceed here after Phase 2 passes (or you've identified and worked around all SCI failures). This phase tests the actual browser delivery mechanism.

### Approach A: Minimal Inline Test

Bundle the statecharts source directly into the HTML page. This is verbose but eliminates all loading/plugin questions:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>clj-statecharts Scittle Test</title>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.6.15/dist/scittle.malli.js"></script>
  <style>
    .pass { color: green; } .fail { color: red; }
    #results { font-family: monospace; white-space: pre; }
  </style>
</head>
<body>
  <h1>clj-statecharts / Scittle Compatibility</h1>
  <div id="results">Running tests...</div>

  <!-- Load statecharts source files in dependency order -->
  <!-- Each file needs type="application/x-scittle" -->
  <script type="application/x-scittle" src="statecharts/utils.cljc"></script>
  <script type="application/x-scittle" src="statecharts/clock.cljc"></script>
  <script type="application/x-scittle" src="statecharts/store.cljc"></script>
  <script type="application/x-scittle" src="statecharts/scheduler.cljc"></script>
  <script type="application/x-scittle" src="statecharts/impl.cljc"></script>
  <script type="application/x-scittle" src="statecharts/delayed.cljc"></script>
  <!-- Skip sim.cljc — uses ^:volatile-mutable, not needed for production -->
  <script type="application/x-scittle" src="statecharts/service.cljc"></script>
  <script type="application/x-scittle" src="statecharts/core.cljc"></script>

  <script type="application/x-scittle">
  (defn log! [msg pass?]
    (let [el (.createElement js/document "div")]
      (set! (.-className el) (if pass? "pass" "fail"))
      (set! (.-textContent el) (str (if pass? "✅ " "❌ ") msg))
      (.appendChild (.getElementById js/document "results") el)))

  (defn test! [name f]
    (try
      (f)
      (log! name true)
      (catch :default e
        (log! (str name " — " (.-message e)) false))))

  ;; Clear loading message
  (set! (.-textContent (.getElementById js/document "results")) "")

  (require '[statecharts.core :as fsm])

  ;; Test 1: Basic machine creation and transition
  (test! "Basic machine + transition"
    (fn []
      (let [m (fsm/machine {:id :t1 :initial :a
                             :states {:a {:on {:go :b}} :b {}}})
            s0 (fsm/initialize m)
            s1 (fsm/transition m s0 {:type :go})]
        (assert (= :a (:_state s0)))
        (assert (= :b (:_state s1))))))

  ;; Test 2: Guards
  (test! "Guards"
    (fn []
      (let [m (fsm/machine
                {:id :t2 :initial :locked
                 :states {:locked {:on {:try {:target :open
                                              :guard (fn [_ e] (= (:code e) 42))}}}
                          :open {}}})
            s0 (fsm/initialize m)
            s1 (fsm/transition m s0 {:type :try :code 0})
            s2 (fsm/transition m s0 {:type :try :code 42})]
        (assert (= :locked (:_state s1)))
        (assert (= :open (:_state s2))))))

  ;; Test 3: Context with assign
  (test! "Context / assign"
    (fn []
      (let [m (fsm/machine
                {:id :t3 :initial :counting
                 :context {:n 0}
                 :states {:counting
                          {:on {:inc {:target :counting
                                      :actions (fsm/assign
                                                 (fn [s _] (update s :n inc)))}}}}})
            s0 (fsm/initialize m)
            s1 (fsm/transition m s0 {:type :inc})
            s2 (fsm/transition m s1 {:type :inc})]
        (assert (= 0 (:n s0)))
        (assert (= 1 (:n s1)))
        (assert (= 2 (:n s2))))))

  ;; Test 4: Nested/hierarchical states
  (test! "Nested states"
    (fn []
      (let [m (fsm/machine
                {:id :t4 :initial :on
                 :states {:off {:on {:toggle :on}}
                          :on {:initial :idle
                               :on {:toggle :off}
                               :states {:idle {:on {:run :active}}
                                        :active {:on {:stop :idle}}}}}})
            s0 (fsm/initialize m)]
        (assert (= [:on :idle] (:_state s0))))))

  ;; Test 5: Unhandled events are ignored (no crash)
  (test! "Unhandled events ignored"
    (fn []
      (let [m (fsm/machine {:id :t5 :initial :a
                             :states {:a {:on {:go :b}} :b {}}})
            s0 (fsm/initialize m)
            s1 (fsm/transition m s0 {:type :nonexistent})]
        (assert (= :a (:_state s1))))))

  ;; Test 6: Multiple transitions
  (test! "Multi-step transitions"
    (fn []
      (let [m (fsm/machine
                {:id :t6 :initial :red
                 :states {:red    {:on {:next :green}}
                          :green  {:on {:next :yellow}}
                          :yellow {:on {:next :red}}}})
            run (fn [n]
                  (loop [s (fsm/initialize m) i 0]
                    (if (>= i n) s
                      (recur (fsm/transition m s {:type :next}) (inc i)))))]
        (assert (= :red    (:_state (run 0))))
        (assert (= :green  (:_state (run 1))))
        (assert (= :yellow (:_state (run 2))))
        (assert (= :red    (:_state (run 3)))))))

  (log! "--- Tests complete ---" true)
  </script>
</body>
</html>
```

### Approach B: Scittle Plugin (Recommended for Production)

If inline loading works, the production path is to build a Scittle plugin that pre-compiles the statecharts namespaces into the SCI context. This is how `scittle.malli.js` and `scittle.reagent.js` work.

This requires a CLJS build step:

```clojure
;; src/scittle/plugin/statecharts.cljs
(ns scittle.plugin.statecharts
  (:require [sci.core :as sci]
            [sci.ctx-store :as ctx-store]
            ;; Require all statecharts namespaces to compile them
            [statecharts.core :as fsm]
            [statecharts.impl :as impl]
            [statecharts.service :as service]
            [statecharts.clock :as clock]
            [statecharts.store :as store]
            [statecharts.utils :as utils]
            [statecharts.delayed :as delayed]
            [statecharts.scheduler :as scheduler]))

;; Register namespaces into SCI's context
(def statecharts-ns
  {'statecharts.core (sci/copy-ns statecharts.core (sci/create-ns 'statecharts.core))
   'statecharts.service (sci/copy-ns statecharts.service (sci/create-ns 'statecharts.service))
   ;; ... other namespaces as needed
   })

(ctx-store/swap-ctx! sci/merge-opts {:namespaces statecharts-ns})
```

This is more work upfront but gives you a clean `<script src="scittle.statecharts.js">` that just works.

### Serving Files for Browser Testing

You need a local web server because browsers block loading `.cljc` files from `file://` due to CORS. Use bb itself:

```clojure
#!/usr/bin/env bb
;; serve-test.bb — minimal static file server
(require '[babashka.http-server :as http])

(http/serve {:port 8888 :dir "."})
(println "Serving at http://localhost:8888/test.html")
@(promise)  ;; Block forever
```

Or if you don't have bb's http-server:

```bash
python3 -m http.server 8888
```

Then open `http://localhost:8888/test.html` and check the results div and browser console.

---

## Phase 4: Known Issues Checklist

Track each issue as you encounter it. Some are certain, some are suspected.

### Certain Issues

| Issue | File | Severity | Workaround |
|---|---|---|---|
| `^:volatile-mutable` in `deftype` | `sim.cljc` | Low (test-only) | Skip file, or use `reify` + `atom` replacement |
| `System/currentTimeMillis` | `clock.cljc` | Medium | Reader conditional or provide browser clock via `reify` |
| `Thread/sleep` or `Thread` refs | Anywhere | Medium | Provide `js/setTimeout` alternative |

### Probable Issues

| Issue | File | Severity | Workaround |
|---|---|---|---|
| Macros in `macros.clj` not available to SCI | `macros.clj` | High if present | Inspect file; provide SCI-compatible versions |
| Malli internal calls failing in SCI | `impl.cljc` | High | Ensure `scittle.malli.js` or `sci.configs.metosin.malli` loaded |

### Possible Issues

| Issue | File | Severity | Workaround |
|---|---|---|---|
| Protocol method dispatch differences | Various | Low–Medium | Test each protocol implementation |
| `deftype` without mutable fields | `service.cljc` | Low | SCI generally supports basic `deftype`; verify |
| Dynamic var binding (`binding` form) | `clock.cljc` | Low | SCI supports `binding`; verify with `*clock*` |
| Namespace aliasing edge cases | Various | Low | Test `require` with `:as` and `:refer` |

---

## Phase 5: Reader Conditionals Audit

clj-statecharts is `.cljc`, so it may use reader conditionals to split JVM/JS behavior. This matters because:

- **Babashka** reads `:bb` first, then falls back to `:clj`
- **Scittle's SCI** reads `:cljs` (it's CLJS-compiled SCI)

Audit every reader conditional in the source:

```bash
grep -rn '#?' src/statecharts/
```

For each one, verify that the `:cljs` branch (which Scittle will take) does the right thing. Common patterns to watch for:

```clojure
;; This is fine — Scittle takes :cljs branch
#?(:clj  (System/currentTimeMillis)
   :cljs (js/Date.now))

;; This is a problem — bb takes :clj, but what does Scittle get?
#?(:clj  (import 'java.util.concurrent.ScheduledExecutorService)
   :cljs nil)

;; This might be a problem — does the :cljs branch work in SCI?
#?(:clj  (deftype Foo [x] ...)
   :cljs (deftype Foo [x] ...))  ;; Same code but SCI may not support it
```

If any reader conditional only has `:clj` and `:default` (no `:cljs`), Scittle will take `:default`. If there's no `:default`, Scittle gets `nil`. Both situations can produce subtle bugs.

---

## Decision Tree: Test Progression

```
Start
  │
  ▼
Phase 1: bb classpath tests
  │ All 36 pass? ──No──▶ Fix library-level issues first
  │ Yes
  ▼
Phase 5: Reader conditional audit
  │ All :cljs branches look correct? ──No──▶ Patch or shim before proceeding
  │ Yes
  ▼
Phase 2: SCI interpreter test via bb
  │ Core loads? (utils, clock, store, impl, core)
  │ Yes ──────────────────────────No──▶ Identify failing construct,
  │                                      create workaround, retry
  ▼
Phase 2 continued: Functional tests through SCI
  │ Basic transitions work? Guards? Assign? Nested?
  │ Yes ──────────────────────────No──▶ Debug at SCI level (faster
  │                                      than browser debugging)
  ▼
Phase 3: Scittle browser test
  │ Inline loading works?
  │ Yes ──────────────────────────No──▶ Namespace loading issue;
  │                                      check dependency order,
  │                                      check malli plugin
  ▼
Phase 3 continued: Full feature test in browser
  │ All tests pass?
  │ Yes
  ▼
Ship it. Optionally build a Scittle plugin for cleaner packaging.
```

---

## Appendix: Quick Commands Reference

```bash
# Phase 1 — run bb tests
bb -cp "src:test:lib" -e "(require '[clojure.test :refer [run-tests]]
  '[statecharts.impl-test] '[statecharts.service-test] '[statecharts.utils-test])
  (run-tests 'statecharts.impl-test 'statecharts.service-test 'statecharts.utils-test)"

# Phase 5 — audit reader conditionals
grep -rn '#?' src/statecharts/

# Phase 2 — run SCI interpreter test
bb test-sci-compat.bb

# Phase 3 — serve test page
bb -e "(require '[babashka.http-server :as http]) (http/serve {:port 8888 :dir \".\"}) @(promise)"
# then open http://localhost:8888/test.html

# Inspect macros file
cat src/statecharts/macros.clj
cat src/statecharts/macros.cljs 2>/dev/null || echo "No .cljs macros file"
```
