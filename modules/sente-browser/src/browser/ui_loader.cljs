(ns ui-loader
    "Loads Pane Browser UI files and mounts.
   Replaces the inline JS loadCodeBrowserUI() function."
    (:require [taoensso.trove :as log]))

(defn- set-status!
  "Update loader status text."
  [text]
  (when-let [el (js/document.getElementById "loader-status")]
            (set! (.-textContent el) text)))

(defn- ^:async fetch-and-eval!
  "Fetch a file and eval it as Scittle code."
  [url label]
  (set-status! (str "Loading " label "..."))
  (let [resp (await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to load " label))))
    (let [code (await (.text resp))]
      (js/scittle.core.eval_string code))))

(defn ^:async load-code-browser-ui!
  "Load all Code Browser v2 files and mount the UI.
   Called from the Load Code Browser button."
  []
  (let [btn (js/document.getElementById "load-ui-btn")]
    (set! (.-disabled btn) true)
    (try
     (await (fetch-and-eval! "/browser/scittle_cm6.cljs" "scittle-cm6"))
     (await (fetch-and-eval! "/cljc/code_browser/uri.cljc" "URI module"))
     (await (fetch-and-eval! "/browser/pane_browser.cljs" "pane-browser"))
     (set-status! "Mounting UI...")
     (js/scittle.core.eval_string "(pane-browser/mount!)")
     (set! (.. (js/document.getElementById "ui-loader") -style -display) "none")
     (set-status! "Pane Browser loaded!")
     (log/log! {:level :info :id ::ui-loaded :msg "Pane Browser loaded"})
     (catch js/Error e
            (js/console.error "[load-ui] Error:" e)
            (set-status! (str "Error: " (.-message e)))
            (set! (.-disabled btn) false)))))

(defn enable-load-button!
  "Enable the Load Code Browser button. Called when WebSocket connects."
  []
  (when-let [btn (js/document.getElementById "load-ui-btn")]
            (set! (.-disabled btn) false)
            (set-status! "Ready - click to load UI")))

;; Expose enableLoadButton to JS for bootstrap_client.cljs (uses aget js/window)
(aset js/window "enableLoadButton" enable-load-button!)
