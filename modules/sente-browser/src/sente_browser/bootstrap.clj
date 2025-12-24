(ns sente-browser.bootstrap
    "HTTP server for browser bootstrap page.

   Serves an HTML page with Scittle that:
   1. Connects to WebSocket server via sente-lite client
   2. Implements nREPL protocol handler
   3. Evals code received from Claude and sends results back"
    (:require [org.httpkit.server :as http-kit]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !server (atom nil))

;; =============================================================================
;; HTML Content
;; =============================================================================

(defn bootstrap-html
  "Generate the bootstrap HTML page with embedded Scittle nREPL client.

   The page:
   - Loads sente-lite client from CDN
   - Connects to WebSocket server
   - Handles :nrepl/eval events
   - Sends :nrepl/response events back"
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
           padding: 1rem; border-radius: 4px; max-height: 400px; overflow-y: auto; }
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
  <div id=\"status\" class=\"status connecting\">Connecting...</div>
  <h3>Eval Log</h3>
  <div id=\"log\"></div>

  <!-- Scittle for ClojureScript in browser -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.6.20/dist/scittle.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.6.20/dist/scittle.promesa.js\"></script>

  <!-- sente-lite client -->
  <script src=\"https://cdn.jsdelivr.net/npm/sente-lite@0.4.2/dist/sente-lite.min.js\"></script>

  <script type=\"application/x-scittle\">
    ;; Browser nREPL client

    (def log-el (js/document.getElementById \"log\"))
    (def status-el (js/document.getElementById \"status\"))

    (defn log! [class msg]
      (let [entry (js/document.createElement \"div\")]
        (set! (.-className entry) (str \"log-entry log-\" class))
        (set! (.-textContent entry) msg)
        (.appendChild log-el entry)
        (set! (.-scrollTop log-el) (.-scrollHeight log-el))))

    (defn set-status! [status text]
      (set! (.-className status-el) (str \"status \" status))
      (set! (.-textContent status-el) text))

    ;; Connect to WebSocket
    (def ws-url \"ws://" ws-host ":" ws-port "\")

    (log! \"info\" (str \"Connecting to \" ws-url \"...\"))

    (def client
      (sente-lite/connect!
        {:url ws-url
         :on-open (fn []
                    (set-status! \"connected\" \"Connected to bb-mcp-server\")
                    (log! \"info\" \"WebSocket connected\"))
         :on-close (fn []
                     (set-status! \"disconnected\" \"Disconnected\")
                     (log! \"info\" \"WebSocket disconnected\"))
         :on-message (fn [event-id data]
                       (case event-id
                         ;; Heartbeat ping - respond with pong
                         :heartbeat/ping
                         (sente-lite/send! client [:heartbeat/pong {}])

                         ;; nREPL eval - execute code and respond
                         :nrepl/eval
                         (let [{:keys [id code ns]} data
                               ns-sym (or (symbol ns) 'user)]
                           (log! \"eval\" (str \"[\" id \"] \" code))
                           (try
                             (let [result (scittle.core/eval-string code)]
                               (log! \"result\" (str \"=> \" (pr-str result)))
                               (sente-lite/send! client
                                 [:nrepl/response {:id id
                                                   :value (pr-str result)
                                                   :ns (str ns-sym)
                                                   :status #{:done}}]))
                             (catch :default e
                               (log! \"error\" (str \"Error: \" (.-message e)))
                               (sente-lite/send! client
                                 [:nrepl/response {:id id
                                                   :err (.-message e)
                                                   :status #{:done :error}}]))))

                         ;; Unknown event - ignore
                         nil))}))

    (log! \"info\" \"Browser nREPL ready - waiting for Claude...\")
  </script>
</body>
</html>"))

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
   - :ws-port - WebSocket port to connect to (default 8090)"
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :bootstrap-port 8091)
        ws-host (get config :ws-host (get config :host "127.0.0.1"))
        ws-port (get config :ws-port 8090)
        ws-config {:ws-host ws-host :ws-port ws-port}]

    (when @!server
      (log/log! {:level :warn
                 :id ::already-running
                 :msg "Bootstrap server already running"}))

    (log/log! {:level :info
               :id ::starting
               :msg "Starting bootstrap HTTP server"
               :data {:host host :port port :ws-host ws-host :ws-port ws-port}})

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
