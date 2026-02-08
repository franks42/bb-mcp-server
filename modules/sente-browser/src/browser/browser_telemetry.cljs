(ns browser-telemetry
  "Browser Trove log-fn wrapper that forwards structured log entries to server.

   Wraps Trove's *log-fn* (same pattern as server telemetry-db.core/make-wrapper-log-fn)
   to capture browser-side structured log data and send it to the server's telemetry-db
   via sente :telemetry/log events.

   Only forwards :info and above to avoid flooding the WebSocket with debug/trace noise.
   Console output is always preserved unchanged.

   Usage:
     (browser-telemetry/init! client-id)   ; After sente connection established
     (browser-telemetry/stop!)             ; Restore original log-fn"
  (:require [taoensso.trove :as log]
            [sente-lite.client-scittle :as client]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !original-log-fn (atom nil))
(defonce ^:private !active-client-id (atom nil))

;; Level priority map - only forward :info and above
(def ^:private level-priority
  {:trace 0 :debug 10 :info 20 :warn 30 :error 40 :fatal 50
   "trace" 0 "debug" 10 "info" 20 "warn" 30 "error" 40 "fatal" 50})

(def ^:private min-forward-priority 20) ;; :info level

;; =============================================================================
;; Wrapper Log-Fn
;; =============================================================================

(defn- make-wrapper-log-fn
  "Create a wrapper log-fn that forwards structured entries to server.
   Always calls original log-fn first (console output unchanged)."
  [original-log-fn client-id]
  (fn [ns-str coords level id lazy_]
    ;; Always forward to original (console output) first
    (when original-log-fn
      (original-log-fn ns-str coords level id lazy_))
    ;; Forward to server if level >= :info
    (when (>= (get level-priority level 0) min-forward-priority)
      (try
        (let [forced (if (delay? lazy_) @lazy_ lazy_)
              {:keys [msg data error]} (if (map? forced) forced {})
              entry (cond-> {:level (name level)
                             :ns (or ns-str "browser")}
                      id (assoc :event-id (str id))
                      msg (assoc :msg (str msg))
                      data (assoc :data (pr-str data))
                      error (assoc :error (str error)))]
          (client/send! client-id [:telemetry/log entry]))
        (catch :default _e
          ;; Never break the app - silently swallow send errors
          nil)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn init!
  "Install browser telemetry wrapper around Trove *log-fn*.
   Call after sente connection is established.

   Args:
     cid - sente client-id for sending events"
  [cid]
  (when (compare-and-set! !active-client-id nil cid)
    (let [original log/*log-fn*
          wrapper (make-wrapper-log-fn original cid)]
      (reset! !original-log-fn original)
      (log/set-log-fn! wrapper)
      (js/console.log "[browser-telemetry] Installed - forwarding :info+ to server"))))

(defn stop!
  "Restore original Trove log-fn, stopping telemetry forwarding."
  []
  (when-let [original @!original-log-fn]
    (log/set-log-fn! original)
    (reset! !original-log-fn nil)
    (reset! !active-client-id nil)
    (js/console.log "[browser-telemetry] Stopped - restored original log-fn")))
