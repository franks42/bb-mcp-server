(ns nrepl-test-server.core
    "Module that auto-starts a Babashka nREPL server for testing.

   This module starts a local nREPL server when the MCP server starts,
   providing a target for testing nrepl client tools like nrepl-connection,
   nrepl-eval, etc.

   Configuration (via system.edn):
     :port - Port to listen on (default: 7888)
     :host - Host to bind to (default: localhost)"
    (:require [babashka.nrepl.server :as nrepl]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private server-state (atom nil))

;; =============================================================================
;; Server Management
;; =============================================================================

(defn- extract-port
  "Extract the actual port from the server socket."
  [server-map]
  (when-let [socket (:socket server-map)]
            (.getLocalPort socket)))

(defn- start-server!
  "Start the nREPL server on the specified host and port."
  [{:keys [host port]}]
  (try
   (let [server-map (nrepl/start-server! {:host host :port port})
         actual-port (extract-port server-map)]
     (reset! server-state {:server server-map
                           :host host
                           :port actual-port
                           :started-at (System/currentTimeMillis)})
     (log/log! {:level :info
                :id ::server-started
                :msg "nREPL test server started"
                :data {:host host :port actual-port}})
     {:status :started :host host :port actual-port})
   (catch Exception e
          (log/log! {:level :error
                     :id ::server-start-failed
                     :msg "Failed to start nREPL test server"
                     :data {:host host :port port :error (.getMessage e)}})
          {:status :failed :error (.getMessage e)})))

(defn- stop-server!
  "Stop the running nREPL server."
  []
  (when-let [{:keys [server host port]} @server-state]
            (try
             (nrepl/stop-server! server)
             (reset! server-state nil)
             (log/log! {:level :info
                        :id ::server-stopped
                        :msg "nREPL test server stopped"
                        :data {:host host :port port}})
             {:status :stopped :host host :port port}
             (catch Exception e
                    (log/log! {:level :error
                               :id ::server-stop-failed
                               :msg "Failed to stop nREPL test server"
                               :data {:error (.getMessage e)}})
                    {:status :failed :error (.getMessage e)}))))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the nREPL test server module.

   Config options:
     :port - Port to listen on (default: 7888)
     :host - Host to bind to (default: localhost)"
  [_deps config]
  (let [host (get config :host "localhost")
        port (get config :port 7888)]
    (log/log! {:level :info
               :id ::module-starting
               :msg "Starting nREPL test server module"
               :data {:host host :port port}})
    (let [result (start-server! {:host host :port port})]
      (if (= :started (:status result))
        {:server-host host
         :server-port port
         :connection-string (str host ":" port)}
        (throw (ex-info "Failed to start nREPL test server" result))))))

(defn stop
  "Stop the nREPL test server module."
  [_instance]
  (log/log! {:level :info
             :id ::module-stopping
             :msg "Stopping nREPL test server module"})
  (stop-server!)
  nil)

(defn status
  "Get nREPL test server status."
  [_instance]
  (if-let [{:keys [host port started-at]} @server-state]
          {:status :running
           :host host
           :port port
           :connection-string (str host ":" port)
           :uptime-ms (- (System/currentTimeMillis) started-at)}
          {:status :stopped}))

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "nREPL test server module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
