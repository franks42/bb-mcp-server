(ns sente-browser.core
    "Sente-browser module - browser nREPL via WebSocket.

   Embeds a sente-lite WebSocket server that accepts browser connections
   running Scittle nREPL clients. Browsers auto-register as nREPL connections
   that Claude can interact with using existing nrepl-eval tools."
    (:require [sente-browser.server :as server]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the sente-browser module.

   Starts WebSocket server on :ws-port (default 8090).

   Returns instance map or nil if disabled."
  [_deps config]
  (let [enabled? (get config :enabled true)]
    (if-not enabled?
      (do
       (log/log! {:level :info
                  :id ::disabled
                  :msg "Sente-browser module disabled"})
       nil)
      (do
       (log/log! {:level :info
                  :id ::starting
                  :msg "Starting sente-browser module"
                  :data {:config config}})

        ;; Start WebSocket server
       (let [ws-server (server/start! config)]
         {:ws-server ws-server
          :config config})))))

(defn stop
  "Stop the sente-browser module.

   Stops WebSocket server and disconnects all browsers."
  [instance]
  (when instance
    (log/log! {:level :info
               :id ::stopping
               :msg "Stopping sente-browser module"})

    ;; Stop WebSocket server
    (server/stop!))
  nil)

(defn status
  "Get module status.

   Returns:
   - :status - :ok or :stopped
   - :ws-port - WebSocket port
   - :browser-count - Number of connected browsers"
  [instance]
  (if instance
    {:status :ok
     :ws-port (get-in instance [:config :ws-port])
     :browser-count (server/browser-count)}
    {:status :stopped}))

;; =============================================================================
;; Module Definition
;; =============================================================================

(def module
     "Sente-browser module definition for bb-mcp-server module system."
     {:start start
      :stop stop
      :status status})
