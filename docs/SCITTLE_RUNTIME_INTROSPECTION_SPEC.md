# Scittle Runtime Introspection Technical Specification

> **Document**: Detailed technical specification for Scittle runtime code browsing  
> **Status**: Draft - Ready for Implementation  
> **Date**: February 12, 2026

---

## 1. Overview

This document specifies the implementation of code browsing capabilities for Scittle (ClojureScript running in browser via SCI) runtimes, extending the existing bb-mcp-server code-browser-v2 module.

### 1.1 Goals

1. **Runtime Detection**: Identify Scittle vs Babashka vs JVM Clojure
2. **Namespace Introspection**: List and query Scittle namespaces
3. **Var Introspection**: Query public vars, macros, and metadata
4. **Source Fetching**: Retrieve source code for vars
5. **Value Introspection**: Inspect runtime values (atoms, reagent, statecharts)
6. **Integration**: Work seamlessly with existing nREPL infrastructure

### 1.2 Constraints

- **Scittle Limitations**: No JVM reflection, limited `clojure.repl` support
- **Browser Context**: Must use nREPL protocol for all introspection
- **Performance**: Introspection should complete in < 5 seconds for typical projects
- **Compatibility**: Support Scittle 0.7.x and future versions

---

## 2. Architecture

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Scittle Code Browser                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    Runtime Layer                             │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │  Detect      │  │  List        │  │  Introspect    │   │  │
│  │  │  Runtime     │  │  Namespaces  │  │  Namespace     │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
│  │  │  Fetch Var   │  │  Fetch Var   │  │  Check         │   │  │
│  │  │  Source      │  │  Value       │  │  Fingerprint   │   │  │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    Source Adapter Layer                      │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  ScittleSource (implements IProjectSource)             │  │  │
│  │  │  - scan-project()                                      │  │  │
│  │  │  - fetch-source()                                      │  │  │
│  │  │  - watch!() / unwatch!()                               │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    Database Layer                            │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  Datalevin (URI-centric schema)                        │  │  │
│  │  │  - Projects, Namespaces, Symbols, Aliases, Refers      │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    Browser UI Layer                          │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  scittle_browser.cljs (Reagent components)             │  │  │
│  │  │  - Connection panel, Project list, Symbol browser      │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. User clicks "Connect to Scittle" in browser UI                  │
│    └─> browser.scittle-browser/load-scittle-project!              │
│        └─> code-browser.sources.scittle/create-scittle-source     │
│            └─> Creates ScittleSource record                       │
│                                                                    │
│ 2. Code browser calls scan-project on ScittleSource                │
│    └─> runtime/detect-runtime (Scittle)                           │
│        └─> eval-fn: "(exists? scittle)"                           │
│        └─> Returns {:type :scittle :version "0.7.28"}            │
│                                                                    │
│ 3. Batch introspect all namespaces                                 │
│    └─> runtime/batch-introspect :scittle                          │
│        └─> eval-fn: "(.-namespaces goog.global)"                  │
│        └─> Returns [{:name "cljs.core" :doc "..."} ...]          │
│                                                                    │
│ 4. For each namespace, introspect vars                            │
│    └─> runtime/introspect-namespace :scittle                      │
│        └─> eval-fn: "(ns-interns 'cljs.core)"                     │
│        └─> Returns [{:name "map" :arglists "..." :doc "..."} ...]│
│                                                                    │
│ 5. Build entities and transact to Datalevin                       │
│    └─> code-browser.db.datalevin/transact!                       │
│        └─> Project, Namespaces, Symbols, Aliases, Refers         │
│                                                                    │
│ 6. Browser UI queries database and displays results               │
│    └─> code-browser.handlers/query-projects                      │
│        └─> Datalog query to Datalevin                            │
│        └─> Returns [{:uri/project "scittle-server" ...}]         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Runtime Detection

### 3.1 Detection Strategy

Scittle can be detected via multiple indicators:

1. **Global Object**: `js/scittle` exists
2. **Namespace Registry**: `goog.global.CLOSURE_UNCOMPILED_NAMESPACES` exists
3. **Version**: `scittle/version` provides version string
4. **Environment**: `js/window` exists (browser context)

### 3.2 Implementation

```clojure
;; code-browser.sources.runtime/scittle.cljs
(ns code-browser.sources.runtime.scittle
  (:require [code-browser.sources.runtime :as runtime]
            [taoensso.trove :as log]))

(defmethod runtime/detect-runtime :scittle
  [_ eval-fn _opts]
  (log/log! {:level :debug
             :id ::detect-scittle
             :msg "Detecting Scittle runtime"})
  (let [result (eval-fn
                "(let [scittle? (exists? scittle)
                       goog? (exists? js/goog)
                       window? (exists? js/window)]
                   (if scittle?
                     {:type :scittle
                      :version (str scittle/version)
                      :browser? (boolean window?)
                      :goog? (boolean goog?)}
                     {:type :jvm-clojure
                      :version (System/getProperty \"java.version\")}))")]
    (if (map? result)
      (do
       (log/log! {:level :info
                  :id ::scittle-detected
                  :msg "Scittle runtime detected"
                  :data result})
       (update result :type keyword))
      (do
       (log/log! {:level :warn
                  :id ::scittle-detect-failed
                  :msg "Failed to detect Scittle, defaulting to JVM Clojure"
                  :data {:result result}})
       {:type :jvm-clojure :version "unknown"}))))
```

### 3.3 Fallback Strategy

If Scittle detection fails:

1. Try `:default` method (Babashka/JVM Clojure)
2. Log warning with detection result
3. Allow user to override via config

---

## 4. Namespace Introspection

### 4.1 Scittle Namespace Registry

Scittle maintains a registry of loaded namespaces in `goog.global.CLOSURE_UNCOMPILED_NAMESPACES`:

```javascript
// Scittle internal structure
goog.global.CLOSURE_UNCOMPILED_NAMESPACES = {
  "cljs.core": {
    docstring: "Core namespace...",
    vars: {...},
    requires: [...],
    uses: [...]
  },
  "reagent.core": {...},
  ...
}
```

### 4.2 Implementation

```clojure
(defmethod runtime/list-namespaces :scittle
  [_ eval-fn opts]
  (log/log! {:level :debug
             :id ::list-namespaces-scittle
             :msg "Listing Scittle namespaces"})
  (let [patterns (or (:exclude-patterns opts)
                     runtime/default-exclude-patterns)
        code "(let [namespaces (js->clj (.-namespaces goog.global) :keywordize-keys true)
                    sorted (sort-by str (keys namespaces))]
           (vec (for [ns-name sorted]
                  {:name ns-name
                   :doc (str (get-in namespaces [ns-name :docstring]))})))"
        result (eval-fn code)]
    (if (sequential? result)
      (do
       (log/log! {:level :info
                  :id ::namespaces-listed
                  :msg "Scittle namespaces listed"
                  :data {:count (count result)}})
       (filterv (fn [ns-map]
                  (not (runtime/excluded-ns? (:name ns-map) patterns)))
                result))
      (do
       (log/log! {:level :warn
                  :id ::namespace-list-failed
                  :msg "Failed to list Scittle namespaces"
                  :data {:result result}})
       []))))
```

### 4.3 Exclusion Patterns

Default patterns to exclude:

```clojure
(def default-exclude-patterns
  [#"clojure\.spec\..*"
   #"clojure\.core\.specs\..*"
   #"nrepl\..*"
   #"borkdude\..*"
   #"sci\..*"
   #"edamame\..*"
   #"goog\..*"])  ;; Exclude Closure Library internals
```

---

## 5. Var Introspection

### 5.1 Scittle Var Metadata

Scittle vars have different metadata than JVM Clojure:

- `:scittle/macros` - Scittle-specific macro flag
- `:scittle/inline` - Inline function flag
- `:scittle/macros` - Macro flag (in addition to `:macro`)

### 5.2 Implementation

```clojure
(defmethod runtime/introspect-namespace :scittle
  [_ eval-fn ns-name _opts]
  (log/log! {:level :debug
             :id ::introspect-namespace-scittle
             :msg "Introspecting Scittle namespace"
             :data {:ns ns-name}})
  (let [code (str "(let [ns-sym (symbol \"" ns-name "\")]
                     (vec (for [[sym v] (sort-by key (ns-interns ns-sym))
                                :let [m (meta v)]]
                            {:name (str sym)
                             :arglists (when (:arglists m) (pr-str (:arglists m)))
                             :doc (:doc m)
                             :macro (or (:macro m) (boolean (:scittle/macros m)))
                             :private (:private m)
                             :dynamic (:dynamic m)
                             :scittle/inline (:scittle/inline m)})))")
        result (eval-fn code)]
    (if (sequential? result)
      (do
       (log/log! {:level :info
                  :id ::namespace-introspected
                  :msg "Scittle namespace introspected"
                  :data {:ns ns-name :var-count (count result)}})
       result)
      (do
       (log/log! {:level :warn
                  :id ::namespace-introspect-failed
                  :msg "Failed to introspect Scittle namespace"
                  :data {:ns ns-name :result result}})
       []))))
```

### 5.3 Macro Detection

Scittle macros are detected via:

1. `:macro` metadata (standard Clojure)
2. `:scittle/macros` metadata (Scittle-specific)
3. Function name ends with `!` or `?` (convention)

---

## 6. Source Fetching

### 6.1 Scittle Source Limitations

Scittle has limited source retrieval:

- `clojure.repl/source-fn` works for most functions
- Inline definitions may not have source
- Macros may not expand correctly

### 6.2 Implementation

```clojure
(defmethod runtime/fetch-var-source :scittle
  [_ eval-fn ns-name var-name _opts]
  (log/log! {:level :debug
             :id ::fetch-var-source-scittle
             :msg "Fetching Scittle var source"
             :data {:ns ns-name :var var-name}})
  (let [code (str "(try (clojure.repl/source-fn '" ns-name "/" var-name ")
                     (catch Exception e
                            (let [v (resolve (symbol \"" ns-name "/" var-name "\"))]
                              (when v
                                (str \"(def \" '~v \" ...inline definition...)\")))))")]
    (-> (eval-fn code)
        (when (and (string? %) (seq %))
          (hash-map :source %)
          (log/log! {:level :info
                     :id ::source-fetched
                     :msg "Scittle var source fetched"
                     :data {:ns ns-name :var var-name :length (count %)}})
          (or (log/log! {:level :warn
                         :id ::source-fetch-failed
                         :msg "Failed to fetch Scittle var source"
                         :data {:ns ns-name :var var-name}})))))
```

### 6.3 Fallback Strategy

If source fetching fails:

1. Return inline definition string
2. Log warning
3. Allow user to view metadata instead

---

## 7. Value Introspection

### 7.1 Scittle Value Types

Scittle values include:

- **Atoms**: `cljs.core/Atom`
- **Reagent Components**: `reagent.core/ILifecycle`
- **Statecharts**: `statecharts.types/Statechart`
- **Services**: `statecharts.service/IService`
- **Stores**: `statecharts.store/IStore`

### 7.2 Implementation

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
           is-service? (boolean (try (require 'statecharts.service)
                                     (let [proto-var (resolve 'statecharts.service/IService)]
                                       (and proto-var (satisfies? @proto-var val)))
                                     (catch Exception _ false)))
           is-store? (boolean (try (require 'statecharts.store)
                                   (let [proto-var (resolve 'statecharts.store/IStore)]
                                     (and proto-var (satisfies? @proto-var val)))
                                   (catch Exception _ false)))
           ;; Statechart info
           sc-info (when is-statechart?
                     {:id (:id val)
                      :initial (:initial val)
                      :compiled? (boolean
                                  (try ((resolve 'statecharts.types/compiled?) val)
                                       (catch Exception _ false)))
                      :states (vec (keys (:states val)))})
           ;; Service info
           svc-info (when is-service?
                      (try
                        (let [state-fn (resolve 'statecharts.service/state)
                              svc-state (state-fn val)]
                          {:current-state (:_state svc-state)
                           :context (dissoc svc-state :_state :_actions)})))
           ;; Store info
           store-info (when is-store?
                        (try
                          (cond
                            (contains? val :states*)
                            {:store-type \"many\"
                             :id-key (:id val)
                             :instance-count (count @(:states* val))
                             :instance-ids (vec (keys @(:states* val)))}
                            (contains? val :state*)
                            {:store-type \"single\"
                             :current-state (:_state @(:state* val))})
                          (catch Exception _ nil)))
           ;; Predicate probing
           preds (vec (keep (fn [[pred-name pred-fn]]
                              (try (when (pred-fn val) pred-name)
                                   (catch Exception _ nil)))
                            [[\"nil?\" nil?]
                             [\"map?\" map?]
                             [\"vector?\" vector?]
                             [\"reagent.core/ILifecycle\" reagent.core/ILifecycle]
                             [\"statecharts.types/statechart?\" statecharts.types/statechart?]
                             ...]))
           ;; Primary type
           vtype (cond
                   is-service? \"service\"
                   is-store? \"store\"
                   is-reagent? \"reagent-component\"
                   is-statechart? \"statechart\"
                   (nil? val) \"nil\"
                   (fn? val) \"function\"
                   (map? val) \"map\"
                   (vector? val) \"vector\"
                   (string? val) \"string\"
                   :else \"other\")
           ;; pprint with truncation
           max-len 4096
           sw (java.io.StringWriter.)
           _ (binding [*print-length* 20 *print-level* 5]
               (clojure.pprint/pprint val sw))
           s (str sw)
           truncated? (> (count s) max-len)
           value-str (if truncated?
                       (str (subs s 0 max-len) \"\\n... (truncated)\")
                       s)
           ;; Var metadata
           vmeta (let [m (meta v)]
                   (into {}
                         (keep (fn [[k v]]
                                 (when (contains?
                                        #{:doc :arglists :file :line :column
                                          :name :ns :macro :private :dynamic
                                          :added :deprecated :tag} k)
                                   [k (if (or (string? v) (number? v)
                                             (boolean? v) (keyword? v) (nil? v))
                                       v
                                       (pr-str v))])))
                         m))
           ;; Value metadata
           val-meta (try
                      (let [m (meta val)]
                        (when (and m (seq m))
                          (let [s (pr-str m)]
                            (when (< (count s) 2000) s))))
                      (catch Exception _ nil))
           ;; Collection count
           cnt (when (counted? val) (count val))]
       {:value-str value-str
        :value-type vtype
        :value-class (str (type val))
        :container-type (when is-atom? \"atom\")
        :predicates preds
        :is-reagent? is-reagent?
        :is-statechart? is-statechart?
        :statechart sc-info
        :is-service? is-service?
        :service svc-info
        :is-store? is-store?
        :store store-info
        :var-meta vmeta
        :value-meta val-meta
        :count cnt
        :truncated? truncated?
        :var-id (System/identityHashCode raw-val)
        :value-id (if is-atom? (System/identityHashCode val) var-id)})))")

(defmethod runtime/fetch-var-value :scittle
  [_runtime-type eval-fn ns-name var-name _opts]
  (log/log! {:level :debug
             :id ::fetch-var-value-scittle
             :msg "Fetching Scittle var value"
             :data {:ns ns-name :var var-name}})
  (let [code (format fetch-var-value-scittle-code ns-name var-name)
        result (eval-fn code)]
    (when (map? result)
      (log/log! {:level :info
                 :id ::var-value-fetched
                 :msg "Scittle var value fetched"
                 :data {:ns ns-name :var var-name :type (:value-type result)}})
      result)))
```

---

## 8. ScittleSource Adapter

### 8.1 Record Definition

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
      (log/log! {:level :info
                 :id ::scan-scittle-complete
                 :msg "Scittle scan complete"
                 :data {:project-name project-name
                        :runtime-type rt-type
                        :namespace-count (count namespaces)
                        :symbol-count (count symbols)}})
      {:project project
       :namespaces namespaces
       :symbols symbols
       :aliases []
       :refers []}))

  (fetch-source [_this uri-string]
    (log/log! {:level :debug
               :id ::fetch-scittle-source
               :msg "Fetching Scittle source"
               :data {:uri uri-string}})
    (let [parsed (uri/parse uri-string)
          ns-name (:uri/namespace parsed)
          sym-name (:uri/symbol parsed)]
      (when (and ns-name sym-name)
        (let [eval-fn (make-eval-fn conn host port)
              rt-type (:type @runtime-info)
              result (runtime/fetch-var-source
                      (or rt-type :scittle)
                      eval-fn ns-name sym-name {})]
          (when result
            {:content (:source result)})))))

  (watch! [_this _callback] nil)
  (unwatch! [_this _handle] nil)

  (source-info [_this]
    {:type :scittle
     :version-type :temporal
     :supports-watch? false
     :description (str "Scittle: " host ":" port)}))
```

### 8.2 Constructor

```clojure
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

---

## 9. Integration with Existing Code

### 9.1 handlers.clj Updates

```clojure
(ns code-browser.handlers
  (:require [code-browser.sources.scittle :as scittle-source]))

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

### 9.2 core.clj Updates

```clojure
(ns code-browser.core
  (:require [code-browser.sources.scittle :as scittle-source]))

(defn add-source!
  "Add a new project source at runtime.
   For :scittle sources: {:type :scittle :host \"localhost\" :port 7888}"
  [{:keys [type path] :as config}]
  (let [source-type (or type (when path :dir))]
    (case source-type
      :scittle
      (if-let [err (validate-scittle-source config)]
        {:success false :error err}
        (register-and-notify! (create-source (assoc config :type :scittle))))
      ;; ... existing cases
      )))
```

---

## 10. Testing Strategy

### 10.1 Unit Tests

```clojure
;; test/code_browser/sources/scittle_test.cljs
(ns code-browser.sources.scittle-test
  (:require [clojure.test :refer [deftest is testing]]
            [code-browser.sources.scittle :as scittle]))

(def test-eval-fn
  (fn [code]
    ;; Mock eval function for testing
    (case code
      "(exists? scittle)" true
      "(str scittle/version)" "0.7.28"
      "(.-namespaces goog.global)" {"cljs.core" {:docstring "Core namespace"}}
      [])))

(deftest detect-runtime-test
  (testing "Detects Scittle runtime"
    (is (= :scittle (:type (scittle/detect-runtime test-eval-fn))))
    (is (= "0.7.28" (:version (scittle/detect-runtime test-eval-fn))))))

(deftest list-namespaces-test
  (testing "Lists Scittle namespaces"
    (let [namespaces (scittle/list-namespaces test-eval-fn {})]
      (is (sequential? namespaces))
      (is (some #(= "cljs.core" (:name %)) namespaces)))))

(deftest create-scittle-source-test
  (testing "Creates ScittleSource"
    (let [source (scittle/create-scittle-source "localhost" 7888
                                                 :project-name "test")]
      (is (= :scittle (:type (scittle/source-info source))))
      (is (= "localhost:7888" (:project-name source))))))
```

### 10.2 Integration Tests

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

---

## 11. Performance Considerations

### 11.1 Optimization Strategies

1. **Caching**: Cache introspection results per namespace
2. **Batching**: Fetch all namespaces in one eval call
3. **Lazy Loading**: Load symbols on-demand, not all at once
4. **Progress Indicators**: Show progress for long scans

### 11.2 Expected Performance

| Operation | Expected Time | Notes |
|-----------|---------------|-------|
| Runtime detection | < 100ms | Single eval call |
| List namespaces | < 500ms | Single eval call |
| Introspect 100 vars | < 2s | Batched eval calls |
| Fetch source for 100 vars | < 5s | Sequential eval calls |
| Full project scan | < 10s | All operations combined |

---

## 12. Future Enhancements

### 12.1 Phase 2 Features

1. **Live Value Updates**: Watch atoms and reagent cursors
2. **Statechart Visualization**: Render statechart diagrams
3. **Macro Expansion**: Show macro expansion results
4. **Dependency Graph**: Visualize namespace dependencies

### 12.2 Phase 3 Features

1. **Scittle Plugin Support**: Integrate with re-frisk, etc.
2. **Source Code Storage**: Store Scittle source in database
3. **Diff View**: Show changes between versions
4. **Search**: Full-text search across all projects

---

## 13. References

- **Scittle**: https://github.com/scittle/scittle
- **code-browser-v2**: `modules/code-browser-v2/`
- **nREPL Protocol**: `src/bb_mcp_server/nrepl_direct/client.clj`
- **clj-statecharts**: `clj-statecharts/`

---

**Document Status**: Draft - Ready for Implementation  
**Next Steps**: Begin Phase 1 implementation (runtime detection)
