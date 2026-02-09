(ns code-browser.bootstrap
  "Code browser infrastructure loaded at startup.
   UI code is loaded via nREPL after connection established."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [sente-lite.client-scittle :as client]
            [nrepl-sente.browser-adapter :as adapter]
            [browser-telemetry]
            [taoensso.trove :as log]))

;; =========================================================================
;; Config - injected via window.__BB_MCP_CONFIG
;; =========================================================================

(def ^:private ws-url
  (str "ws://" (aget js/window.__BB_MCP_CONFIG "ws_host")
       ":" (aget js/window.__BB_MCP_CONFIG "ws_port")))

;; =========================================================================
;; Error Boundary - Catches render errors for safe REPL development
;; =========================================================================

(defn error-boundary
  "Wrap components to catch errors during render.
   Displays error message with clear button instead of crashing."
  [& children]
  (let [!error (r/atom nil)]
    (r/create-class
      {:display-name "ErrorBoundary"
       :component-did-catch
       (fn [this error info]
         (js/console.error "[error-boundary]" error info)
         (reset! !error {:error error :info info}))
       :reagent-render
       (fn [& children]
         (if-let [{:keys [error]} @!error]
           [:div.error-boundary
            [:h3 "Render Error"]
            [:pre (str error)]
            [:button {:on-click #(reset! !error nil)} "Clear & Retry"]]
           (into [:<>] children)))})))

;; =========================================================================
;; Synced Atoms - One-way sync from server with seq validation
;; =========================================================================

;; Registry of synced Reagent atoms: {:key (r/atom value)}
(defonce !synced-atoms (atom {}))

;; Sync state tracking: {:key {:seq n}} for gap detection
(defonce !sync-state (atom {}))

;; Track last-seen epoch per key for server restart detection
;; When epoch changes, we know server restarted and should accept any seq
(defonce !last-epoch (atom {}))

;; Watchers for future bidirectional sync (Phase 2)
(defonce !sync-watchers-installed (atom #{}))

;; Track pending resync requests to prevent flooding
;; Set of keys with outstanding resync requests
(defonce !resync-pending (atom #{}))

;; Client ID for sending messages (forward declaration, set by init!)
(defonce !client-id (atom nil))

(defn get-synced-atom
  "Get or create a synced Reagent atom by key.
   Atoms are automatically synced with server."
  [key]
  (or (get @!synced-atoms key)
      (let [a (r/atom nil)]
        (swap! !synced-atoms assoc key a)
        a)))

(defn request-resync!
  "Request full resync from server for an atom.
   Called when gap detected in seq numbers.
   Prevents flooding by tracking pending requests."
  [key]
  (if (contains? @!resync-pending key)
    ;; Already have a pending resync for this key - don't flood
    (log/log! {:level :debug
               :id ::resync-already-pending
               :msg "Resync already pending, skipping duplicate request"
               :data {:key key}})
    ;; No pending resync - send request and mark as pending
    (do
      (swap! !resync-pending conj key)
      (log/log! {:level :warn
                 :id ::resync-requested
                 :msg "Requesting resync from server"
                 :data {:key key}})
      (when @!client-id
        (client/send! @!client-id [:sync/resync-request {:key key}])))))

(defn apply-sync-op
  "Apply a sync operation with seq validation and epoch detection.
   Returns :applied, :stale, or :gap.

   Protocol: [:sync/op {:key k :seq n :epoch e :op :assoc-in :path [] :value v}]

   Epoch detection: When server epoch changes (server restart), we reset
   all local sync state for that key and accept the update unconditionally.

   Special case: Full state replace (path []) accepts any seq and resets
   local tracking. This handles both initial sync and resync recovery."
  [{:keys [key seq epoch op path value]}]
  (let [last-epoch (get @!last-epoch key)
        epoch-changed? (and epoch last-epoch (not= epoch last-epoch))
        ;; On epoch change, reset local state
        _ (when epoch-changed?
            (log/log! {:level :warn
                       :id ::epoch-changed
                       :msg "Server epoch changed - server restarted, resetting local state"
                       :data {:key key :old-epoch last-epoch :new-epoch epoch}})
            (swap! !sync-state dissoc key)
            (swap! !resync-pending disj key))
        ;; Track current epoch
        _ (when epoch
            (swap! !last-epoch assoc key epoch))
        current-seq (get-in @!sync-state [key :seq] 0)
        expected-seq (inc current-seq)
        is-full-replace? (= path [])]
    (cond
      ;; Full state replace: accept any seq, reset tracking
      ;; This handles initial sync (server at seq 12, client at 0)
      ;; and resync responses (recovery from gaps)
      is-full-replace?
      (do
        (when-let [a (get-synced-atom key)]
          (reset! a value))
        (swap! !sync-state assoc-in [key :seq] seq)
        ;; Clear pending resync flag - we've received full state
        (swap! !resync-pending disj key)
        (log/log! {:level :info
                   :id ::full-sync-applied
                   :msg "Full state sync applied"
                   :data {:key key :seq seq :prev-seq current-seq :epoch epoch}})
        :applied)

      ;; Epoch changed - accept update even if seq looks wrong
      epoch-changed?
      (do
        (when-let [a (get-synced-atom key)]
          (swap! a assoc-in path value))
        (swap! !sync-state assoc-in [key :seq] seq)
        (log/log! {:level :info
                   :id ::sync-applied-epoch-reset
                   :msg "Sync op applied after epoch reset"
                   :data {:key key :seq seq :op op :path path}})
        :applied)

      ;; Normal case: sequential update
      (= seq expected-seq)
      (do
        (when-let [a (get-synced-atom key)]
          (swap! a assoc-in path value))
        (swap! !sync-state assoc-in [key :seq] seq)
        (log/log! {:level :debug
                   :id ::sync-applied
                   :msg "Sync op applied"
                   :data {:key key :seq seq :op op :path path}})
        :applied)

      ;; Gap detected: missed updates - request resync
      (> seq expected-seq)
      (do
        (log/log! {:level :warn
                   :id ::sync-gap
                   :msg "Sync gap detected"
                   :data {:key key :expected expected-seq :received seq}})
        (request-resync! key)
        :gap)

      ;; Stale/duplicate: ignore
      :else
      (do
        (log/log! {:level :debug
                   :id ::sync-stale
                   :msg "Ignoring stale sync message"
                   :data {:key key :current-seq current-seq :received seq}})
        :stale))))

(defn handle-resync-response
  "Handle resync response from server.
   Applies all ops and resets seq tracking.
   Clears pending resync flag to allow future requests."
  [{:keys [key ops error]}]
  ;; Always clear pending flag - response received (success or error)
  (swap! !resync-pending disj key)
  (if error
    (log/log! {:level :error
               :id ::resync-error
               :msg "Resync failed"
               :data {:key key :error error}})
    (do
      (log/log! {:level :info
                 :id ::resync-received
                 :msg "Resync response received"
                 :data {:key key :op-count (count ops)}})
      ;; Reset seq tracking before applying
      (swap! !sync-state assoc-in [key :seq] 0)
      ;; Apply all ops (they should start from seq 1 or current)
      (doseq [op ops]
        (let [[_ op-data] op]
          (apply-sync-op op-data))))))

(defn install-sync-watcher!
  "Install watcher to push atom changes back to server.
   (Phase 2 - bidirectional sync)"
  [key client-id]
  (when-not (contains? @!sync-watchers-installed key)
    (when-let [a (get @!synced-atoms key)]
      (add-watch a ::sync-to-server
        (fn [_ _ old-val new-val]
          (when (not= old-val new-val)
            (client/send! client-id [:sync/atom-update {:key key :value new-val}]))))
      (swap! !sync-watchers-installed conj key))))

(defn get-sync-status
  "Get current sync status for debugging."
  []
  {:atoms (keys @!synced-atoms)
   :state @!sync-state
   :epochs @!last-epoch})

;; =========================================================================
;; Connection Client
;; =========================================================================

(defonce !initialized (atom false))
(defonce !browser-session-id (atom nil))
;; !client-id moved to sync section (forward declaration)
(defonce !event-handlers (atom {}))

(defn register-event-handler!
  "Register a handler for custom event types.
   handler-fn will be called with [event-id data]."
  [event-prefix handler-fn]
  (swap! !event-handlers assoc event-prefix handler-fn)
  (js/console.log (str "[bootstrap] Registered handler for " event-prefix))
  (log/log! {:level :info :id ::handler-registered
             :msg "Event handler registered"
             :data {:prefix event-prefix}}))

(defn dispatch-custom-event!
  "Dispatch to custom event handlers by prefix match."
  [event-id data]
  (js/console.log (str "[dispatch] event-id=" event-id " handlers=" (pr-str (keys @!event-handlers))))
  (if-let [ns-str (namespace event-id)]
    (if-let [handler (get @!event-handlers (keyword ns-str))]
      (do
        (js/console.log (str "[dispatch] Found handler for " ns-str ", calling..."))
        (log/log! {:level :debug :id ::event-dispatched
                   :msg "Custom event dispatched"
                   :data {:event-id event-id :handler-ns ns-str}})
        (handler [event-id data]))
      (do
        (js/console.log (str "[dispatch] No handler for namespace: " ns-str))
        (log/log! {:level :warn :id ::no-handler
                   :msg "No handler for event namespace"
                   :data {:event-id event-id :ns ns-str}})))
    (js/console.log "[dispatch] Event has no namespace")))

(defn get-or-create-session-id
  "Get existing session-id or create a new one."
  []
  (or @!browser-session-id
      (let [new-id (str "session-" (com.github.franks42.uuidv7.core/uuidv7))]
        (reset! !browser-session-id new-id)
        new-id)))

(defn log-el
  "Get log DOM element."
  [] (js/document.getElementById "log"))

(defn status-el
  "Get status DOM element."
  [] (js/document.getElementById "status"))

(defn log!
  "Log a message to the browser log panel."
  [class msg]
  (when-let [el (log-el)]
    (let [entry (js/document.createElement "div")]
      (set! (.-className entry) (str "log-entry log-" class))
      (set! (.-textContent entry) msg)
      (.appendChild el entry)
      (set! (.-scrollTop el) (.-scrollHeight el)))))

(defn set-status!
  "Set the status bar text and CSS class."
  [status text]
  (when-let [el (status-el)]
    (set! (.-className el) (str "status " status))
    (set! (.-textContent el) text)))

(defn init!
  "Initialize the code browser WebSocket connection."
  []
  (when (compare-and-set! !initialized false true)
    (log! "info" (str "Connecting to " ws-url "..."))
    (set-status! "connecting" "Connecting...")

    (let [cid (client/make-client!
                {:url ws-url
                 :on-open (fn [uid]
                            (set-status! "connecting" (str "Handshaking (uid: " uid ")"))
                            (log! "info" (str "WebSocket connected, uid: " uid))
                            (let [session-id (get-or-create-session-id)]
                              (log! "info" (str "Session ID: " session-id))
                              (client/send! @!client-id [:client/ready {:session-id session-id}]))
                            (adapter/connect! {:client @!client-id
                                               :on-connect #(log! "info" "nREPL adapter connected")}))
                 :on-close (fn [event]
                             (set-status! "disconnected" "Disconnected")
                             (log! "info" "WebSocket disconnected")
                             (adapter/disconnect!))
                 :on-reconnect (fn []
                                 (set-status! "connecting" "Reconnecting...")
                                 (log! "info" "WebSocket reconnected")
                                 (let [session-id (get-or-create-session-id)]
                                   (log! "info" (str "Re-sending session ID: " session-id))
                                   (client/send! @!client-id [:client/ready {:session-id session-id}]))
                                 (adapter/connect! {:client @!client-id}))
                 :on-message (fn [event-id data]
                               (case event-id
                                 :heartbeat/ping
                                 (client/send! @!client-id [:heartbeat/pong {}])
                                 :server/ready
                                 (let [{:keys [nickname connection-id reconnect]} data]
                                   (set-status! "connected" (str "Code Browser - " nickname))
                                   (log! "info" (str (if reconnect "Reconnected" "Registered")
                                                     " as " nickname))
                                   ;; Start browser telemetry forwarding
                                   (browser-telemetry/init! @!client-id)
                                   ;; Enable the Load UI button
                                   (when-let [enable-fn (aget js/window "enableLoadButton")]
                                     (enable-fn)))
                                 :sync/op
                                 (apply-sync-op data)
                                 :sync/resync-response
                                 (handle-resync-response data)
                                 :nrepl/request
                                 (log! "eval" (str "eval: " (subs (pr-str (:code data)) 0 (min 50 (count (pr-str (:code data)))))))
                                 ;; Try custom handlers for unrecognized events
                                 (dispatch-custom-event! event-id data)))})]
      (reset! !client-id cid))

    (log! "info" "Code Browser ready - click button or load UI via nREPL")))

;; Initialize
(init!)

;; =========================================================================
;; Dev helpers (available in REPL)
;; =========================================================================

(defn cm6-ready?
  "Check if CodeMirror 6 is loaded."
  [] js/window.CM6_READY)

(defn mount-root!
  "Mount a component to #code-browser-root with error boundary."
  [component]
  (rdom/render [error-boundary component]
               (js/document.getElementById "code-browser-root")))
