(ns atom-sync.server
    "Sente-lite transport integration for atom-sync.

   Thin wrapper that wires atom-sync.core to sente-browser WebSocket transport.
   Core handles all sync logic; this just handles transport concerns.

   Usage:
   1. Call (init!) on server startup to wire broadcast callback
   2. Call (on-browser-connected! sente-conn-id) when browser handshake completes
   3. Handle [:sync/resync-request ...] messages via dispatch-event

   Architecture:
   ```
   atom-sync.core (logic)     atom-sync.server (transport)
   ┌─────────────────────┐    ┌─────────────────────────┐
   │ deep-diff->ops      │    │ init!                   │
   │ apply-sync-op       │◀──▶│ on-browser-connected!   │
   │ subscribe!/notify   │    │ dispatch-event          │
   │ handle-heartbeat    │    │ → sente-lite broadcast  │
   └─────────────────────┘    └─────────────────────────┘
   ```"
    (:require [atom-sync.core :as core]
              [taoensso.trove :as log]))

;; Forward declaration for sente-browser functions
;; These will be resolved at runtime to avoid circular deps
(def ^:private broadcast-fn (atom nil))
(def ^:private send-fn (atom nil))

;; On-connect callbacks - run before initial sync to allow just-in-time registration
;; Map of key -> callback-fn. Callbacks receive sente-conn-id and should be idempotent.
#_{:clj-kondo/ignore [:missing-docstring]}
(defonce !on-connect-callbacks (atom {}))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private subscriber-key ::sente-broadcast)

;; =============================================================================
;; Transport Functions
;; =============================================================================

(defn- broadcast!
  "Broadcast sync op to all connected browsers."
  [event]
  (when-let [f @broadcast-fn]
            (let [count (f event)]
              (log/log! {:level :debug
                         :id ::broadcast
                         :msg "Broadcast sync op"
                         :data {:event-id (first event)
                                :browser-count count}})
              count)))

(defn- send-to-browser!
  "Send sync op to specific browser."
  [sente-conn-id event]
  (when-let [f @send-fn]
            (f sente-conn-id event)))

;; =============================================================================
;; On-Connect Callback Registry
;; =============================================================================

(defn register-on-connect!
  "Register a callback to run when a browser connects.
   Callbacks run BEFORE initial sync, allowing just-in-time atom registration.

   Use case: Module auto-initialization when first browser connects.
   Callbacks should be idempotent (may be called multiple times).

   Args:
     key         - unique identifier for this callback
     callback-fn - (fn [sente-conn-id] ...) called on each browser connect

   Returns: key

   Example:
     (register-on-connect! :my-module
       (fn [_conn-id]
         (when-not @!initialized
           (initialize!))))"
  [key callback-fn]
  (swap! !on-connect-callbacks assoc key callback-fn)
  (log/log! {:level :debug
             :id ::on-connect-registered
             :msg "Registered on-connect callback"
             :data {:key key}})
  key)

(defn unregister-on-connect!
  "Remove an on-connect callback.

   Args:
     key - the callback's key

   Returns: key"
  [key]
  (swap! !on-connect-callbacks dissoc key)
  (log/log! {:level :debug
             :id ::on-connect-unregistered
             :msg "Unregistered on-connect callback"
             :data {:key key}})
  key)

;; =============================================================================
;; Browser Connection Lifecycle
;; =============================================================================

(defn- run-on-connect-callbacks!
  "Run all registered on-connect callbacks.
   Errors in callbacks are logged but don't stop other callbacks."
  [sente-conn-id]
  (doseq [[key callback-fn] @!on-connect-callbacks]
         (try
          (callback-fn sente-conn-id)
          (catch Exception e
                 (log/log! {:level :warn
                            :id ::on-connect-callback-error
                            :msg "On-connect callback failed"
                            :data {:key key
                                   :error (ex-message e)}})))))

(defn on-browser-connected!
  "Called when a browser completes handshake.
   First runs on-connect callbacks (for just-in-time module init),
   then pushes all synced atoms to the new client for initial state.

   Args:
     sente-conn-id - the sente connection ID for the new browser

   Returns: count of ops sent"
  [sente-conn-id]
  ;; Run callbacks first - allows modules to register atoms just-in-time
  (run-on-connect-callbacks! sente-conn-id)
  ;; Now push all registered atoms (including just-registered ones)
  (let [ops (core/generate-all-full-sync-ops)]
    (when (seq ops)
      (log/log! {:level :info
                 :id ::initial-sync
                 :msg "Pushing initial sync to new browser"
                 :data {:sente-conn-id sente-conn-id
                        :op-count (count ops)
                        :keys (map #(get-in % [1 :key]) ops)}})
      (doseq [op ops]
             (send-to-browser! sente-conn-id op)))
    (count ops)))

;; =============================================================================
;; Event Dispatch (Browser → Server)
;; =============================================================================

(defn dispatch-event
  "Handle atom-sync related events from browser.
   Returns [response-event-id response-data] or nil if not handled.

   Supported events:
   - [:sync/resync-request {:key k}] - Request full resync for an atom
   - [:sync/heartbeat {:key k :seq n}] - Check sync status"
  [event-id data]
  (case event-id
    :sync/resync-request
    (let [{:keys [key]} data
          ops (core/generate-full-sync-ops key)]
      (log/log! {:level :info
                 :id ::resync-request
                 :msg "Browser requested resync"
                 :data {:key key :has-ops (some? ops)}})
      (if ops
        [:sync/resync-response {:key key :ops ops}]
        [:sync/resync-response {:key key :error :unknown-key}]))

    :sync/heartbeat
    (let [{:keys [key seq]} data
          response (core/handle-heartbeat key seq)]
      (log/log! {:level :debug
                 :id ::heartbeat-check
                 :msg "Browser heartbeat check"
                 :data {:key key :client-seq seq :status (:status response)}})
      [:sync/heartbeat-response response])

    ;; Not a sync event
    nil))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize atom-sync server integration.

   Subscribes to atom-sync.core notifications and wires them to sente-lite broadcast.
   Call this on server startup after sente-browser is started.

   Args:
     broadcast-to-browsers-fn - fn that broadcasts to all browsers
     send-to-browser-fn       - fn that sends to single browser

   Returns: subscriber key"
  [broadcast-to-browsers-fn send-to-browser-fn]
  (reset! broadcast-fn broadcast-to-browsers-fn)
  (reset! send-fn send-to-browser-fn)

  ;; Subscribe to core - broadcasts all ops to all browsers
  (core/subscribe! subscriber-key
                   (fn [ops]
                     (doseq [op ops]
                            (broadcast! op))))

  (log/log! {:level :info
             :id ::initialized
             :msg "Atom-sync server integration initialized"
             :data {:subscriber-key subscriber-key}})

  subscriber-key)

(defn stop!
  "Stop atom-sync server integration.
   Unsubscribes from core, clears transport functions, and clears callbacks."
  []
  (core/unsubscribe! subscriber-key)
  (reset! broadcast-fn nil)
  (reset! send-fn nil)
  (reset! !on-connect-callbacks {})
  (log/log! {:level :info
             :id ::stopped
             :msg "Atom-sync server integration stopped"})
  nil)

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(def module
     "Module lifecycle map for bb-mcp-server module system."
     {:start (fn [config]
            ;; Transport functions will be injected via init!
            ;; after sente-browser is available
               {:status :started
                :config config})
      :stop (fn [_instance]
              (stop!)
              nil)})
