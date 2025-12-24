(ns sente-browser.bootstrap
    "HTTP server for browser bootstrap page.

   Serves an HTML page with Scittle that:
   1. Connects to WebSocket server via sente-lite client
   2. Implements nREPL protocol handler
   3. Evals code received from Claude and sends results back"
    (:require [clojure.java.io :as io]
              [org.httpkit.server :as http-kit]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !server (atom nil))

;; Path to sente-lite-nrepl.cljs bundle (set at startup)
(defonce ^:private !bundle-path (atom nil))

;; =============================================================================
;; HTML Content
;; =============================================================================

(defn bootstrap-html
  "Generate the bootstrap HTML page with embedded Scittle nREPL client.

   The page loads:
   1. Scittle core from CDN
   2. FakeWebSocket setup (for sci.nrepl integration)
   3. Scittle nREPL
   4. Trove logging from CDN
   5. sente-lite-nrepl.cljs bundle (served locally)
   6. Our client code that uses make-client! and browser_adapter"
  [{:keys [ws-host ws-port]}]
  (str
   "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <title>bb-mcp-server Browser nREPL</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 800px; margin: 2rem auto; padding: 0 1rem; }
    .status { padding: 0.5rem 1rem; border-radius: 4px; margin-bottom: 1rem; }
    .connected { background: #d4edda; color: #155724; }
    .disconnected { background: #f8d7da; color: #721c24; }
    .connecting { background: #fff3cd; color: #856404; }
    #log { font-family: monospace; font-size: 13px; background: #1e1e1e; color: #d4d4d4;
           padding: 1rem; border-radius: 4px; max-height: 400px; overflow-y: auto; white-space: pre-wrap; }
    .log-entry { margin: 0.25rem 0; }
    .log-eval { color: #569cd6; }
    .log-result { color: #4ec9b0; }
    .log-error { color: #f14c4c; }
    .log-info { color: #9cdcfe; }
    h1 { color: #333; }
  </style>
</head>
<body>
  <h1>bb-mcp-server Browser nREPL</h1>
  <div id=\"status\" class=\"status connecting\">Loading...</div>
  <h3>Eval Log</h3>
  <div id=\"log\"></div>

  <!-- 1. Scittle core -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js\"></script>

  <!-- 2. FakeWebSocket - MUST be before scittle.nrepl.js -->
  <script>
    scittle.core.eval_string(`
      ;; FakeWebSocket for sci.nrepl integration
      (defonce !fake-ws-state
        (atom {:ready-state 1 :onmessage nil :onerror nil :onclose nil
               :onopen nil :pending-inbound [] :send-fn nil}))

      (defn- create-fake-ws []
        (let [obj (js-obj)]
          (js/Object.defineProperty obj \"readyState\"
            #js {:get (fn [] (:ready-state @!fake-ws-state)) :configurable true})
          (js/Object.defineProperty obj \"onmessage\"
            #js {:get (fn [] (:onmessage @!fake-ws-state))
                 :set (fn [f] (swap! !fake-ws-state assoc :onmessage f)) :configurable true})
          (js/Object.defineProperty obj \"onerror\"
            #js {:get (fn [] (:onerror @!fake-ws-state))
                 :set (fn [f] (swap! !fake-ws-state assoc :onerror f)) :configurable true})
          (js/Object.defineProperty obj \"onclose\"
            #js {:get (fn [] (:onclose @!fake-ws-state))
                 :set (fn [f] (swap! !fake-ws-state assoc :onclose f)) :configurable true})
          (js/Object.defineProperty obj \"onopen\"
            #js {:get (fn [] (:onopen @!fake-ws-state))
                 :set (fn [f] (swap! !fake-ws-state assoc :onopen f)) :configurable true})
          (aset obj \"send\" (fn [data]
            (if-let [f (:send-fn @!fake-ws-state)] (f data)
              (js/console.warn \"[fake-ws] send called but no send-fn\"))))
          (aset obj \"injectMessage\" (fn [data]
            (if-let [f (:onmessage @!fake-ws-state)] (f #js {:data data})
              (swap! !fake-ws-state update :pending-inbound conj data))))
          (aset obj \"flushPending\" (fn []
            (let [pending (:pending-inbound @!fake-ws-state)]
              (swap! !fake-ws-state assoc :pending-inbound [])
              (when-let [f (:onmessage @!fake-ws-state)]
                (doseq [d pending] (f #js {:data d}))))))
          (aset obj \"setSendFn\" (fn [f]
            (swap! !fake-ws-state assoc :send-fn f)))
          (aset obj \"close\" (fn []
            (swap! !fake-ws-state assoc :ready-state 3)
            (when-let [f (:onclose @!fake-ws-state)] (f #js {:code 1000}))))
          obj))

      (when-not (aget js/window \"ws_nrepl\")
        (aset js/window \"ws_nrepl\" (create-fake-ws))
        (js/console.log \"[bb-mcp] FakeWebSocket installed\"))
    `);
  </script>

  <!-- 3. Scittle nREPL (finds our FakeWebSocket) -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js\"></script>
  <script>console.log('[bb-mcp] scittle.nrepl loaded, ws_nrepl.onmessage:', window.ws_nrepl?.onmessage ? 'SET' : 'NOT SET');</script>

  <!-- 4. Trove (logging) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc\" type=\"application/x-scittle\"></script>

  <!-- 5. sente-lite-nrepl bundle (served from our server) -->
  <script src=\"/sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>

  <!-- 6. Our client code (uses defonce to guard against double eval) -->
  <script type=\"application/x-scittle\">
    (ns bb-mcp.browser-client
      (:require [sente-lite.client-scittle :as client]
                [nrepl-sente.browser-adapter :as adapter]))

    ;; Guard against double evaluation (scittle.nrepl.js may auto-eval)
    (defonce !initialized (atom false))

    (defn log-el [] (js/document.getElementById \"log\"))
    (defn status-el [] (js/document.getElementById \"status\"))

    (defn log! [class msg]
      (when-let [el (log-el)]
        (let [entry (js/document.createElement \"div\")]
          (set! (.-className entry) (str \"log-entry log-\" class))
          (set! (.-textContent entry) msg)
          (.appendChild el entry)
          (set! (.-scrollTop el) (.-scrollHeight el)))))

    (defn set-status! [status text]
      (when-let [el (status-el)]
        (set! (.-className el) (str \"status \" status))
        (set! (.-textContent el) text)))

    (defn init! []
      (when (compare-and-set! !initialized false true)
        (let [ws-url \"ws://" ws-host ":" ws-port "\"]
          (log! \"info\" (str \"Connecting to \" ws-url \"...\"))
          (set-status! \"connecting\" \"Connecting...\")

          (def client-id
            (client/make-client!
              {:url ws-url
               :on-open (fn [uid]
                          (set-status! \"connected\" (str \"Connected (uid: \" uid \")\"))
                          (log! \"info\" (str \"WebSocket connected, uid: \" uid))
                          (adapter/connect! {:client client-id
                                             :on-connect #(log! \"info\" \"nREPL adapter connected\")}))
               :on-close (fn [event]
                           (set-status! \"disconnected\" \"Disconnected\")
                           (log! \"info\" \"WebSocket disconnected\")
                           (adapter/disconnect!))
               :on-reconnect (fn []
                               (set-status! \"connected\" \"Reconnected\")
                               (log! \"info\" \"WebSocket reconnected\")
                               (adapter/connect! {:client client-id}))
               :on-message (fn [event-id data]
                             (case event-id
                               :heartbeat/ping
                               (client/send! client-id [:heartbeat/pong {}])
                               :nrepl/registered
                               (let [{:keys [nickname connection-id]} data]
                                 (set-status! \"connected\" (str \"Connected as \" nickname))
                                 (log! \"info\" (str \"Registered as \" nickname \" (\" connection-id \")\")))
                               :nrepl/request
                               (log! \"eval\" (str \"Request: \" (pr-str data)))
                               nil))}))

          (log! \"info\" \"Browser nREPL ready - waiting for Claude...\"))))

    ;; Initialize on load
    (init!)
  </script>

  <!-- 7. Evaluate all Scittle scripts (bundle + client code) -->
  <script>scittle.core.eval_script_tags();</script>
</body>
</html>"))

;; =============================================================================
;; Bundle File
;; =============================================================================

(defn- read-bundle-file
  "Read the sente-lite-nrepl.cljs bundle file."
  []
  (when-let [path @!bundle-path]
            (let [file (io/file path)]
              (when (.exists file)
                (slurp file)))))

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(defn- handler
  "HTTP request handler for bootstrap server."
  [ws-config req]
  (let [uri (:uri req)]
    (cond
      ;; Health check
      (= uri "/health")
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"status\":\"ok\"}"}

      ;; Bootstrap page
      (or (= uri "/") (= uri "/index.html"))
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (bootstrap-html ws-config)}

      ;; sente-lite-nrepl bundle
      (= uri "/sente-lite-nrepl.cljs")
      (if-let [content (read-bundle-file)]
              {:status 200
               :headers {"Content-Type" "application/x-clojure; charset=utf-8"
                         "Cache-Control" "public, max-age=3600"}
               :body content}
              {:status 404
               :headers {"Content-Type" "text/plain"}
               :body "Bundle file not found"})

      ;; 404
      :else
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start!
  "Start the bootstrap HTTP server.

   Options:
   - :host - bind address (default 127.0.0.1)
   - :bootstrap-port - HTTP port (default 8091)
   - :ws-host - WebSocket host to connect to (default 127.0.0.1)
   - :ws-port - WebSocket port to connect to (default 8090)
   - :bundle-path - path to sente-lite-nrepl.cljs bundle file"
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :bootstrap-port 8091)
        ws-host (get config :ws-host (get config :host "127.0.0.1"))
        ws-port (get config :ws-port 8090)
        bundle-path (get config :bundle-path)
        ws-config {:ws-host ws-host :ws-port ws-port}]

    ;; Store bundle path
    (when bundle-path
      (reset! !bundle-path bundle-path))

    (when @!server
      (log/log! {:level :warn
                 :id ::already-running
                 :msg "Bootstrap server already running"}))

    (log/log! {:level :info
               :id ::starting
               :msg "Starting bootstrap HTTP server"
               :data {:host host :port port :ws-host ws-host :ws-port ws-port
                      :bundle-path bundle-path}})

    (let [server (http-kit/run-server
                  (partial handler ws-config)
                  {:ip host :port port})]
      (reset! !server server)

      (log/log! {:level :info
                 :id ::started
                 :msg "Bootstrap server started"
                 :data {:url (str "http://" host ":" port)}})
      server)))

(defn stop!
  "Stop the bootstrap HTTP server."
  []
  (when-let [server @!server]
            (log/log! {:level :info
                       :id ::stopping
                       :msg "Stopping bootstrap HTTP server"})
            (server) ; http-kit stop function
            (reset! !server nil)
            (log/log! {:level :info
                       :id ::stopped
                       :msg "Bootstrap server stopped"})))
