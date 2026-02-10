(ns statecharts.machines.widget-lifecycle
    "Documentation statechart for Code Browser v2 widget lifecycle.
   NOT runtime-integrated — used for validation and testing only.

   States: loading -> ready <-> refreshing
                 |             |
               error <---------+

   All states can transition to :closed (terminal).

   Key insight: :version-hash in context exposes the staleness bug
   where widgets re-fetch using stale version hashes after invalidation."
    (:require [statecharts.core :as fsm]))

;; =============================================================================
;; Context Assignment Actions (named, public, docstrings)
;; =============================================================================

(defn assign-widget-config
  "Store widget configuration on open.
   Sets widget-id, widget-type, uri, and initial version-hash."
  [ctx event]
  (assoc ctx
         :widget-id (:widget-id event)
         :widget-type (:widget-type event)
         :uri (:uri event)
         :version-hash (:version-hash event)))

(defn assign-data
  "Store fetched data and version-hash, clear error.
   Used after successful fetch from both :loading and :refreshing."
  [ctx event]
  (assoc ctx
         :data (:data event)
         :error nil
         :version-hash (:version-hash event)))

(defn assign-error
  "Store error message on fetch failure.
   Does NOT clear :data — preserves stale data from :refreshing state."
  [ctx event]
  (assoc ctx :error (:error event)))

(defn assign-invalidate
  "Update version-hash when project data changes.
   Data is preserved (shown as stale) while re-fetch happens."
  [ctx event]
  (assoc ctx :version-hash (:new-version-hash event)))

(defn assign-retry
  "Clear error before retrying fetch."
  [ctx _event]
  (assoc ctx :error nil))

;; =============================================================================
;; Machine Configuration (inspectable raw map)
;; =============================================================================

(def widget-lifecycle-machine-config
     "Configuration map for the widget lifecycle state machine.
   Inspectable at runtime — use this var to see states/transitions."
     {:id      :widget-lifecycle
      :initial :loading
      :context {:widget-id nil
                :widget-type nil
                :uri nil
                :data nil
                :error nil
                :version-hash nil}
      :states
      {:loading     {:on {:fetch-success {:target  :ready
                                          :actions [(fsm/assign assign-data)]}
                          :fetch-error   {:target  :error
                                          :actions [(fsm/assign assign-error)]}
                          :close         {:target :closed}}}
       :ready       {:on {:invalidate {:target  :refreshing
                                       :actions [(fsm/assign assign-invalidate)]}
                          :close      {:target :closed}}}
       :refreshing  {:on {:fetch-success {:target  :ready
                                          :actions [(fsm/assign assign-data)]}
                          :fetch-error   {:target  :error
                                          :actions [(fsm/assign assign-error)]}
                          :close         {:target :closed}}}
       :error       {:on {:retry {:target  :loading
                                  :actions [(fsm/assign assign-retry)]}
                          :close {:target :closed}}}
       :closed      {}}})

;; =============================================================================
;; Compiled Machine
;; =============================================================================

(def widget-lifecycle-machine
     "Compiled state machine for widget lifecycle.

   States: loading -> ready <-> refreshing
                 |             |
               error <---------+

   Events:
   - :fetch-success - Data fetched successfully (from :loading or :refreshing)
   - :fetch-error   - Fetch failed (from :loading or :refreshing)
   - :invalidate    - Project data changed, re-fetch needed (from :ready)
   - :retry         - Retry after error (from :error)
   - :close         - Widget closed (from any active state)"
     (fsm/machine widget-lifecycle-machine-config))
