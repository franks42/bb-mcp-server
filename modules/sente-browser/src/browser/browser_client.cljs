(ns bb-mcp.browser-client
  "Simple nREPL browser client for the bootstrap page.
   Connects via sente-lite and displays eval log."
  (:require [sente-lite.client-scittle :as client]
            [nrepl-sente.browser-adapter :as adapter]))

;; =========================================================================
;; Config - injected via window.__BB_MCP_CONFIG
;; =========================================================================

(def ^:private ws-url
  (str "ws://" (aget js/window.__BB_MCP_CONFIG "ws_host")
       ":" (aget js/window.__BB_MCP_CONFIG "ws_port")))

;; Guard against double evaluation (scittle.nrepl.js may auto-eval)
(defonce !initialized (atom false))

;; Persistent session-id survives WebSocket reconnects (Safari tab throttling)
;; defonce ensures same ID is reused when WS reconnects after background pause
(defonce !browser-session-id (atom nil))

(defn get-or-create-session-id
  "Get existing session-id or create a new one.
   Uses defonce atom so ID persists across WebSocket reconnects."
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
  "Initialize the browser nREPL client connection."
  []
  (when (compare-and-set! !initialized false true)
    (log! "info" (str "Connecting to " ws-url "..."))
    (set-status! "connecting" "Connecting...")

    (def client-id
      (client/make-client!
        {:url ws-url
         :on-open (fn [uid]
                    (set-status! "connecting" (str "Handshaking (uid: " uid ")"))
                    (log! "info" (str "WebSocket connected, uid: " uid))
                    ;; Send client/ready to initiate handshake with session-id
                    (let [session-id (get-or-create-session-id)]
                      (log! "info" (str "Session ID: " session-id))
                      (client/send! client-id [:client/ready {:session-id session-id}]))
                    ;; Connect nREPL adapter (handles describe probe)
                    (adapter/connect! {:client client-id
                                       :on-connect #(log! "info" "nREPL adapter connected")}))
         :on-close (fn [event]
                     (set-status! "disconnected" "Disconnected")
                     (log! "info" "WebSocket disconnected")
                     (adapter/disconnect!))
         :on-reconnect (fn []
                         (set-status! "connecting" "Reconnecting...")
                         (log! "info" "WebSocket reconnected")
                         ;; Re-send client/ready with same session-id for stable identity
                         (let [session-id (get-or-create-session-id)]
                           (log! "info" (str "Re-sending session ID: " session-id))
                           (client/send! client-id [:client/ready {:session-id session-id}]))
                         (adapter/connect! {:client client-id}))
         :on-message (fn [event-id data]
                       (case event-id
                         :heartbeat/ping
                         (client/send! client-id [:heartbeat/pong {}])
                         :server/ready
                         (let [{:keys [nickname connection-id reconnect]} data]
                           (set-status! "connected" (str "Connected as " nickname))
                           (log! "info" (str (if reconnect "Reconnected" "Registered")
                                             " as " nickname " (" connection-id ")")))
                         :nrepl/request
                         (log! "eval" (str "Request: " (pr-str data)))
                         nil))}))

    (log! "info" "Browser nREPL ready - waiting for Claude...")))

;; Initialize on load
(init!)
