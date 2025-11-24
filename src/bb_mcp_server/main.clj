(ns bb-mcp-server.main
    "Unified entry point for bb-mcp-server.

   Supports multiple transports that can run individually or simultaneously:
   - stdio: JSON-RPC over stdin/stdout (Claude Desktop, pipes)
   - http:  MCP Streamable HTTP with SSE + REST API

   Usage:
     bb server              ; stdio (default, for Claude Desktop)
     bb server --http       ; HTTP only on port 3000
     bb server --http 8080  ; HTTP only on port 8080
     bb server --stdio --http       ; both transports
     bb server --stdio --http 8080  ; both, HTTP on 8080"
    (:require [bb-mcp-server.module.system :as sys]
              [bb-mcp-server.test-harness :as harness]
              [bb-mcp-server.protocol.processor :as processor]
              [bb-mcp-server.protocol.router :as router]
              [bb-mcp-server.registry :as registry]
              [mcp-stdio.core :as stdio]
              [streamable-http.core :as shttp]
              [pid-util :as pid-util]
              [taoensso.trove :as log]))

;; =============================================================================
;; CLI Argument Parsing
;; =============================================================================

(defn parse-args
  "Parse command line arguments into options map.

   Returns {:stdio bool, :http bool, :port int, :help bool}"
  [args]
  (loop [args args
         opts {:stdio false :http false :port 3000 :help false}]
        (if (empty? args)
      ;; Default to stdio if nothing specified
          (if (and (not (:stdio opts)) (not (:http opts)))
            (assoc opts :stdio true)
            opts)
          (let [[arg & rest-args] args]
            (cond
              (or (= arg "-h") (= arg "--help"))
              (assoc opts :help true)

              (= arg "--stdio")
              (recur rest-args (assoc opts :stdio true))

              (= arg "--http")
          ;; Check if next arg is a port number
              (let [maybe-port (first rest-args)]
                (if (and maybe-port (re-matches #"\d+" maybe-port))
                  (recur (rest rest-args) (assoc opts :http true :port (parse-long maybe-port)))
                  (recur rest-args (assoc opts :http true))))

              (= arg "--port")
          ;; Explicit --port flag
              (let [port-str (first rest-args)]
                (if (and port-str (re-matches #"\d+" port-str))
                  (recur (rest rest-args) (assoc opts :port (parse-long port-str)))
                  (do (println "Error: --port requires a number")
                      (assoc opts :help true))))

          ;; Unknown flag
              :else
              (do (println "Unknown option:" arg)
                  (assoc opts :help true)))))))

(defn print-help
  "Print usage help."
  []
  (println "
bb-mcp-server - MCP Server with multiple transports

USAGE:
  bb server [OPTIONS]

OPTIONS:
  --stdio         Run stdio transport (JSON-RPC over stdin/stdout)
  --http [PORT]   Run HTTP transport (default port: 3000)
  --port PORT     Set HTTP port explicitly
  -h, --help      Show this help

EXAMPLES:
  bb server                    # stdio only (default, for Claude Desktop)
  bb server --http             # HTTP only on port 3000
  bb server --http 8080        # HTTP only on port 8080
  bb server --port 8080 --http # Same as above
  bb server --stdio --http     # Both transports simultaneously

TRANSPORTS:
  stdio - JSON-RPC over stdin/stdout
          For Claude Desktop and pipe-based integrations
          Logs go to stderr, JSON-RPC to stdout

  http  - MCP Streamable HTTP (spec 2025-03-26)
          POST /mcp     - JSON-RPC requests
          GET  /mcp     - SSE stream (with Mcp-Session-Id)
          DELETE /mcp   - Terminate session
          GET  /health  - Health check

          REST API:
          GET  /api/server                      - Server info
          GET  /api/modules                     - List modules
          GET  /api/modules/:mod/tools          - List tools
          GET  /api/modules/:mod/tools/:name    - Tool metadata
          POST /api/modules/:mod/tools/:name    - Call tool
          GET  /api/docs                        - HTML docs
          GET  /api/openapi.json                - OpenAPI spec
"))

;; =============================================================================
;; Initialization (shared by all transports)
;; =============================================================================

(defn initialize-system!
  "Initialize the module system and MCP handlers.

   Returns true on success, exits on failure."
  [verbose?]
  (log/log! {:level :info
             :id    ::system-initializing
             :msg   "Initializing bb-mcp-server"})
  (let [start (System/currentTimeMillis)]

    ;; Load modules from system.edn
    (when verbose? (println "[1/3] Loading modules from system.edn..."))
    (let [create-result (sys/create-system-from-config)]
      (if (:error create-result)
        (do (log/log! {:level :error
                       :id    ::system-create-failed
                       :msg   "Failed to create system"
                       :data  {:error (:error create-result)}})
            (binding [*out* *err*]
                     (println "ERROR: Failed to create system:" (:error create-result)))
            (System/exit 1))
        (when verbose?
          (println "      Modules:" (get-in create-result [:success :modules])))))

    ;; Start module system
    (when verbose? (println "[2/3] Starting modules..."))
    (let [start-result (sys/start-system!)]
      (if (:error start-result)
        (do (log/log! {:level :error
                       :id    ::system-start-failed
                       :msg   "Failed to start module system"
                       :data  {:error (:error start-result)}})
            (binding [*out* *err*]
                     (println "ERROR: Failed to start system:" (:error start-result)))
            (System/exit 1))
        (when verbose?
          (println "      Started:" (get-in start-result [:success :started])))))

    ;; Register MCP handlers
    (when verbose? (println "[3/3] Registering MCP handlers..."))
    (harness/setup-handlers-only!)
    (when verbose? (println "      Done"))

    (let [duration (- (System/currentTimeMillis) start)]
      (log/log! {:level :info
                 :id    ::system-initialized
                 :msg   "System initialization complete"
                 :data  {:duration-ms duration}}))
    true))

;; =============================================================================
;; Transport Starters
;; =============================================================================

(defn start-http!
  "Start HTTP transport on given port. Returns server instance."
  [port]
  (log/log! {:level :info
             :id    ::http-starting
             :msg   "Starting HTTP transport"
             :data  {:port port}})
  (println (str "\n=== Starting HTTP transport on port " port " ==="))

  ;; Write PID file
  (pid-util/write-pid-file! port)

  ;; Create JSON-RPC handler
  (let [handler (fn [ctx request] (router/route-request ctx request))
        server (shttp/start-server!
                handler
                {:port port
                 :host "0.0.0.0"
                 :path "/mcp"
                 :health-path "/health"
                 :rest-config {:list-tools-fn            registry/list-tools-for-transport
                               :get-tool-fn              registry/get-tool
                               :get-handler-fn           registry/get-handler
                               :supports-rest-fn         registry/tool-supports-transport?
                               :list-modules-fn          registry/list-modules
                               :list-tools-for-module-fn registry/list-tools-for-module
                               :get-tool-in-module-fn    registry/get-tool-in-module
                               :server-info-fn           (fn []
                                                           {:name "bb-mcp-server"
                                                            :version "0.1.0"
                                                            :moduleToolSeparator registry/module-tool-separator
                                                            :mcpProtocolVersion "2025-03-26"})}})]

    ;; Set up tool list change notifications
    (registry/set-list-changed-callback!
     #(shttp/broadcast-notification! "notifications/tools/list_changed" {}))

    (log/log! {:level :info
               :id    ::http-started
               :msg   "HTTP transport started"
               :data  {:port port
                       :mcp-path "/mcp"
                       :rest-path "/api/"
                       :docs-path "/api/docs"}})

    (println (str "    MCP:  http://localhost:" port "/mcp"))
    (println (str "    REST: http://localhost:" port "/api/"))
    (println (str "    Docs: http://localhost:" port "/api/docs"))

    server))

(defn start-stdio!
  "Start stdio transport. Blocks until stdin closes."
  []
  (log/log! {:level :info
             :id    ::stdio-starting
             :msg   "Starting stdio transport"})
  (let [ctx (processor/make-stdio-ctx)
        handler (fn [line] (processor/process-request-str ctx line))]
    (stdio/run-stdio-loop! handler)))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point."
  [& args]
  (let [opts (parse-args args)]
    (cond
      ;; Help requested
      (:help opts)
      (do (print-help)
          (System/exit 0))

      ;; Stdio only (default)
      (and (:stdio opts) (not (:http opts)))
      (do (initialize-system! false) ; quiet for stdio
          (start-stdio!))

      ;; HTTP only
      (and (:http opts) (not (:stdio opts)))
      (do (println "\n=== BB MCP Server ===")
          (initialize-system! true)
          (let [server (start-http! (:port opts))]
            ;; Shutdown hook
            (.addShutdownHook
             (Runtime/getRuntime)
             (Thread. (fn []
                        (log/log! {:level :info
                                   :id    ::shutdown-initiated
                                   :msg   "Shutdown initiated"
                                   :data  {:transport :http}})
                        (println "\nShutting down...")
                        (shttp/stop-server! server)
                        (pid-util/delete-pid-file! (:port opts))
                        (log/log! {:level :info
                                   :id    ::shutdown-complete
                                   :msg   "Shutdown complete"})
                        (println "Goodbye!"))))
            (println "\nServer ready. Press Ctrl+C to stop.")
            ;; Keep running
            (deref (promise))))

      ;; Both transports
      (and (:stdio opts) (:http opts))
      (do (log/log! {:level :info
                     :id    ::dual-transport-mode
                     :msg   "Starting dual transport mode"
                     :data  {:http-port (:port opts)}})
          (println "\n=== BB MCP Server (Dual Transport) ===")
          (initialize-system! true)

          ;; Start HTTP in background
          (let [server (start-http! (:port opts))]
            (.addShutdownHook
             (Runtime/getRuntime)
             (Thread. (fn []
                        (log/log! {:level :info
                                   :id    ::shutdown-initiated
                                   :msg   "Shutdown initiated"
                                   :data  {:transport :dual}})
                        (println "\nShutting down...")
                        (shttp/stop-server! server)
                        (pid-util/delete-pid-file! (:port opts))
                        (log/log! {:level :info
                                   :id    ::shutdown-complete
                                   :msg   "Shutdown complete"})
                        (println "Goodbye!")))))

          (println "\n=== Starting stdio transport ===")
          (println "    Reading from stdin, writing to stdout")
          (println "    (HTTP continues running in background)\n")

          ;; Stdio blocks
          (start-stdio!)))))

;; Run main when loaded as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
