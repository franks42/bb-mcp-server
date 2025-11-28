(ns webserver.reload
    "Live reload via WebSocket and file watching."
    (:require [babashka.fs :as fs]
              [org.httpkit.server :as http]
              [taoensso.trove :as log]))

;; =============================================================================
;; WebSocket Clients
;; =============================================================================

(defonce ^{:doc "WebSocket clients per port: {port #{channel ...}}"}
 clients (atom {}))

(defn add-client!
  "Add WebSocket client for port."
  [port channel]
  (swap! clients update port (fnil conj #{}) channel)
  (log/log! {:level :debug
             :id ::client-added
             :msg "Reload client connected"
             :data {:port port}}))

(defn remove-client!
  "Remove WebSocket client."
  [port channel]
  (swap! clients update port disj channel)
  (log/log! {:level :debug
             :id ::client-removed
             :msg "Reload client disconnected"
             :data {:port port}}))

(defn broadcast-reload!
  "Send reload message to all clients on port."
  [port]
  (let [chans (get @clients port #{})]
    (log/log! {:level :info
               :id ::broadcast-reload
               :msg "Broadcasting reload"
               :data {:port port :client-count (count chans)}})
    (doseq [ch chans]
           (try
            (http/send! ch "reload")
            (catch Exception _
                   (remove-client! port ch))))))

;; =============================================================================
;; WebSocket Handler
;; =============================================================================

(defn websocket-handler
  "WebSocket handler for reload endpoint."
  [port req]
  #_{:clj-kondo/ignore [:deprecated-var]}
  (http/with-channel req channel
                     (add-client! port channel)
                     (http/on-close channel
                                    (fn [_status]
                                      (remove-client! port channel)))))

;; =============================================================================
;; File Watcher
;; =============================================================================

(defonce ^{:doc "Active file watchers per port: {port watcher}"}
 watchers (atom {}))

(defn start-watcher!
  "Start file watcher for root directory."
  [port root]
  (log/log! {:level :info
             :id ::watcher-starting
             :msg "Starting file watcher"
             :data {:port port :root (str root)}})
  (let [watcher-future
        (future
         (try
            ;; Use polling-based approach for simplicity
            ;; babashka.fs/modified-since tracks changes
          (let [last-modified (atom (System/currentTimeMillis))]
            (while (contains? @watchers port)
                   (Thread/sleep 500)
                   (let [now (System/currentTimeMillis)
                         files (fs/glob root "**/*")
                         changed? (some (fn [f]
                                          (when (fs/regular-file? f)
                                            (> (fs/file-time->millis
                                                (fs/last-modified-time f))
                                               @last-modified)))
                                        files)]
                     (when changed?
                       (reset! last-modified now)
                       (broadcast-reload! port)))))
          (catch Exception e
                 (log/log! {:level :error
                            :id ::watcher-error
                            :msg "File watcher error"
                            :data {:port port :error (.getMessage e)}}))))]
    (swap! watchers assoc port watcher-future)
    watcher-future))

(defn stop-watcher!
  "Stop file watcher for port."
  [port]
  (when-let [watcher (get @watchers port)]
            (log/log! {:level :info
                       :id ::watcher-stopping
                       :msg "Stopping file watcher"
                       :data {:port port}})
            (swap! watchers dissoc port)
            (future-cancel watcher)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn enable-reload!
  "Enable live reload for server on port."
  [port root]
  (start-watcher! port root))

(defn disable-reload!
  "Disable live reload for server on port."
  [port]
  (stop-watcher! port)
  (swap! clients dissoc port))

(defn cleanup!
  "Cleanup all reload state for port."
  [port]
  (stop-watcher! port)
  ;; Close all WebSocket connections
  (doseq [ch (get @clients port #{})]
         (try
          (http/close ch)
          (catch Exception _)))
  (swap! clients dissoc port))
