(ns nrepl-proxy-server.core
    "nREPL Proxy Server - connect Calva/terminal to bb or browser REPLs.

   Shadow-cljs style API:
   - (browser/list) - list available browsers
   - (browser/repl :browser-2) - switch to browser
   - :cljs/quit - return to bb

   Usage:
     (require '[nrepl-proxy-server.core :as proxy])
     (proxy/start! {:port 1667})  ; or 0 for ephemeral

     ;; Connect with: lein repl :connect 1667
     ;; Or in Calva: connect to localhost:1667

     (proxy/stop!)"
    (:require [nrepl-proxy-server.server :as server]
              [nrepl-proxy-server.session :as session]
              [nrepl-proxy-server.api :as api]
              [taoensso.trove :as log]))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Start the nREPL proxy server.

   Options:
   - :port - TCP port (default: 0 = ephemeral)
   - :host - bind address (default: 127.0.0.1)
   - :port-file - path to port file (default: .nrepl-proxy-port)
   - :write-port-file? - write port file (default: true)

   Returns: {:port actual-port :port-file path-or-nil}"
  ([]
   (start! {}))
  ([config]
   (server/start! config)))

(defn stop!
  "Stop the nREPL proxy server."
  []
  (server/stop!))

(defn status
  "Get server status including session and browser counts."
  []
  (server/status))

(defn get-url
  "Get the nREPL URL for connecting (e.g., nrepl://127.0.0.1:1667).
   Returns nil if server not running."
  []
  (server/get-url))

;; =============================================================================
;; Browser API (re-exported for convenience)
;; =============================================================================

(defn list-browsers
  "List all available browser connections.
   Returns vector of {:id :browser-1 :nickname \"browser-1\" ...}"
  []
  (api/list-browsers))

(defn browser-connected?
  "Check if a specific browser is connected."
  [browser-id]
  (api/browser-connected? browser-id))

;; =============================================================================
;; Session API (for debugging)
;; =============================================================================

(defn list-sessions
  "List all active nREPL sessions with their targets."
  []
  (session/list-sessions))

;; =============================================================================
;; Module Entry Point
;; =============================================================================

(def module
     "Module definition for bb-mcp-server module system."
     {:name "nrepl-proxy-server"
      :version "0.1.0"
      :description "nREPL proxy - connect Calva/terminal to bb or browser REPLs"

      :start
      (fn [_deps config]
        (log/log! {:level :info
                   :id ::module-starting
                   :msg "nrepl-proxy-server module starting"
                   :data {:config config}})
        (if (:enabled config)
          (let [result (start! config)]
            (log/log! {:level :info
                       :id ::auto-started
                       :msg "nREPL proxy auto-started"
                       :data result})
            result)
       ;; Return nil instance if not enabled
          nil))

      :stop
      (fn [instance]
        (when instance
          (stop!)
          (log/log! {:level :info
                     :id ::module-stopped
                     :msg "nrepl-proxy-server module stopped"})))

      :status
      (fn [_instance]
        (let [server-status (status)]
          (if (:running? server-status)
            {:status :ok
             :port (:port server-status)
             :host (:host server-status)
             :sessions (:session-count server-status)
             :browsers (:browser-count server-status)}
            {:status :stopped})))

   ;; No MCP tools - this is infrastructure for external nREPL clients
      :tools []})
