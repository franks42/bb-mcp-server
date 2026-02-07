(ns atom-sync.core
    "Transport-independent atom synchronization.

   Core sync logic that works without any network transport.
   Can be tested locally with two atoms in the same process.

   Architecture:
   - Source atoms registered with `register-synced-atom!`
   - Subscribers receive ops via callbacks on `subscribe!`
   - Target atoms updated via `apply-sync-op`

   Message Protocol:
   [:sync/op {:key   :my-atom
              :seq   42
              :op    :assoc-in   ; or :dissoc-in
              :path  []          ; [] = full replace
              :value {...}}]

   Example local sync:
   (def !source (atom {:count 0}))
   (def !target (atom nil))

   (subscribe! :my-sub
     (fn [ops]
       (doseq [op ops]
         (apply-sync-op !target op))))

   (register-synced-atom! :my-state !source)

   (swap! !source assoc :count 1)
   @!target  ; => {:count 1}")

;; =============================================================================
;; Diff Logic
;; =============================================================================

(defn deep-diff->ops
  "Recursively diff two values, return assoc-in/dissoc-in ops with full paths.
   Works with nested maps. Vectors and other values are replaced wholesale.

   Args:
     key      - atom key (included in each op)
     old-val  - previous value
     new-val  - new value

   Returns: vector of [:sync/op {...}] messages

   Examples:
     (deep-diff->ops :s {:a 1} {:a 2})
     ;; => [[:sync/op {:key :s :op :assoc-in :path [:a] :value 2}]]

     (deep-diff->ops :s {:a {:b 1}} {:a {:b 2 :c 3}})
     ;; => [[:sync/op {:key :s :op :assoc-in :path [:a :b] :value 2}]
     ;;     [:sync/op {:key :s :op :assoc-in :path [:a :c] :value 3}]]"
  ([key old-val new-val]
   (deep-diff->ops key [] old-val new-val))
  ([key path old-val new-val]
   (cond
     ;; Same value - no ops needed
     (= old-val new-val)
     []

     ;; Both maps - recurse into each key
     (and (map? old-val) (map? new-val))
     (let [all-keys (into (set (keys old-val)) (keys new-val))]
       (vec
        (mapcat
         (fn [k]
           (let [ov (get old-val k ::missing)
                 nv (get new-val k ::missing)
                 path' (conj path k)]
             (cond
               (= ov nv) []
               (= nv ::missing) [[:sync/op {:key key :op :dissoc-in :path path'}]]
               (= ov ::missing) [[:sync/op {:key key :op :assoc-in :path path' :value nv}]]
               :else (deep-diff->ops key path' ov nv))))
         all-keys)))

     ;; Different values or types - replace at current path
     :else
     [[:sync/op {:key key :op :assoc-in :path path :value new-val}]])))

;; =============================================================================
;; Apply Sync Op
;; =============================================================================

(defn apply-sync-op
  "Apply a sync op to a target atom.

   Args:
     target-atom - atom to update
     op          - [:sync/op {:op :assoc-in/:dissoc-in :path [...] :value v}]

   Returns: the new value of the atom

   Example:
     (def !a (atom {:x 1}))
     (apply-sync-op !a [:sync/op {:op :assoc-in :path [:y] :value 2}])
     @!a  ; => {:x 1 :y 2}"
  [target-atom op]
  (let [[_sync-op {:keys [op path value]}] op]
    (case op
      :assoc-in
      (if (empty? path)
        ;; Full replace
        (reset! target-atom value)
        ;; Nested update
        (swap! target-atom assoc-in path value))

      :dissoc-in
      (if (= 1 (count path))
        ;; Top-level dissoc
        (swap! target-atom dissoc (first path))
        ;; Nested dissoc
        (swap! target-atom update-in (butlast path) dissoc (last path)))

      ;; Unknown op - ignore but log
      (do
       (println "[atom-sync] Unknown op:" op)
       @target-atom))))

;; =============================================================================
;; Registry
;; =============================================================================

;; Registry of synced atoms.
;; Map of key -> {:atom ref :seq n :last-value v}
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !synced-atoms (atom {}))

;; Registry of subscribers.
;; Map of subscriber-key -> callback-fn
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !subscribers (atom {}))

;; Server epoch - unique ID that changes on each server start.
;; Used by browser to detect server restarts and reset local sync state.
;; Browsers track this and when it changes, they know to accept any seq.
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !server-epoch (atom (System/currentTimeMillis)))

(defn get-server-epoch
  "Get current server epoch.
   Epoch changes on each server start - used for stale detection."
  []
  @!server-epoch)

(defn reset-server-epoch!
  "Reset server epoch to current timestamp.
   Called on module start to signal server restart to browsers."
  []
  (reset! !server-epoch (System/currentTimeMillis)))

(defn- notify-subscribers!
  "Call all subscriber callbacks with ops."
  [ops]
  (when (seq ops)
    (doseq [[_sub-key callback] @!subscribers]
           (try
            (callback ops)
            (catch Exception e
                   (println "[atom-sync] Subscriber error:" (ex-message e)))))))

(defn- on-atom-change
  "Watcher callback when registered atom changes.
   Generates ops, updates seq, notifies subscribers.
   Each op gets its own seq number and current epoch to ensure browser applies all."
  [key _ref old-val new-val]
  (when (not= old-val new-val)
    (let [base-ops (deep-diff->ops key old-val new-val)
          start-seq (get-in @!synced-atoms [key :seq] 0)
          epoch @!server-epoch
          ;; Assign incrementing seq and epoch to each op
          ops (vec
               (map-indexed
                (fn [idx [op-type op-data]]
                  [op-type (assoc op-data
                                  :seq (+ start-seq idx 1)
                                  :epoch epoch)])
                base-ops))
          final-seq (+ start-seq (count base-ops))]
      ;; Update registry with final seq
      (swap! !synced-atoms update key assoc
             :seq final-seq
             :last-value new-val)
      ;; Notify subscribers
      (notify-subscribers! ops))))

;; =============================================================================
;; Registration API
;; =============================================================================

(defn register-synced-atom!
  "Register an atom for sync.

   On any change, ops are generated and pushed to subscribers.

   Args:
     key       - unique identifier for this atom
     atom-ref  - the atom to sync

   Returns: the key

   Example:
     (def !state (atom {:count 0}))
     (register-synced-atom! :my-state !state)"
  [key atom-ref]
  (let [current-val @atom-ref]
    ;; Add to registry
    (swap! !synced-atoms assoc key
           {:atom atom-ref
            :seq 0
            :last-value current-val})
    ;; Install watcher
    (add-watch atom-ref [::sync key]
               (fn [_key ref old-val new-val]
                 (on-atom-change key ref old-val new-val)))
    key))

(defn unregister-synced-atom!
  "Remove an atom from sync registry.

   Args:
     key - the atom's key

   Returns: the key"
  [key]
  (when-let [{:keys [atom]} (get @!synced-atoms key)]
            (remove-watch atom [::sync key]))
  (swap! !synced-atoms dissoc key)
  key)

(defn get-synced-atom
  "Get the atom ref for a key, or nil if not registered."
  [key]
  (get-in @!synced-atoms [key :atom]))

(defn get-sync-status
  "Get current sync status for debugging.
   Returns map of atom keys to their current seq numbers."
  []
  (into {}
        (map (fn [[k {:keys [seq]}]] [k seq]))
        @!synced-atoms))

;; =============================================================================
;; Subscriber API
;; =============================================================================

(defn subscribe!
  "Subscribe to receive sync ops.

   The callback is called with a vector of ops whenever any
   registered atom changes.

   Args:
     subscriber-key - unique identifier for this subscriber
     callback-fn    - (fn [ops] ...) called with vector of ops

   Returns: subscriber-key

   Example:
     (subscribe! :my-transport
       (fn [ops]
         (broadcast! ops)))"
  [subscriber-key callback-fn]
  (swap! !subscribers assoc subscriber-key callback-fn)
  subscriber-key)

(defn unsubscribe!
  "Remove a subscriber.

   Args:
     subscriber-key - the subscriber to remove

   Returns: subscriber-key"
  [subscriber-key]
  (swap! !subscribers dissoc subscriber-key)
  subscriber-key)

;; =============================================================================
;; Push API
;; =============================================================================

(defn generate-full-sync-ops
  "Generate full sync ops for an atom (path []).
   Used for initial sync to new clients.
   Includes epoch for stale detection on browser side.

   Args:
     key - atom key

   Returns: vector of ops, or nil if key not registered"
  [key]
  (when-let [{:keys [atom seq]} (get @!synced-atoms key)]
            [[:sync/op {:key key
                        :seq seq
                        :epoch @!server-epoch
                        :op :assoc-in
                        :path []
                        :value @atom}]]))

(defn generate-all-full-sync-ops
  "Generate full sync ops for all registered atoms.
   Used for initial sync to new clients."
  []
  (vec (mapcat generate-full-sync-ops (keys @!synced-atoms))))

;; =============================================================================
;; Seq Validation (Client-Side)
;; =============================================================================

(defn apply-sync-op-validated
  "Apply op with seq validation. For use on receiving side (browser).

   Args:
     target-atom      - atom to update
     op               - [:sync/op {:key k :seq n :op ... }]
     expected-seqs    - atom tracking expected seq per key {:key seq}

   Returns:
     :applied - op applied, seq was expected (normal)
     :stale   - op ignored, seq < expected (duplicate/old message)
     :gap     - op applied, seq > expected (missed updates, may need resync)

   Example:
     (def !target (atom nil))
     (def !seqs (atom {}))
     (apply-sync-op-validated !target op !seqs)
     ;; => :applied, :stale, or :gap"
  [target-atom op expected-seqs]
  (let [[_ {:keys [key seq]}] op
        expected (inc (get @expected-seqs key 0))]
    (cond
      ;; Normal: sequential
      (= seq expected)
      (do
       (apply-sync-op target-atom op)
       (swap! expected-seqs assoc key seq)
       :applied)

      ;; Stale: already seen or old
      (< seq expected)
      :stale

      ;; Gap: missed some updates, apply anyway (newer is better)
      :else
      (do
       (apply-sync-op target-atom op)
       (swap! expected-seqs assoc key seq)
       :gap))))

(defn reset-expected-seq!
  "Reset expected seq for a key. Use after receiving full resync.

   Args:
     expected-seqs - atom tracking expected seqs
     key           - atom key
     seq           - new seq value to expect next increment from"
  [expected-seqs key seq]
  (swap! expected-seqs assoc key seq))

;; =============================================================================
;; Force Sync (Server-Side)
;; =============================================================================

(defn force-full-sync!
  "Force broadcast of full state for an atom.
   Use after async operations that may have caused seq gaps on clients.
   Generates a full state op (path []) and notifies all subscribers.

   Args:
     key - atom key

   Returns: count of ops sent, or nil if key not registered"
  [key]
  (when-let [{:keys [atom seq]} (get @!synced-atoms key)]
            (let [epoch @!server-epoch
                  new-seq (inc seq)
                  op [:sync/op {:key key
                                :seq new-seq
                                :epoch epoch
                                :op :assoc-in
                                :path []
                                :value @atom}]]
      ;; Update seq tracking
              (swap! !synced-atoms update key assoc
                     :seq new-seq
                     :last-value @atom)
      ;; Notify subscribers
              (notify-subscribers! [op])
              1)))

;; =============================================================================
;; Heartbeat / Sync Check (Server-Side)
;; =============================================================================

(defn get-server-seq
  "Get current server seq for an atom key.
   Returns nil if key not registered."
  [key]
  (get-in @!synced-atoms [key :seq]))

(defn check-sync-status
  "Check if client is in sync with server.

   Args:
     key        - atom key
     client-seq - client's last seen seq

   Returns:
     :in-sync   - client has latest
     :behind    - client missed updates (client-seq < server-seq)
     :ahead     - unexpected (client-seq > server-seq, shouldn't happen)
     :unknown   - key not registered on server"
  [key client-seq]
  (if-let [server-seq (get-server-seq key)]
          (cond
            (= client-seq server-seq) :in-sync
            (< client-seq server-seq) :behind
            :else :ahead)
          :unknown))

(defn handle-heartbeat
  "Handle heartbeat request from client.

   Args:
     key        - atom key
     client-seq - client's last seen seq

   Returns map with:
     :status     - :in-sync, :behind, :ahead, or :unknown
     :server-seq - current server seq (nil if unknown)
     :resync-ops - full sync ops if client is behind (nil otherwise)

   Example:
     (handle-heartbeat :my-atom 5)
     ;; => {:status :behind :server-seq 8 :resync-ops [[:sync/op ...]]}

     (handle-heartbeat :my-atom 8)
     ;; => {:status :in-sync :server-seq 8 :resync-ops nil}"
  [key client-seq]
  (let [status (check-sync-status key client-seq)
        server-seq (get-server-seq key)]
    {:status status
     :server-seq server-seq
     :resync-ops (when (= status :behind)
                   (generate-full-sync-ops key))}))

;; =============================================================================
;; Reset (for testing)
;; =============================================================================

(defn reset-all!
  "Reset all state. Unregisters all atoms and clears subscribers.
   Primarily for testing."
  []
  (doseq [key (keys @!synced-atoms)]
         (unregister-synced-atom! key))
  (reset! !subscribers {})
  nil)

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(def module
     "Module lifecycle map for bb-mcp-server module system."
     {:start (fn [_deps _config]
               ;; Reset epoch on server start so browsers detect restart
               (reset-server-epoch!)
               {:status :started
                :synced-atoms !synced-atoms
                :subscribers !subscribers
                :server-epoch !server-epoch})
      :stop (fn [_instance]
              (reset-all!)
              nil)
      :status (fn [_instance]
                {:status :ok
                 :synced-atom-count (count @!synced-atoms)
                 :subscriber-count (count @!subscribers)})})
