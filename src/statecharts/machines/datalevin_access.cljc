(ns statecharts.machines.datalevin-access
    "Documentation statechart for Datalevin pod access serialization.

   Models the mutual-exclusion invariant required by Babashka pods:
   the pod message channel serializes ALL calls at the process level,
   so concurrent callers from different threads can interleave pod
   messages and deadlock.

   States:
                       :pod-loaded
     :unloaded ──────────────────────► :idle
                                        │  ▲
                              :op-start │  │ :op-complete / :op-error
                                        ▼  │
                                       :busy
                                        │
                          :heartbeat-   │
                           timeout      ▼
                                     :deadlocked
                                        │
                              :restart  │      :shutdown
                                        ▼         │
                   :idle ◄─────── :recovering     ▼
                   (loop)                       :closed

   KEY INVARIANT: Only ONE thread may be in :busy at a time.
   All other callers must queue, fail-fast, or timeout.

   This statechart is documentation and audit — no write-gate wiring yet.

   Code Audit Summary (5 violations identified):

   V1: No mutual exclusion on pod calls (handlers.clj)
       update-namespace-symbols! and update-ns-list-in-datalevin! do
       multiple db-proto/q + db-proto/transact! with no locking.
       Poll-triggered fingerprint checks race through pod concurrently.

   V2: File watcher lock not shared with handlers (core.clj)
       core.clj uses per-file locking via !rescan-locks, but handlers.clj
       has no lock at all. File-watcher rescans and poll-triggered updates
       can hit the pod simultaneously from different lock scopes.

   V3: Initialization unprotected (core.clj scan-and-populate!)
       Does many sequential transact! calls during startup. If a browser
       connects and starts polling before init completes, poll handlers
       race with init transacts.

   V4: No timeout/deadlock detection (datalevin.clj DatalevinDB)
       Every when-let blocks indefinitely waiting for pod response.
       If pod deadlocks, calling thread hangs forever with no timeout,
       no error, no way to detect or recover.

   V5: Read-only queries compete with writes (handlers.clj)
       Even reads (db-proto/q) go through the pod and can deadlock
       with concurrent writes. Unlike a real DB, the pod serializes
       ALL calls through a single message channel."
    (:require [statecharts.core :as fsm]
              [statecharts.types :as types]))

;; =============================================================================
;; Context Assignment Actions (named, public, docstrings)
;; =============================================================================

(defn assign-pod-loaded
  "Store connection and path when pod loads successfully.
   Transitions :unloaded -> :idle."
  [ctx event]
  (assoc ctx
         :conn (:conn event)
         :path (:path event)))

(defn assign-op-start
  "Record the start of a pod operation.
   Captures operation type, caller identity, and start timestamp.
   Transitions :idle -> :busy."
  [ctx event]
  (assoc ctx
         :op-type (:op-type event)
         :caller (:caller event)
         :start-ms (:start-ms event)))

(defn assign-op-complete
  "Clear operation tracking and increment success counter.
   Transitions :busy -> :idle."
  [ctx _event]
  (assoc ctx
         :op-type nil
         :caller nil
         :start-ms nil
         :op-count (inc (:op-count ctx 0))))

(defn assign-op-error
  "Clear operation tracking and record the error.
   Transitions :busy -> :idle (operation failed, but pod is responsive)."
  [ctx event]
  (assoc ctx
         :op-type nil
         :caller nil
         :start-ms nil
         :last-error (:error event)))

(defn assign-deadlock-detected
  "Record when deadlock was detected by heartbeat timeout.
   Transitions :busy -> :deadlocked."
  [ctx event]
  (assoc ctx
         :deadlock-detected-at (:detected-at event)))

(defn assign-recovery-success
  "Store new connection after successful pod restart.
   Transitions :recovering -> :idle."
  [ctx event]
  (assoc ctx
         :conn (:conn event)
         :op-type nil
         :caller nil
         :start-ms nil
         :deadlock-detected-at nil))

;; =============================================================================
;; Machine Configuration (inspectable raw map)
;; =============================================================================

(def datalevin-access-statechart
     "Configuration map for the Datalevin pod access state machine.
   Inspectable at runtime - use this var to see states/transitions.

   The key invariant: :busy state enforces mutual exclusion.
   Only ONE thread can be in :busy at a time. All other callers
   that attempt :op-start while in :busy must either:
   1. Queue (wait for :idle) - preferred for writes/transacts
   2. Fail fast (return nil/error) - acceptable for non-critical reads
   3. Timeout (wait with deadline, then fail) - fallback"
     (types/map->Statechart
      {:id      :datalevin-access
       :initial :unloaded
       :context {:conn nil
                 :path nil
                 :op-type nil
                 :caller nil
                 :start-ms nil
                 :op-count 0
                 :last-error nil
                 :deadlock-detected-at nil}
       :states
       {:unloaded    {:on {:pod-loaded {:target  :idle
                                        :actions [(fsm/assign assign-pod-loaded)]}}}

        :idle        {:on {:op-start {:target  :busy
                                      :actions [(fsm/assign assign-op-start)]}
                           :shutdown {:target :closed}}}

        :busy        {:on {:op-complete      {:target  :idle
                                              :actions [(fsm/assign assign-op-complete)]}
                           :op-error         {:target  :idle
                                              :actions [(fsm/assign assign-op-error)]}
                           :heartbeat-timeout {:target  :deadlocked
                                               :actions [(fsm/assign assign-deadlock-detected)]}}}

        :deadlocked  {:on {:restart  {:target :recovering}
                           :shutdown {:target :closed}}}

        :recovering  {:on {:recovery-success {:target  :idle
                                              :actions [(fsm/assign assign-recovery-success)]}
                           :recovery-failed  {:target :closed}}}

        :closed      {}}}))

;; =============================================================================
;; Compiled Machine
;; =============================================================================

(def datalevin-access-compiled
     "Compiled state machine for Datalevin pod access serialization.

   States:
                       :pod-loaded
     :unloaded ──────────────────────► :idle
                                        │  ▲
                              :op-start │  │ :op-complete / :op-error
                                        ▼  │
                                       :busy
                                        │
                          :heartbeat-   │
                           timeout      ▼
                                     :deadlocked
                                        │
                              :restart  │      :shutdown
                                        ▼         │
                   :idle ◄─────── :recovering     ▼
                   (loop)                       :closed

   Events:
   - :pod-loaded        - Pod loaded, connection ready (from :unloaded)
   - :op-start          - Begin a pod operation (from :idle only!)
   - :op-complete       - Pod operation finished successfully (from :busy)
   - :op-error          - Pod operation failed (from :busy)
   - :heartbeat-timeout - Pod unresponsive for >10s (from :busy)
   - :restart           - Attempt pod restart (from :deadlocked)
   - :recovery-success  - Pod restarted successfully (from :recovering)
   - :recovery-failed   - Pod restart failed (from :recovering)
   - :shutdown          - Close connection (from :idle or :deadlocked)"
     (fsm/machine datalevin-access-statechart))

;; =============================================================================
;; Heartbeat Specification (not implemented, documents expected behavior)
;; =============================================================================

;; A background timer should run every 5 seconds and check:
;;
;; 1. If state is :busy AND (- now start-ms) > 10000ms:
;;    -> emit :heartbeat-timeout
;;    This detects pod deadlocks proactively.
;;
;; 2. If state is :idle:
;;    -> run a trivial query with 3s timeout:
;;       (db-proto/q db '[:find ?e :where [?e :uri/source _]] {:limit 1})
;;    If heartbeat query times out -> emit :heartbeat-timeout
;;    This detects pod hangs even without active callers.
;;
;; 3. If state is :unloaded, :closed, :deadlocked, or :recovering:
;;    -> do nothing (heartbeat is irrelevant in these states)

;; =============================================================================
;; Code Audit: Caller -> Statechart Mapping
;; =============================================================================

;; Each caller below should trigger :op-start -> :op-complete/:op-error
;; around its pod calls. "Compound operations" (multiple pod calls in
;; sequence) should hold :busy for the entire duration.
;;
;; ┌─────────────────────────────┬──────────────────────┬──────────┬──────────────┐
;; │ Caller                      │ File:Line            │ Op Type  │ Protected?   │
;; ├─────────────────────────────┼──────────────────────┼──────────┼──────────────┤
;; │ query-projects              │ handlers.clj:83      │ :query   │ NO           │
;; │ query-namespaces            │ handlers.clj:103     │ :query   │ NO           │
;; │ query-symbols               │ handlers.clj:119     │ :query   │ NO           │
;; │ query-aliases               │ handlers.clj:135     │ :query   │ NO           │
;; │ query-refers                │ handlers.clj:149     │ :query   │ NO           │
;; │ update-namespace-symbols!   │ handlers.clj:426+453 │ :compound│ NO           │
;; │ update-ns-list-in-datalevin!│ handlers.clj:543+575 │ :compound│ NO           │
;; │ scan-and-populate!          │ core.clj:101-115     │ :compound│ NO           │
;; │ retract-project-entities!   │ core.clj:223+235     │ :compound│ NO           │
;; │ retract-file-entities!      │ core.clj:243-298     │ :compound│ per-file lock│
;; │ find-ns-names-for-file      │ core.clj:315         │ :query   │ per-file lock│
;; │ transact-file-entities!     │ core.clj:336-346     │ :compound│ per-file lock│
;; │ close!                      │ core.clj:581         │ :close   │ NO           │
;; │ create-db (constructor)     │ datalevin.clj:140-148│ :init    │ NO           │
;; └─────────────────────────────┴──────────────────────┴──────────┴──────────────┘
;;
;; Violation Details:
;;
;; V1: update-namespace-symbols! (handlers.clj:417-454)
;;     Does db-proto/q (line 426) then db-proto/transact! (line 453).
;;     Two separate pod calls with no mutual exclusion.
;;     When browser polls for multiple widgets simultaneously (e.g.,
;;     symbol-list + ns-list both detect changes), both threads enter
;;     this function and interleave pod messages -> deadlock.
;;     FIX: Entire function must be one :op-start -> :op-complete unit.
;;
;; V2: update-ns-list-in-datalevin! (handlers.clj:519-576)
;;     Calls query-namespaces (1 pod call), then per-removed-ns:
;;     2x db-proto/q (lines 543, 550), then db-proto/transact! (line 575).
;;     Up to 2*N+2 pod calls with no locking at all.
;;     FIX: Same as V1 — compound operation needs mutual exclusion.
;;
;; V3: rescan-file! (core.clj:348-429)
;;     Uses per-file locking via !rescan-locks atom. This prevents
;;     two rescans of the SAME file from racing, but does NOT prevent
;;     a file rescan from racing with a poll-triggered handler update.
;;     The lock object in core.clj is invisible to handlers.clj.
;;     FIX: All pod access must share ONE gatekeeper, not per-file locks.
;;
;; V4: scan-and-populate! (core.clj:83-128)
;;     Does 5+ sequential transact! calls (project, ns batches, symbol
;;     batches, alias batches, refer batches). If a browser connects
;;     and starts polling before init completes, polling handlers
;;     interleave with init transacts.
;;     FIX: Init should hold :busy for entire scan, or module should
;;     not accept poll requests until init is complete (stay in :unloaded
;;     until scan-and-populate! finishes).
;;
;; V5: DatalevinDB protocol methods (datalevin.clj:77-119)
;;     Every method uses when-let which blocks indefinitely waiting for
;;     pod response. No timeout, no error detection, no way to detect
;;     deadlock. If pod hangs, the calling thread hangs forever.
;;     FIX: Heartbeat timer should detect when :busy exceeds 10s and
;;     transition to :deadlocked, allowing recovery or clean shutdown.
;;
;; Race Scenario Example (observed in production):
;;   T=0ms  Thread A: check-symbol-list-fingerprint -> update-namespace-symbols!
;;   T=0ms  Thread B: check-ns-list-fingerprint -> update-ns-list-in-datalevin!
;;   T=1ms  Thread A: db-proto/q (query existing symbols) -> pod starts working
;;   T=1ms  Thread B: db-proto/q (query-namespaces) -> pod gets confused
;;   T=2ms  Both threads wait forever for pod responses -> DEADLOCK
;;   T=∞    Only SIGKILL can recover the server
