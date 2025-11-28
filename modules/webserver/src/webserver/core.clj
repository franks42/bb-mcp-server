(ns webserver.core
    "Simple static file server with live reload support.

   Usage:
     (require '[webserver.core :as ws])

     ;; Start server (opens browser)
     (ws/start! {:port 9876 :root \"./webroot\"})

     ;; With options
     (ws/start! {:port 9876
                 :root \"./webroot\"
                 :name \"dashboard\"
                 :reload true
                 :open-browser true})

     ;; List/stop
     (ws/list-servers)
     (ws/stop! 9876)
     (ws/stop! \"dashboard\")
     (ws/stop-all!)"
    (:require [org.httpkit.server :as http]
              [babashka.fs :as fs]
              [babashka.process :as process]
              [webserver.handler :as handler]
              [webserver.reload :as reload]
              [taoensso.trove :as log]))

;; =============================================================================
;; Server Registry
;; =============================================================================

(defonce ^{:doc "Running servers: {port {:server ... :name ... :root ... :reload ...}}"}
 servers (atom {}))

(defn- get-by-name
  "Find server info by name."
  [name]
  (first (filter #(= (:name (val %)) name) @servers)))

(defn- resolve-port
  "Resolve port from port number or server name."
  [port-or-name]
  (if (string? port-or-name)
    (first (get-by-name port-or-name))
    port-or-name))

;; =============================================================================
;; Browser Opening
;; =============================================================================

(defn- open-browser!
  "Open URL in default browser."
  [url]
  (log/log! {:level :info
             :id ::opening-browser
             :msg "Opening browser"
             :data {:url url}})
  (try
    ;; macOS
   (process/shell {:out :inherit :err :inherit}
                  "open" url)
   (catch Exception e
          (log/log! {:level :warn
                     :id ::browser-failed
                     :msg "Failed to open browser"
                     :data {:error (.getMessage e)}}))))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(def ^{:doc "Default port for webserver."} default-port 9876)

(defn start!
  "Start a static file server.

   Options:
     :port         - Port number (default: 9876)
     :root         - Webroot directory (default: ./webroot)
     :name         - Server name for identification
     :reload       - Enable live reload (default: false)
     :open-browser - Open browser on start (default: true)
     :hiccup       - Enable Hiccup rendering (default: false)

   Returns: Server info map"
  [{:keys [port root name reload open-browser hiccup]
    :or {port default-port
         root "./webroot"
         open-browser true
         reload false
         hiccup false}}]
  (let [port (or port default-port)
        root (fs/absolutize root)
        name (or name (str "server-" port))]

    ;; Validate root exists
    (when-not (fs/exists? root)
      (throw (ex-info "Webroot directory does not exist"
                      {:root (str root)})))

    ;; Check port not already in use
    (when (contains? @servers port)
      (throw (ex-info "Port already in use"
                      {:port port})))

    (log/log! {:level :info
               :id ::server-starting
               :msg "Starting webserver"
               :data {:port port :root (str root) :name name :reload reload}})

    ;; Create handler
    (let [file-handler (handler/create-handler {:root root
                                                :reload reload
                                                :hiccup hiccup})
          ;; Wrap with WebSocket support for reload
          wrapped-handler (fn [req]
                            (if (and reload (= (:uri req) "/_reload"))
                              (reload/websocket-handler port req)
                              (file-handler req)))

          ;; Start server
          server (http/run-server wrapped-handler {:port port})

          server-info {:server server
                       :port port
                       :root (str root)
                       :name name
                       :reload reload
                       :hiccup hiccup}]

      ;; Register server
      (swap! servers assoc port server-info)

      ;; Start file watcher if reload enabled
      (when reload
        (reload/enable-reload! port root))

      ;; Open browser
      (when open-browser
        (open-browser! (str "http://localhost:" port)))

      (log/log! {:level :info
                 :id ::server-started
                 :msg "Webserver started"
                 :data {:port port :url (str "http://localhost:" port)}})

      ;; Return public info (without server object)
      (dissoc server-info :server))))

(defn stop!
  "Stop server by port or name."
  [port-or-name]
  (let [port (resolve-port port-or-name)]
    (when-let [{:keys [server name]} (get @servers port)]
              (log/log! {:level :info
                         :id ::server-stopping
                         :msg "Stopping webserver"
                         :data {:port port :name name}})

      ;; Cleanup reload
              (reload/cleanup! port)

      ;; Stop server
              (server)

      ;; Unregister
              (swap! servers dissoc port)

              (log/log! {:level :info
                         :id ::server-stopped
                         :msg "Webserver stopped"
                         :data {:port port}}))))

(defn stop-all!
  "Stop all running servers."
  []
  (doseq [port (keys @servers)]
         (stop! port)))

(defn list-servers
  "List all running servers."
  []
  (mapv (fn [[_ info]]
          (dissoc info :server))
        @servers))

(defn set-reload!
  "Enable/disable live reload for running server."
  [port-or-name enabled?]
  (let [port (resolve-port port-or-name)]
    (when-let [info (get @servers port)]
              (if enabled?
                (do
                 (reload/enable-reload! port (:root info))
                 (swap! servers assoc-in [port :reload] true))
                (do
                 (reload/disable-reload! port)
                 (swap! servers assoc-in [port :reload] false))))))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Module start - no-op, servers started via API."
  [_deps _config]
  (log/log! {:level :info
             :id ::module-started
             :msg "Webserver module loaded"})
  {})

(defn stop
  "Module stop - stop all servers."
  [_instance]
  (log/log! {:level :info
             :id ::module-stopping
             :msg "Stopping webserver module"})
  (stop-all!)
  nil)

(defn status
  "Module status."
  [_instance]
  {:status :ok
   :servers (list-servers)})

(def module
     "Webserver module lifecycle."
     {:start start
      :stop stop
      :status status})
