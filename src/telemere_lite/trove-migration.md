# Trove Migration Plan for Sente-Lite

## Executive Summary

Migrate sente-lite from hardcoded telemere-lite logging to Trove-based pluggable logging, following the architecture proven by Sente v1.21.0 (released 2025-11-04). This will make sente-lite a true zero-dependency library facade while preserving our robust telemetry infrastructure for development and testing.

**Status**: Phase 1 Complete (2025-11-07) ✅
**Complexity**: Medium
**Timeline**: 2-3 days for Phase 1-2, 1 week for Phase 3
**Breaking Changes**: No (backward compatible)

**Progress**:
- ✅ Phase 1: Trove wrapper added (2025-11-07)
- ⏳ Phase 2: Migrate sente-lite calls (next)
- ⏳ Phase 3: Extract backend (future)

---

## Table of Contents

1. [Why This Migration](#why-this-migration)
2. [What is Trove](#what-is-trove)
3. [Current State Analysis](#current-state-analysis)
4. [Architecture Comparison](#architecture-comparison)
5. [Migration Strategy](#migration-strategy)
6. [Detailed Steps](#detailed-steps)
7. [Code Examples](#code-examples)
8. [Benefits](#benefits)
9. [Risks & Mitigations](#risks--mitigations)
10. [Success Criteria](#success-criteria)

---

## Why This Migration

### The Discovery

On 2025-11-04, Peter Taoussanis released **Sente v1.21.0** with a major breaking change: switching from Timbre to **Trove** for logging. This validates the exact architectural pattern we discussed earlier.

### Problems with Current Approach

**1. Forced Dependency**
```clojure
;; Current: Sente-lite forces telemere-lite on all users
(require '[sente-lite.server :as server])
;; → Implicitly requires telemere-lite + Timbre + Cheshire
```

**2. Monolithic Architecture**
- Telemere-lite: ~890 LOC mixing facade + backend
- Can't swap logging systems
- Users stuck with our choices

**3. Misaligned with Official Sente**
- Official Sente: Uses Trove (0 deps)
- Our Sente-Lite: Uses custom solution
- Makes migration path unclear

### Why Trove is Better

**1. Separation of Concerns**
- **Trove**: Facade (~100 LOC, 0 deps) ← Library uses this
- **Backends**: Implementations ← Users choose

**2. Industry Validation**
- Created by Peter Taoussanis (Sente, Telemere, Timbre author)
- Battle-tested in Sente production
- Proven architecture pattern

**3. Zero Dependencies**
- Sente-lite becomes truly lightweight
- No forced choices on users
- Perfect for library code

---

## What is Trove

### Overview

Trove is a **minimal logging facade** for Clojure/ClojureScript providing:
- Single macro API: `trove/log!`
- Pluggable backends via `trove/*log-fn*`
- Lazy evaluation for performance
- Structured data support
- ~100 LOC, 0 dependencies

### API Example

```clojure
;; Simple usage
(require '[taoensso.trove :as trove])

(trove/log! {:level :info
             :id ::user-login
             :data {:user-id 1234}
             :msg "User logged in"})

;; Backend selection (user's choice)
(trove/set-log-fn! (some-backend/get-log-fn))
```

### How It Works

```
Library Code
    ↓
trove/log! {:level :info, :id ::event, :data {...}}
    ↓
trove/*log-fn* (dynamic var - pluggable)
    ↓
Backend Implementation (user configures)
    ↓
[Filtering] → [Handlers] → [Output]
```

### Lazy Evaluation

Trove automatically delays expensive computations:

```clojure
;; Expensive data only computed if logging enabled
(trove/log! {:level :debug
             :id ::complex-state
             :data (expensive-computation)})  ; Wrapped in delay
```

Backend receives `:lazy_` map and can filter before forcing evaluation.

---

## Current State Analysis

### Logging Call Distribution

| File | Log Calls | Primary Purpose |
|------|-----------|-----------------|
| `server.cljc` | 39 | Connection lifecycle, heartbeat, errors |
| `server_simple.cljc` | 18 | Simplified server events |
| `channels.cljc` | 17 | Channel management, pub/sub |
| `transit_multiplexer.cljc` | 13 | Message multiplexing |
| `wire_multiplexer.cljc` | 11 | Wire format handling |
| `wire_format.cljc` | 9 | Format negotiation |
| **TOTAL** | **107** | - |

### Current API Usage

```clojure
;; Event logging (majority)
(tel/event! ::connection-added {:conn-id conn-id :timestamp ts})

;; Error logging
(tel/error! {:msg "Failed to parse" :error e :data {:raw msg}})

;; Info logging
(tel/log! :info "Server starting" {:port port})
```

### Comparison with Sente v1.21.0

| Aspect | Sente v1.21.0 | Our Sente-Lite |
|--------|---------------|----------------|
| **Log Library** | Trove (0 deps) | Telemere-Lite (Timbre dep) |
| **API** | `trove/log!` | `tel/event!` + `tel/error!` |
| **Call Count** | ~40 calls | ~107 calls |
| **Verbosity** | Conservative | Extensive |
| **ID Format** | `:ns.component/event` | `::event` |
| **Trace Level** | Heavy use (40%) | Not used |

### Log Level Distribution (Sente Pattern)

```
:trace  → Internal flow (40%)        [We don't use]
:debug  → Lifecycle (30%)            [We use heavily]
:info   → Important events (20%)     [We use for everything]
:warn   → Anomalies (5%)             [We use for errors]
:error  → Failures (5%)              [We use extensively]
```

**Takeaway**: We're probably over-logging for production use.

---

## Architecture Comparison

### Current Architecture

```
Sente-Lite Source Code
    ↓
telemere-lite/event!
    ↓
telemere-lite core (hardcoded)
    ↓
[filtering] → [handlers] → [sinks]
    ↓
Output (no choice)
```

**Problems**:
- Sente-lite couples to telemere-lite
- Users can't choose their logging system
- Breaking changes affect everyone

### Target Architecture (Trove-based)

```
Sente-Lite Source Code
    ↓
trove/log! (0 deps facade)
    ↓
trove/*log-fn* (user configures)
    ↓
Backend Choice:
  ├─ telemere-lite-backend (BB + Scittle)
  ├─ official Telemere (JVM)
  ├─ Timbre (JVM)
  ├─ tools.logging (JVM)
  └─ custom implementation
    ↓
[filtering] → [handlers] → [output]
```

**Benefits**:
- Sente-lite has 0 logging deps
- Users choose their backend
- Sente-lite changes don't break user logging
- Official Sente compatibility

---

## Migration Strategy

### Three-Phase Approach

**Phase 1: Add Trove Wrapper (Backward Compatible)**
- Add `log!` wrapper to telemere-lite
- Supports Trove-style calls
- Existing code keeps working
- Timeline: 1-2 hours

**Phase 2: Migrate Sente-Lite Calls (Refactor)**
- Update all logging calls to Trove pattern
- Follow Sente v1.21.0 conventions
- Reduce verbosity (107 → ~60 calls)
- Timeline: 1-2 days

**Phase 3: Extract Backend (Architecture)**
- Split telemere-lite into facade + backend
- Implement `get-log-fn` for Trove
- Support multiple backends
- Timeline: 1 week

### Backward Compatibility Strategy

**Keep existing API working**:
```clojure
;; Old API (still works)
(tel/event! ::connection-added {:conn-id id})

;; New API (Trove pattern)
(trove/log! {:level :debug, :id ::connection-added, :data {:conn-id id}})

;; Both dispatch to same backend
```

---

## Detailed Steps

### Phase 1: Add Trove Wrapper to Telemere-Lite

**Status**: ✅ **COMPLETED** (2025-11-07)
**Implementation**: Modified `signal!` and `log!` macros in `src/telemere_lite/core.cljc` to accept both `:id` (Trove) and `:event-id` (ours).
**Result**: Backward compatible - all existing code works, Trove-style calls now supported.
**Tests**: All existing tests pass (exit code 0).

**Step 1.1: Add Trove Dependency**

Update `deps.edn`:
```clojure
{:deps {com.taoensso/trove {:mvn/version "1.1.0"}}}
```

**Step 1.2: Add `log!` Macro to Telemere-Lite**

In `src/telemere_lite/core.cljc`:
```clojure
(defmacro log!
  "Trove-compatible logging interface.

  Accepts Trove-style map and converts to our internal format.
  Enables gradual migration from tel/* to trove/log! calls."
  [opts]
  `(when *telemetry-enabled*
     (let [opts# ~opts
           level# (:level opts# :info)
           id# (:id opts#)
           msg# (:msg opts#)
           data# (:data opts#)
           error# (:error opts#)]
       (signal! {:level level#
                 :event-id id#
                 :msg msg#
                 :data data#
                 :error error#}))))
```

**Step 1.3: Test Trove-Style Calls**

Create test file `test/telemere_lite/trove_test.cljc`:
```clojure
(ns telemere-lite.trove-test
  (:require [telemere-lite.core :as tel]
            [clojure.test :refer [deftest is testing]]))

(deftest test-trove-compatibility
  (testing "Trove-style log! calls work"
    (tel/set-enabled! true)
    (tel/add-stdout-handler!)

    ;; Trove pattern
    (tel/log! {:level :info
               :id ::test-event
               :data {:key "value"}})

    ;; Should dispatch without error
    (is true)))
```

Run: `bb test/telemere_lite/trove_test.cljc`

**Success Criteria Phase 1**:
- ✅ Trove dependency added
- ✅ `log!` macro implemented
- ✅ Tests pass with Trove-style calls
- ✅ Backward compatible (old API still works)

---

### Phase 2: Migrate Sente-Lite to Trove Pattern

**Step 2.1: Create Migration Template**

Document the conversion pattern:
```clojure
;; BEFORE (our current style)
(tel/event! ::connection-added {:conn-id conn-id :timestamp ts})

;; AFTER (Trove/Sente style)
(trove/log! {:level :debug
             :id :sente-lite.server/connection-added
             :data {:conn-id conn-id :timestamp ts}})
```

**Step 2.2: Update Namespace Imports**

In each sente-lite source file:
```clojure
;; BEFORE
(ns sente-lite.server
  (:require [telemere-lite.core :as tel]))

;; AFTER
(ns sente-lite.server
  (:require [telemere-lite.core :as trove]))  ; Alias for gradual migration
```

**Step 2.3: Migrate by Category**

**A. Connection Lifecycle (Critical - :debug level)**
```clojure
;; server.cljc line 46
;; BEFORE
(tel/event! ::connection-added {:conn-id conn-id :timestamp ts})

;; AFTER
(trove/log! {:level :debug
             :id :sente-lite.server/connection-added
             :data {:conn-id conn-id :timestamp ts}})
```

**B. Server Lifecycle (Important - :info level)**
```clojure
;; server.cljc - server start
;; BEFORE
(tel/event! ::server-starting config)

;; AFTER
(trove/log! {:level :info
             :id :sente-lite.server/server-starting
             :data config})
```

**C. Message Flow (Verbose - :trace level)**
```clojure
;; server.cljc - message routing
;; BEFORE
(tel/event! ::message-routing {:conn-id conn-id :type msg-type})

;; AFTER
(trove/log! {:level :trace  ; Changed to trace!
             :id :sente-lite.server/message-routing
             :data {:conn-id conn-id :type msg-type}})
```

**D. Errors (Critical - :error level)**
```clojure
;; server.cljc - parse errors
;; BEFORE
(tel/error! {:msg "Failed to parse WebSocket message"
             :error e
             :data {:raw-msg msg}})

;; AFTER
(trove/log! {:level :error
             :id :sente-lite.server/parse-error
             :error e
             :data {:raw-msg msg}})
```

**Step 2.4: Reduce Verbosity**

Apply Sente's logging philosophy:
- Remove redundant trace logs (keep only critical paths)
- Consolidate related events
- Target: 107 calls → ~60 calls

**Candidates for removal**:
- Duplicate state tracking
- Overly verbose message flow logs
- Redundant heartbeat pings (keep timeouts only)

**Step 2.5: Update ID Conventions**

Follow Sente's pattern:
```clojure
;; Pattern: :namespace.component/event-type

:sente-lite.server/connection-added
:sente-lite.server/ws-open
:sente-lite.server/send-error
:sente-lite.client/state-changed
:sente-lite.channels/subscription-added
```

**Step 2.6: Test After Migration**

Run full test suite:
```bash
./run_tests.bb
TELEMETRY=1 bb test/scripts/multiprocess/01_basic_multiprocess.bb
```

**Success Criteria Phase 2**:
- ✅ All logging calls use Trove pattern
- ✅ ID conventions match Sente style
- ✅ Log verbosity reduced (~60 calls)
- ✅ All tests pass
- ✅ Telemetry output still works

---

### Phase 3: Extract Telemere-Lite Backend

**Step 3.1: Create Backend Module**

New file: `src/telemere_lite/backend.cljc`

```clojure
(ns telemere-lite.backend
  "Telemere-Lite backend for Trove.

  Provides filtering, handlers, and sinks for BB + Scittle environments
  where official Telemere doesn't work."
  (:require [telemere-lite.core :as tel]))

(defn get-log-fn
  "Return Trove-compatible log function.

  Options:
    :filters    - Namespace and event-id filters
    :handlers   - Handler configuration
    :enabled?   - Enable/disable telemetry

  Example:
    (trove/set-log-fn!
      (get-log-fn {:enabled? true
                   :filters {:ns-allow [\"sente-lite.*\"]}
                   :handlers [:stdout]}))"
  [{:keys [filters handlers enabled?]
    :or {enabled? true handlers [:stdout]}}]

  ;; Configure telemere-lite
  (tel/set-enabled! enabled?)

  (when filters
    (when-let [ns-allow (:ns-allow filters)]
      (tel/set-ns-filter! {:allow ns-allow}))
    (when-let [id-allow (:id-allow filters)]
      (tel/set-id-filter! {:allow id-allow})))

  (doseq [h handlers]
    (case h
      :stdout (tel/add-stdout-handler!)
      :stderr (tel/add-stderr-handler!)
      (when (map? h)
        (tel/add-handler! (:id h) (:fn h) (:opts h)))))

  ;; Return Trove-compatible function
  (fn trove-backend-log-fn [signal-map]
    (tel/log! signal-map)))
```

**Step 3.2: Update Telemere-Lite Core**

Slim down `core.cljc`:
- ✅ Keep: `signal!` macro (internal)
- ✅ Keep: Filtering (ns, event-id, level)
- ✅ Keep: Handler infrastructure
- ✅ Keep: Sinks (console, atom, WebSocket)
- ❌ Remove: Public `event!`, `log!`, etc. (use `log!` from backend)

**Step 3.3: Update Sente-Lite Integration**

In sente-lite source files:
```clojure
;; BEFORE
(ns sente-lite.server
  (:require [telemere-lite.core :as trove]))

;; AFTER
(ns sente-lite.server
  (:require [taoensso.trove :as trove]))  ; Real Trove!
```

**Step 3.4: Update User Configuration**

Users configure backend:
```clojure
;; User's application code
(require '[taoensso.trove :as trove]
         '[telemere-lite.backend :as tl-backend])

;; Configure for BB/Scittle
(trove/set-log-fn!
  (tl-backend/get-log-fn
    {:enabled? true
     :filters {:ns-allow ["sente-lite.*"]}
     :handlers [:stdout]}))
```

**Step 3.5: Provide Multiple Backend Options**

Document backend choices:
```clojure
;; Option 1: Telemere-Lite (BB + Scittle support)
(require '[telemere-lite.backend :as tl])
(trove/set-log-fn! (tl/get-log-fn {...}))

;; Option 2: Official Telemere (JVM only)
(require '[taoensso.trove.telemere :as trove.tel])
(trove/set-log-fn! (trove.tel/get-log-fn))

;; Option 3: Timbre (JVM only)
(require '[taoensso.trove.timbre :as trove.timbre])
(trove/set-log-fn! (trove.timbre/get-log-fn))

;; Option 4: Disable logging
(trove/set-log-fn! nil)
```

**Success Criteria Phase 3**:
- ✅ Backend extracted to separate module
- ✅ Sente-lite uses real Trove (not wrapper)
- ✅ Users can swap backends
- ✅ Tests pass with telemere-lite backend
- ✅ Documentation updated

---

## Code Examples

### Before Migration

```clojure
;; Sente-Lite library code (BEFORE)
(ns sente-lite.server
  (:require [telemere-lite.core :as tel]))

(defn handle-connection [conn-id]
  (tel/event! ::connection-added {:conn-id conn-id})
  ;; ... connection handling
  )

;; User's application (BEFORE)
(require '[sente-lite.server :as server])
;; Automatically gets telemere-lite + Timbre deps
```

### After Migration (Phase 2)

```clojure
;; Sente-Lite library code (AFTER Phase 2)
(ns sente-lite.server
  (:require [telemere-lite.core :as trove]))  ; Wrapper

(defn handle-connection [conn-id]
  (trove/log! {:level :debug
               :id :sente-lite.server/connection-added
               :data {:conn-id conn-id}})
  ;; ... connection handling
  )

;; User's application (AFTER Phase 2)
(require '[sente-lite.server :as server]
         '[telemere-lite.core :as tel])

;; Configure telemetry
(tel/set-enabled! true)
(tel/add-stdout-handler!)
```

### After Migration (Phase 3)

```clojure
;; Sente-Lite library code (AFTER Phase 3)
(ns sente-lite.server
  (:require [taoensso.trove :as trove]))  ; Real Trove!

(defn handle-connection [conn-id]
  (trove/log! {:level :debug
               :id :sente-lite.server/connection-added
               :data {:conn-id conn-id}})
  ;; ... connection handling
  )

;; User's application (AFTER Phase 3)
(require '[sente-lite.server :as server]
         '[taoensso.trove :as trove]
         '[telemere-lite.backend :as tl-backend])

;; Configure logging backend (user's choice!)
(trove/set-log-fn!
  (tl-backend/get-log-fn {:enabled? true
                          :handlers [:stdout]}))
```

---

## Benefits

### For Library (Sente-Lite)

1. **Zero Logging Dependencies**
   - Trove: 0 deps
   - vs Current: Timbre + Cheshire

2. **Aligned with Official Sente**
   - Same logging architecture
   - Easier migration path for Sente users

3. **Flexible for Users**
   - Don't force logging choices
   - Users choose their backend

### For Users

1. **Choice of Backend**
   - Telemere-Lite (BB + Scittle)
   - Official Telemere (JVM)
   - Timbre (JVM)
   - tools.logging (JVM)
   - Custom implementation

2. **No Breaking Changes**
   - Existing code keeps working
   - Opt-in migration

3. **Better Performance**
   - Can disable logging entirely (nil backend)
   - Lazy evaluation prevents waste

### For Development/Testing

1. **Keep Our Infrastructure**
   - Telemere-lite backend for tests
   - Meta-telemetry still works
   - Same observability

2. **Proven Patterns**
   - Learn from Sente v1.21.0
   - Industry-validated approach

---

## Risks & Mitigations

### Risk 1: Breaking Existing Code

**Risk**: Migration changes APIs users depend on

**Mitigation**:
- Phase 1 is backward compatible
- Keep old API working indefinitely
- Document migration path clearly
- Provide both old and new examples

### Risk 2: Increased Complexity

**Risk**: More moving parts (Trove + backend)

**Mitigation**:
- Phase 1-2 don't add complexity
- Phase 3 actually reduces coupling
- Better separation of concerns
- Document architecture clearly

### Risk 3: Testing Overhead

**Risk**: Need to test multiple backends

**Mitigation**:
- Keep telemere-lite as primary test backend
- Add smoke tests for other backends
- CI tests use telemere-lite only

### Risk 4: Performance Regression

**Risk**: Trove adds overhead

**Mitigation**:
- Trove is minimal (~100 LOC)
- Lazy evaluation improves performance
- Can disable entirely (nil backend)
- Benchmark before/after

### Risk 5: User Confusion

**Risk**: More choice = more confusion

**Mitigation**:
- Provide sensible defaults
- Document "happy path" clearly
- Examples for common scenarios
- Migration guide

---

## Success Criteria

### Phase 1 Success

- [ ] Trove dependency added to deps.edn
- [ ] `log!` macro in telemere-lite works with Trove pattern
- [ ] Tests pass with Trove-style calls
- [ ] Old API still works (backward compatible)
- [ ] Documentation updated

### Phase 2 Success

- [ ] All sente-lite logging uses Trove pattern
- [ ] ID conventions match Sente style (`:ns.component/event`)
- [ ] Log verbosity reduced to ~60 calls
- [ ] All tests pass (`./run_tests.bb`)
- [ ] Telemetry demos work (`TELEMETRY=1 bb test/scripts/...`)
- [ ] No linting errors (`clj-kondo`)

### Phase 3 Success

- [ ] Backend extracted to `telemere_lite/backend.cljc`
- [ ] `get-log-fn` implements Trove contract
- [ ] Sente-lite uses real `taoensso.trove`
- [ ] Users can swap backends
- [ ] Tests pass with telemere-lite backend
- [ ] Documentation covers all backend options
- [ ] Migration guide complete

### Overall Success

- [ ] Zero-dependency sente-lite (Trove only)
- [ ] Backward compatible (old code works)
- [ ] Pluggable backends (user choice)
- [ ] Aligned with Sente v1.21.0
- [ ] Maintained observability (meta-telemetry works)
- [ ] All tests pass
- [ ] Documentation complete

---

## Timeline

### Conservative Estimate

- **Phase 1**: 2 hours
  - Add dependency: 10 min
  - Implement wrapper: 1 hour
  - Test: 30 min
  - Document: 20 min

- **Phase 2**: 2 days
  - Plan migration: 2 hours
  - Migrate calls: 4 hours
  - Reduce verbosity: 2 hours
  - Test thoroughly: 2 hours
  - Update docs: 2 hours

- **Phase 3**: 1 week
  - Design backend API: 4 hours
  - Extract backend: 8 hours
  - Update sente-lite: 4 hours
  - Integration testing: 4 hours
  - Documentation: 4 hours
  - Polish: 4 hours

### Aggressive Estimate

- **Phase 1**: 1 hour
- **Phase 2**: 1 day
- **Phase 3**: 3 days

**Total**: 2-3 days (aggressive) to 1.5 weeks (conservative)

---

## Next Steps

1. **Review this plan** - Ensure alignment with goals
2. **Start Phase 1** - Add Trove wrapper (low risk, high value)
3. **Test thoroughly** - Verify backward compatibility
4. **Commit progress** - Small, incremental commits
5. **Document learnings** - Update this doc with discoveries

---

## References

- [Trove GitHub](https://github.com/taoensso/trove)
- [Sente v1.21.0 Release Notes](https://github.com/taoensso/sente/releases/tag/v1.21.0)
- [Sente v1.21.0 Source](https://github.com/taoensso/sente/blob/master/src/taoensso/sente.cljc)
- [Official Telemere](https://github.com/taoensso/telemere)

---

**Document Status**: Draft
**Last Updated**: 2025-11-07
**Next Review**: After Phase 1 completion
