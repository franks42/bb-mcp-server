(ns sente-browser.server
    "Embed sente-lite WebSocket server for browser nREPL connections.

   When a browser connects via WebSocket:
   1. sente-lite handles the WebSocket lifecycle
   2. We send :describe probe to verify nREPL capability
   3. On valid response, register as validated nREPL connection
   4. Claude can then use nrepl-eval with that connection

   Key difference from socket connections:
   - Browsers connect TO us (vs Claude connecting to nREPL servers)
   - Claude discovers browsers via `op=list`, then selects one to eval in
   - Only validated browsers (responded to :describe) are listed"
    (:require [clojure.set :as set]
              [sente-lite.server :as sente-server]
              [nrepl.state.connection :as conn-state]
              [nrepl.state.messages :as msg-state]
              [nrepl.state.results :as results]
              [nrepl.state.watchers :as watchers]
              [nrepl.utils.uuid-v7 :as uuid]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Map of sente-conn-id -> {:mcp-conn-id string, :last-heartbeat epoch-ms,
;;                          :status :pending-validation | :validated,
;;                          :probe-id string, :capabilities {...}}
(defonce ^:private !browser-connections (atom {}))

;; Connection sync task control
(defonce ^:private !sync-task-running (atom false))

;; Heartbeat task control
(defonce ^:private !heartbeat-task-running (atom false))

;; Heartbeat configuration (in ms)
(def ^:private heartbeat-interval-ms 10000)  ; Send heartbeat every 10s
(def ^:private heartbeat-timeout-ms 30000)   ; Consider stale after 30s
;; NOTE: validation-timeout-ms not yet used - pending validation cleanup runs via heartbeat

;; =============================================================================
;; Browser Connection Management
;; =============================================================================

(defn- send-describe-probe!
  "Send :describe probe to verify browser has nREPL capability."
  [sente-conn-id probe-id]
  (log/log! {:level :info
             :id ::sending-describe-probe
             :msg "Sending :describe probe to validate nREPL capability"
             :data {:sente-conn-id sente-conn-id :probe-id probe-id}})
  (sente-server/send-event-to-connection!
   sente-conn-id
   [:nrepl/request {:op :describe :id probe-id}]))

(defn handle-browser-connect!
  "Called when a browser connects via WebSocket.
   Starts in pending-validation status and sends :describe probe.
   Connection is promoted to validated after successful :describe response.
   Called by connection sync task when new sente connections are detected."
  [sente-conn-id]
  (let [probe-id (str "probe-" (uuid/uuid-v7-string))
        now (System/currentTimeMillis)]
    ;; Track as pending validation (not yet registered with conn-state)
    (swap! !browser-connections assoc sente-conn-id
           {:status :pending-validation
            :probe-id probe-id
            :connected-at now
            :last-heartbeat now})
    (log/log! {:level :info
               :id ::browser-connected-pending
               :msg "Browser connected, pending nREPL validation"
               :data {:sente-conn-id sente-conn-id :probe-id probe-id}})
    ;; Send describe probe to validate nREPL capability
    (send-describe-probe! sente-conn-id probe-id)
    probe-id))

(defn handle-browser-disconnect!
  "Called when a browser disconnects.
   Called by connection sync task when sente connections disappear."
  [sente-conn-id]
  (when-let [conn-info (get @!browser-connections sente-conn-id)]
    ;; Only close in conn-state if it was validated (has mcp-conn-id)
            (when-let [mcp-conn-id (:mcp-conn-id conn-info)]
                      (conn-state/mark-connection-closed! mcp-conn-id :browser-disconnect "Browser closed"))
            (swap! !browser-connections dissoc sente-conn-id)
            (log/log! {:level :info
                       :id ::browser-disconnected
                       :msg "Browser disconnected"
                       :data {:sente-conn-id sente-conn-id
                              :status (:status conn-info)
                              :mcp-conn-id (:mcp-conn-id conn-info)}})))

(defn- update-heartbeat!
  "Update last-heartbeat timestamp for a connection."
  [sente-conn-id]
  (swap! !browser-connections update sente-conn-id
         assoc :last-heartbeat (System/currentTimeMillis)))

(defn- promote-to-validated!
  "Promote a pending connection to validated after successful :describe response.
   Registers with conn-state and stores capabilities."
  [sente-conn-id describe-response]
  (let [ops (get describe-response :ops {})
        versions (get describe-response :versions {})]
    (if (get ops "eval")
      ;; Valid nREPL - has eval capability
      (let [mcp-conn-id (conn-state/register-browser-connection! sente-conn-id)
            capabilities {:ops (keys ops)
                          :nrepl-version (get versions "sci-nrepl")}]
        ;; Update connection state with capabilities
        (conn-state/update-browser-capabilities! mcp-conn-id capabilities)
        ;; Update our tracking
        (swap! !browser-connections update sente-conn-id merge
               {:status :validated
                :mcp-conn-id mcp-conn-id
                :capabilities capabilities})
        (log/log! {:level :info
                   :id ::browser-validated
                   :msg "Browser nREPL validated"
                   :data {:sente-conn-id sente-conn-id
                          :mcp-conn-id mcp-conn-id
                          :ops (keys ops)
                          :nrepl-version (get versions "sci-nrepl")}})
        mcp-conn-id)
      ;; No eval capability - not a valid nREPL
      (do
       (log/log! {:level :warn
                  :id ::browser-validation-failed
                  :msg "Browser lacks eval capability, not registering"
                  :data {:sente-conn-id sente-conn-id
                         :ops (keys ops)}})
       (swap! !browser-connections dissoc sente-conn-id)
       nil))))

(defn- handle-describe-response!
  "Handle :describe response for pending validation."
  [sente-conn-id response]
  (let [conn-info (get @!browser-connections sente-conn-id)
        probe-id (:probe-id conn-info)
        response-id (:id response)]
    (when (and (= :pending-validation (:status conn-info))
               (= probe-id response-id))
      (log/log! {:level :debug
                 :id ::describe-response-received
                 :msg "Received :describe response for probe"
                 :data {:sente-conn-id sente-conn-id
                        :probe-id probe-id
                        :ops (keys (:ops response))}})
      (promote-to-validated! sente-conn-id response))))

(defn- on-browser-message
  "Handle message from browser - route nREPL responses and heartbeats."
  [sente-conn-id event-id data]
  (log/log! {:level :debug
             :id ::browser-message
             :msg "Received browser message"
             :data {:sente-conn-id sente-conn-id
                    :event-id event-id
                    :data-keys (keys data)}})

  (case event-id
    ;; Heartbeat pong - update timestamp
    :heartbeat/pong
    (do
     (update-heartbeat! sente-conn-id)
     (log/log! {:level :trace
                :id ::heartbeat-pong
                :msg "Heartbeat pong received"
                :data {:sente-conn-id sente-conn-id}}))

    ;; nREPL response - native map format (browser_adapter parses before sending)
    :nrepl/response
    (let [conn-info (get @!browser-connections sente-conn-id)
          msg-id (:id data)]
      (cond
        ;; Check if this is a describe probe response for pending validation
        (and (= :pending-validation (:status conn-info))
             (= (:probe-id conn-info) msg-id))
        (handle-describe-response! sente-conn-id data)

        ;; Validated connection - route to waiting promises
        (= :validated (:status conn-info))
        (do
         (log/log! {:level :debug
                    :id ::routing-response
                    :msg "Routing nREPL response"
                    :data {:msg-id msg-id
                           :status (:status data)
                           :value (:value data)}})
          ;; deliver-result! looks up connection from message-id internally
         (results/deliver-result! msg-id {:status :success :response data}))

        :else
        (log/log! {:level :warn
                   :id ::unexpected-response
                   :msg "Response from unvalidated connection"
                   :data {:sente-conn-id sente-conn-id
                          :status (:status conn-info)
                          :msg-id msg-id
                          :probe-id (:probe-id conn-info)
                          :response-keys (keys data)}})))

    ;; Unknown event - log it
    (log/log! {:level :debug
               :id ::unknown-event
               :msg "Unknown browser event"
               :data {:event-id event-id}})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn browser-count
  "Get count of connected browsers."
  []
  (count @!browser-connections))

(defn get-browser-connections
  "Get all browser connections as {sente-conn-id {:mcp-conn-id ... :last-heartbeat ...}}."
  []
  @!browser-connections)

(defn get-mcp-conn-id
  "Get MCP connection ID for a sente connection ID."
  [sente-conn-id]
  (get-in @!browser-connections [sente-conn-id :mcp-conn-id]))

(defn get-sente-conn-id
  "Get sente connection ID for an MCP connection ID."
  [target-mcp-conn-id]
  (->> @!browser-connections
       (filter (fn [[_ {:keys [mcp-conn-id]}]] (= mcp-conn-id target-mcp-conn-id)))
       first
       first))

(defn send-to-browser!
  "Send an event to a browser via sente.
   Returns true if sent, false otherwise."
  [sente-conn-id event]
  (sente-server/send-event-to-connection! sente-conn-id event))

(defn broadcast-to-browsers!
  "Send an event to all connected browsers.
   Returns count of browsers message was sent to."
  [event]
  (let [conn-ids (keys @!browser-connections)]
    (doseq [conn-id conn-ids]
           (send-to-browser! conn-id event))
    (count conn-ids)))

(defn get-connection-health
  "Get health status for all browser connections.
   Returns map of sente-conn-id -> {:healthy? bool :last-seen-ms-ago long}."
  []
  (let [now (System/currentTimeMillis)]
    (into {}
          (map (fn [[sente-conn-id {:keys [mcp-conn-id last-heartbeat]}]]
                 (let [ms-ago (- now last-heartbeat)]
                   [sente-conn-id {:mcp-conn-id mcp-conn-id
                                   :healthy? (< ms-ago heartbeat-timeout-ms)
                                   :last-seen-ms-ago ms-ago}]))
               @!browser-connections))))

;; =============================================================================
;; Connection Sync Task
;; =============================================================================

(defn- sync-connections!
  "Sync our browser registry with sente-lite's connection state.
   Detects new connections and disconnections."
  []
  (let [sente-conns (set (map :conn-id (sente-server/get-connections)))
        our-conns (set (keys @!browser-connections))
        new-conns (set/difference sente-conns our-conns)
        gone-conns (set/difference our-conns sente-conns)]

    ;; Register new connections
    (doseq [conn-id new-conns]
           (handle-browser-connect! conn-id))

    ;; Handle disconnections
    (doseq [conn-id gone-conns]
           (handle-browser-disconnect! conn-id))))

(defn- start-sync-task!
  "Start background task to sync connections every 500ms."
  []
  (reset! !sync-task-running true)
  (future
   (while @!sync-task-running
          (try
           (sync-connections!)
           (catch Exception e
                  (log/log! {:level :error
                             :id ::sync-error
                             :msg "Connection sync error"
                             :data {:error (.getMessage e)}})))
          (Thread/sleep 500))))

(defn- stop-sync-task!
  "Stop the connection sync background task."
  []
  (reset! !sync-task-running false))

;; =============================================================================
;; Heartbeat Task
;; =============================================================================

(defn- send-heartbeat-ping!
  "Send heartbeat ping to a single browser."
  [sente-conn-id]
  (try
   (send-to-browser! sente-conn-id [:heartbeat/ping {:ts (System/currentTimeMillis)}])
   (catch Exception e
          (log/log! {:level :warn
                     :id ::heartbeat-send-failed
                     :msg "Failed to send heartbeat"
                     :data {:sente-conn-id sente-conn-id :error (.getMessage e)}}))))

(defn- check-stale-connections!
  "Check for and disconnect stale browsers (no heartbeat response)."
  []
  (let [now (System/currentTimeMillis)
        stale-conns (->> @!browser-connections
                         (filter (fn [[_ {:keys [last-heartbeat]}]]
                                   (> (- now last-heartbeat) heartbeat-timeout-ms)))
                         (map first))]
    (when (seq stale-conns)
      (log/log! {:level :info
                 :id ::stale-connections-detected
                 :msg "Disconnecting stale browsers"
                 :data {:count (count stale-conns)}})
      (doseq [conn-id stale-conns]
             (handle-browser-disconnect! conn-id)))))

(defn- heartbeat-cycle!
  "Run one heartbeat cycle: send pings to all, check for stale."
  []
  ;; Send pings to all connected browsers
  (doseq [conn-id (keys @!browser-connections)]
         (send-heartbeat-ping! conn-id))
  ;; Check for stale connections
  (check-stale-connections!))

(defn- start-heartbeat-task!
  "Start background heartbeat task."
  []
  (reset! !heartbeat-task-running true)
  (future
   (while @!heartbeat-task-running
          (try
           (heartbeat-cycle!)
           (catch Exception e
                  (log/log! {:level :error
                             :id ::heartbeat-error
                             :msg "Heartbeat cycle error"
                             :data {:error (.getMessage e)}})))
          (Thread/sleep heartbeat-interval-ms))))

(defn- stop-heartbeat-task!
  "Stop the heartbeat background task."
  []
  (reset! !heartbeat-task-running false))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start!
  "Start the sente-lite WebSocket server.

   Config options:
   - :host - bind address (default 127.0.0.1)
   - :ws-port - WebSocket port (default 8090)"
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :ws-port 8090)]
    (log/log! {:level :info
               :id ::starting
               :msg "Starting sente WebSocket server"
               :data {:host host :port port}})

    (sente-server/start-server!
     {:host host
      :port port
      :on-message on-browser-message})

    ;; Register browser send function so watchers can send to browsers
    (msg-state/register-browser-send-fn! send-to-browser!)

    ;; Start global message queue watcher
    (watchers/start-all-watchers!)

    ;; Start background task to sync browser connections
    (start-sync-task!)

    ;; Start heartbeat task for connection health monitoring
    (start-heartbeat-task!)

    (log/log! {:level :info
               :id ::started
               :msg "Sente WebSocket server started"
               :data {:host host
                      :port port
                      :url (str "ws://" host ":" port)}})))

(defn stop!
  "Stop the sente-lite WebSocket server."
  []
  (log/log! {:level :info
             :id ::stopping
             :msg "Stopping sente WebSocket server"
             :data {:browser-count (browser-count)}})

  ;; Stop heartbeat task first
  (stop-heartbeat-task!)

  ;; Stop sync task
  (stop-sync-task!)

  ;; Stop global message queue watcher
  (watchers/stop-all-watchers!)

  ;; Unregister browser send function
  (msg-state/unregister-browser-send-fn!)

  ;; Mark all browser connections as closed
  (doseq [[_sente-conn-id {:keys [mcp-conn-id]}] @!browser-connections]
         (conn-state/mark-connection-closed! mcp-conn-id :server-shutdown "Server stopping"))

  ;; Clear our tracking
  (reset! !browser-connections {})

  ;; Stop sente server
  (sente-server/stop-server!)

  (log/log! {:level :info
             :id ::stopped
             :msg "Sente WebSocket server stopped"}))
