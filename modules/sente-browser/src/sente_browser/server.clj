(ns sente-browser.server
    "Embed sente-lite WebSocket server for browser nREPL connections.

   When a browser connects via WebSocket:
   1. sente-lite handles the WebSocket lifecycle
   2. We register the browser as an nREPL connection (type :browser)
   3. Claude can then use nrepl-eval with that connection

   Key difference from socket connections:
   - Browsers connect TO us (vs Claude connecting to nREPL servers)
   - Claude discovers browsers via `op=list`, then selects one to eval in"
    (:require [sente-lite.server :as sente-server]
              [nrepl.state.connection :as conn-state]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Map of sente-conn-id -> mcp-conn-id
(defonce ^:private !browser-connections (atom {}))

;; =============================================================================
;; Browser Connection Management
;; =============================================================================

(defn handle-browser-connect!
  "Called when a browser connects via WebSocket.
   Registers the browser as an nREPL connection.
   Called by connection sync task when new sente connections are detected."
  [sente-conn-id]
  (let [mcp-conn-id (conn-state/register-browser-connection! sente-conn-id)]
    (swap! !browser-connections assoc sente-conn-id mcp-conn-id)
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
  (when-let [mcp-conn-id (get @!browser-connections sente-conn-id)]
            (conn-state/mark-connection-closed! mcp-conn-id :browser-disconnect "Browser closed")
            (swap! !browser-connections dissoc sente-conn-id)
            (log/log! {:level :info
                       :id ::browser-disconnected
                       :msg "Browser disconnected"
                       :data {:sente-conn-id sente-conn-id
                              :mcp-conn-id mcp-conn-id}})))

(defn- on-browser-message
  "Handle message from browser.
   For now, just log it - full nREPL response routing comes in Phase 2."
  [conn-id event-id data]
  (log/log! {:level :debug
             :id ::browser-message
             :msg "Received browser message"
             :data {:conn-id conn-id
                    :event-id event-id
                    :data data}}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn browser-count
  "Get count of connected browsers."
  []
  (count @!browser-connections))

(defn get-browser-connections
  "Get all browser connections as {sente-conn-id mcp-conn-id}."
  []
  @!browser-connections)

(defn get-mcp-conn-id
  "Get MCP connection ID for a sente connection ID."
  [sente-conn-id]
  (get @!browser-connections sente-conn-id))

(defn get-sente-conn-id
  "Get sente connection ID for an MCP connection ID."
  [mcp-conn-id]
  (->> @!browser-connections
       (filter (fn [[_ mcp]] (= mcp mcp-conn-id)))
       first
       first))

(defn send-to-browser!
  "Send an event to a browser via sente.
   Returns true if sent, false otherwise."
  [sente-conn-id event]
  (sente-server/send-event-to-connection! sente-conn-id event))

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

    ;; Add watch to track connections from sente-lite
    ;; sente-lite registers connections in its registry
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

  ;; Mark all browser connections as closed
  (doseq [[_sente-conn-id mcp-conn-id] @!browser-connections]
         (conn-state/mark-connection-closed! mcp-conn-id :server-shutdown "Server stopping"))

  ;; Clear our tracking
  (reset! !browser-connections {})

  ;; Stop sente server
  (sente-server/stop-server!)

  (log/log! {:level :info
             :id ::stopped
             :msg "Sente WebSocket server stopped"}))
