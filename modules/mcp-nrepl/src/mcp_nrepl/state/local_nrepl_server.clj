(ns mcp-nrepl.state.local-nrepl-server
    "State management for embedded local babashka nREPL server lifecycle.

   Uses clj-statecharts for validated state transitions:
   stopped -> starting -> running -> stopping -> stopped
                  |                      |
                error                  error

   The state machine enforces valid transitions — attempting an invalid
   transition (e.g. stop when already stopped) throws an exception."
    (:require [babashka.nrepl.server :as nrepl-server]
              [statecharts.core :as fsm]
              [taoensso.trove :as log]))

;; =============================================================================
;; Context Assignment Actions (named vars for navigability)
;; =============================================================================

(defn assign-config
  "Store server config and clear error on start."
  [ctx event]
  (assoc ctx :config (:config event) :error nil))

(defn assign-server-info
  "Store connection info after successful server start."
  [ctx event]
  (merge ctx (select-keys event [:server-map :host :port :connection :started-at])))

(defn assign-error
  "Store error message on failure."
  [ctx event]
  (assoc ctx :error (:error event)))

(defn assign-stopped
  "Clear server-map and record stop timestamp."
  [ctx event]
  (assoc ctx
         :server-map nil
         :stopped-at (:stopped-at event)
         :error nil))

;; =============================================================================
;; State Machine Definition
;; =============================================================================

(def nrepl-server-machine-config
     "Configuration map for the nREPL server state machine.
   Inspectable at runtime — use this var to see states/transitions."
     {:id      :nrepl-server
      :initial :stopped
      :context {:server-map nil
                :host       "localhost"
                :port       nil
                :connection nil
                :started-at nil
                :stopped-at nil
                :config     {}
                :error      nil}
      :states
      {:stopped  {:on {:start {:target  :starting
                               :actions [(fsm/assign assign-config)]}}}
       :starting {:on {:started {:target  :running
                                 :actions [(fsm/assign assign-server-info)]}
                       :failed  {:target  :error
                                 :actions [(fsm/assign assign-error)]}}}
       :running  {:on {:stop :stopping}}
       :stopping {:on {:stopped {:target  :stopped
                                 :actions [(fsm/assign assign-stopped)]}
                       :failed  {:target  :error
                                 :actions [(fsm/assign assign-error)]}}}
       :error    {:on {:start {:target  :starting
                               :actions [(fsm/assign assign-config)]}
                       :reset :stopped}}}})

(def nrepl-server-machine
     "Compiled state machine for local nREPL server lifecycle.

   States: stopped -> starting -> running -> stopping -> stopped
                         |                      |
                       error                  error

   Events:
   - :start   - Begin server startup (from :stopped or :error)
   - :started - Server successfully started (from :starting)
   - :failed  - Server start/stop failed (from :starting or :stopping)
   - :stop    - Begin server shutdown (from :running)
   - :stopped - Server successfully stopped (from :stopping)
   - :reset   - Reset to stopped state (from :error)"
     (fsm/machine nrepl-server-machine-config))

;; =============================================================================
;; State Atom
;; =============================================================================

(def !state
     "State atom initialized from the nrepl-server-machine.
   Contains :_state (current state keyword) plus context fields."
     (atom (fsm/initialize nrepl-server-machine {:exec false})))

;; =============================================================================
;; Port Extraction Utilities
;; =============================================================================

(defn extract-server-port
  "Extract actual bound port from server socket.
   Handles auto-assigned ports (port 0) properly."
  [server-map]
  (when-let [socket (:socket server-map)]
            (.getLocalPort socket)))

(defn extract-server-host
  "Extract host from server configuration or default to localhost."
  [config]
  (get config :host "localhost"))

(defn format-connection
  "Format host:port connection string."
  [host port]
  (str host ":" port))

;; =============================================================================
;; State Transition Helper
;; =============================================================================

(defn- transition!
  "Apply a state machine transition atomically.
   Logs before/after state for full observability.
   Returns the new state. Throws on invalid transition."
  [event]
  (let [from-state (:_state @!state)]
    (swap! !state #(fsm/transition nrepl-server-machine % event))
    (let [to-state (:_state @!state)]
      (log/log! {:level :info :id ::transition
                 :msg (str "Transition: " (name from-state) " -> " (name to-state))
                 :data {:from from-state
                        :to to-state
                        :event (:type event)}}))))

;; =============================================================================
;; State Query Functions
;; =============================================================================

(defn running?
  "Check if nREPL server is currently running."
  []
  (= :running (:_state @!state)))

(defn get-status
  "Get current server status."
  []
  (:_state @!state))

(defn get-server-map
  "Get stored server map (contains :socket and :future)."
  []
  (:server-map @!state))

(defn get-connection-info
  "Get current connection information as map."
  []
  (let [state @!state]
    {:host       (:host state)
     :port       (:port state)
     :connection (:connection state)
     :status     (:_state state)
     :started-at (:started-at state)
     :stopped-at (:stopped-at state)
     :error      (:error state)}))

(defn get-uptime-ms
  "Get server uptime in milliseconds, or nil if not running."
  []
  (when-let [started-at (:started-at @!state)]
            (when (running?)
              (- (System/currentTimeMillis) started-at))))

;; =============================================================================
;; State Management Functions
;; =============================================================================

(defn start-server!
  "Start babashka nREPL server and update state.
   Config options:
   - :port (optional) - Port to bind to, 0 for auto-assign
   - :host (optional) - Host to bind to, defaults to localhost"
  [config]
  (log/log! {:level :info :id ::start-server
             :msg "Starting nREPL server"
             :data {:config (dissoc config :server-map)}})
  ;; 1. Transition: :stopped -> :starting (throws if invalid)
  (transition! {:type :start :config config})
  (try
    ;; 2. Effect: actually start the server
   (let [server-map (nrepl-server/start-server! config)
         host       (extract-server-host config)
         port       (extract-server-port server-map)
         connection (format-connection host port)
         started-at (System/currentTimeMillis)]
      ;; 3. Transition: :starting -> :running
     (transition! {:type       :started
                   :server-map server-map
                   :host       host
                   :port       port
                   :connection connection
                   :started-at started-at})
     (log/log! {:level :info :id ::server-started
                :msg "nREPL server started"
                :data {:host host :port port :connection connection}})
      ;; Return connection info
     {:status     :running
      :host       host
      :port       port
      :connection connection
      :started-at started-at})
   (catch Exception e
          (log/log! {:level :error :id ::start-failed
                     :msg "nREPL server start failed"
                     :data {:error (.getMessage e)}})
      ;; 3b. Transition: :starting -> :error
          (transition! {:type :failed :error (.getMessage e)})
          (throw e))))

(defn stop-server!
  "Stop babashka nREPL server and update state."
  []
  (let [server-map     (get-server-map)
        connection-info (get-connection-info)]
    (log/log! {:level :info :id ::stop-server
               :msg "Stopping nREPL server"
               :data {:connection (:connection connection-info)}})
    ;; 1. Transition: :running -> :stopping (throws if invalid)
    (transition! {:type :stop})
    (try
      ;; 2. Effect: actually stop the server
     (nrepl-server/stop-server! server-map)
     (let [stopped-at (System/currentTimeMillis)]
        ;; 3. Transition: :stopping -> :stopped
       (transition! {:type :stopped :stopped-at stopped-at})
       (log/log! {:level :info :id ::server-stopped
                  :msg "nREPL server stopped"
                  :data {:connection (:connection connection-info)
                         :stopped-at stopped-at}})
        ;; Return info about stopped server
       (assoc connection-info
              :status     :stopped
              :stopped-at stopped-at))
     (catch Exception e
            (log/log! {:level :error :id ::stop-failed
                       :msg "nREPL server stop failed"
                       :data {:error (.getMessage e)}})
        ;; 3b. Transition: :stopping -> :error
            (transition! {:type :failed :error (.getMessage e)})
            (throw e)))))

(defn restart-server!
  "Restart nREPL server with given config."
  [config]
  (when (running?)
    (stop-server!))
  (start-server! config))

;; =============================================================================
;; Debug Support
;; =============================================================================

(defn get-full-state
  "Get complete state for debugging."
  []
  @!state)

(defn reset-state!
  "Reset state to initial values (for testing)."
  []
  (reset! !state (fsm/initialize nrepl-server-machine {:exec false})))
