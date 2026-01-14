(ns sente-browser.bootstrap
    "HTTP server for browser bootstrap page.

   Serves an HTML page with Scittle that:
   1. Connects to WebSocket server via sente-lite client
   2. Implements nREPL protocol handler
   3. Evals code received from Claude and sends results back"
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
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
  <!-- Fira Code font for code display -->
  <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">
  <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>
  <link href=\"https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500&display=swap\" rel=\"stylesheet\">
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
    #log { font-family: 'Fira Code', monospace; font-size: 12px; background: #1e1e1e; color: #d4d4d4;
           padding: 0.5rem; max-height: 150px; overflow-y: auto; white-space: pre-wrap; margin: 0.5rem; }
    .log-entry { margin: 0.1rem 0; }
    .log-eval { color: #569cd6; }
    .log-result { color: #4ec9b0; }
    .log-error { color: #f14c4c; }
    .log-info { color: #9cdcfe; }
    /* CodeMirror container */
    .cm-container { height: 100%; }
    .cm-editor { height: 100%; font-family: 'Fira Code', monospace !important; }
    .cm-editor .cm-content { font-family: 'Fira Code', monospace !important; }
    .cm-editor .cm-gutters { font-family: 'Fira Code', monospace !important; }
    /* Phase 1.5E.12: Line highlighting for protocol impls/methods */
    .cm-editor .cm-highlighted-line { background-color: rgba(255, 220, 0, 0.25); }
    .cm-editor.cm-focused .cm-highlighted-line { background-color: rgba(255, 220, 0, 0.35); }

    /* Code Browser three-panel layout */
    .code-browser { display: flex; flex-direction: column; height: calc(100vh - 200px); }
    .panels-container { display: flex; flex: 1; overflow: hidden; border: 1px solid #ddd; }
    .panel { display: flex; flex-direction: column; border-right: 1px solid #ddd; overflow: hidden; }
    .panel:last-child { border-right: none; }
    .panel-header { padding: 0.5rem; background: #f5f5f5; border-bottom: 1px solid #ddd; display: flex; justify-content: space-between; align-items: center; }
    .panel-header h3 { margin: 0; font-size: 14px; }
    .panel-footer { padding: 0.25rem 0.5rem; background: #f5f5f5; border-top: 1px solid #ddd; font-size: 12px; color: #666; }
    .filter-input { width: 100%; padding: 0.5rem; border: none; border-bottom: 1px solid #ddd; font-size: 13px; }
    .filter-input:focus { outline: none; background: #fffef0; }
    .list-container { flex: 1; overflow-y: auto; }
    .list-item { padding: 0.4rem 0.75rem; cursor: pointer; font-size: 13px; font-family: 'Fira Code', monospace; border-bottom: 1px solid #eee; }
    .list-item:hover { background: #f0f0f0; }
    .list-item.selected { background: #e3f2fd; font-weight: 500; }
    .symbol-name { margin-right: 0.5rem; }
    .symbol-kind { font-size: 11px; color: #888; }
    .source-container { flex: 1; overflow: auto; }
    .empty-message { padding: 1rem; color: #888; font-style: italic; }
    .refresh-btn { font-size: 12px; padding: 0.25rem 0.5rem; cursor: pointer; }
    .load-ui-btn { font-size: 14px; padding: 0.5rem 1rem; cursor: pointer; background: #4CAF50; color: white; border: none; border-radius: 4px; margin: 0.5rem; }
    .load-ui-btn:hover { background: #45a049; }
    .load-ui-btn:disabled { background: #ccc; cursor: not-allowed; }
    .loading-overlay { position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(255,255,255,0.7); display: flex; align-items: center; justify-content: center; }
    .spinner { width: 30px; height: 30px; border: 3px solid #ddd; border-top-color: #333; border-radius: 50%; animation: spin 1s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .error-banner { background: #f8d7da; color: #721c24; padding: 0.5rem 1rem; display: flex; justify-content: space-between; align-items: center; }
    /* Phase 1.5E.10: Tab styles */
    .tab-bar { display: flex; gap: 0.25rem; margin-left: auto; }
    .tab-btn { padding: 0.2rem 0.5rem; font-size: 11px; border: 1px solid #ddd; background: #f5f5f5; cursor: pointer; border-radius: 3px; }
    .tab-btn:hover { background: #e8e8e8; }
    .tab-btn.active { background: #e3f2fd; border-color: #2196F3; font-weight: 500; }
    .doc-container { flex: 1; overflow: auto; padding: 1rem; }
    .docstring { font-family: 'Fira Code', monospace; font-size: 13px; white-space: pre-wrap; margin: 0; line-height: 1.5; background: #f8f9fa; padding: 1rem; border-radius: 4px; }
    .deps-container { flex: 1; overflow: auto; padding: 0.5rem; }
    .deps-list { }
    .deps-header { font-size: 12px; color: #666; padding: 0.5rem; border-bottom: 1px solid #eee; margin-bottom: 0.5rem; }
    .dep-item { display: flex; padding: 0.3rem 0.5rem; font-size: 13px; font-family: 'Fira Code', monospace; border-bottom: 1px solid #f0f0f0; }
    .dep-item:hover { background: #f0f0f0; cursor: pointer; }
    .dep-name { color: #333; margin-right: 0.5rem; }
    .dep-ns { color: #888; font-size: 11px; }
  </style>
</head>
<body>
  <div id=\"app\">
    <div id=\"status\" class=\"status connecting\">Loading Code Browser...</div>
    <div id=\"ui-loader\">
      <button id=\"load-ui-btn\" class=\"load-ui-btn\" onclick=\"loadCodeBrowserUI()\" disabled>Load Code Browser</button>
      <span id=\"loader-status\">Waiting for WebSocket connection...</span>
    </div>
    <div id=\"log\"></div>
    <div id=\"code-browser-root\"><!-- UI loaded via button or nREPL --></div>
  </div>
  <script>
    // Load Code Browser UI files and mount
    async function loadCodeBrowserUI() {
      const btn = document.getElementById('load-ui-btn');
      const status = document.getElementById('loader-status');
      btn.disabled = true;
      try {
        status.textContent = 'Loading scittle-cm6...';
        const cm6Resp = await fetch('/browser/scittle_cm6.cljs');
        if (!cm6Resp.ok) throw new Error('Failed to load scittle_cm6.cljs');
        const cm6Code = await cm6Resp.text();
        scittle.core.eval_string(cm6Code);

        status.textContent = 'Loading code-browser...';
        const cbResp = await fetch('/browser/code_browser.cljs');
        if (!cbResp.ok) throw new Error('Failed to load code_browser.cljs');
        const cbCode = await cbResp.text();
        scittle.core.eval_string(cbCode);

        status.textContent = 'Mounting UI...';
        scittle.core.eval_string('(code-browser/mount!)');

        // Hide loader once mounted
        document.getElementById('ui-loader').style.display = 'none';
        status.textContent = 'Code Browser loaded!';
      } catch (e) {
        console.error('[load-ui] Error:', e);
        status.textContent = 'Error: ' + e.message;
        btn.disabled = false;
      }
    }
    // Enable button once WebSocket is connected
    function enableLoadButton() {
      const btn = document.getElementById('load-ui-btn');
      const status = document.getElementById('loader-status');
      if (btn) { btn.disabled = false; status.textContent = 'Ready - click to load UI'; }
    }
  </script>

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

  <!-- 3. React + ReactDOM (required for Reagent) -->
  <script src=\"https://cdn.jsdelivr.net/npm/react@18.2.0/umd/react.production.min.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/react-dom@18.2.0/umd/react-dom.production.min.js\"></script>

  <!-- 4. Scittle plugins: nREPL, Reagent, Promesa -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.reagent.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.promesa.js\"></script>

  <!-- 5. Trove (logging) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc\" type=\"application/x-scittle\"></script>

  <!-- 6. CodeMirror 6 via ES modules -->
  <!-- Import codemirror (EditorView, basicSetup) + @codemirror/state (EditorState) separately -->
  <!-- The codemirror meta-package does NOT export EditorState - must import from @codemirror/state -->
  <!-- Only pin state version to avoid multiple-instances error; let view resolve naturally -->
  <script>window.CM6_READY = false;</script>
  <script type=\"module\">
    const STATE_VERSION = '6.4.1';

    // Import EditorState and StateField from @codemirror/state
    const { EditorState, StateField, RangeSet } = await import(`https://esm.sh/@codemirror/state@${STATE_VERSION}`);

    // Import EditorView and basicSetup from codemirror meta-package
    const { EditorView, basicSetup } = await import(`https://esm.sh/codemirror?deps=@codemirror/state@${STATE_VERSION}`);

    // Import Decoration from @codemirror/view (not re-exported by codemirror bundle)
    const { Decoration } = await import(`https://esm.sh/@codemirror/view?deps=@codemirror/state@${STATE_VERSION}`);

    // Import Clojure language support with same state version
    const { clojure } = await import(`https://esm.sh/@nextjournal/lang-clojure?deps=@codemirror/state@${STATE_VERSION}`);

    // Expose all modules needed by scittle_cm6.cljs (including Phase 1.5E.12 highlighting)
    globalThis.CM = { EditorView, EditorState, basicSetup, clojure, Decoration, StateField, RangeSet };
    window.CM6_READY = true;
    console.log('[code-browser] CodeMirror 6 loaded (with decoration support)');
  </script>

  <!-- 7. sente-lite-nrepl bundle -->
  <script src=\"/sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>

  <!-- 8. Code browser infrastructure: error boundary, atom sync, client -->
  <script type=\"application/x-scittle\">
    (ns code-browser.bootstrap
      \"Code browser infrastructure loaded at startup.
       UI code is loaded via nREPL after connection established.\"
      (:require [reagent.core :as r]
                [reagent.dom :as rdom]
                [sente-lite.client-scittle :as client]
                [nrepl-sente.browser-adapter :as adapter]
                [taoensso.trove :as log]))

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
    ;; Synced Atoms - One-way sync from server with seq validation
    ;; =========================================================================

    ;; Registry of synced Reagent atoms: {:key (r/atom value)}
    (defonce !synced-atoms (atom {}))

    ;; Sync state tracking: {:key {:seq n}} for gap detection
    (defonce !sync-state (atom {}))

    ;; Track last-seen epoch per key for server restart detection
    ;; When epoch changes, we know server restarted and should accept any seq
    (defonce !last-epoch (atom {}))

    ;; Watchers for future bidirectional sync (Phase 2)
    (defonce !sync-watchers-installed (atom #{}))

    ;; Track pending resync requests to prevent flooding
    ;; Set of keys with outstanding resync requests
    (defonce !resync-pending (atom #{}))

    ;; Client ID for sending messages (forward declaration, set by init!)
    (defonce !client-id (atom nil))

    (defn get-synced-atom
      \"Get or create a synced Reagent atom by key.
       Atoms are automatically synced with server.\"
      [key]
      (or (get @!synced-atoms key)
          (let [a (r/atom nil)]
            (swap! !synced-atoms assoc key a)
            a)))

    (defn request-resync!
      \"Request full resync from server for an atom.
       Called when gap detected in seq numbers.
       Prevents flooding by tracking pending requests.\"
      [key]
      (if (contains? @!resync-pending key)
        ;; Already have a pending resync for this key - don't flood
        (log/log! {:level :debug
                   :id ::resync-already-pending
                   :msg \"Resync already pending, skipping duplicate request\"
                   :data {:key key}})
        ;; No pending resync - send request and mark as pending
        (do
          (swap! !resync-pending conj key)
          (log/log! {:level :warn
                     :id ::resync-requested
                     :msg \"Requesting resync from server\"
                     :data {:key key}})
          (when @!client-id
            (client/send! @!client-id [:sync/resync-request {:key key}])))))

    (defn apply-sync-op
      \"Apply a sync operation with seq validation and epoch detection.
       Returns :applied, :stale, or :gap.

       Protocol: [:sync/op {:key k :seq n :epoch e :op :assoc-in :path [] :value v}]

       Epoch detection: When server epoch changes (server restart), we reset
       all local sync state for that key and accept the update unconditionally.

       Special case: Full state replace (path []) accepts any seq and resets
       local tracking. This handles both initial sync and resync recovery.\"
      [{:keys [key seq epoch op path value]}]
      (let [last-epoch (get @!last-epoch key)
            epoch-changed? (and epoch last-epoch (not= epoch last-epoch))
            ;; On epoch change, reset local state
            _ (when epoch-changed?
                (log/log! {:level :warn
                           :id ::epoch-changed
                           :msg \"Server epoch changed - server restarted, resetting local state\"
                           :data {:key key :old-epoch last-epoch :new-epoch epoch}})
                (swap! !sync-state dissoc key)
                (swap! !resync-pending disj key))
            ;; Track current epoch
            _ (when epoch
                (swap! !last-epoch assoc key epoch))
            current-seq (get-in @!sync-state [key :seq] 0)
            expected-seq (inc current-seq)
            is-full-replace? (= path [])]
        (cond
          ;; Full state replace: accept any seq, reset tracking
          ;; This handles initial sync (server at seq 12, client at 0)
          ;; and resync responses (recovery from gaps)
          is-full-replace?
          (do
            (when-let [a (get-synced-atom key)]
              (reset! a value))
            (swap! !sync-state assoc-in [key :seq] seq)
            ;; Clear pending resync flag - we've received full state
            (swap! !resync-pending disj key)
            (log/log! {:level :info
                       :id ::full-sync-applied
                       :msg \"Full state sync applied\"
                       :data {:key key :seq seq :prev-seq current-seq :epoch epoch}})
            :applied)

          ;; Epoch changed - accept update even if seq looks wrong
          epoch-changed?
          (do
            (when-let [a (get-synced-atom key)]
              (swap! a assoc-in path value))
            (swap! !sync-state assoc-in [key :seq] seq)
            (log/log! {:level :info
                       :id ::sync-applied-epoch-reset
                       :msg \"Sync op applied after epoch reset\"
                       :data {:key key :seq seq :op op :path path}})
            :applied)

          ;; Normal case: sequential update
          (= seq expected-seq)
          (do
            (when-let [a (get-synced-atom key)]
              (swap! a assoc-in path value))
            (swap! !sync-state assoc-in [key :seq] seq)
            (log/log! {:level :debug
                       :id ::sync-applied
                       :msg \"Sync op applied\"
                       :data {:key key :seq seq :op op :path path}})
            :applied)

          ;; Gap detected: missed updates - request resync
          (> seq expected-seq)
          (do
            (log/log! {:level :warn
                       :id ::sync-gap
                       :msg \"Sync gap detected\"
                       :data {:key key :expected expected-seq :received seq}})
            (request-resync! key)
            :gap)

          ;; Stale/duplicate: ignore
          :else
          (do
            (log/log! {:level :debug
                       :id ::sync-stale
                       :msg \"Ignoring stale sync message\"
                       :data {:key key :current-seq current-seq :received seq}})
            :stale))))

    (defn handle-resync-response
      \"Handle resync response from server.
       Applies all ops and resets seq tracking.
       Clears pending resync flag to allow future requests.\"
      [{:keys [key ops error]}]
      ;; Always clear pending flag - response received (success or error)
      (swap! !resync-pending disj key)
      (if error
        (log/log! {:level :error
                   :id ::resync-error
                   :msg \"Resync failed\"
                   :data {:key key :error error}})
        (do
          (log/log! {:level :info
                     :id ::resync-received
                     :msg \"Resync response received\"
                     :data {:key key :op-count (count ops)}})
          ;; Reset seq tracking before applying
          (swap! !sync-state assoc-in [key :seq] 0)
          ;; Apply all ops (they should start from seq 1 or current)
          (doseq [op ops]
            (let [[_ op-data] op]
              (apply-sync-op op-data))))))

    (defn install-sync-watcher!
      \"Install watcher to push atom changes back to server.
       (Phase 2 - bidirectional sync)\"
      [key client-id]
      (when-not (contains? @!sync-watchers-installed key)
        (when-let [a (get @!synced-atoms key)]
          (add-watch a ::sync-to-server
            (fn [_ _ old-val new-val]
              (when (not= old-val new-val)
                (client/send! client-id [:sync/atom-update {:key key :value new-val}]))))
          (swap! !sync-watchers-installed conj key))))

    (defn get-sync-status
      \"Get current sync status for debugging.\"
      []
      {:atoms (keys @!synced-atoms)
       :state @!sync-state
       :epochs @!last-epoch})

    ;; =========================================================================
    ;; Connection Client
    ;; =========================================================================

    (defonce !initialized (atom false))
    (defonce !browser-session-id (atom nil))
    ;; !client-id moved to sync section (forward declaration)
    (defonce !event-handlers (atom {}))

    (defn register-event-handler!
      \"Register a handler for custom event types.
       handler-fn will be called with [event-id data].\"
      [event-prefix handler-fn]
      (swap! !event-handlers assoc event-prefix handler-fn)
      (js/console.log (str \"[bootstrap] Registered handler for \" event-prefix)))

    (defn dispatch-custom-event!
      \"Dispatch to custom event handlers by prefix match.\"
      [event-id data]
      (js/console.log (str \"[dispatch] event-id=\" event-id \" handlers=\" (pr-str (keys @!event-handlers))))
      (if-let [ns-str (namespace event-id)]
        (if-let [handler (get @!event-handlers (keyword ns-str))]
          (do
            (js/console.log (str \"[dispatch] Found handler for \" ns-str \", calling...\"))
            (handler [event-id data]))
          (js/console.log (str \"[dispatch] No handler for namespace: \" ns-str)))
        (js/console.log \"[dispatch] Event has no namespace\")))

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
                                                            \" as \" nickname))
                                         ;; Enable the Load UI button
                                         (when-let [enable-fn (aget js/window \"enableLoadButton\")]
                                           (enable-fn)))
                                       :sync/op
                                       (apply-sync-op data)
                                       :sync/resync-response
                                       (handle-resync-response data)
                                       :nrepl/request
                                       (log! \"eval\" (str \"eval: \" (subs (pr-str (:code data)) 0 (min 50 (count (pr-str (:code data)))))))
                                       ;; Try custom handlers for unrecognized events
                                       (dispatch-custom-event! event-id data)))})]
            (reset! !client-id cid))

          (log! \"info\" \"Code Browser ready - click button or load UI via nREPL\"))))

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

  <!-- 9. Evaluate all Scittle scripts -->
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

      ;; Serve UI .cljs files from modules/sente-browser/src/browser/
      (and (str/starts-with? uri "/browser/")
           (str/ends-with? uri ".cljs"))
      (let [filename (subs uri 9) ;; strip "/browser/"
            file (io/file "modules/sente-browser/src/browser" filename)]
        (if (.exists file)
          {:status 200
           :headers {"Content-Type" "application/x-clojure; charset=utf-8"
                     "Cache-Control" "no-cache"}
           :body (slurp file)}
          {:status 404
           :headers {"Content-Type" "text/plain"}
           :body (str "File not found: " filename)}))

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
