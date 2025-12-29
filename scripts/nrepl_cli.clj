#!/usr/bin/env bb

(ns nrepl-cli
  "nREPL CLI - Command-line interface for nREPL operations via MCP.

   Usage: bb scripts/nrepl_cli.clj <subcommand> [args] [options]

   Subcommands:
     connect <target>     Connect to an nREPL server (port, host:port, or .nrepl-port)
     disconnect [name]    Disconnect from nREPL server
     list                 List all connections
     status               Show active connection status
     eval <code>          Evaluate Clojure code
     load-file <path>     Load and evaluate a Clojure file
     help                 Show this help

   Options:
     --mcp NAME           MCP server nickname (default: bb-nrepl-system-1)
     --connection NAME    nREPL connection nickname (for eval/load-file)
     --nickname NAME      Nickname for new connection (for connect)
     --output MODE        Output mode: result (default), full, pipe
     --pprint             Pretty-print output
     --timeout MS         Timeout in milliseconds (default: 30000)"
  (:require [bb-mcp-server.mcp-client :as client]
            [cheshire.core :as json]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn parse-args
  "Parse command line arguments into structured options."
  [args]
  (loop [args args
         opts {:subcommand nil
               :positional []
               :mcp "bb-nrepl-system-1"
               :connection nil
               :nickname nil
               :output :result
               :pprint false
               :timeout 30000}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
          ;; Options
              (= arg "--mcp")
              (recur (rest rest-args) (assoc opts :mcp (first rest-args)))

              (= arg "--connection")
              (recur (rest rest-args) (assoc opts :connection (first rest-args)))

              (= arg "--nickname")
              (recur (rest rest-args) (assoc opts :nickname (first rest-args)))

              (= arg "--output")
              (recur (rest rest-args) (assoc opts :output (keyword (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              (= arg "--timeout")
              (recur (rest rest-args) (assoc opts :timeout (Integer/parseInt (first rest-args))))

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
    (prn value)))

(defn output-json
  "Output JSON result in human-readable format."
  [result pprint?]
  (if pprint?
    (pp/pprint result)
    (println (json/generate-string result {:pretty true}))))

(defn format-connection
  "Format a connection for display."
  [conn]
  (let [active (if (:is-active conn) " *ACTIVE*" "")
        nick (if (:nickname conn) (str " (" (:nickname conn) ")") "")]
    (format "  %s: %s%s%s"
            (:connection-id conn)
            (or (:connection conn) (name (:type conn)))
            nick
            active)))

;; =============================================================================
;; Subcommand Handlers
;; =============================================================================

(defn cmd-connect
  "Connect to an nREPL server."
  [{:keys [mcp positional nickname]}]
  (let [target (first positional)]
    (when-not target
      (println "Usage: bb nrepl connect <target> [--nickname NAME]")
      (println)
      (println "Target can be:")
      (println "  port          - Port number (localhost)")
      (println "  host:port     - Hostname and port")
      (println "  .nrepl-port   - Read port from .nrepl-port file")
      (System/exit 1))
    (try
     (let [args (cond-> {:op "connect"
                         :connection target}
                        nickname (assoc :nickname nickname))
           response (client/call-tool! mcp "nrepl.nrepl-connection" args)
           result (client/extract-tool-result response)]
       (if (= "success" (:status result))
         (do
          (println "Connected:" (:message result))
          (println "Connection ID:" (:connection-id result)))
         (do
          (println "Error:" (:error result))
          (System/exit 1))))
     (catch Exception e
            (println "Error:" (ex-message e))
            (System/exit 1)))))

(defn cmd-disconnect
  "Disconnect from an nREPL server."
  [{:keys [mcp positional]}]
  (try
   (let [connection (first positional)
         args (cond-> {:op "disconnect"}
                      connection (assoc :connection connection))
         response (client/call-tool! mcp "nrepl.nrepl-connection" args)
         result (client/extract-tool-result response)]
     (if (= "success" (:status result))
       (println "Disconnected:" (:message result))
       (do
        (println "Error:" (:error result))
        (System/exit 1))))
   (catch Exception e
          (println "Error:" (ex-message e))
          (System/exit 1))))

(defn cmd-list
  "List all nREPL connections."
  [{:keys [mcp pprint]}]
  (try
   (let [response (client/call-tool! mcp "nrepl.nrepl-connection" {:op "list"})
         result (client/extract-tool-result response)]
     (if (= "success" (:status result))
       (if pprint
         (pp/pprint result)
         (do
          (println (format "Connections: %d" (:connection-count result)))
          (when-let [active (:active-connection result)]
                    (println (format "Active: %s" active)))
          (doseq [conn (:connections result)]
                 (println (format-connection conn)))))
       (do
        (println "Error:" (:error result))
        (System/exit 1))))
   (catch Exception e
          (println "Error:" (ex-message e))
          (System/exit 1))))

(defn cmd-status
  "Show nREPL connection status."
  [{:keys [mcp pprint]}]
  (try
   (let [response (client/call-tool! mcp "nrepl.nrepl-connection" {:op "status"})
         result (client/extract-tool-result response)]
     (if (= "success" (:status result))
       (if pprint
         (pp/pprint result)
         (do
          (println (format "Status: %s" (name (or (:connection-status result) :unknown))))
          (when-let [hostname (:hostname result)]
                    (println (format "Server: %s:%s" hostname (:port result))))
          (when-let [count (:connection-count result)]
                    (println (format "Connections: %d" count)))))
       (do
        (println "Error:" (:error result))
        (System/exit 1))))
   (catch Exception e
          (println "Error:" (ex-message e))
          (System/exit 1))))

(defn cmd-eval
  "Evaluate Clojure code on an nREPL server."
  [{:keys [mcp connection positional output pprint timeout]}]
  (let [code (first positional)]
    (when-not code
      (println "Usage: bb nrepl eval <code> [--connection NAME] [--output MODE]")
      (System/exit 1))
    (try
     (let [args (cond-> {:code code :timeout timeout}
                        connection (assoc :connection connection))
           response (client/call-tool! mcp "nrepl.nrepl-eval" args)
           result (client/extract-tool-result response)]
       (case output
          ;; Result-only mode
         :result
         (if (= "success" (:status result))
           (let [value (:value-parsed result (:value result))]
             (output-value value pprint))
           (do
            (binding [*out* *err*]
                     (println "Error:" (:error result)))
            (System/exit 1)))

          ;; Full mode - return complete response
         :full
         (output-json result pprint)

          ;; Pipe mode - stdout→stdout, stderr→stderr
         :pipe
         (do
          (when-let [out (:out result)]
                    (print out))
          (when-let [err (:err result)]
                    (binding [*out* *err*]
                             (print err)))
          (if (= "success" (:status result))
            (let [value (:value-parsed result (:value result))]
              (output-value value pprint))
            (do
             (binding [*out* *err*]
                      (println "Error:" (:error result)))
             (System/exit 1))))

          ;; Unknown mode
         (do
          (binding [*out* *err*]
                   (println "Unknown output mode:" output))
          (System/exit 1))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-load-file
  "Load and evaluate a Clojure file on an nREPL server."
  [{:keys [mcp connection positional output pprint timeout]}]
  (let [path (first positional)]
    (when-not path
      (println "Usage: bb nrepl load-file <path> [--connection NAME] [--output MODE]")
      (System/exit 1))
    (try
     (let [args (cond-> {:file-path path :timeout timeout}
                        connection (assoc :connection connection))
           response (client/call-tool! mcp "nrepl.nrepl-load-file" args)
           result (client/extract-tool-result response)]
       (case output
          ;; Result-only mode
         :result
         (if (= "success" (:status result))
           (do
            (println (format "Loaded: %s" path))
            (when-let [value (:value result)]
                      (output-value value pprint)))
           (do
            (binding [*out* *err*]
                     (println "Error:" (:error result)))
            (System/exit 1)))

          ;; Full mode
         :full
         (output-json result pprint)

          ;; Pipe mode
         :pipe
         (do
          (when-let [out (:out result)]
                    (print out))
          (when-let [err (:err result)]
                    (binding [*out* *err*]
                             (print err)))
          (if (= "success" (:status result))
            (do
             (println (format "Loaded: %s" path))
             (when-let [value (:value result)]
                       (output-value value pprint)))
            (do
             (binding [*out* *err*]
                      (println "Error:" (:error result)))
             (System/exit 1))))

          ;; Unknown mode
         (do
          (binding [*out* *err*]
                   (println "Unknown output mode:" output))
          (System/exit 1))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-help
  "Show help information."
  [_]
  (println "bb nrepl - Command-line interface for nREPL operations via MCP")
  (println)
  (println "Usage: bb nrepl <subcommand> [args] [options]")
  (println)
  (println "Subcommands:")
  (println "  connect <target>     Connect to an nREPL server")
  (println "  disconnect [name]    Disconnect from nREPL server")
  (println "  list                 List all connections")
  (println "  status               Show active connection status")
  (println "  eval <code>          Evaluate Clojure code")
  (println "  load-file <path>     Load and evaluate a Clojure file")
  (println "  help                 Show this help")
  (println)
  (println "Options:")
  (println "  --mcp NAME           MCP server nickname (default: bb-nrepl-system-1)")
  (println "  --connection NAME    nREPL connection nickname (for eval/load-file)")
  (println "  --nickname NAME      Nickname for new connection (for connect)")
  (println "  --output MODE        Output mode: result (default), full, pipe")
  (println "  --pprint             Pretty-print output")
  (println "  --timeout MS         Timeout in milliseconds (default: 30000)")
  (println)
  (println "Examples:")
  (println "  # Start MCP server with nrepl module")
  (println "  bb server --http 0 --config bb-nrepl-system.edn --nickname nrepl-mcp")
  (println)
  (println "  # Connect to an nREPL server")
  (println "  bb nrepl connect 7888 --nickname app --mcp nrepl-mcp")
  (println)
  (println "  # Evaluate code")
  (println "  bb nrepl eval \"(+ 1 2 3)\" --mcp nrepl-mcp")
  (println)
  (println "  # Load a file")
  (println "  bb nrepl load-file src/my_app/core.clj --mcp nrepl-mcp")
  (println)
  (println "  # List connections")
  (println "  bb nrepl list --mcp nrepl-mcp"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for nrepl CLI."
  []
  (let [opts (parse-args *command-line-args*)]
    (case (:subcommand opts)
      "connect" (cmd-connect opts)
      "disconnect" (cmd-disconnect opts)
      "list" (cmd-list opts)
      "status" (cmd-status opts)
      "eval" (cmd-eval opts)
      "load-file" (cmd-load-file opts)
      ("help" "-h" "--help" nil) (cmd-help opts)
      (do
       (println "Unknown subcommand:" (:subcommand opts))
       (println "Run 'bb nrepl help' for usage")
       (System/exit 1)))))

(-main)
