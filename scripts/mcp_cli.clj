#!/usr/bin/env bb

;; MCP CLI - Generic command-line interface for MCP tool operations
;;
;; Usage: bb scripts/mcp_cli.clj <subcommand> [args] [options]
;;
;; Subcommands:
;;   init              Show server info (initialize response)
;;   tools             List all available tools
;;   tool <name>       Show specific tool schema
;;   call <name> <json> Call a tool with JSON arguments
;;   servers           List running servers
;;   help              Show this help
;;
;; Options:
;;   --mcp NAME        Server nickname (auto-detects if single server)
;;   --port PORT       Server port number
;;   --pprint          Pretty-print output

(require '[bb-mcp-server.mcp-client :as client]
         '[cheshire.core :as json]
         '[clojure.pprint :as pp])

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn parse-args
  "Parse command line arguments into structured options."
  [args]
  (loop [args args
         opts {:subcommand nil
               :positional []
               :mcp nil
               :port nil
               :pprint false}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              ;; Options
              (= arg "--mcp")
              (recur (rest rest-args) (assoc opts :mcp (first rest-args)))

              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (Integer/parseInt (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              ;; First non-option is subcommand
              (nil? (:subcommand opts))
              (recur rest-args (assoc opts :subcommand arg))

              ;; Remaining args are positional
              :else
              (recur rest-args (update opts :positional conj arg)))))))

;; =============================================================================
;; Output Helpers
;; =============================================================================

(defn output-value
  "Output a value, optionally pretty-printed."
  [value pprint?]
  (if pprint?
    (pp/pprint value)
    (println (json/generate-string value {:pretty true}))))

(defn resolve-server
  "Resolve server from options: --port > --mcp > auto-detect"
  [{:keys [port mcp]}]
  (cond
    port port
    mcp (or (client/find-port-by-nickname mcp)
            (throw (ex-info "Server not found" {:nickname mcp})))
    :else (let [servers (client/find-any-ports)]
            (cond
              (= 1 (count servers)) (:port (first servers))
              (empty? servers) (throw (ex-info "No servers running" {}))
              :else (throw (ex-info "Multiple servers running, specify --mcp or --port"
                                    {:servers (mapv :nickname servers)}))))))

;; =============================================================================
;; Subcommand Handlers
;; =============================================================================

(defn cmd-servers
  "List all running MCP servers."
  [{:keys [pprint]}]
  (let [servers (client/find-any-ports)]
    (if (empty? servers)
      (println "No servers running")
      (if pprint
        (pp/pprint servers)
        (do
         (println (format "Running servers: %d" (count servers)))
         (doseq [{:keys [nickname port data]} servers]
                (println (format "  %s: port %d (pid %s)"
                                 nickname port (:pid data)))))))))

(defn cmd-init
  "Show server information."
  [opts]
  (try
   (let [port (resolve-server opts)
         info (client/get-server-info! port)]
     (output-value info (:pprint opts)))
   (catch Exception e
          (binding [*out* *err*]
                   (println "Error:" (ex-message e)))
          (System/exit 1))))

(defn cmd-tools
  "List all available tools."
  [opts]
  (try
   (let [port (resolve-server opts)
         tools (client/list-tools! port)]
     (if (:pprint opts)
       (pp/pprint tools)
       (do
        (println (format "Tools: %d" (count tools)))
        (doseq [{:keys [name description]} tools]
               (println (format "  %s" name))
               (when description
                 (println (format "    %s" description)))))))
   (catch Exception e
          (binding [*out* *err*]
                   (println "Error:" (ex-message e)))
          (System/exit 1))))

(defn cmd-tool
  "Show specific tool schema."
  [{:keys [positional] :as opts}]
  (let [tool-name (first positional)]
    (when-not tool-name
      (println "Usage: bb mcp tool <name>")
      (System/exit 1))
    (try
     (let [port (resolve-server opts)
           tool (client/get-tool port tool-name)]
       (if tool
         (output-value tool (:pprint opts))
         (do
          (binding [*out* *err*]
                   (println "Tool not found:" tool-name))
          (System/exit 1))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-call
  "Call a tool with JSON arguments."
  [{:keys [positional pprint] :as opts}]
  (let [tool-name (first positional)
        args-json (second positional)]
    (when-not tool-name
      (println "Usage: bb mcp call <name> [json-args]")
      (println)
      (println "Examples:")
      (println "  bb mcp call echo.echo '{\"message\":\"hello\"}'")
      (println "  bb mcp call calculate.calculate '{\"expr\":\"(+ 1 2 3)\"}'")
      (System/exit 1))
    (try
     (let [port (resolve-server opts)
           arguments (if args-json
                       (json/parse-string args-json true)
                       {})
           response (client/call-tool! port tool-name arguments)
           result (client/extract-tool-result response)]
       (output-value result pprint))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e))
                     (when-let [data (ex-data e)]
                               (pp/pprint data)))
            (System/exit 1)))))

(defn cmd-help
  "Show help information."
  [_]
  (println "bb mcp - Generic MCP tool interface")
  (println)
  (println "Usage: bb mcp <subcommand> [args] [options]")
  (println)
  (println "Subcommands:")
  (println "  init              Show server info (initialize response)")
  (println "  tools             List all available tools")
  (println "  tool <name>       Show specific tool schema")
  (println "  call <name> <json> Call a tool with JSON arguments")
  (println "  servers           List running servers")
  (println "  help              Show this help")
  (println)
  (println "Options:")
  (println "  --mcp NAME        Server nickname (auto-detects if single server)")
  (println "  --port PORT       Server port number")
  (println "  --pprint          Pretty-print output")
  (println)
  (println "Examples:")
  (println "  # List running servers")
  (println "  bb mcp servers")
  (println)
  (println "  # Show server info")
  (println "  bb mcp init --mcp my-server")
  (println)
  (println "  # List tools")
  (println "  bb mcp tools")
  (println)
  (println "  # Show tool schema")
  (println "  bb mcp tool echo.echo")
  (println)
  (println "  # Call a tool")
  (println "  bb mcp call echo.echo '{\"message\":\"hello\"}'")
  (println "  bb mcp call calculate.calculate '{\"expr\":\"(+ 1 2 3)\"}'"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for mcp CLI."
  []
  (let [opts (parse-args *command-line-args*)]
    (case (:subcommand opts)
      "init" (cmd-init opts)
      "tools" (cmd-tools opts)
      "tool" (cmd-tool opts)
      "call" (cmd-call opts)
      "servers" (cmd-servers opts)
      ("help" "-h" "--help" nil) (cmd-help opts)
      (do
       (println "Unknown subcommand:" (:subcommand opts))
       (println "Run 'bb mcp help' for usage")
       (System/exit 1)))))

(-main)
