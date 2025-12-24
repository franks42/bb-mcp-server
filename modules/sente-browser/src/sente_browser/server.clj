(ns sente-browser.server
    "Embed sente-lite WebSocket server for browser nREPL connections.

   When a browser connects via WebSocket:
   1. sente-lite handles the WebSocket lifecycle
   2. We register the browser as an nREPL connection (type :browser)
   3. Claude can then use nrepl-eval with that connection

   Key difference from socket connections:
   - Browsers connect TO us (vs Claude connecting to nREPL servers)
   - Claude discovers browsers via `op=list`, then selects one to eval in"
    (:require [clojure.set :as set]
              [sente-lite.server :as sente-server]
              [nrepl.state.connection :as conn-state]
              [nrepl.state.results :as results]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Map of sente-conn-id -> {:mcp-conn-id string, :last-heartbeat epoch-ms}
(defonce ^:private !browser-connections (atom {}))

;; Connection sync task control
(defonce ^:private !sync-task-running (atom false))

;; Heartbeat task control
(defonce ^:private !heartbeat-task-running (atom false))

;; Heartbeat configuration (in ms)
(def ^:private heartbeat-interval-ms 10000)  ; Send heartbeat every 10s
(def ^:private heartbeat-timeout-ms 30000)   ; Consider stale after 30s

;; =============================================================================
;; Browser Connection Management
;; =============================================================================

(defn handle-browser-connect!
  "Called when a browser connects via WebSocket.
   Registers the browser as an nREPL connection.
   Called by connection sync task when new sente connections are detected."
  [sente-conn-id]
  (let [mcp-conn-id (conn-state/register-browser-connection! sente-conn-id)
        now (System/currentTimeMillis)]
    (swap! !browser-connections assoc sente-conn-id
           {:mcp-conn-id mcp-conn-id
            :last-heartbeat now})
    (log/log! {:level :info
               :id ::browser-connected
               :msg "Browser connected"
               :data {:sente-conn-id sente-conn-id
                      :mcp-conn-id mcp-conn-id}})
    mcp-conn-id))

(defn handle-browser-disconnect!
  "Called when a browser disconnects.
   Called by connection sync task when sente connections disappear."
  [sente-conn-id]
  (when-let [{:keys [mcp-conn-id]} (get @!browser-connections sente-conn-id)]
            (conn-state/mark-connection-closed! mcp-conn-id :browser-disconnect "Browser closed")
            (swap! !browser-connections dissoc sente-conn-id)
            (log/log! {:level :info
                       :id ::browser-disconnected
                       :msg "Browser disconnected"
                       :data {:sente-conn-id sente-conn-id
                              :mcp-conn-id mcp-conn-id}})))

(defn- update-heartbeat!
  "Update last-heartbeat timestamp for a connection."
  [sente-conn-id]
  (swap! !browser-connections update sente-conn-id
         assoc :last-heartbeat (System/currentTimeMillis)))

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

    ;; nREPL response - route to waiting promises
    :nrepl/response
    (when-let [{:keys [_mcp-conn-id]} (get @!browser-connections sente-conn-id)]
              (when-let [msg-id (:id data)]
                        (log/log! {:level :debug
                                   :id ::routing-response
                                   :msg "Routing nREPL response"
                                   :data {:msg-id msg-id
                                          :status (:status data)}})
        ;; deliver-result! looks up connection from message-id internally
                        (results/deliver-result! msg-id {:status :success :response data})))

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
