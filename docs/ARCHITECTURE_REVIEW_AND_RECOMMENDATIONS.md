# bb-mcp-server Architecture Review & Recommendations

> **Date**: February 12, 2026  
> **Reviewer**: AI Assistant (GitHub Copilot)  
> **Focus**: Code browsing architecture, statechart adoption, communication channels, UI widgets

---

## Executive Summary

This document provides a comprehensive architecture review of the bb-mcp-server project, with a focus on the code browsing module and statechart adoption for managing communication channels and UI widgets.

### Key Findings

1. **Code Browser Architecture**: The existing implementation uses a "Shared State" pattern with atom-sync that introduces scalability issues for multi-user scenarios.

2. **Statechart Adoption**: Statecharts are the **right choice** for lifecycle management (connections, modules, widgets), but should be combined with Reagent atoms for UI state and data.

3. **nREPL Module**: The hardcoded connection type pattern creates circular dependencies; a registry pattern is recommended.

4. **Performance**: Several bottlenecks identified (brute-force LSP, unbounded cache, sequential source fetch).

### Overall Assessment

| Aspect | Assessment | Recommendation |
|--------|------------|----------------|
| Code Browser Architecture | ⚠️ Needs Refactoring | Local-State, Remote-Data Pattern |
| Statechart Adoption | ✅ Excellent | Continue, with hybrid approach |
| nREPL Module | ⚠️ Technical Debt | Registry Pattern for Adapters |
| Performance | ⚠️ Bottlenecks | LRU Cache, Optimized LSP |
| Testing | ✅ Good | Add Property-Based Testing |

---

## 1. Architecture Analysis

### 1.1 Current State

**Architecture Pattern**: Shared State / Collaborative

```
┌─────────────────────────────────────────────────────────────────────┐
│  Current Architecture (Shared State)                               │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Server (Babashka)                                           │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  !code-browser-state (atom)                            │  │  │
│  │  │  - selected-ns                                          │  │  │
│  │  │  - selected-symbol                                      │  │  │
│  │  │  - source-by-var (unbounded cache)                     │  │  │
│  │  │  - projects, namespaces, symbols (metadata)            │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                              │                                │  │
│  │                              ▼                                │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  atom-sync (diff propagation)                          │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Client (Scittle Browser)                                    │  │
│  │  - Synced from server atom                                  │  │
│  │  - Re-render on sync                                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

**Data Flow**:
```
Client Click → Server Event → Server Fetch → Update Atom → Sync → Client Re-render
```

### 1.2 Critical Issues

#### A. Coupled Selection State & "The Phantom Driver"

**Problem**: `selected-ns` and `selected-symbol` are stored in the server-side atom, forcing all connected browsers to view the same thing.

**Impact**:
- User A's selection instantly affects User B's view
- Not suitable for multi-user scenarios or independent tabs
- UI latency due to full server round-trip

**Evidence**:
```clojure
;; Current: Server atom holds selection state
(defonce !code-browser-state
  (atom {:selected-ns nil
         :selected-symbol nil
         :source-by-var {}}))
```

#### B. State Accumulation (Memory Leak)

**Problem**: The server atom acts as an unbounded cache.

**Impact**:
- `:source-by-var` map accumulates every file ever viewed
- Server memory usage grows indefinitely
- New browsers receive entire history of every file ever browsed

**Evidence**:
```clojure
;; Current: Unbounded cache
(defonce !code-browser-state
  (atom {:source-by-var {}}))  ;; Grows forever
```

#### C. Performance: Brute-Force LSP Fetching

**Problem**: The current implementation calls `workspace/symbol` (query "") for every interaction.

**Impact**:
- Fetches **every symbol in the entire project** and filters in memory
- Time complexity is **O(Project Size)** for every click
- Should be **O(File Size)**

**Evidence**:
```clojure
;; Current: Fetches all symbols for every click
(workspace/symbol "")  ;; O(n) where n = total symbols in project
```

---

## 2. Recommended Architecture

### 2.1 Local-State, Remote-Data Pattern

**New Architecture**:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Recommended Architecture (Local State + RPC)                      │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Server (Babashka)                                           │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  !project-metadata (atom)                              │  │  │
│  │  │  - projects, namespaces, symbols (metadata only)       │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                              │                                │  │
│  │                              ▼                                │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  RPC Handlers                                          │  │  │
│  │  │  - get-source {:file "..."}                            │  │  │
│  │  │  - get-symbols {:file "..."}                           │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                              │                                │  │
│  │                              ▼                                │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  File Watcher → Broadcast Invalidation Signal          │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Client (Scittle Browser)                                    │  │
│  │  ┌────────────────────────────────────────────────────────┐  │  │
│  │  │  !ui-state (Reagent atom)                              │  │  │
│  │  │  - selected-ns (ephemeral)                             │  │  │
│  │  │  - selected-symbol (ephemeral)                         │  │  │
│  │  │  - cache (local, LRU)                                  │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                              │                                │  │
│  │  ┌───────────────────────────┴────────────────────────────┐  │  │
│  │  │  RPC + Signal & Refetch Pattern                        │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

**Data Flow**:
```
Client Click → Update Local UI (instant) → RPC Fetch → Update Local Cache → Render

Server Watcher → File Change → Broadcast Invalidation → Client Re-fetch (only if displaying)
```

### 2.2 Implementation Plan

#### Step 1: Decouple Selection State

**Client (`code_browser.cljs`)**:
```clojure
(ns code-browser.ui
  (:require [reagent.core :as r]))

;; Client holds ephemeral UI state
(defonce !ui-state
  (r/atom {:selected-ns nil
           :selected-symbol nil
           :cache {}}))

;; On selection, update local state immediately (instant UI feedback)
(defn select-namespace [ns-name]
  (swap! !ui-state assoc :selected-ns ns-name)
  ;; Trigger RPC fetch if not cached
  (fetch-symbols-for-ns ns-name))
```

**Server**:
```clojure
;; Server atom only stores metadata, not ephemeral UI state
(defonce !project-metadata
  (atom {:projects []
         :namespaces {}
         :symbols {}}))
```

#### Step 2: RPC for Heavy Data

**Server Handlers**:
```clojure
;; modules/code-browser-v2/src/code_browser/rpc.clj
(ns code-browser.rpc
  (:require [bb-mcp-server.protocol.message :as msg]))

(defn get-source [{:keys [file]}]
  (let [source (fetch-source file)]
    {:status 200
     :content source}))

(defn get-symbols [{:keys [file]}]
  (let [symbols (fetch-symbols file)]
    {:status 200
     :symbols symbols}))
```

**Client RPC Calls**:
```clojure
;; Client requests data explicitly via RPC
(defn fetch-symbols-for-ns [ns-name]
  (let [result (rpc/call :code-browser/get-symbols {:ns ns-name})]
    (when (= 200 (:status result))
      (swap! !ui-state assoc-in [:cache ns-name :symbols] (:symbols result)))))
```

#### Step 3: Signal & Refetch Pattern

**Server (Watcher)**:
```clojure
;; File watcher detects changes
(defn on-file-change [event]
  (when (file-being-displayed? (:path event))
    ;; Broadcast simple invalidation event
    (atom-sync/broadcast!
      [:code-browser/invalidated {:uri (:uri event)}])))
```

**Client (Reactive)**:
```clojure
;; Client listens for invalidation
(defn on-invalidation [{:keys [uri]}]
  (if (currently-displaying-file? uri)
    ;; Trigger new RPC fetch immediately
    (fetch-source-for-file uri)
    ;; Clear local cache for that file (lazy update)
    (swap! !ui-state update :cache dissoc uri)))
```

#### Step 4: Optimize LSP Integration

**Replace `workspace/symbol`**:
```clojure
;; OLD: Fetches all symbols in project
(workspace/symbol "")  ;; O(n)

;; NEW: Fetch symbols only for target file
(textDocument/documentSymbol {:textDocument {:uri file-uri}})  ;; O(file size)
```

**Retain clj-kondo for Rich Metadata**:
```clojure
;; Keep shell-out to clj-kondo CLI for metadata like :defined-by
;; This is pragmatic and works well with Babashka
(sh "clj-kondo" "--lint" file "--format" "json")
```

---

## 3. Statechart Adoption Assessment

### 3.1 Current Statechart Usage

**Implemented Statecharts**:

| Statechart | States | File | Status |
|------------|--------|------|--------|
| Local nREPL Server | 5 (stopped/starting/running/stopping/error) | `mcp_nrepl/state/local_nrepl_server.clj` | ✅ Done (v1.21.0) |
| Browser Connection (client) | 6 (idle/connecting/ws-open/connected/disconnected/reconnecting) | `browser/bootstrap_client.cljs` | ✅ Done (v1.23.0) |
| Browser Connection (server) | 4 (pending-validation/validated/validation-failed/disconnected) | `sente_browser/server.clj` | ✅ Done (v1.24.0) |

### 3.2 Statechart Benefits Observed

| Benefit | Evidence |
|---------|----------|
| **Pure Transition Tests** | 70 tests with `{:exec false}`, no I/O, no mocking |
| **Explicit Transitions** | Can't stop when already stopped |
| **Telemetry** | Every transition logs from/to/event |
| **Static Validation** | `bb statechart:validate` confirms 0 errors, 0 warnings |

### 3.3 Statechart Adoption for Communication Channels & UI Widgets

**Assessment: YES, statecharts are the right choice** - with caveats

#### Why Statecharts Work Well

| Use Case | Statechart Fit | Reason |
|----------|---------------|--------|
| **Connection Lifecycle** | ✅ Excellent | Clear states, well-defined transitions, terminal states |
| **Module Lifecycle** | ✅ Excellent | stopped→starting→running→stopping pattern |
| **Widget Lifecycle** | ✅ Good | created→loading→loaded→error→closed |
| **AI Orchestrator** | ✅ Good | instance states (idle/waiting/accumulating/completed) |

#### Statechart Pattern for UI Widgets

```clojure
;; GOOD: Statechart for lifecycle, Reagent for data
(def widget-lifecycle
  (fsm/machine
    {:id :code-browser-widget
     :initial :created
     :context {:widget-id nil :data nil :error nil}
     :states
     {:created   {:on {:load {:target :loading}}}
      :loading   {:entry [(assign assign-widget-id)]
                  :on {:loaded {:target :loaded}
                       :error {:target :error}}}
      :loaded    {:on {:close {:target :closed}}}
      :error     {:on {:retry {:target :loading}}}
      :closed    {}}}))

;; Reagent atom for widget data (not in statechart)
(defonce !widget-data
  (r/atom {:selected-ns nil
           :selected-symbol nil
           :source-code nil
           :layout {:x 100 :y 100 :w 400 :h 300}}))
```

### 3.4 Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Pure Event Sourcing** | Overkill for current scale; statecharts provide similar benefits with less complexity |
| **Redux-like Reducer Tree** | More complex than statecharts for lifecycle management |
| **CRDTs for Sync** | Not needed; single-server, multi-client sync is manageable with atom-sync |
| **Actor Model** | Overkill; current protocol-based approach is sufficient |

### 3.5 Hybrid Approach Recommendation

```
┌─────────────────────────────────────────────────────────────────────┐
│  Hybrid State Management                                            │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Statecharts (Lifecycle Management)                          │  │
│  │  - Connection states (idle/connecting/connected/disconnected)│  │
│  │  - Module states (stopped/starting/running/stopping)         │  │
│  │  - Widget states (created/loading/loaded/error/closed)       │  │
│  │  - AI Instance states (idle/waiting/accumulating/completed)  │  │
│  │                                                                │  │
│  │  Benefits: Validation, Observability, Testability            │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  Reagent Atoms (UI State & Data)                             │  │
│  │  - Selected namespace/symbol                                  │  │
│  │  - Widget layout (position, size, open/closed)               │  │
│  │  - Cache (fetched source code, symbol lists)                 │  │
│  │  - User preferences                                           │  │
│  │                                                                │  │
│  │  Benefits: Reactivity, Performance, Flexibility              │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

**Why This Hybrid Works**:

1. **Statecharts** handle *what transitions are valid* (validation, testing)
2. **Reagent Atoms** handle *current data* (reactivity, performance)
3. **Clear Boundary**: Statechart = lifecycle, Reagent = state

---

## 4. nREPL Module Architecture

### 4.1 Current Pattern: Hardcoded Connection Types

**Issue**:
```clojure
;; modules/nrepl/src/nrepl/state/messages.clj
(defn handle-message [conn message]
  (case (:type conn)
    :browser (adapt-browser...)  ;; Hard dependency
    :socket  (adapt-socket...)))
```

**Problem**: Creates circular dependency - `nrepl` module now has hardcoded logic for `sente-browser`

### 4.2 Recommended: Registry Pattern for Connection Adapters

```clojure
;; modules/nrepl/src/nrepl/state/registry.clj
(ns nrepl.state.registry
  "Registry for connection adapters.")

(defonce !connection-adapters (atom {}))

(defn register-adapter! [type adapter-fn]
  "Register a connection adapter."
  (swap! !connection-adapters assoc type adapter-fn))

(defn get-adapter [type]
  "Get a registered connection adapter."
  (get @!connection-adapters type))

(defn handle-message [conn message]
  "Handle a message using the appropriate adapter."
  (let [adapter (get-adapter (:type conn))]
    (if adapter
      (adapter conn message)
      (default-adapter conn message))))
```

**Usage**:
```clojure
;; modules/sente-browser/src/sente_browser/core.clj
(defn start [_deps config]
  ;; Register browser adapter on startup
  (nrepl-registry/register-adapter! :browser browser-adapter-fn)
  ;; ... rest of start logic
  )

;; modules/nrepl/src/nrepl/state/messages.clj
(defn handle-message [conn message]
  (let [adapter (nrepl-registry/get-adapter (:type conn))]
    (if adapter
      (adapter conn message)
      (default-adapter conn message))))
```

**Benefits**:
- `nrepl` module unaware of `sente-browser` internals
- Easy to add new connection types (proxy, remote, etc.)
- Clean separation of concerns

---

## 5. Performance Optimization

### 5.1 Current Bottlenecks

| Bottleneck | Impact | Fix |
|------------|--------|-----|
| **Brute-Force LSP** | High | Use `textDocument/documentSymbol` instead of `workspace/symbol ""` |
| **Unbounded Cache** | High | Implement LRU cache with eviction policy |
| **Sequential Source Fetch** | Medium | Batch source fetches |
| **No Query Caching** | Medium | Cache symbol lists per file |

### 5.2 Recommended Optimizations

#### LRU Cache for Source Code

```clojure
;; Use clojure.core.cache for LRU
(require '[clojure.core.cache :as cache])

(defonce !source-cache
  (atom (cache/lu-cache-factory :threshold 100)))

(defn get-source [file]
  (if-let [cached (@!source-cache file)]
    cached
    (let [source (fetch-source file)]
      (swap! !source-cache cache/lookup-insert file source)
      source)))
```

#### Batch Source Fetches

```clojure
(defn fetch-sources-batch [files]
  (if (seq files)
    (let [results (pmap fetch-source files)]
      (zipmap files results))
    {}))
```

#### Query Caching

```clojure
(defonce !query-cache
  (atom {}))

(defn get-symbols-for-file [file]
  (if-let [cached (@!query-cache file)]
    cached
    (let [symbols (fetch-symbols file)]
      (swap! !query-cache assoc file symbols)
      symbols)))
```

---

## 6. Telemetry & Observability

### 6.1 Current Pattern: Structured Logging

**Strengths**:
- ✅ Level-based filtering (error/warn/info/debug/trace)
- ✅ Structured `:data` field for queryability
- ✅ Event ID naming with `::keyword`

### 6.2 Recommendations

1. **Add Request Tracing**
   ```clojure
   ;; Add trace-id to all log entries for a request
   (log/log! {:level :info
              :id ::request-started
              :msg "Code browser request started"
              :data {:trace-id trace-id
                     :method :get-source
                     :client client-id}})
   ```

2. **Add Metrics**
   - Track transition durations
   - Track RPC call latencies
   - Track cache hit/miss rates

3. **Add Health Check Endpoints**
   ```clojure
   ;; /api/health/statecharts
   {:statecharts {:local-nrepl-server :ok
                  :browser-connection :ok
                  :module-system :ok}}
   ```

---

## 7. Error Handling & Recovery

### 7.1 Current Pattern: Try/Catch with Logging

**Issues**:
- No automatic retry mechanism
- No circuit breaker pattern
- No graceful degradation

### 7.2 Recommendations

1. **Retry with Exponential Backoff**
   ```clojure
   (defn retry-with-backoff
     [f max-retries base-delay]
     (loop [attempt 0]
       (try
         (f)
         (catch Exception e
           (if (< attempt max-retries)
             (do (Thread. sleep (* base-delay (Math/pow 2 attempt)))
                 (recur (inc attempt)))
             (throw e))))))
   ```

2. **Circuit Breaker**
   ```clojure
   ;; modules/nrepl/src/nrepl/state/circuit_breaker.clj
   (defonce !circuit-breakers (atom {}))

   (defn call-with-circuit-breaker
     [name f]
     (let [cb (get @!circuit-breakers name)]
       (if (:open cb)
         (throw (ex-info "Circuit breaker open" {:circuit name}))
         (try
           (let [result (f)]
             (reset! (:successes cb) (inc @(:successes cb)))
             result)
           (catch Exception e
             (reset! (:failures cb) (inc @(:failures cb)))
             (when (> @(:failures cb) 5)
               (reset! (:open cb) true))
             (throw e))))))
   ```

3. **Graceful Degradation**
   - If Datalevin DB unavailable, serve from cache
   - If LSP unavailable, serve from clj-kondo cache
   - If source fetch fails, show metadata only

---

## 8. Testing Strategy

### 8.1 Current Pattern: Unit Tests + Integration Tests

**Recommendations**:

1. **Property-Based Testing**
   ```clojure
   ;; Test statechart transitions with random event sequences
   (deftest statechart-properties
     (is (valid-statechart-transitions machine initial-state 100)))
   ```

2. **Contract Testing**
   - Test provider contracts (AI orchestrator)
   - Test protocol implementations
   - Test nREPL message formats

3. **Chaos Testing**
   - Random connection drops
   - Delayed responses
   - Resource exhaustion

---

## 9. Implementation Priority Matrix

### Architecture Improvements

| Priority | Improvement | Effort | Impact | Status |
|----------|-------------|--------|--------|--------|
| **1** | Local-State, Remote-Data Pattern | High | High | 📋 Planned |
| **2** | Registry Pattern for Connection Adapters | Medium | High | 📋 Planned |
| **3** | Hybrid State Management (Statecharts + Reagent) | Medium | High | ✅ Done |
| **4** | Request Tracing & Metrics | Low | Medium | 📋 Planned |
| **5** | LRU Cache for Source Code | Low | High | 📋 Planned |

### Performance Optimizations

| Priority | Improvement | Effort | Impact | Status |
|----------|-------------|--------|--------|--------|
| **1** | Optimize LSP Integration | Medium | High | 📋 Planned |
| **2** | LRU Cache for Source Code | Low | High | 📋 Planned |
| **3** | Batch Source Fetches | Low | Medium | 📋 Planned |
| **4** | Query Caching | Low | Medium | 📋 Planned |

### Testing Improvements

| Priority | Improvement | Effort | Impact | Status |
|----------|-------------|--------|--------|--------|
| **1** | Property-Based Testing | Medium | Medium | 📋 Planned |
| **2** | Contract Testing | Medium | Medium | 📋 Planned |
| **3** | Chaos Testing | High | Low | 📋 Future |

---

## 10. Conclusion

### Summary of Recommendations

1. **Adopt Local-State, Remote-Data Pattern** for code browser
   - Decouple selection state to client
   - Use RPC for heavy data
   - Implement signal & refetch pattern

2. **Continue Statechart Adoption** for lifecycle management
   - Statecharts handle *valid transitions*
   - Reagent atoms handle *current data*
   - Hybrid approach provides best of both worlds

3. **Refactor nREPL Module** to use registry pattern
   - Eliminate hardcoded connection types
   - Enable easy addition of new connection types

4. **Optimize Performance**
   - Replace brute-force LSP with file-specific queries
   - Implement LRU cache for source code
   - Add query caching

5. **Enhance Observability**
   - Add request tracing
   - Add metrics
   - Add health check endpoints

### Final Verdict

| Aspect | Verdict |
|--------|---------|
| **Code Browser Architecture** | ⚠️ Needs Refactoring (Local-State, Remote-Data) |
| **Statechart Adoption** | ✅ Excellent (Continue with hybrid approach) |
| **nREPL Module** | ⚠️ Technical Debt (Registry Pattern) |
| **Performance** | ⚠️ Bottlenecks (Optimize LSP, Cache) |
| **Testing** | ✅ Good (Add Property-Based Testing) |

**Overall Assessment**: The architecture is solid and well-designed, with statecharts providing excellent value for lifecycle management. The main areas for improvement are in the code browser's data flow and the nREPL module's adapter pattern.

---

## Appendix A: Statechart Conventions

### Current Conventions (from `STATECHARTS_REFERENCE.md`)

1. **Separate Machine Config from Compiled Machine**
   ```clojure
   (def my-machine-config {...})
   (def my-machine (fsm/machine my-machine-config))
   ```

2. **Telemetry on Every Transition**
   ```clojure
   (defn- transition! [event]
     (let [from-state (:_state @!state)]
       (swap! !state #(fsm/transition my-machine % event))
       (let [to-state (:_state @!state)]
         (log/log! {:level :info :id ::transition
                    :msg (str "Transition: " (name from-state) " -> " (name to-state))
                    :data {:from from-state :to to-state :event (:type event)}}))))
   ```

3. **Named Assign Functions**
   ```clojure
   (defn assign-config [ctx event]
     (assoc ctx :config (:config event) :error nil))
   ```

4. **Always Use Longform `{:target :state}`**
   ```clojure
   {:on {:stop {:target :stopping}}}
   ```

5. **Name Terminal States Clearly**
   - Recognized terminal names: `disconnected`, `terminal`, `done`, `final`, `completed`, `finished`, `closed`, `destroyed`

6. **Effect Separation Pattern**
   ```clojure
   (defn start-server! [config]
     (transition! {:type :start :config config})  ;; stopped -> starting
     (try
       (let [result (do-actual-start! config)]
         (transition! {:type :started ...})       ;; starting -> running
         result)
       (catch Exception e
         (transition! {:type :failed :error ...}) ;; starting -> error
         (throw e))))
   ```

---

## Appendix B: References

- **code-browser-v2**: `modules/code-browser-v2/`
- **clj-statecharts**: `clj-statecharts/`
- **sente-browser**: `modules/sente-browser/`
- **nREPL Protocol**: `src/bb_mcp_server/nrepl_direct/client.clj`
- **AI Orchestrator**: `modules/ai-orchestrator/`

---

**Document Status**: Complete  
**Last Updated**: February 12, 2026  
**Next Review**: After Phase 1 implementation
