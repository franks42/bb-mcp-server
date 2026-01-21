(ns sente-browser.server
    "Embed sente-lite WebSocket server for browser nREPL connections.

   Protocol flow:
   1. Browser connects via WebSocket
   2. Browser sends :client/ready {:session-id ...} when handlers ready
   3. Server registers connection, sends :describe probe
   4. Browser responds to describe
   5. Server validates, sends :server/ready {:nickname ...}
   6. Both sides now ready for normal communication

   Key difference from socket connections:
   - Browsers connect TO us (vs Claude connecting to nREPL servers)
   - Claude discovers browsers via `op=list`, then selects one to eval in
   - Only validated browsers (responded to :describe) are listed"
    (:require [clojure.string :as str]
              [sente-lite.server :as sente-server]
              [sente-browser.code-browser :as code-browser]
              [code-browser.core :as code-browser-v2]
              [atom-sync.server :as atom-sync]
              [mcp-nrepl.state.connection :as conn-state]
              [mcp-nrepl.state.messages :as msg-state]
              [mcp-nrepl.state.results :as results]
              [mcp-nrepl.state.watchers :as watchers]
              [mcp-nrepl.utils.uuid-v7 :as uuid]
              [taoensso.trove :as log]))

;; Forward declarations
(declare send-to-browser!)

;; =============================================================================
;; State
;; =============================================================================

;; Map of sente-conn-id -> {:mcp-conn-id string, :last-heartbeat epoch-ms,
;;                          :status :pending-client-ready | :pending-validation | :validated,
;;                          :probe-id string, :capabilities {...}, :session-id string}
(defonce ^:private !browser-connections (atom {}))

;; Session registry: session-id -> mcp-conn-id
;; Persists across WebSocket reconnects so browser keeps same identity
(defonce ^:private !session-registry (atom {}))

;; Heartbeat task control
(defonce ^:private !heartbeat-task-running (atom false))

;; Heartbeat configuration (in ms)
(def ^:private heartbeat-interval-ms 10000)  ; Send heartbeat every 10s
(def ^:private heartbeat-timeout-ms 30000)   ; Consider stale after 30s

;; Session registry cleanup (truly stale sessions that won't reconnect)
(def ^:private session-stale-threshold-ms (* 60 60 1000))  ; 1 hour

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

(defn- handle-client-ready!
  "Handle :client/ready from browser - this is the handshake initiation.
   Browser is signaling that its handlers are ready for communication."
  [sente-conn-id {:keys [session-id]}]
  (let [probe-id (str "probe-" (uuid/uuid-v7-string))
        now (System/currentTimeMillis)]
    ;; Register connection with session-id immediately
    (swap! !browser-connections assoc sente-conn-id
           {:status :pending-validation
            :session-id session-id
            :probe-id probe-id
            :connected-at now
            :last-heartbeat now})
    (log/log! {:level :info
               :id ::client-ready-received
               :msg "Browser client-ready received, starting validation"
               :data {:sente-conn-id sente-conn-id
                      :session-id session-id
                      :has-registry-entry (boolean (get @!session-registry session-id))}})
    ;; Send describe probe to validate nREPL capability
    ;; Return nil to avoid sente-lite echoing response back to browser
    (send-describe-probe! sente-conn-id probe-id)
    nil))

(defn handle-browser-disconnect!
  "Called when a browser disconnects.

   Note: Does NOT remove session-id from !session-registry so browser
   can reconnect with same identity. Registry cleanup happens separately
   for truly stale sessions."
  [sente-conn-id]
  (when-let [conn-info (get @!browser-connections sente-conn-id)]
    ;; Only close in conn-state if it was validated (has mcp-conn-id)
            (when-let [mcp-conn-id (:mcp-conn-id conn-info)]
                      (conn-state/mark-connection-closed! mcp-conn-id :browser-disconnect "Browser closed"))
            (swap! !browser-connections dissoc sente-conn-id)
            (log/log! {:level :info
                       :id ::browser-disconnected
                       :msg "Browser disconnected (session registry preserved for reconnect)"
                       :data {:sente-conn-id sente-conn-id
                              :status (:status conn-info)
                              :session-id (:session-id conn-info)
                              :mcp-conn-id (:mcp-conn-id conn-info)}})))

(defn- update-heartbeat!
  "Update last-heartbeat timestamp for a connection."
  [sente-conn-id]
  (swap! !browser-connections update sente-conn-id
         assoc :last-heartbeat (System/currentTimeMillis)))

(defn- promote-to-validated!
  "Promote a pending connection to validated after successful :describe response.
   Registers with conn-state and stores capabilities.
   Sends :server/ready event to browser with connection nickname.

   If browser sent session-id with a known registry entry, reuses existing
   mcp-conn-id for stable identity across WebSocket reconnects."
  [sente-conn-id describe-response]
  (let [ops (get describe-response :ops {})
        versions (get describe-response :versions {})]
    (if (get ops "eval")
      ;; Valid nREPL - has eval capability
      (let [session-id (get-in @!browser-connections [sente-conn-id :session-id])
            ;; Check if this session-id has existing mcp-conn-id
            existing-mcp-conn-id (when session-id (get @!session-registry session-id))
            ;; Reuse existing or create new
            mcp-conn-id (if existing-mcp-conn-id
                          (do
                            ;; Reactivate existing connection with new sente-conn-id
                           (conn-state/reactivate-browser-connection! existing-mcp-conn-id sente-conn-id)
                           existing-mcp-conn-id)
                          (conn-state/register-browser-connection! sente-conn-id))
            ;; Extract nickname from mcp-conn-id (e.g., "browser-2-uuid" -> "browser-2")
            nickname (when mcp-conn-id
                       (let [parts (str/split mcp-conn-id #"-")]
                         (str (first parts) "-" (second parts))))
            capabilities {:ops (keys ops)
                          :nrepl-version (get versions "sci-nrepl")}
            is-reconnect (boolean existing-mcp-conn-id)]
        ;; Update session registry with session-id -> mcp-conn-id mapping
        (when session-id
          (swap! !session-registry assoc session-id mcp-conn-id))
        ;; Update connection state with capabilities
        (conn-state/update-browser-capabilities! mcp-conn-id capabilities)
        ;; Update our tracking
        (swap! !browser-connections update sente-conn-id merge
               {:status :validated
                :mcp-conn-id mcp-conn-id
                :nickname nickname
                :capabilities capabilities})
        ;; Send server/ready to complete handshake - browser is now fully connected
        (send-to-browser! sente-conn-id [:server/ready {:nickname nickname
                                                        :connection-id mcp-conn-id
                                                        :reconnect is-reconnect}])
        (log/log! {:level :info
                   :id ::browser-validated
                   :msg (if is-reconnect
                          "Browser nREPL reconnected with same identity"
                          "Browser nREPL validated (new)")
                   :data {:sente-conn-id sente-conn-id
                          :mcp-conn-id mcp-conn-id
                          :nickname nickname
                          :session-id session-id
                          :reconnect is-reconnect
                          :ops (keys ops)
                          :nrepl-version (get versions "sci-nrepl")}})
        ;; Push synced atoms to newly connected browser
        (atom-sync/on-browser-connected! sente-conn-id)
        ;; Return nil - :server/ready already sent the info to browser
        ;; Returning mcp-conn-id would cause sente-lite to send it as raw response,
        ;; which breaks client's parse-message (expects vector, not string)
        nil)
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
  "Handle message from browser - route based on handshake state and event type."
  [sente-conn-id event-id data]
  (log/log! {:level :debug
             :id ::browser-message
             :msg "Received browser message"
             :data {:sente-conn-id sente-conn-id
                    :event-id event-id
                    :data-keys (keys data)}})

  (case event-id
    ;; Client ready - handshake initiation from browser
    :client/ready
    (handle-client-ready! sente-conn-id data)

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

    ;; Phase 1.5E.17: Clone repo - special async handling
    :code-browser/clone-repo
    (let [conn-info (get @!browser-connections sente-conn-id)]
      (when (= :validated (:status conn-info))
        (log/log! {:level :info
                   :id ::clone-repo-requested
                   :msg "Clone repo requested"
                   :data {:url (:url data)}})
        ;; Run clone in future to avoid blocking
        (future
         (let [send-progress-fn (fn [progress]
                                  (send-to-browser! sente-conn-id
                                                    [:code-browser/clone-progress progress]))
               result (code-browser/handle-clone-repo data send-progress-fn)]
            ;; Send final result
           (send-to-browser! sente-conn-id [:code-browser/clone-result result])))))

    ;; Try atom-sync dispatch first
    (if-let [[response-event-id response-data] (atom-sync/dispatch-event event-id data)]
      ;; Atom-sync event handled - send response
            (let [conn-info (get @!browser-connections sente-conn-id)]
              (when (= :validated (:status conn-info))
                (send-to-browser! sente-conn-id [response-event-id response-data])))

      ;; Try code-browser dispatch
            (if-let [[response-event-id response-data] (code-browser/dispatch-event event-id data)]
        ;; Code browser event handled - send response
                    (let [conn-info (get @!browser-connections sente-conn-id)]
                      (log/log! {:level :info
                                 :id ::code-browser-response
                                 :msg "Sending code-browser response"
                                 :data {:response-event response-event-id
                                        :status (:status conn-info)
                                        :sente-conn-id sente-conn-id}})
                      (when (= :validated (:status conn-info))
                        (let [sent? (send-to-browser! sente-conn-id [response-event-id response-data])]
                          (log/log! {:level :info
                                     :id ::code-browser-sent
                                     :msg "Response sent"
                                     :data {:sent? sent?}}))))

        ;; Try code-browser-v2 dispatch
                    (if-let [[response-event-id response-data] (code-browser-v2/dispatch-event event-id data)]
          ;; Code browser v2 event handled - send response
                            (let [conn-info (get @!browser-connections sente-conn-id)]
                              (log/log! {:level :info
                                         :id ::code-browser-v2-response
                                         :msg "Sending code-browser-v2 response"
                                         :data {:response-event response-event-id
                                                :status (:status conn-info)
                                                :sente-conn-id sente-conn-id}})
                              (when (= :validated (:status conn-info))
                                (let [sent? (send-to-browser! sente-conn-id [response-event-id response-data])]
                                  (log/log! {:level :info
                                             :id ::code-browser-v2-sent
                                             :msg "Response sent"
                                             :data {:sent? sent?}}))))

          ;; Unknown event - log it
                            (log/log! {:level :debug
                                       :id ::unknown-event
                                       :msg "Unknown browser event"
                                       :data {:event-id event-id}}))))))

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

(defn get-session-registry
  "Get the session registry (session-id -> mcp-conn-id mappings).
   For debugging and monitoring."
  []
  @!session-registry)

(defn cleanup-stale-sessions!
  "Remove session registry entries for connections that have been closed
   for longer than session-stale-threshold-ms.

   This allows truly abandoned sessions to be cleaned up while preserving
   sessions for browsers that might reconnect."
  []
  (let [now (System/currentTimeMillis)
        ;; Get all mcp-conn-ids that are closed and stale
        stale-mcp-conn-ids (->> (conn-state/get-browser-connections)
                                (filter (fn [[_ conn]]
                                          (and (= :closed (:status conn))
                                               (some? (:closed-at conn))
                                               (> (- now (:closed-at conn))
                                                  session-stale-threshold-ms))))
                                (map first)
                                set)
        ;; Find session-ids that map to stale mcp-conn-ids
        stale-sessions (->> @!session-registry
                            (filter (fn [[_ mcp-conn-id]]
                                      (contains? stale-mcp-conn-ids mcp-conn-id)))
                            (map first))]
    (when (seq stale-sessions)
      (log/log! {:level :info
                 :id ::cleaning-stale-sessions
                 :msg "Cleaning up stale session registry entries"
                 :data {:count (count stale-sessions)
                        :session-ids stale-sessions}})
      (doseq [session-id stale-sessions]
             (swap! !session-registry dissoc session-id)))))

;; =============================================================================
;; Heartbeat Task (also handles disconnect detection)
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
                                   (and last-heartbeat
                                        (> (- now last-heartbeat) heartbeat-timeout-ms))))
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
  (check-stale-connections!)
  ;; Periodically clean up truly stale sessions from registry
  (cleanup-stale-sessions!))

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
   - :ws-port - WebSocket port (default 0 = ephemeral)
   - :code-browser - enable code browser handlers (default false)
   - :projects - list of project root paths for code browser (Phase 1.5E.3)

   Returns map with :port (actual bound port)."
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :ws-port 0)
        code-browser-enabled? (get config :code-browser false)
        projects (get config :projects [])]

    ;; Enable code browser if configured, passing projects list
    (when code-browser-enabled?
      (code-browser/enable! {:projects projects}))
    (log/log! {:level :info
               :id ::starting
               :msg "Starting sente WebSocket server"
               :data {:host host :port port}})

    (sente-server/start-server!
     {:host host
      :port port
      :on-message on-browser-message})

    ;; Get actual bound port (for ephemeral ports)
    (let [actual-port (sente-server/get-server-port)]

      ;; Register browser send function so watchers can send to browsers
      (msg-state/register-browser-send-fn! send-to-browser!)

      ;; Initialize atom-sync server integration
      (atom-sync/init! broadcast-to-browsers! send-to-browser!)

      ;; Start global message queue watcher
      (watchers/start-all-watchers!)

      ;; Start heartbeat task for connection health monitoring
      (start-heartbeat-task!)

      (log/log! {:level :info
                 :id ::started
                 :msg "Sente WebSocket server started"
                 :data {:host host
                        :port actual-port
                        :url (str "ws://" host ":" actual-port)}})
      {:port actual-port})))

(defn stop!
  "Stop the sente-lite WebSocket server."
  []
  (log/log! {:level :info
             :id ::stopping
             :msg "Stopping sente WebSocket server"
             :data {:browser-count (browser-count)}})

  ;; Disable code browser handlers
  (code-browser/disable!)

  ;; Stop atom-sync server integration
  (atom-sync/stop!)

  ;; Stop heartbeat task
  (stop-heartbeat-task!)

  ;; Stop global message queue watcher
  (watchers/stop-all-watchers!)

  ;; Unregister browser send function
  (msg-state/unregister-browser-send-fn!)

  ;; Mark all browser connections as closed
  (doseq [[_sente-conn-id {:keys [mcp-conn-id]}] @!browser-connections]
         (when mcp-conn-id
           (conn-state/mark-connection-closed! mcp-conn-id :server-shutdown "Server stopping")))

  ;; Clear our tracking
  (reset! !browser-connections {})

  ;; Stop sente server
  (sente-server/stop-server!)

  (log/log! {:level :info
             :id ::stopped
             :msg "Sente WebSocket server stopped"}))
