# Scittle Runtime Code Browser Implementation Plan

> **Goal**: Implement code browsing capabilities for a live Scittle runtime via the nREPL interface, similar to the existing bb-mcp-server code-browser-v2 module for bb-runtime.

**Date**: February 12, 2026  
**Status**: Planning Phase  
**Reference**: `modules/code-browser-v2` implementation

---

## Executive Summary

The code-browser-v2 module already supports nREPL sources for **Babashka** runtimes. To extend this to **Scittle** (ClojureScript running in browser), we need to:

1. Add Scittle-specific runtime introspection (different namespace/symbol APIs)
2. Handle ClojureScript-specific constructs (macros, goog namespace, etc.)
3. Support browser-specific state (atoms, reagent components, statecharts)
4. Integrate with existing sente-browser/nrepl-proxy infrastructure

**Key Insight**: Scittle runs in the browser context, so introspection must happen via the browser's nREPL connection, not direct JVM reflection.

---

## Architecture Overview

### Current State (bb-runtime)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Code Browser v2                                  │
│                                                                     │
│  ┌─────────────────┐         ┌──────────────────┐                  │
│  │  Directory      │         │   nREPL Source   │                  │
│  │  Source         │         │  (Babashka)      │                  │
│  │  - clj-kondo    │         │  - all-ns        │                  │
│  │  - scan files   │         │  - ns-publics    │                  │
│  │  - parse AST    │         │  - source-fn     │                  │
│  └────────┬────────┘         └────────┬─────────┘                  │
│           │                           │                            │
│           └───────────┬───────────────┘                            │
│                       ▼                                            │
│              ┌──────────────────┐                                  │
│              │  Datalevin DB    │                                  │
│              │  (URI-centric)   │                                  │
│              └────────┬─────────┘                                  │
│                       ▼                                            │
│              ┌──────────────────┐                                  │
│              │  Browser UI      │                                  │
│              │  (scittle)       │                                  │
│              └──────────────────┘                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Target State (Scittle-runtime)

```
┌─────────────────────────────────────────────────────────────────────┐
│              Scittle Runtime Code Browser                           │
│                                                                     │
│  ┌─────────────────┐         ┌──────────────────┐                  │
│  │  Directory      │         │   nREPL Source   │                  │
│  │  Source         │         │  (Scittle)       │                  │
│  │  - clj-kondo    │         │  - all-ns*       │                  │
│  │  - scan files   │         │  - ns-publics*   │                  │
│  │  - parse AST    │         │  - source-fn*    │                  │
│  └────────┬────────┘         └────────┬─────────┘                  │
│           │                           │                            │
│           └───────────┬───────────────┘                            │
│                       ▼                                            │
│              ┌──────────────────┐                                  │
│              │  Datalevin DB    │                                  │
│              │  (URI-centric)   │                                  │
│              └────────┬─────────┘                                  │
│                       ▼                                            │
│              ┌──────────────────┐                                  │
│              │  Browser UI      │                                  │
│              │  (scittle)       │                                  │
│              └──────────────────┘                                  │
│                                                                     │
│  * Scittle-specific: goog namespace, macros, reagent, statecharts  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Plan

### Phase 1: Scittle Runtime Introspection Layer

**Location**: `modules/code-browser-v2/src/code_browser/sources/runtime/scittle.cljs`

#### 1.1 Runtime Detection

**Problem**: Need to detect if connected runtime is Scittle vs Babashka vs JVM Clojure.

**Solution**: Add detection via environment checks:

```clojure
;; code-browser.sources.runtime/scittle.cljs
(defmethod runtime/detect-runtime :scittle [_ eval-fn _opts]
  (let [result (eval-fn
                "(let [scittle? (exists? scittle)
                       goog? (exists? js/goog)]
                   {:type (if scittle? :scittle :jvm-clojure)
                    :version (or (when scittle? scittle/version)
                                 (System/getProperty \"java.version\"))})")]
    (if (map? result)
      (update result :type keyword)
      {:type :jvm-clojure :version "unknown"})))
```

#### 1.2 Namespace Introspection

**Problem**: Scittle uses `goog.require` and `goog.provide` instead of `ns`.

**Solution**: Use Scittle's internal namespace registry:

```clojure
(defmethod runtime/list-namespaces :scittle [_ eval-fn opts]
  (let [patterns (or (:exclude-patterns opts)
                     runtime/default-exclude-patterns)
        ;; Scittle maintains goog.global.CLOSURE_UNCOMPILED_NAMESPACES
        code "(let [namespaces (js->clj (.-namespaces goog.global) :keywordize-keys true)
                    sorted (sort-by str (keys namespaces))]
           (vec (for [ns-name sorted]
                  {:name ns-name
                   :doc (str (get-in namespaces [ns-name :docstring]))})))"
        result (eval-fn code)]
    (if (sequential? result)
      (filterv (fn [ns-map]
                 (not (runtime/excluded-ns? (:name ns-map) patterns)))
               result)
      [])))
```

#### 1.3 Var Introspection

**Problem**: Scittle macros and special forms need different handling.

**Solution**: Extend Babashka implementation with Scittle-specific handling:

```clojure
(defmethod runtime/introspect-namespace :scittle [_ eval-fn ns-name _opts]
  (let [code (str "(let [ns-sym (symbol \"" ns-name "\")]
                     (vec (for [[sym v] (sort-by key (ns-interns ns-sym))
                                :let [m (meta v)]]
                            {:name (str sym)
                             :arglists (when (:arglists m) (pr-str (:arglists m)))
                             :doc (:doc m)
                             :macro (or (:macro m) (boolean (:scittle/macros m)))
                             :private (:private m)
                             :dynamic (:dynamic m)})))")
        result (eval-fn code)]
    (if (sequential? result) result [])))
```

#### 1.4 Source Fetching

**Problem**: Scittle source maps and inline definitions.

**Solution**: Use `clojure.repl/source-fn` with Scittle's source tracking:

```clojure
(defmethod runtime/fetch-var-source :scittle [_ eval-fn ns-name var-name _opts]
  (let [code (str "(try (clojure.repl/source-fn '" ns-name "/" var-name ")
                     (catch Exception _
                            (let [v (resolve (symbol \"" ns-name "/" var-name "\"))]
                              (when v
                                (str \"(def \" '~v \" ...Scittle inline definition...)\")))))")]
    (-> (eval-fn code)
        (when (and (string? %) (seq %))
          (hash-map :source %)))))
```

#### 1.5 Value Introspection

**Problem**: Scittle values include Reagent atoms, statecharts, etc.

**Solution**: Extend `fetch-var-value` with Scittle-specific detection:

```clojure
(def ^:private fetch-var-value-scittle-code
  "(let [v (resolve (symbol \"%s\" \"%s\"))]
   (when v
     (let [raw-val (deref v)
           is-atom? (instance? cljs.core/IAtom raw-val)
           val (if is-atom? (deref raw-val) raw-val)
           ;; Scittle-specific checks
           is-reagent? (boolean (try (require 'reagent.core)
                                     (satisfies? reagent.core/ILifecycle val)
                                     (catch Exception _ false)))
           is-statechart? (boolean (try (require 'statecharts.types)
                                        ((resolve 'statecharts.types/statechart?) val)
                                        (catch Exception _ false)))
           ;; ... (extend with Scittle predicates)
           vtype (cond
                   is-reagent? \"reagent-component\"
                   is-statechart? \"statechart\"
                   ;; ... existing types
                   )]
       {:value-str ...
        :value-type vtype
        :is-reagent? is-reagent?
        :is-statechart? is-statechart?
        :var-id (System/identityHashCode raw-val)
        :value-id (if is-atom? (System/identityHashCode val) var-id)})))")

(defmethod runtime/fetch-var-value :scittle
  [_runtime-type eval-fn ns-name var-name _opts]
  (let [code (format fetch-var-value-scittle-code ns-name var-name)
        result (eval-fn code)]
    (when (map? result) result)))
```

---

### Phase 2: Scittle Source Adapter

**Location**: `modules/code-browser-v2/src/code_browser/sources/scittle.cljs`

#### 2.1 ScittleSource Record

```clojure
(ns code-browser.sources.scittle
  (:require [code-browser.sources.protocol :as proto]
            [code-browser.sources.runtime :as runtime]
            [code-browser.uri :as uri]
            [bb-mcp-server.nrepl-direct.client :as nrepl-client]
            [com.github.franks42.uuidv7.core :as uuidv7]
            [taoensso.trove :as log]))

(defrecord ScittleSource [host port project-name version uri-base
                          conn runtime-info]
  proto/IProjectSource

  (scan-project [_this]
    (log/log! {:level :info
               :id ::scanning-scittle
               :msg "Scanning Scittle runtime"
               :data {:host host :port port
                      :project-name project-name
                      :version version}})
    (let [eval-fn (make-eval-fn conn host port)
          rt-info (runtime/detect-runtime eval-fn)
          _ (reset! runtime-info rt-info)
          rt-type (:type rt-info)
          ns-data (runtime/batch-introspect rt-type eval-fn {})
          project {:uri/string uri-base
                   :uri/source :scittle
                   :uri/project project-name
                   :uri/version version
                   :uri/version-type :temporal
                   :project/nrepl-host (str host ":" port)}
          namespaces (mapv #(build-ns-entity % uri-base project-name version)
                           ns-data)
          symbols (vec (mapcat
                        (fn [ns-map]
                          (map #(build-symbol-entity % (:name ns-map)
                                                     uri-base project-name version)
                               (:vars ns-map)))
                        ns-data))]
      {:project project
       :namespaces namespaces
       :symbols symbols
       :aliases []
       :refers []}))

  (fetch-source [_this uri-string]
    ;; Same as nrepl.clj - fetch from runtime
    )

  (watch! [_this _callback] nil)
  (unwatch! [_this _handle] nil)

  (source-info [_this]
    {:type :scittle
     :version-type :temporal
     :supports-watch? false
     :description (str "Scittle: " host ":" port)}))

(defn create-scittle-source
  ([host port]
   (create-scittle-source host port {}))
  ([host port {:keys [project-name]}]
   (let [proj-name (or project-name (str host ":" port))
         ver (str (uuidv7/uuidv7))
         uri-base (uri/build {:source :scittle :project proj-name :version ver})
         conn-atom (atom nil)
         rt-info (atom nil)]
     (log/log! {:level :info
                :id ::creating-scittle-source
                :msg "Creating Scittle source"
                :data {:host host :port port
                       :project-name proj-name
                       :version ver
                       :uri-base uri-base}})
     (->ScittleSource host port proj-name ver uri-base conn-atom rt-info))))
```

#### 2.2 Integration with handlers.clj

```clojure
;; code-browser.handlers
(ns code-browser.handlers
  (:require [code-browser.sources.scittle :as scittle-source]))

(defn- fetch-source
  "Fetch source for a symbol using registered source adapters."
  [symbol-uri]
  (let [sources (:sources @!module-state)]
    (some (fn [[_proj-uri source]]
            (source-proto/fetch-source source symbol-uri))
          sources)))

(defn- fetch-var-value
  "Fetch the current runtime value for a var from nREPL sources."
  [ns-name var-name]
  (let [sources (:sources @!module-state)]
    (some (fn [[_proj-uri source]]
            (case (:type (source-proto/source-info source))
              :nrepl (nrepl-source/fetch-var-value source ns-name var-name {})
              :scittle (scittle-source/fetch-var-value source ns-name var-name {})
              nil))
          sources)))
```

---

### Phase 3: Browser UI Enhancements

**Location**: `modules/sente-browser/src/browser/scittle_browser.cljs`

#### 3.1 Scittle-Specific Browser Module

```clojure
(ns browser.scittle-browser
  (:require [reagent.core :as r]
            [code-browser-v2.core :as cb]
            [code-browser-v2.uri :as uri]))

(defonce !scittle-projects (r/atom {}))

(defn load-scittle-project!
  "Load a Scittle runtime project into the code browser."
  [host port & {:keys [project-name]}]
  (let [source (cb/create-scittle-source host port {:project-name project-name})
        proj-name (:project-name source)]
    (cb/add-source! source)
    (swap! !scittle-projects assoc proj-name source)
    {:success true :project-name proj-name}))

(defn unload-scittle-project!
  "Remove a Scittle runtime project from the code browser."
  [project-name]
  (cb/remove-source! project-name)
  (swap! !scittle-projects dissoc project-name)
  {:success true :project-name project-name})

(defn scittle-projects []
  @!scittle-projects)
```

#### 3.2 Browser UI Components

```clojure
(ns browser.scittle-ui
  (:require [reagent.core :as r]
            [browser.scittle-browser :as scittle]
            [code-browser-v2.widgets :as widgets]))

(defn scittle-connection-panel []
  (let [host (r/atom "localhost")
        port (r/atom 7888)
        project-name (r/atom "")
        loading? (r/atom false)
        result (r/atom nil)]
    (fn []
      [:div.scittle-connection
       [:h3 "Scittle Runtime Connection"]
       [:div [:label "Host: "] [:input {:type "text"
                                        :value @host
                                        :on-change #(reset! host (-> % .-target .-value))}]]
       [:div [:label "Port: "] [:input {:type "number"
                                        :value @port
                                        :on-change #(reset! port (-> % .-target .-value))}]]
       [:div [:label "Project Name: "] [:input {:type "text"
                                                 :value @project-name
                                                 :on-change #(reset! project-name (-> % .-target .-value))}]]
       [:button {:on-click (fn []
                             (reset! loading? true)
                             (reset! result nil)
                             (scittle/load-scittle-project! @host @port
                               :project-name @project-name)
                             (reset! loading? false))}
        "Connect"]
       @loading? [:div "Loading..."]
       @result [:div.result @result]])))

(defn scittle-project-widget []
  [:div.scittle-projects
   [:h3 "Scittle Projects"]
   (for [[proj-name source] @scittle/!scittle-projects]
     ^{:key proj-name} [:div.project
                        [:h4 proj-name]
                        [:button {:on-click #(scittle/unload-scittle-project! proj-name)}
                         "Disconnect"]])])
```

---

### Phase 4: Integration with nREPL Proxy

**Location**: `modules/nrepl-proxy-server/`

#### 4.1 Browser Connection Routing

The nrepl-proxy-server already routes messages to browsers via sente-browser. We need to:

1. Detect when a browser is running Scittle (via runtime detection)
2. Route Scittle-specific queries to the browser's nREPL connection
3. Cache Scittle source metadata separately from bb-runtime

```clojure
;; nrepl-proxy-server/src/nrepl_proxy/router.cljs
(ns nrepl-proxy.router
  (:require [sente-browser.state :as sente-state]
            [code-browser.sources.scittle :as scittle-source]))

(defmulti route-scittle-query
  "Route queries to appropriate runtime (Scittle vs bb)."
  (fn [query-type conn-info]
    (if (= :scittle (:runtime-type conn-info))
      :scittle
      :bb)))

(defmethod route-scittle-query :scittle
  [query-type conn-info query]
  ;; Route to Scittle runtime via browser nREPL
  (let [conn (:connection conn-info)
        result (nrepl-client/eval-code conn query)]
    result))

(defmethod route-scittle-query :bb
  [query-type conn-info query]
  ;; Route to bb runtime via direct nREPL
  )
```

---

## Technical Considerations

### 1. Scittle Version Compatibility

Scittle 0.7.x has different internal APIs than 0.6.x. We need to:

- Detect Scittle version via `scittle/version`
- Use version-specific introspection code
- Provide fallbacks for older versions

### 2. goog Namespace Handling

Scittle uses Closure Library's `goog` namespace. We need to:

- Include `goog` namespaces in the project view
- Handle `goog.require` and `goog.provide` in symbol resolution
- Support `goog.module` style modules

### 3. Macro Expansion

Scittle macros expand differently than bb macros. We need to:

- Use `macroexpand-1` and `macroexpand` for Scittle
- Handle special forms (`.`, `^`, `#()`, etc.)
- Show expanded code in source view

### 4. Statechart Integration

Since the project uses clj-statecharts extensively:

- Detect statechart instances via `statecharts.types/statechart?`
- Show statechart diagram in browser
- Allow live state transitions from code browser

### 5. Performance Optimization

Scittle runtime introspection can be slow:

- Cache introspection results per namespace
- Implement lazy loading for large projects
- Add progress indicators for long scans

---

## Testing Strategy

### Unit Tests

```clojure
;; test/code_browser/sources/scittle_test.cljs
(ns code-browser.sources.scittle-test
  (:require [clojure.test :refer [deftest is testing]]
            [code-browser.sources.scittle :as scittle]))

(deftest detect-runtime-test
  (testing "Detects Scittle runtime"
    (is (= :scittle (:type (scittle/detect-runtime test-eval-fn))))))

(deftest list-namespaces-test
  (testing "Lists Scittle namespaces"
    (let [namespaces (scittle/list-namespaces test-eval-fn {})]
      (is (sequential? namespaces))
      (is (some #(= "cljs.core" (:name %)) namespaces)))))
```

### Integration Tests

```clojure
;; test/integration/scittle_browser_test.cljs
(ns integration.scittle-browser-test
  (:require [clojure.test :refer [deftest is testing]]
            [browser.scittle-browser :as scittle]
            [sente-browser.test-helpers :as helpers]))

(deftest connect-and-browse-test
  (testing "Connect to Scittle runtime and browse"
    (let [result (scittle/load-scittle-project! "localhost" 7888
                                                 :project-name "test")]
      (is (:success result))
      (is (contains? @scittle/!scittle-projects "test"))
      (scittle/unload-scittle-project! "test")
      (is (not (contains? @scittle/!scittle-projects "test"))))))
```

### Manual Testing

1. Start Scittle browser with nREPL server
2. Connect via `bb nrepl-direct eval` to verify runtime detection
3. Load Scittle project in code browser
4. Browse namespaces and symbols
5. Fetch source code
6. Inspect runtime values

---

## Migration Path

### Step 1: Runtime Detection (Week 1)

- [ ] Add `runtime/scittle.cljs` with detection
- [ ] Test with Scittle 0.7.x
- [ ] Handle version fallbacks

### Step 2: Namespace Introspection (Week 2)

- [ ] Implement `list-namespaces` for Scittle
- [ ] Handle goog namespace
- [ ] Test with cljs.core

### Step 3: Var Introspection (Week 3)

- [ ] Implement `introspect-namespace` for Scittle
- [ ] Handle macros
- [ ] Test with reagent components

### Step 4: Source Fetching (Week 4)

- [ ] Implement `fetch-var-source` for Scittle
- [ ] Handle inline definitions
- [ ] Test with various macro forms

### Step 5: Value Introspection (Week 5)

- [ ] Extend `fetch-var-value` for Scittle
- [ ] Add statechart detection
- [ ] Add reagent detection

### Step 6: UI Integration (Week 6)

- [ ] Create Scittle browser module
- [ ] Add connection panel
- [ ] Test with sente-browser

### Step 7: nREPL Proxy (Week 7)

- [ ] Integrate with nrepl-proxy-server
- [ ] Test routing
- [ ] Performance optimization

---

## Success Criteria

### Phase 1 Complete When:

- [ ] Scittle runtime detection works reliably
- [ ] Namespace listing includes goog namespaces
- [ ] Var introspection handles macros
- [ ] Source fetching works for inline definitions

### Phase 2 Complete When:

- [ ] ScittleSource adapter works end-to-end
- [ ] Datalevin storage works for Scittle metadata
- [ ] Browser UI shows Scittle projects

### Phase 3 Complete When:

- [ ] nREPL proxy routes Scittle queries correctly
- [ ] Performance is acceptable (< 5s for 100 symbols)
- [ ] Documentation is complete

---

## References

- **code-browser-v2**: `modules/code-browser-v2/`
- **Scittle**: https://github.com/scittle/scittle
- **clj-statecharts**: `clj-statecharts/`
- **sente-browser**: `modules/sente-browser/`
- **nrepl-proxy**: `modules/nrepl-proxy-server/`

---

## Open Questions

1. **Should we support multiple Scittle runtimes simultaneously?**
   - Pros: Debug multiple browser tabs
   - Cons: Complex UI, resource usage

2. **How to handle Scittle plugins (e.g., re-frisk)?**
   - May need plugin-specific introspection
   - Could use same pattern as statecharts detection

3. **Should we include Scittle source code in the database?**
   - Pros: Offline browsing
   - Cons: Large database, stale code

4. **How to handle dynamic namespace creation in Scittle?**
   - Scittle can create namespaces at runtime
   - May need periodic rescan or watch mechanism

---

**Next Steps**:

1. Review this plan with the team
2. Create Phase 1 implementation tasks
3. Set up Scittle test environment
4. Begin runtime detection implementation
