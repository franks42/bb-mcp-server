(ns statecharts.machines.datalevin-access
    "Documentation statechart for Datalevin connection lifecycle and health.

   PREMISE CORRECTION (2026-02-14):
   The original version of this statechart assumed Babashka pods do NOT
   serialize concurrent calls, requiring external mutual exclusion.
   Empirical testing (scripts/test_pod_concurrency.clj) DISPROVED this:
   550 concurrent pod calls across 10 threads — reads, writes, mixed,
   compound query+transact — all completed with 0 errors, 0 deadlocks.

   Babashka pods DO serialize calls internally. The :idle/:busy mutual
   exclusion gate is unnecessary for correctness.

   ACTUAL ROOT CAUSE of observed deadlocks (commit 15aaad5):
   Missing fingerprint storage caused infinite rescan loops. Each 3s
   poll cycle triggered a full rescan because the fingerprint was never
   stored, so every check reported 'changed'. This created a rescan
   storm — hundreds of concurrent write transactions overwhelming the
   Datalevin pod process. The fix was storing initial fingerprints
   (breaking the loop) and adding a compare-and-set guard on rescans
   (preventing concurrent rescans). The pod serialization theory was
   a wrong explanation for why the fix worked.

   REMAINING VALUE of this statechart:
   - Connection lifecycle (unloaded → ready → closed)
   - Health monitoring (detect pod hangs, timeout detection)
   - Documents what states are valid and when operations are allowed

   States:
     :unloaded ───:pod-loaded──► :ready ───:shutdown──► :closed
                                   │  ▲
                                   │  │ (normal operation,
                                   │  │  pod serializes internally)
                                   │  │
                              :hang-detected
                                   ▼
                               :unhealthy ───:shutdown──► :closed
                                   │
                              :restart
                                   ▼
                  :ready ◄── :recovering ──:failed──► :closed"
    (:require [statecharts.core :as fsm]
              [statecharts.types :as types]))

;; =============================================================================
;; Context Assignment Actions (named, public, docstrings)
;; =============================================================================

(defn assign-pod-loaded
  "Store connection and path when pod loads successfully.
   Transitions :unloaded -> :ready."
  [ctx event]
  (assoc ctx
         :conn (:conn event)
         :path (:path event)))

(defn assign-hang-detected
  "Record when a pod operation exceeded the expected timeout.
   This does NOT mean the pod needs external serialization — it means
   something caused the pod process to become unresponsive (e.g.,
   rescan storm flooding it with transactions).
   Transitions :ready -> :unhealthy."
  [ctx event]
  (assoc ctx
         :hang-detected-at (:detected-at event)
         :hang-caller (:caller event)
         :hang-op-type (:op-type event)))

(defn assign-recovery-success
  "Store new connection after successful pod restart.
   Transitions :recovering -> :ready."
  [ctx event]
  (assoc ctx
         :conn (:conn event)
         :hang-detected-at nil
         :hang-caller nil
         :hang-op-type nil))

;; =============================================================================
;; Machine Configuration (inspectable raw map)
;; =============================================================================

(def datalevin-access-statechart
     "Configuration map for the Datalevin connection lifecycle.
   Inspectable at runtime — use this var to see states/transitions.

   NOTE: This statechart does NOT enforce mutual exclusion on pod calls.
   Empirical testing confirmed that Babashka pods serialize internally.
   The purpose is connection lifecycle and health monitoring only."
     (types/map->Statechart
      {:id      :datalevin-access
       :initial :unloaded
       :context {:conn nil
                 :path nil
                 :hang-detected-at nil
                 :hang-caller nil
                 :hang-op-type nil}
       :states
       {:unloaded    {:on {:pod-loaded {:target  :ready
                                        :actions [(fsm/assign assign-pod-loaded)]}}}

        :ready       {:on {:hang-detected {:target    :unhealthy
                                           :actions   [(fsm/assign assign-hang-detected)]}
                           :shutdown      {:target :closed}}}

        :unhealthy   {:on {:restart  {:target :recovering}
                           :shutdown {:target :closed}}}

        :recovering  {:on {:recovery-success {:target  :ready
                                              :actions [(fsm/assign assign-recovery-success)]}
                           :recovery-failed  {:target :closed}}}

        :closed      {}}}))

;; =============================================================================
;; Compiled Machine
;; =============================================================================

(def datalevin-access-compiled
     "Compiled state machine for Datalevin connection lifecycle.

   States:
     :unloaded ───:pod-loaded──► :ready ───:shutdown──► :closed
                                   │  ▲
                              :hang │  │ :recovery-success
                                   ▼  │
                               :unhealthy ───:shutdown──► :closed
                                   │
                              :restart
                                   ▼
                               :recovering ──:failed──► :closed

   Events:
   - :pod-loaded        - Pod loaded, connection created (from :unloaded)
   - :hang-detected     - Pod unresponsive (from :ready)
   - :restart           - Attempt pod restart (from :unhealthy)
   - :recovery-success  - Pod restarted successfully (from :recovering)
   - :recovery-failed   - Pod restart failed (from :recovering)
   - :shutdown          - Close connection (from :ready or :unhealthy)"
     (fsm/machine datalevin-access-statechart))

;; =============================================================================
;; Empirical Findings (2026-02-14)
;; =============================================================================

;; TEST: scripts/test_pod_concurrency.clj
;; RESULT: All 550 concurrent pod calls completed, 0 errors, 0 deadlocks.
;;
;; | Test                    | Threads | Ops | Errors | Time  |
;; |-------------------------|---------|-----|--------|-------|
;; | Concurrent reads        |      10 | 200 |      0 |  40ms |
;; | Concurrent writes       |      10 | 100 |      0 | 627ms |
;; | Mixed read+write        |      10 | 200 |      0 | 695ms |
;; | Compound query+transact |       5 |  50 |      0 | 316ms |
;;
;; CONCLUSION: Babashka pods serialize calls internally.
;; No external mutual exclusion (locking, gating) is needed for
;; correctness. The observed production deadlocks were caused by
;; a rescan storm (missing fingerprint storage → infinite loop →
;; hundreds of transactions overwhelming the pod process), not by
;; concurrent pod message interleaving.

;; =============================================================================
;; Health Monitoring Specification (not yet implemented)
;; =============================================================================

;; A background timer (every 10s) should check pod responsiveness:
;;
;; 1. Run a trivial query with a 5s timeout:
;;    (db-proto/q db '[:find ?e :where [?e :uri/source _]] {:limit 1})
;;
;; 2. If the query completes within timeout → pod is healthy, no action
;;
;; 3. If the query times out → emit :hang-detected
;;    This catches situations where the pod process is overwhelmed
;;    (e.g., by rescan storms or pathological queries).
;;
;; The purpose is early detection and alerting, not serialization.

;; =============================================================================
;; Caller Inventory (for reference, not violations)
;; =============================================================================

;; These callers all use the pod concurrently. This is SAFE because
;; the pod serializes internally. Listed for documentation only.
;;
;; ┌─────────────────────────────┬──────────────────────┬──────────┐
;; │ Caller                      │ File:Line            │ Op Type  │
;; ├─────────────────────────────┼──────────────────────┼──────────┤
;; │ query-projects              │ handlers.clj:83      │ :query   │
;; │ query-namespaces            │ handlers.clj:103     │ :query   │
;; │ query-symbols               │ handlers.clj:119     │ :query   │
;; │ query-aliases               │ handlers.clj:135     │ :query   │
;; │ query-refers                │ handlers.clj:149     │ :query   │
;; │ update-namespace-symbols!   │ handlers.clj:426+453 │ :compound│
;; │ update-ns-list-in-datalevin!│ handlers.clj:543+575 │ :compound│
;; │ scan-and-populate!          │ core.clj:101-115     │ :compound│
;; │ retract-project-entities!   │ core.clj:223+235     │ :compound│
;; │ retract-file-entities!      │ core.clj:243-298     │ :compound│
;; │ find-ns-names-for-file      │ core.clj:315         │ :query   │
;; │ transact-file-entities!     │ core.clj:336-346     │ :compound│
;; │ close!                      │ core.clj:581         │ :close   │
;; │ create-db (constructor)     │ datalevin.clj:140-148│ :init    │
;; └─────────────────────────────┴──────────────────────┴──────────┘
;;
;; The per-file lock in core.clj (!rescan-locks) is still valuable —
;; not for pod serialization, but to prevent logical races where two
;; rescans of the same file interleave retract/transact operations
;; and produce incorrect Datalevin state.
