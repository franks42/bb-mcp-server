(ns sente-browser.core
    "Sente-browser module - browser nREPL via WebSocket.

   Embeds a sente-lite WebSocket server that accepts browser connections
   running Scittle nREPL clients. Browsers auto-register as nREPL connections
   that Claude can interact with using existing nrepl-eval tools."
    (:require [clojure.java.io :as io]
              [sente-browser.server :as server]
              [sente-browser.bootstrap :as bootstrap]
              [nrepl.state.messages :as msg-state]
              [bb-mcp-server.port-registry :as port-registry]
              [taoensso.trove :as log]))

;; =============================================================================
;; Bundle Path Resolution
;; =============================================================================

(defn- find-bundle-path
  "Find the sente-lite-nrepl.cljs bundle file.

   Checks in order:
   1. :bundle-path from config
   2. SENTE_LITE_BUNDLE_PATH env var
   3. SENTE_LITE_PATH/dist/sente-lite-nrepl.cljs
   4. ~/Development/sente_lite/dist/sente-lite-nrepl.cljs (dev default)"
  [config]
  (let [candidates [(get config :bundle-path)
                    (System/getenv "SENTE_LITE_BUNDLE_PATH")
                    (when-let [base (System/getenv "SENTE_LITE_PATH")]
                              (str base "/dist/sente-lite-nrepl.cljs"))
                    (str (System/getProperty "user.home")
                         "/Development/sente_lite/dist/sente-lite-nrepl.cljs")]]
    (->> candidates
         (filter some?)
         (map io/file)
         (filter #(.exists %))
         (first)
         (str))))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the sente-browser module.

   Starts WebSocket server on :ws-port (default 0 = ephemeral).

   Returns instance map or nil if disabled."
  [_deps config]
  (let [enabled? (get config :enabled true)]
    (if-not enabled?
      (do
       (log/log! {:level :info
                  :id ::disabled
                  :msg "Sente-browser module disabled"})
       nil)
      (let [bundle-path (find-bundle-path config)]
        (when-not bundle-path
          (log/log! {:level :warn
                     :id ::bundle-not-found
                     :msg "sente-lite-nrepl.cljs bundle not found - browser nREPL may not work"
                     :data {:checked ["config :bundle-path"
                                      "SENTE_LITE_BUNDLE_PATH env"
                                      "SENTE_LITE_PATH/dist/sente-lite-nrepl.cljs"
                                      "~/Development/sente_lite/dist/sente-lite-nrepl.cljs"]}}))

        (log/log! {:level :info
                   :id ::starting
                   :msg "Starting sente-browser module"
                   :data {:config config :bundle-path bundle-path}})

        ;; Start WebSocket server first (returns {:port actual-port})
        (let [ws-result (server/start! config)
              ws-port (:port ws-result)
              ;; Then start bootstrap HTTP server with bundle path and actual ws-port
              bootstrap-config (-> config
                                   (assoc :bundle-path bundle-path)
                                   (assoc :ws-port ws-port))
              http-result (bootstrap/start! bootstrap-config)
              http-port (:port http-result)]

          ;; Register send function so nrepl can route messages to browsers
          (msg-state/register-browser-send-fn! server/send-to-browser!)

          ;; Register actual bound ports in unified port registry
          (port-registry/register-port! :sente-websocket ws-port)
          (port-registry/register-port! :http-server http-port)

          {:ws-server ws-result
           :http-server http-result
           :ws-port ws-port
           :http-port http-port
           :bundle-path bundle-path
           :config config})))))

(defn stop
  "Stop the sente-browser module.

   Stops WebSocket server, bootstrap HTTP server, and disconnects all browsers."
  [instance]
  (when instance
    (log/log! {:level :info
               :id ::stopping
               :msg "Stopping sente-browser module"})

    ;; Unregister send function first
    (msg-state/unregister-browser-send-fn!)

    ;; Stop bootstrap HTTP server
    (bootstrap/stop!)

    ;; Then stop WebSocket server
    (server/stop!)

    ;; Unregister ports from unified registry
    (port-registry/unregister-port! :sente-websocket)
    (port-registry/unregister-port! :http-server))
  nil)

(defn status
  "Get module status.

   Returns:
   - :status - :ok or :stopped
   - :ws-port - WebSocket port (actual bound port)
   - :http-port - HTTP bootstrap port (actual bound port)
   - :browser-count - Number of connected browsers"
  [instance]
  (if instance
    {:status :ok
     :ws-port (:ws-port instance)
     :http-port (:http-port instance)
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
