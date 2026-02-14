(ns statecharts.machines.runtime-data-sync
  "Statechart for Runtime → Datalevin → UI Widget data synchronization.

  Models the lifecycle of runtime data (nREPL) flowing through the system:
  - Runtime introspection (source of truth)
  - Datalevin persistence (queryable cache)
  - Widget rendering (UI layer)

  Key insight: Runtime changes should cascade back to Datalevin BEFORE
  widgets fetch, ensuring queries always return fresh data."
  (:require [statecharts.core :as fsm]
            [statecharts.types :as types]))

;; =============================================================================
;; Context Assignment Actions
;; =============================================================================

(defn assign-fresh-data
  "Store freshly scanned data from runtime.
   Updates entity counts for observability."
  [ctx event]
  (assoc ctx
         :ns-count (:ns-count event)
         :symbol-count (:symbol-count event)
         :last-rescan-ms #?(:clj (System/currentTimeMillis)
                            :cljs (.getTime (js/Date.)))))

(defn assign-error
  "Store error from failed rescan attempt."
  [ctx event]
  (assoc ctx :error (:error event)))

(defn clear-error
  "Clear error state before retry."
  [ctx _event]
  (assoc ctx :error nil))

;; =============================================================================
;; Machine Configuration
;; =============================================================================

(def runtime-data-sync-statechart
  "Statechart for runtime data synchronization lifecycle.

  States represent the synchronization status of runtime data:
  - :idle       - No widgets open, no active sync needed
  - :syncing    - Scanning runtime and writing to Datalevin
  - :ready      - Datalevin has current snapshot, widgets can query
  - :resyncing  - Runtime changed, updating Datalevin
  - :error      - Rescan failed, stale data may be cached

  Events:
  - :widget-opened          - First widget needs data (idle → syncing)
  - :poll-detected-change   - Runtime changed (ready → resyncing)
  - :rescan-complete        - Fresh data written to Datalevin
  - :rescan-failed          - Scan/write error
  - :retry                  - Manual retry after error
  - :close-last-widget      - No widgets viewing data (→ idle)

  Critical invariant: Widgets MUST NOT query Datalevin while in :syncing/:resyncing
  states. The `:ready` state guarantees data freshness."
  (types/map->Statechart
   {:id      :runtime-data-sync
    :initial :idle
    :context {:ns-count nil
              :symbol-count nil
              :last-rescan-ms nil
              :error nil}
    :states
    {:idle       {:on {:widget-opened {:target :syncing}}}

     :syncing    {:on {:rescan-complete {:target  :ready
                                         :actions [(fsm/assign assign-fresh-data)]}
                       :rescan-failed   {:target  :error
                                         :actions [(fsm/assign assign-error)]}}}

     :ready      {:on {:poll-detected-change {:target :resyncing}
                       :close-last-widget     {:target :idle}}}

     :resyncing  {:on {:rescan-complete {:target  :ready
                                         :actions [(fsm/assign assign-fresh-data)]}
                       :rescan-failed   {:target  :error
                                         :actions [(fsm/assign assign-error)]}}}

     :error      {:on {:retry             {:target  :syncing
                                           :actions [(fsm/assign clear-error)]}
                       :close-last-widget {:target :idle}}}}}))

;; =============================================================================
;; Compiled Machine
;; =============================================================================

(def runtime-data-sync-statechart-compiled
  "Compiled runtime data sync state machine.

  Use this for transitions:
    (fsm/transition machine state event)

  Example:
    (fsm/transition runtime-data-sync-statechart-compiled
                    {:_state :idle}
                    {:type :widget-opened})
    => {:_state :syncing, :ns-count nil, :symbol-count nil, ...}

  Then implement side effects based on state transitions:
    - Enter :syncing/:resyncing → call scan-project, write to Datalevin
    - Enter :ready → widgets safe to query
    - Enter :error → log/alert/retry"
  (fsm/machine runtime-data-sync-statechart))
