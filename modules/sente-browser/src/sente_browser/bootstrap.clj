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

    ;; Persistent session-id survives WebSocket reconnects (Safari tab throttling)
    ;; defonce ensures same ID is reused when WS reconnects after background pause
    (defonce !browser-session-id (atom nil))

    (defn get-or-create-session-id
      \"Get existing session-id or create a new one.
       Uses defonce atom so ID persists across WebSocket reconnects.\"
      []
      (or @!browser-session-id
          (let [new-id (str \"session-\" (random-uuid))]
            (reset! !browser-session-id new-id)
            new-id)))

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
                          (set-status! \"connecting\" (str \"Handshaking (uid: \" uid \")\"))
                          (log! \"info\" (str \"WebSocket connected, uid: \" uid))
                          ;; Send client/ready to initiate handshake with session-id
                          (let [session-id (get-or-create-session-id)]
                            (log! \"info\" (str \"Session ID: \" session-id))
                            (client/send! client-id [:client/ready {:session-id session-id}]))
                          ;; Connect nREPL adapter (handles describe probe)
                          (adapter/connect! {:client client-id
                                             :on-connect #(log! \"info\" \"nREPL adapter connected\")}))
               :on-close (fn [event]
                           (set-status! \"disconnected\" \"Disconnected\")
                           (log! \"info\" \"WebSocket disconnected\")
                           (adapter/disconnect!))
               :on-reconnect (fn []
                               (set-status! \"connecting\" \"Reconnecting...\")
                               (log! \"info\" \"WebSocket reconnected\")
                               ;; Re-send client/ready with same session-id for stable identity
                               (let [session-id (get-or-create-session-id)]
                                 (log! \"info\" (str \"Re-sending session ID: \" session-id))
                                 (client/send! client-id [:client/ready {:session-id session-id}]))
                               (adapter/connect! {:client client-id}))
               :on-message (fn [event-id data]
                             (case event-id
                               :heartbeat/ping
                               (client/send! client-id [:heartbeat/pong {}])
                               :server/ready
                               (let [{:keys [nickname connection-id reconnect]} data]
                                 (set-status! \"connected\" (str \"Connected as \" nickname))
                                 (log! \"info\" (str (if reconnect \"Reconnected\" \"Registered\")
                                                    \" as \" nickname \" (\" connection-id \")\")))
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
;; Code Browser Bootstrap HTML
;; =============================================================================

(defn code-browser-html
  "Generate the code browser HTML page with enhanced Scittle environment.

   Includes:
   - Scittle core + Reagent + Promesa + nREPL
   - CodeMirror 6 via ES modules (esm.sh)
   - Trove logging
   - sente-lite-nrepl bundle
   - Error boundary for safe REPL development
   - Atom sync primitives for bidirectional state sync
   - Dev namespace with helpers

   UI code is loaded via nREPL, not hardcoded here."
  [{:keys [ws-host ws-port]}]
  (str
   "<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <title>bb-mcp Code Browser</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: system-ui, sans-serif; margin: 0; padding: 0; height: 100vh; }
    #app { height: 100%; display: flex; flex-direction: column; }
    .status { padding: 0.5rem 1rem; border-radius: 4px; margin: 0.5rem; }
    .connected { background: #d4edda; color: #155724; }
    .disconnected { background: #f8d7da; color: #721c24; }
    .connecting { background: #fff3cd; color: #856404; }
    .error-boundary { background: #f8d7da; padding: 1rem; margin: 1rem; border-radius: 4px; }
    .error-boundary h3 { color: #721c24; margin-top: 0; }
    .error-boundary pre { background: #1e1e1e; color: #f14c4c; padding: 1rem; overflow: auto; }
    .error-boundary button { background: #721c24; color: white; border: none; padding: 0.5rem 1rem; cursor: pointer; }
    #log { font-family: monospace; font-size: 12px; background: #1e1e1e; color: #d4d4d4;
           padding: 0.5rem; max-height: 150px; overflow-y: auto; white-space: pre-wrap; margin: 0.5rem; }
    .log-entry { margin: 0.1rem 0; }
    .log-eval { color: #569cd6; }
    .log-result { color: #4ec9b0; }
    .log-error { color: #f14c4c; }
    .log-info { color: #9cdcfe; }
    /* CodeMirror container */
    .cm-container { height: 100%; }
    .cm-editor { height: 100%; }
  </style>
</head>
<body>
  <div id=\"app\">
    <div id=\"status\" class=\"status connecting\">Loading Code Browser...</div>
    <div id=\"log\"></div>
    <div id=\"code-browser-root\"><!-- UI loaded via nREPL --></div>
  </div>

  <!-- 1. Scittle core -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js\"></script>

  <!-- 2. FakeWebSocket - MUST be before scittle.nrepl.js -->
  <script>
    scittle.core.eval_string(`
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
        (aset js/window \"ws_nrepl\" (create-fake-ws)))
    `);
  </script>

  <!-- 3. Scittle plugins: nREPL, Reagent, Promesa -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.reagent.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.promesa.js\"></script>

  <!-- 4. Trove (logging) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc\" type=\"application/x-scittle\"></script>

  <!-- 5. CodeMirror 6 via ES modules - loaded async, signals when ready -->
  <script>window.CM6_READY = false;</script>
  <script type=\"module\">
    import {EditorView, basicSetup} from 'https://esm.sh/@codemirror/basic-setup@0.20.0';
    import {EditorState} from 'https://esm.sh/@codemirror/state@6.2.0';
    import {clojure} from 'https://esm.sh/@nextjournal/lang-clojure@1.0.0';
    globalThis.CM = {EditorView, EditorState, basicSetup, clojure};
    window.CM6_READY = true;
    console.log('[code-browser] CodeMirror 6 loaded');
  </script>

  <!-- 6. sente-lite-nrepl bundle -->
  <script src=\"/sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>

  <!-- 7. Code browser infrastructure: error boundary, atom sync, client -->
  <script type=\"application/x-scittle\">
    (ns code-browser.bootstrap
      \"Code browser infrastructure loaded at startup.
       UI code is loaded via nREPL after connection established.\"
      (:require [reagent.core :as r]
                [reagent.dom :as rdom]
                [sente-lite.client-scittle :as client]
                [nrepl-sente.browser-adapter :as adapter]))

    ;; =========================================================================
    ;; Error Boundary - Catches render errors for safe REPL development
    ;; =========================================================================

    (defn error-boundary
      \"Wrap components to catch errors during render.
       Displays error message with clear button instead of crashing.\"
      [& children]
      (let [!error (r/atom nil)]
        (r/create-class
          {:display-name \"ErrorBoundary\"
           :component-did-catch
           (fn [this error info]
             (js/console.error \"[error-boundary]\" error info)
             (reset! !error {:error error :info info}))
           :reagent-render
           (fn [& children]
             (if-let [{:keys [error]} @!error]
               [:div.error-boundary
                [:h3 \"Render Error\"]
                [:pre (str error)]
                [:button {:on-click #(reset! !error nil)} \"Clear & Retry\"]]
               (into [:<>] children)))})))

    ;; =========================================================================
    ;; Synced Atoms - Bidirectional state sync with server
    ;; =========================================================================

    (defonce !synced-atoms (atom {}))
    (defonce !sync-watchers-installed (atom #{}))

    (defn get-synced-atom
      \"Get or create a synced Reagent atom by key.
       Atoms are automatically synced with server.\"
      [key]
      (or (get @!synced-atoms key)
          (let [a (r/atom nil)]
            (swap! !synced-atoms assoc key a)
            a)))

    (defn on-sync-message
      \"Handle :sync/atom message from server.\"
      [{:keys [key value]}]
      (when-let [a (get @!synced-atoms key)]
        (reset! a value)))

    (defn install-sync-watcher!
      \"Install watcher to push atom changes back to server.\"
      [key client-id]
      (when-not (contains? @!sync-watchers-installed key)
        (when-let [a (get @!synced-atoms key)]
          (add-watch a ::sync-to-server
            (fn [_ _ old-val new-val]
              (when (not= old-val new-val)
                (client/send! client-id [:sync/atom-update {:key key :value new-val}]))))
          (swap! !sync-watchers-installed conj key))))

    ;; =========================================================================
    ;; Connection Client
    ;; =========================================================================

    (defonce !initialized (atom false))
    (defonce !browser-session-id (atom nil))
    (defonce !client-id (atom nil))

    (defn get-or-create-session-id []
      (or @!browser-session-id
          (let [new-id (str \"session-\" (random-uuid))]
            (reset! !browser-session-id new-id)
            new-id)))

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

          (let [cid (client/make-client!
                      {:url ws-url
                       :on-open (fn [uid]
                                  (set-status! \"connecting\" (str \"Handshaking (uid: \" uid \")\"))
                                  (log! \"info\" (str \"WebSocket connected, uid: \" uid))
                                  (let [session-id (get-or-create-session-id)]
                                    (log! \"info\" (str \"Session ID: \" session-id))
                                    (client/send! @!client-id [:client/ready {:session-id session-id}]))
                                  (adapter/connect! {:client @!client-id
                                                     :on-connect #(log! \"info\" \"nREPL adapter connected\")}))
                       :on-close (fn [event]
                                   (set-status! \"disconnected\" \"Disconnected\")
                                   (log! \"info\" \"WebSocket disconnected\")
                                   (adapter/disconnect!))
                       :on-reconnect (fn []
                                       (set-status! \"connecting\" \"Reconnecting...\")
                                       (log! \"info\" \"WebSocket reconnected\")
                                       (let [session-id (get-or-create-session-id)]
                                         (log! \"info\" (str \"Re-sending session ID: \" session-id))
                                         (client/send! @!client-id [:client/ready {:session-id session-id}]))
                                       (adapter/connect! {:client @!client-id}))
                       :on-message (fn [event-id data]
                                     (case event-id
                                       :heartbeat/ping
                                       (client/send! @!client-id [:heartbeat/pong {}])
                                       :server/ready
                                       (let [{:keys [nickname connection-id reconnect]} data]
                                         (set-status! \"connected\" (str \"Code Browser - \" nickname))
                                         (log! \"info\" (str (if reconnect \"Reconnected\" \"Registered\")
                                                            \" as \" nickname)))
                                       :sync/atom
                                       (on-sync-message data)
                                       :nrepl/request
                                       (log! \"eval\" (str \"eval: \" (subs (pr-str (:code data)) 0 (min 50 (count (pr-str (:code data)))))))
                                       nil))})]
            (reset! !client-id cid))

          (log! \"info\" \"Code Browser ready - load UI via nREPL\"))))

    ;; Initialize
    (init!)

    ;; =========================================================================
    ;; Dev helpers (available in REPL)
    ;; =========================================================================

    (defn cm6-ready? [] js/window.CM6_READY)

    (defn mount-root!
      \"Mount a component to #code-browser-root with error boundary.\"
      [component]
      (rdom/render [error-boundary component]
                   (js/document.getElementById \"code-browser-root\")))
  </script>

  <!-- 8. Evaluate all Scittle scripts -->
  <script>scittle.core.eval_script_tags();</script>
</body>
</html>"))

;; =============================================================================
;; Bundle File
;; =============================================================================

;; Code browser mode flag (set at startup)
(defonce ^:private !code-browser-mode (atom false))

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

      ;; Bootstrap page - use code-browser-html when code-browser mode enabled
      (or (= uri "/") (= uri "/index.html"))
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (if @!code-browser-mode
               (code-browser-html ws-config)
               (bootstrap-html ws-config))}

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
   - :bundle-path - path to sente-lite-nrepl.cljs bundle file
   - :code-browser - enable code-browser mode (enhanced HTML with CM6)"
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :bootstrap-port 8091)
        ws-host (get config :ws-host (get config :host "127.0.0.1"))
        ws-port (get config :ws-port 8090)
        bundle-path (get config :bundle-path)
        code-browser-mode? (get config :code-browser false)
        ws-config {:ws-host ws-host :ws-port ws-port}]

    ;; Store config
    (when bundle-path
      (reset! !bundle-path bundle-path))
    (reset! !code-browser-mode code-browser-mode?)

    (when @!server
      (log/log! {:level :warn
                 :id ::already-running
                 :msg "Bootstrap server already running"}))

    (log/log! {:level :info
               :id ::starting
               :msg (if code-browser-mode?
                      "Starting Code Browser bootstrap server"
                      "Starting bootstrap HTTP server")
               :data {:host host :port port :ws-host ws-host :ws-port ws-port
                      :bundle-path bundle-path :code-browser code-browser-mode?}})

    (let [server (http-kit/run-server
                  (partial handler ws-config)
                  {:ip host :port port})]
      (reset! !server server)

      (log/log! {:level :info
                 :id ::started
                 :msg (if code-browser-mode?
                        "Code Browser ready"
                        "Bootstrap server started")
                 :data {:url (str "http://" host ":" port)
                        :code-browser code-browser-mode?}})
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
