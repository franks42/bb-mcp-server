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
;; HTML Helpers
;; =============================================================================

(defn- fake-ws-script
  "Return the FakeWebSocket inline <script> block.
   Must execute synchronously via eval_string before scittle.nrepl.js loads."
  []
  "  <script>
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
  </script>")

(defn- config-script
  "Return a <script> tag that injects WebSocket config as window.__BB_MCP_CONFIG."
  [{:keys [ws-host ws-port]}]
  (str "  <script>window.__BB_MCP_CONFIG = {\"ws_host\": \"" ws-host
       "\", \"ws_port\": \"" ws-port "\"};</script>"))

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
  [ws-config]
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
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js\"></script>

  <!-- 2. FakeWebSocket - MUST be before scittle.nrepl.js -->
"  (fake-ws-script) "

  <!-- 3. UUIDv7 (zero deps - load first so all code can use temporal IDs) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.5.0/src/com/github/franks42/uuidv7/core.cljc\" type=\"application/x-scittle\"></script>

  <!-- 4. Scittle nREPL (finds our FakeWebSocket) -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.nrepl.js\"></script>
  <script>console.log('[bb-mcp] scittle.nrepl loaded, ws_nrepl.onmessage:', window.ws_nrepl?.onmessage ? 'SET' : 'NOT SET');</script>

  <!-- 5. Trove (logging) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc\" type=\"application/x-scittle\"></script>

  <!-- 6. sente-lite-nrepl bundle (served from our server) -->
  <script src=\"/sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>

  <!-- 7. Config injection + client code -->
"  (config-script ws-config) "
  <script src=\"/browser/browser_client.cljs\" type=\"application/x-scittle\"></script>

  <!-- 8. Evaluate all Scittle scripts (bundle + client code) -->
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
  [ws-config]
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
  <!-- WinBox floating windows -->
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/winbox@0.2.82/dist/css/winbox.min.css\">
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
    /* Cmd+Click navigation cursor */
    .cm-editor.cm-cmd-hover .cm-content { cursor: pointer; }
    .cm-editor.cm-cmd-hover .cm-line { text-decoration-color: #1976D2; }
    /* Phase 1.5E.12: Line highlighting for protocol impls/methods */
    .cm-editor .cm-highlighted-line { background-color: rgba(255, 220, 0, 0.25); }
    .cm-editor.cm-focused .cm-highlighted-line { background-color: rgba(255, 220, 0, 0.35); }

    /* Code Browser three-panel layout (v1 and v2) */
    .code-browser, .code-browser-v2 { display: flex; flex-direction: column; height: calc(100vh - 200px); }
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
    .refreshing-indicator { position: absolute; top: 0; right: 8px; width: 16px; height: 16px; border: 2px solid transparent; border-top: 2px solid #1e88e5; border-radius: 50%; animation: spin 0.8s linear infinite; z-index: 10; }
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
    /* Phase 1.5E.8: Implementations view */
    .impls-container { flex: 1; overflow: auto; padding: 0.5rem; }
    .impls-list { }
    .definition-section { margin-bottom: 1rem; padding-bottom: 0.5rem; border-bottom: 2px solid #e0e0e0; }
    .impl-type { color: #1976D2; font-size: 11px; margin-left: 0.5rem; }
    .dispatch-val { color: #7B1FA2; font-size: 11px; margin-left: 0.5rem; }
    .def-type { color: #388E3C; font-size: 11px; margin-left: 0.5rem; }
    /* Phase 1.5E.2 & 1.5E.3: Git status bar and project selector */
    .git-status-bar { display: flex; align-items: center; gap: 1rem; padding: 0.5rem 1rem; background: #f8f9fa; border-bottom: 1px solid #ddd; font-size: 13px; }
    .project-name { font-weight: 500; color: #333; }
    .project-selector { font-size: 13px; padding: 0.25rem 0.5rem; border: 1px solid #ccc; border-radius: 4px; background: white; cursor: pointer; font-family: 'Fira Code', monospace; }
    .project-selector:hover { border-color: #999; }
    .project-selector:focus { outline: none; border-color: #2196F3; }
    /* Phase 1.5E.15: Add project input */
    .add-project-input { display: flex; align-items: center; gap: 0.25rem; }
    .add-project-input input { font-size: 12px; padding: 0.2rem 0.4rem; border: 1px solid #ccc; border-radius: 4px; width: 180px; font-family: 'Fira Code', monospace; }
    .add-project-input input:focus { outline: none; border-color: #2196F3; }
    .add-project-input button { font-size: 13px; padding: 0.15rem 0.5rem; border: 1px solid #4CAF50; border-radius: 4px; background: #4CAF50; color: white; cursor: pointer; font-weight: bold; }
    .add-project-input button:hover { background: #388E3C; }
    .add-project-input button:disabled { background: #ccc; border-color: #ccc; cursor: not-allowed; }
    .add-project-error { color: #d32f2f; font-size: 11px; padding: 0.2rem 0.4rem; }
    .branch-info { display: flex; align-items: center; gap: 0.25rem; color: #666; }
    .branch-icon { font-size: 14px; }
    .branch-name { font-family: 'Fira Code', monospace; }
    .dirty-indicator { color: #e65100; font-weight: bold; }
    .upstream-info { color: #999; font-size: 12px; }
    /* Phase 1.5E.19/20: Aliases panel and NS deps */
    .aliases-panel { background: #f8f9fa; border-bottom: 1px solid #ddd; font-size: 12px; }
    .aliases-header { display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.75rem; cursor: pointer; user-select: none; }
    .aliases-header:hover { background: #eef0f2; }
    .aliases-toggle { font-size: 10px; color: #666; }
    .aliases-title { font-weight: 500; color: #333; }
    .aliases-count { color: #888; }
    .aliases-content { padding: 0.25rem 0.75rem 0.5rem; display: flex; flex-wrap: wrap; gap: 0.5rem 1rem; }
    .alias-item { display: flex; align-items: center; gap: 0.25rem; font-family: 'Fira Code', monospace; }
    .alias-short { color: #1976D2; font-weight: 500; }
    .alias-arrow { color: #888; }
    .alias-full { color: #666; font-size: 11px; }
    .aliases-section { display: contents; }
    .refers-section { display: contents; }
    .refer-item { display: inline-flex; align-items: center; gap: 0.25rem; margin-right: 0.75rem; font-family: 'Fira Code', monospace; }
    .refer-sym { color: #7B1FA2; font-weight: 500; }
    .refer-from { color: #888; font-size: 10px; }
    /* Shadow warning for refers that shadow clojure.core */
    .refer-item.shadows-core { background: #fff3cd; padding: 0.1rem 0.3rem; border-radius: 3px; }
    .refer-item.shadows-core .refer-sym { color: #856404; }
    .shadow-warning { color: #856404; font-size: 10px; margin-left: 0.1rem; }
    /* NS deps in deps view */
    .ns-dep-item { display: flex; padding: 0.3rem 0.5rem; font-size: 13px; font-family: 'Fira Code', monospace; border-bottom: 1px solid #f0f0f0; align-items: center; gap: 0.5rem; }
    .ns-dep-item:hover { background: #f0f0f0; }
    .ns-dep-name { color: #333; }
    .ns-dep-alias { color: #1976D2; font-size: 11px; }
    .ns-dep-refers { color: #7B1FA2; font-size: 11px; }
    /* Phase 1.5E.11: Multi-file namespace display */
    .file-count-badge { font-size: 10px; color: #888; margin-left: 0.5rem; font-weight: normal; }
    .file-divider { display: flex; align-items: center; gap: 0.5rem; padding: 0.3rem 0.5rem; background: #f0f0f0; font-size: 11px; user-select: none; }
    .file-divider-line { flex: 1; height: 1px; background: #ccc; }
    .file-divider-name { color: #666; font-family: 'Fira Code', monospace; white-space: nowrap; }
    .symbol-file-badge { font-size: 10px; color: #888; margin-left: auto; font-style: italic; }
    .ns-name { flex: 1; overflow: hidden; text-overflow: ellipsis; }
    /* Phase 1.5E.18: JAR Dependency Exploration */
    .external-badge { font-size: 11px; margin-right: 0.25rem; }
    .dep-item.external, .ns-dep-item.external { background: #f5f5ff; border-left: 2px solid #7B68EE; }
    .dep-item.external:hover, .ns-dep-item.external:hover { background: #ebebff; }
    .ns-dep-item.clickable { cursor: pointer; }
    .explored-deps-section { margin-top: 1rem; border-top: 2px solid #7B68EE; padding-top: 0.5rem; }
    .section-header { padding: 0.3rem 0.5rem; font-size: 12px; font-weight: 600; color: #666; background: #f8f8ff; border-bottom: 1px solid #e0e0e0; }
    .list-item.explored-dep { background: #f5f5ff; border-left: 2px solid #7B68EE; }
    .list-item.explored-dep:hover { background: #ebebff; }
    .list-item.explored-dep .ns-name { color: #5B4B8E; }
    /* Phase 1.5E.16: Directory Browser Dialog */
    .browse-btn { background: #f5f5f5; border: 1px solid #ccc; border-radius: 4px; cursor: pointer; padding: 0.15rem 0.4rem; margin-left: 0.25rem; }
    .browse-btn:hover { background: #e8e8e8; }
    .dir-browser-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .dir-browser-dialog { background: white; border-radius: 8px; width: 600px; max-width: 90vw; max-height: 80vh; display: flex; flex-direction: column; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
    .dir-browser-header { display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem; border-bottom: 1px solid #ddd; }
    .dir-browser-header h3 { margin: 0; font-size: 16px; }
    .dir-browser-header .close-btn { background: none; border: none; font-size: 20px; cursor: pointer; color: #666; }
    .dir-browser-header .close-btn:hover { color: #333; }
    .dir-breadcrumbs { padding: 0.5rem 1rem; background: #f5f5f5; border-bottom: 1px solid #ddd; font-family: 'Fira Code', monospace; font-size: 12px; }
    .breadcrumb-item { cursor: pointer; color: #1976D2; }
    .breadcrumb-item:hover { text-decoration: underline; }
    .breadcrumb-sep { color: #888; margin: 0 0.25rem; }
    .dir-browser-path-bar { padding: 0.5rem 1rem; background: #fafafa; border-bottom: 1px solid #eee; display: flex; align-items: center; gap: 0.5rem; }
    .current-path { font-family: 'Fira Code', monospace; font-size: 12px; color: #666; flex: 1; overflow: hidden; text-overflow: ellipsis; }
    .path-props { display: flex; gap: 0.25rem; }
    .dir-prop-badge { font-size: 10px; padding: 0.1rem 0.4rem; border-radius: 3px; background: #e3f2fd; color: #1565C0; }
    .dir-prop-badge.git { background: #fce4ec; color: #c2185b; }
    .dir-prop-badge.clojure-project { background: #e8f5e9; color: #2e7d32; }
    .dir-prop-badge.node-project { background: #fff3e0; color: #e65100; }
    .dir-prop-badge.vscode, .dir-prop-badge.cursor, .dir-prop-badge.windsurf { background: #ede7f6; color: #5e35b1; }
    .dir-browser-error { padding: 0.5rem 1rem; background: #ffebee; color: #c62828; font-size: 13px; }
    .dir-browser-list { flex: 1; overflow-y: auto; min-height: 200px; max-height: 400px; }
    .dir-entry { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 1rem; cursor: pointer; border-bottom: 1px solid #f0f0f0; font-family: 'Fira Code', monospace; font-size: 13px; }
    .dir-entry:hover { background: #f5f5f5; }
    .dir-entry.is-dir { font-weight: 500; }
    .dir-entry.is-hidden { opacity: 0.6; }
    .dir-entry-icon { width: 20px; }
    .dir-entry-name { flex: 1; }
    .dir-entry-props { display: flex; gap: 0.25rem; }
    .empty-dir { padding: 2rem; text-align: center; color: #888; font-style: italic; }
    .dir-browser-footer { padding: 0.75rem 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; align-items: center; }
    .show-hidden { font-size: 12px; color: #666; display: flex; align-items: center; gap: 0.25rem; cursor: pointer; }
    .dir-browser-actions { display: flex; gap: 0.5rem; }
    .cancel-btn { padding: 0.4rem 1rem; border: 1px solid #ccc; border-radius: 4px; background: white; cursor: pointer; }
    .cancel-btn:hover { background: #f5f5f5; }
    .select-btn { padding: 0.4rem 1rem; border: none; border-radius: 4px; background: #4CAF50; color: white; cursor: pointer; }
    .select-btn:hover { background: #388E3C; }
    .select-btn:disabled { background: #ccc; cursor: not-allowed; }
    /* Widget architecture styles */
    .widget-toolbar { display: flex; gap: 0.25rem; padding: 0.5rem; background: #f0f0f0; border-bottom: 1px solid #ddd; flex-wrap: wrap; }
    .toolbar-btn { font-size: 12px; padding: 0.25rem 0.5rem; border: 1px solid #ccc; border-radius: 3px; background: white; cursor: pointer; }
    .toolbar-btn:hover { background: #e8e8e8; }
    .widgets-container { position: relative; flex: 1; overflow: hidden; background: #e8e8e8; border: 1px solid #ddd; }
    .winbox-widget-body { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
    .widget { display: flex; flex-direction: column; flex: 1; overflow: hidden; }
    .widget-header { padding: 0.4rem 0.5rem; background: #f5f5f5; border-bottom: 1px solid #ddd; }
    .widget-title-row { display: flex; justify-content: space-between; align-items: center; }
    .widget-title-row h3 { margin: 0; font-size: 13px; }
    .widget-actions { display: flex; gap: 0.25rem; }
    .widget-btn { font-size: 11px; padding: 0.1rem 0.4rem; border: 1px solid #ccc; border-radius: 3px; background: white; cursor: pointer; line-height: 1; }
    .widget-btn:hover { background: #e8e8e8; }
    .widget-btn.close-btn { color: #c62828; border-color: #e0e0e0; }
    .widget-btn.close-btn:hover { background: #ffebee; }
    .widget-breadcrumb { font-size: 11px; color: #666; font-family: 'Fira Code', monospace; margin-top: 0.2rem; cursor: default; user-select: none; }
    .widget-breadcrumb.expanded { background: #fafafa; border-radius: 3px; padding: 0.2rem 0; }
    .breadcrumb-collapsed { display: flex; align-items: center; gap: 0.25rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
    .breadcrumb-chevron { font-size: 8px; color: #999; }
    .breadcrumb-vertical { display: flex; flex-direction: column; gap: 0.1rem; }
    .breadcrumb-row { padding: 0.1rem 0.25rem; border-radius: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .breadcrumb-parent { cursor: pointer; }
    .breadcrumb-parent:hover { background: #e3f2fd; }
    .breadcrumb-parent .breadcrumb-segment { color: #1976D2; }
    .breadcrumb-current .breadcrumb-segment { color: #333; font-weight: 500; }
    .breadcrumb-segment { color: #333; }
    .widget-footer { padding: 0.2rem 0.5rem; background: #f5f5f5; border-top: 1px solid #ddd; font-size: 11px; color: #666; }
    /* CM6 editor inside WinBox source view */
    .source-view { display: flex; flex-direction: column; flex: 1; overflow: hidden; }
    .source-view .cm-container { flex: 1; overflow: hidden; }
    .source-view .cm-editor { height: 100%; }
    .source-info { padding: 0.25rem 0.5rem; font-size: 11px; color: #666; border-top: 1px solid #eee; }
    /* Var value view */
    .var-value-view { display: flex; flex-direction: column; flex: 1; overflow: hidden; padding: 0.5rem; gap: 0.5rem; }
    .value-header { display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
    .value-class-label { font-size: 11px; font-family: 'Fira Code', monospace; color: #555; }
    .atom-badge { font-size: 11px; padding: 0.1rem 0.4rem; border-radius: 3px; background: #fff3e0; color: #e65100; font-weight: 500; }
    .value-count-badge { font-size: 11px; color: #666; }
    .truncated-badge { font-size: 10px; padding: 0.1rem 0.3rem; border-radius: 3px; background: #fff9c4; color: #f57f17; }
    .predicate-badges { display: flex; flex-wrap: wrap; gap: 0.2rem; }
    .pred-badge { font-size: 10px; padding: 0.05rem 0.35rem; border-radius: 3px; background: #e8eaf6; color: #283593; font-family: 'Fira Code', monospace; cursor: default; }
    .pred-badge.pred-statechart { background: #f3e5f5; color: #7b1fa2; font-weight: 500; }
    .statechart-info { background: #faf5ff; border: 1px solid #e1bee7; border-radius: 4px; padding: 0.5rem; font-size: 12px; }
    .sc-row { margin-bottom: 0.2rem; }
    .sc-row strong { color: #6a1b9a; }
    .sc-states { display: flex; flex-wrap: wrap; gap: 0.25rem; }
    .sc-state-tag { font-size: 11px; padding: 0.05rem 0.35rem; border-radius: 3px; background: #e8eaf6; color: #283593; font-family: 'Fira Code', monospace; }
    .value-content { flex: 1; overflow: auto; font-family: 'Fira Code', monospace; font-size: 12px; background: #1e1e1e; color: #d4d4d4; padding: 0.75rem; border-radius: 4px; margin: 0; white-space: pre-wrap; word-break: break-word; }
    .meta-section { border-top: 1px solid #eee; padding-top: 0.4rem; }
    .meta-title { font-size: 11px; font-weight: 600; color: #666; margin-bottom: 0.25rem; }
    .meta-entries { display: grid; grid-template-columns: auto 1fr; gap: 0.1rem 0.5rem; font-size: 11px; }
    .meta-entry { display: contents; }
    .meta-key { color: #1565C0; font-family: 'Fira Code', monospace; }
    .meta-val { color: #333; font-family: 'Fira Code', monospace; overflow: hidden; text-overflow: ellipsis; }
    .meta-content { font-family: 'Fira Code', monospace; font-size: 11px; background: #f5f5f5; padding: 0.4rem; border-radius: 3px; margin: 0; white-space: pre-wrap; max-height: 100px; overflow: auto; }
    /* Var-value change flash animation */
    .value-flash { animation: flash-bg 1s ease-out; }
    @keyframes flash-bg { 0% { background-color: rgba(76, 175, 80, 0.3); } 100% { background-color: transparent; } }
    /* ======= Pane Browser Layout ======= */
    .pane-browser { display: flex; flex-direction: column; height: 100%; overflow: hidden; font-family: system-ui, sans-serif; }
    .pane-columns { display: flex; overflow: hidden; }
    .pane-column { display: flex; flex-direction: column; overflow: hidden; min-width: 60px; border-right: 1px solid #ddd; }
    .pane-column:last-of-type { border-right: none; }
    .pane-column-header { padding: 4px 8px; background: #f0f0f0; border-bottom: 1px solid #ddd; font-size: 11px; font-weight: 600; color: #555; user-select: none; flex-shrink: 0; }
    .pane-filter { width: 100%; padding: 3px 6px; border: none; border-bottom: 1px solid #ddd; font-size: 11px; outline: none; flex-shrink: 0; box-sizing: border-box; }
    .pane-filter:focus { background: #fffef0; }
    .pane-list { flex: 1; overflow-y: auto; }
    .pane-item { padding: 3px 8px; cursor: pointer; font-size: 12px; font-family: 'Fira Code', monospace; border-bottom: 1px solid #f5f5f5; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; }
    .pane-item:hover { background: #e8f0fe; }
    .pane-item.selected { background: #1976D2; color: white; }
    .pane-footer { padding: 2px 8px; font-size: 10px; color: #888; border-top: 1px solid #eee; background: #fafafa; flex-shrink: 0; }
    .pane-add-project { border-top: 1px solid #eee; flex-shrink: 0; }
    .pane-item .type-count { font-size: 10px; color: #888; margin-left: 4px; }
    .pane-item.selected .type-count { color: rgba(255,255,255,0.7); }
    .pane-item .sym-kind { font-size: 10px; color: #888; margin-left: auto; padding-left: 8px; }
    .pane-item.selected .sym-kind { color: rgba(255,255,255,0.7); }
    .pane-loading { display: flex; align-items: center; justify-content: center; padding: 2rem; color: #888; flex: 1; }
    .pane-loading .mini-spinner { width: 16px; height: 16px; border: 2px solid #ddd; border-top-color: #333; border-radius: 50%; animation: spin 0.8s linear infinite; margin-right: 8px; }
    .pane-idle { flex: 1; }
    /* Dividers */
    .pane-divider-v { width: 4px; background: #e0e0e0; cursor: col-resize; flex-shrink: 0; }
    .pane-divider-v:hover { background: #bdbdbd; }
    .pane-divider-h { height: 4px; background: #e0e0e0; cursor: row-resize; flex-shrink: 0; }
    .pane-divider-h:hover { background: #bdbdbd; }
    /* Inspector */
    .pane-inspector { display: flex; flex-direction: column; overflow: hidden; border-top: 1px solid #ddd; }
    .inspector-tabs { display: flex; gap: 2px; padding: 4px 8px; background: #f0f0f0; border-bottom: 1px solid #ddd; flex-shrink: 0; }
    .inspector-tab { padding: 3px 10px; font-size: 11px; border: 1px solid #ddd; background: #fafafa; cursor: pointer; border-radius: 3px 3px 0 0; user-select: none; }
    .inspector-tab:hover { background: #e8e8e8; }
    .inspector-tab.active { background: white; border-bottom-color: white; font-weight: 600; color: #1976D2; }
    .inspector-content { flex: 1; overflow: auto; }
    .inspector-empty { padding: 2rem; text-align: center; color: #888; font-style: italic; }
    .inspector-content .source-view { display: flex; flex-direction: column; height: 100%; }
    .inspector-content .source-view .cm-container { flex: 1; overflow: hidden; }
    .inspector-content .source-view .cm-editor { height: 100%; }
    /* Keyboard navigation */
    .pane-column.pane-focused { box-shadow: inset 0 0 0 2px #90caf9; }
    .pane-item.highlighted { background: #e8f0fe; outline: 1px solid #bbdefb; }
    .pane-item.highlighted.selected { background: #1565C0; outline: 1px solid #0d47a1; }
    .pane-browser:focus { outline: none; }
    /* Dep/Caller open-in-new-window icon */
    .dep-open-new { margin-left: auto; padding: 0 4px; cursor: pointer; color: #999; font-size: 12px; }
    .dep-open-new:hover { color: #1976D2; }
    /* Navigation buttons */
    .nav-btn { font-size: 11px; padding: 2px 6px; border: 1px solid #ccc; border-radius: 3px; background: #fafafa; cursor: pointer; line-height: 1; color: #555; margin-right: 2px; }
    .nav-btn:hover:not(:disabled) { background: #e3f2fd; border-color: #90caf9; }
    .nav-btn:disabled { opacity: 0.3; cursor: default; }
    /* Sort mode toggle */
    .pane-column-header { display: flex; align-items: center; justify-content: space-between; }
    .sort-mode-btn { font-size: 10px; padding: 1px 6px; border: 1px solid #ccc; border-radius: 3px; background: #fafafa; cursor: pointer; line-height: 1.2; color: #555; }
    .sort-mode-btn:hover { background: #e3f2fd; border-color: #90caf9; }
    /* Top-level form items */
    .pane-item.top-level { font-style: italic; color: #888; border-left: 2px solid #ffb74d; }
    .pane-item.top-level:hover { background: #fff8e1; }
    .pane-item.top-level.selected { color: white; border-left-color: #ffa726; }
    /* Copy feedback */
    .copy-fqn-btn.nav-btn:not(:disabled) { color: #1976D2; }
    /* Drop target highlight */
    .pane-browser.drop-target { outline: 2px dashed #1976D2; outline-offset: -2px; }
    /* Draggable items */
    .pane-item[draggable=true] { cursor: grab; }
    .pane-item[draggable=true]:active { cursor: grabbing; }
    .dep-item[draggable=true] { cursor: grab; }
    .dep-item[draggable=true]:active { cursor: grabbing; }
    /* Browser toolbar */
    .browser-toolbar { display: flex; gap: 0.5rem; padding: 0.5rem; background: #f5f5f5; border-bottom: 1px solid #ddd; }
    .new-browser-btn { font-size: 12px; padding: 4px 12px; border: 1px solid #ccc; border-radius: 3px; background: white; cursor: pointer; }
    .new-browser-btn:hover { background: #e8e8e8; }
  </style>
</head>
<body>
  <div id=\"app\">
    <div id=\"status\" class=\"status connecting\">Loading Code Browser...</div>
    <div id=\"ui-loader\">
      <button id=\"load-ui-btn\" class=\"load-ui-btn\" onclick=\"scittle.core.eval_string('(ui-loader/load-code-browser-ui!)')\" disabled>Load Code Browser</button>
      <span id=\"loader-status\">Waiting for WebSocket connection...</span>
    </div>
    <div id=\"log\"></div>
    <div id=\"code-browser-root\"><!-- UI loaded via button or nREPL --></div>
  </div>
  <!-- 1. Scittle core -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js\"></script>

  <!-- 2. FakeWebSocket - MUST be before scittle.nrepl.js -->
"  (fake-ws-script) "

  <!-- 3. UUIDv7 (zero deps - load first so all code can use temporal IDs) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.5.0/src/com/github/franks42/uuidv7/core.cljc\" type=\"application/x-scittle\"></script>

  <!-- 4. React + ReactDOM (required for Reagent) -->
  <script src=\"https://cdn.jsdelivr.net/npm/react@18.2.0/umd/react.production.min.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/react-dom@18.2.0/umd/react-dom.production.min.js\"></script>

  <!-- 4. Scittle plugins: nREPL, Reagent, Promesa -->
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.nrepl.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.reagent.js\"></script>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.promesa.js\"></script>

  <!-- 5. Trove (logging) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc\" type=\"application/x-scittle\"></script>
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc\" type=\"application/x-scittle\"></script>

  <!-- 5b. clj-statecharts (connection lifecycle state machine) -->
  <script src=\"https://cdn.jsdelivr.net/gh/franks42/clj-statecharts-bb-scittle@v0.1.7-scittle/dist/statecharts-bundle.cljc\" type=\"application/x-scittle\"></script>

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

  <!-- WinBox floating windows -->
  <script src=\"https://cdn.jsdelivr.net/npm/winbox@0.2.82/dist/winbox.bundle.min.js\"></script>

  <!-- 7. sente-lite-nrepl bundle -->
  <script src=\"/sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>

  <!-- 7b. Browser telemetry (forwards Trove logs to server) -->
  <script src=\"/browser/browser_telemetry.cljs\" type=\"application/x-scittle\"></script>

  <!-- 8. Config injection + code browser infrastructure -->
"  (config-script ws-config) "
  <script src=\"/browser/bootstrap_client.cljs\" type=\"application/x-scittle\"></script>

  <!-- 8b. UI Loader (replaces inline JS loadCodeBrowserUI) -->
  <script src=\"/browser/ui_loader.cljs\" type=\"application/x-scittle\"></script>

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

      ;; Serve .cljc files from code-browser-v2 module or src/ (for browser use)
      (and (str/starts-with? uri "/cljc/")
           (str/ends-with? uri ".cljc"))
      (let [filename (subs uri 6) ;; strip "/cljc/"
            file (let [f1 (io/file "modules/code-browser-v2/src" filename)]
                   (if (.exists f1)
                     f1
                     (let [f2 (io/file "src" filename)]
                       (when (.exists f2) f2))))]
        (if file
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
   - :bootstrap-port - HTTP port (default 0 = ephemeral)
   - :ws-host - WebSocket host to connect to (default 127.0.0.1)
   - :ws-port - WebSocket port to connect to (default 0)
   - :bundle-path - path to sente-lite-nrepl.cljs bundle file
   - :code-browser - enable code-browser mode (enhanced HTML with CM6)

   Returns map with :server and :port (actual bound port)."
  [config]
  (let [host (get config :host "127.0.0.1")
        port (get config :bootstrap-port 0)
        ws-host (get config :ws-host (get config :host "127.0.0.1"))
        ws-port (get config :ws-port 0)
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
                  {:ip host :port port})
          actual-port (-> server meta :local-port)]
      (reset! !server server)

      (log/log! {:level :info
                 :id ::started
                 :msg (if code-browser-mode?
                        "Code Browser ready"
                        "Bootstrap server started")
                 :data {:url (str "http://" host ":" actual-port)
                        :port actual-port
                        :code-browser code-browser-mode?}})
      {:server server :port actual-port})))

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
