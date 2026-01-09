#!/usr/bin/env bb

(ns clojure-lsp-cli
    "Clojure LSP CLI - Command-line interface for clojure-lsp operations.

   Usage: bb clojure-lsp <subcommand> [args] [options]

   Lifecycle:
     start <project-root>   Start LSP server for project
     stop                   Stop the LSP server
     status                 Check LSP server status

   Navigation:
     definition <file> <line> <col>   Go to definition
     references <file> <line> <col>   Find all references
     hover <file> <line> <col>        Get hover info

   Analysis:
     diagnostics [file]               Get diagnostics (all or for file)
     symbols <file>                   List symbols in file
     call-hierarchy <file> <line> <col> [--outgoing]  Get call hierarchy

   Refactoring:
     completions <file> <line> <col>  Get completions
     code-actions <file> <line> <col> List available refactorings
     rename <file> <line> <col> <new-name>  Rename symbol

   Options:
     --port PORT       Server port (auto-detects if single server)
     --mcp NAME        Server nickname
     --executable PATH Path to clojure-lsp binary
     --json            Output as JSON instead of EDN
     --help            Show this help"
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
               :port nil
               :mcp nil
               :executable nil
               :json false
               :outgoing false}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (Integer/parseInt (first rest-args))))

              (= arg "--mcp")
              (recur (rest rest-args) (assoc opts :mcp (first rest-args)))

              (= arg "--executable")
              (recur (rest rest-args) (assoc opts :executable (first rest-args)))

              (= arg "--json")
              (recur rest-args (assoc opts :json true))

              (= arg "--outgoing")
              (recur rest-args (assoc opts :outgoing true))

              (or (= arg "--help") (= arg "-h"))
              (assoc opts :subcommand "help")

              (nil? (:subcommand opts))
              (recur rest-args (assoc opts :subcommand arg))

              :else
              (recur rest-args (update opts :positional conj arg)))))))

;; =============================================================================
;; Output Helpers
;; =============================================================================

(defn output-value
  "Output a value as EDN (default) or JSON."
  [value json?]
  (if json?
    (println (json/generate-string value {:pretty true}))
    (pp/pprint value)))

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
              (empty? servers) (throw (ex-info "No MCP servers running. Start one with: bb server --http" {}))
              :else (throw (ex-info "Multiple servers running, specify --mcp or --port"
                                    {:servers (mapv :nickname servers)}))))))

;; =============================================================================
;; Local-eval Wrapper
;; =============================================================================

(defn eval-on-server
  "Evaluate Clojure code on the MCP server via local-eval."
  [port code]
  (let [response (client/call-tool! port "local-eval.local-eval" {:code code})
        result (client/extract-tool-result response)]
    (if (= "success" (:status result))
      (let [raw (:result result)]
        ;; Parse the result if it's a string representation of EDN
        (if (string? raw)
          (try
           (read-string raw)
           (catch Exception _ raw))
          raw))
      (throw (ex-info "Evaluation failed" {:result result})))))

;; =============================================================================
;; Lifecycle Commands
;; =============================================================================

(defn cmd-start
  "Start clojure-lsp for a project."
  [{:keys [positional executable json] :as opts}]
  (when (empty? positional)
    (println "Usage: bb clojure-lsp start <project-root> [--executable path]")
    (System/exit 1))
  (let [port (resolve-server opts)
        project-root (first positional)
        ;; Build the code to call clj-init tool
        code (format "(bb-mcp-server.modules.clojure-lsp.client/start! {:project-root %s :executable-path %s})"
                     (pr-str project-root)
                     (pr-str (or executable "clojure-lsp")))
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-stop
  "Stop clojure-lsp server."
  [{:keys [json] :as opts}]
  (let [port (resolve-server opts)
        result (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.client/stop!)")]
    (output-value result json)))

(defn cmd-status
  "Get clojure-lsp server status."
  [{:keys [json] :as opts}]
  (let [port (resolve-server opts)
        running (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.client/running?)")
        initialized (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.client/initialized?)")]
    (output-value {:running running :initialized initialized} json)))

(defn cmd-debug-dump
  "Enable or disable LSP debug dump to a file.
   All raw LSP messages will be written to the file for debugging NUL byte issues."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        file-path (first positional)
        _ (eval-on-server port "(require '[bb-mcp-server.modules.clojure-lsp.jsonrpc :as jsonrpc])")
        code (if file-path
               (format "(jsonrpc/enable-debug-dump! %s)" (pr-str file-path))
               "(jsonrpc/enable-debug-dump! nil)")
        _ (eval-on-server port code)]
    (if file-path
      (output-value {:status "enabled" :file file-path} json)
      (output-value {:status "disabled"} json))))

;; =============================================================================
;; Navigation Commands
;; =============================================================================

(defn ensure-tools-loaded
  "Ensure tools.clj is loaded on the server."
  [port]
  (eval-on-server port
                  "(when-not (find-ns 'bb-mcp-server.modules.clojure-lsp.tools)
                     (require '[bb-mcp-server.modules.clojure-lsp.tools]))"))

(defn- parse-position-args
  "Parse file, line, column from positional args."
  [positional cmd-name]
  (when (< (count positional) 3)
    (println (format "Usage: bb clojure-lsp %s <file> <line> <column>" cmd-name))
    (System/exit 1))
  {:file (first positional)
   :line (Integer/parseInt (second positional))
   :column (Integer/parseInt (nth positional 2))})

(defn cmd-definition
  "Go to definition of symbol at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "definition")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/definition {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-references
  "Find all references to symbol at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "references")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/references {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-hover
  "Get hover information for symbol at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "hover")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/hover {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

;; =============================================================================
;; Analysis Commands
;; =============================================================================

(defn cmd-diagnostics
  "Get diagnostics for a file or all files."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        _ (ensure-tools-loaded port)
        code (if (seq positional)
               (format "(bb-mcp-server.modules.clojure-lsp.tools/diagnostics {:file %s})"
                       (pr-str (first positional)))
               "(bb-mcp-server.modules.clojure-lsp.tools/diagnostics)")
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-symbols
  "Get all symbols in a file."
  [{:keys [positional json] :as opts}]
  (when (empty? positional)
    (println "Usage: bb clojure-lsp symbols <file>")
    (System/exit 1))
  (let [port (resolve-server opts)
        file (first positional)
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/document-symbols {:file %s})"
                     (pr-str file))
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-call-hierarchy
  "Get call hierarchy for function at position."
  [{:keys [positional outgoing json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "call-hierarchy")
        direction (if outgoing :outgoing :incoming)
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/call-hierarchy {:file %s :line %d :column %d :direction %s})"
                     (pr-str file) line column direction)
        result (eval-on-server port code)]
    (output-value result json)))

;; =============================================================================
;; Refactoring Commands
;; =============================================================================

(defn cmd-completions
  "Get completion suggestions at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "completions")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/completions {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-code-actions
  "Get available code actions at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "code-actions")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/code-actions {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-rename
  "Rename symbol at position."
  [{:keys [positional json] :as opts}]
  (when (< (count positional) 4)
    (println "Usage: bb clojure-lsp rename <file> <line> <column> <new-name>")
    (System/exit 1))
  (let [port (resolve-server opts)
        file (first positional)
        line (Integer/parseInt (second positional))
        column (Integer/parseInt (nth positional 2))
        new-name (nth positional 3)
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/rename {:file %s :line %d :column %d :new-name %s})"
                     (pr-str file) line column (pr-str new-name))
        result (eval-on-server port code)]
    (output-value result json)))

;; =============================================================================
;; Workspace Commands
;; =============================================================================

(defn cmd-find-symbol
  "Search for symbols by name across the workspace."
  [{:keys [positional json] :as opts}]
  (when (empty? positional)
    (println "Usage: bb clojure-lsp find-symbol <query>")
    (System/exit 1))
  (let [port (resolve-server opts)
        query (first positional)
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/find-symbol {:query %s})"
                     (pr-str query))
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-format
  "Format a file."
  [{:keys [positional json] :as opts}]
  (when (empty? positional)
    (println "Usage: bb clojure-lsp format <file>")
    (System/exit 1))
  (let [port (resolve-server opts)
        file (first positional)
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/format-file {:file %s})"
                     (pr-str file))
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-implementations
  "Find implementations of protocol/interface at position."
  [{:keys [positional json] :as opts}]
  (let [port (resolve-server opts)
        {:keys [file line column]} (parse-position-args positional "implementations")
        _ (ensure-tools-loaded port)
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/implementations {:file %s :line %d :column %d})"
                     (pr-str file) line column)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-refactor
  "Execute a refactoring command."
  [{:keys [positional json] :as opts}]
  (when (< (count positional) 4)
    (println "Usage: bb clojure-lsp refactor <command> <file> <line> <col> [extra-arg]")
    (println "")
    (println "Commands: cycle-privacy, extract-function, introduce-let, move-to-let,")
    (println "          thread-first, thread-last, unwind-thread, clean-ns, etc.")
    (System/exit 1))
  (let [port (resolve-server opts)
        command (first positional)
        file (second positional)
        line (Integer/parseInt (nth positional 2))
        column (Integer/parseInt (nth positional 3))
        extra-arg (when (> (count positional) 4) (nth positional 4))
        _ (ensure-tools-loaded port)
        ;; Build arguments array: [uri line column optional-arg]
        args-code (if extra-arg
                    (format "[\"%s\" %d %d %s]" (str "file://" file) (dec line) (dec column) (pr-str extra-arg))
                    (format "[\"%s\" %d %d]" (str "file://" file) (dec line) (dec column)))
        code (format "(bb-mcp-server.modules.clojure-lsp.tools/execute-command {:command %s :arguments %s})"
                     (pr-str command) args-code)
        result (eval-on-server port code)]
    (output-value result json)))

(defn cmd-watch
  "Watch for file changes and update clojure-lsp index."
  [{:keys [positional] :as opts}]
  (let [port (resolve-server opts)
        path (first positional)
        _ (ensure-tools-loaded port)
        ;; Load watcher module
        _ (eval-on-server port "(require '[bb-mcp-server.modules.clojure-lsp.watcher :as watcher])")
        ;; Get watch path
        watch-path (or path
                       (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.client/get-project-root)"))
        ;; Start watcher (non-blocking on server)
        _ (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.watcher/start!)")]
    (println (str "Watching " watch-path " for Clojure file changes..."))
    (println "File changes will be sent to clojure-lsp to keep index fresh.")
    (println "Press Ctrl-C to stop.")
    (println "")
    ;; Add shutdown hook to stop watcher
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "\nStopping watcher...")
                                 (eval-on-server port "(bb-mcp-server.modules.clojure-lsp.watcher/stop!)"))))
    ;; Block until Ctrl-C
    @(promise)))

;; =============================================================================
;; Help
;; =============================================================================

(def ^{:doc "Help text displayed for --help and unknown commands."}
 help-text
     "bb clojure-lsp - Clojure LSP command-line interface

Usage: bb clojure-lsp <subcommand> [args] [options]

Lifecycle:
  start <project-root>   Start LSP server for project
  stop                   Stop the LSP server
  status                 Check LSP server status
  watch [path]           Watch files and update index (Ctrl-C to stop)
  debug-dump [file]      Enable debug dump to file (or disable if no file)

Navigation:
  definition <file> <line> <col>   Go to definition
  references <file> <line> <col>   Find all references
  hover <file> <line> <col>        Get hover info
  implementations <file> <line> <col>  Find protocol implementations

Search:
  find-symbol <query>    Search symbols by name across workspace

Analysis:
  diagnostics [file]               Get diagnostics (all or for file)
  symbols <file>                   List symbols in file
  call-hierarchy <file> <line> <col> [--outgoing]  Get call hierarchy

Refactoring:
  completions <file> <line> <col>  Get completions
  code-actions <file> <line> <col> List available refactorings
  rename <file> <line> <col> <new-name>  Rename symbol
  refactor <cmd> <file> <line> <col> [arg]  Execute refactoring
  format <file>          Format a file

Refactor commands: cycle-privacy, extract-function, introduce-let,
                   move-to-let, thread-first, thread-last, clean-ns, etc.

Options:
  --port PORT       Server port (auto-detects if single server)
  --mcp NAME        Server nickname
  --executable PATH Path to clojure-lsp binary (for start)
  --outgoing        Show outgoing calls (for call-hierarchy)
  --json            Output as JSON instead of EDN
  --help            Show this help

Examples:
  # Start clojure-lsp for current project
  bb clojure-lsp start .

  # Find all symbols named 'register'
  bb clojure-lsp find-symbol register

  # Get definition at line 10, column 5
  bb clojure-lsp definition src/myns/core.clj 10 5

  # Cycle privacy of a function
  bb clojure-lsp refactor cycle-privacy src/myns/core.clj 10 5

  # Format a file
  bb clojure-lsp format src/myns/core.clj

  # Watch for file changes (keeps index fresh)
  bb clojure-lsp watch")

(defn cmd-help
  "Show help."
  [_opts]
  (println help-text))

;; =============================================================================
;; Main Dispatcher
;; =============================================================================

(def ^{:doc "Map of subcommand names to handler functions."}
 commands
     {"start" cmd-start
      "stop" cmd-stop
      "status" cmd-status
      "debug-dump" cmd-debug-dump
      "watch" cmd-watch
      "definition" cmd-definition
      "references" cmd-references
      "hover" cmd-hover
      "implementations" cmd-implementations
      "find-symbol" cmd-find-symbol
      "diagnostics" cmd-diagnostics
      "symbols" cmd-symbols
      "call-hierarchy" cmd-call-hierarchy
      "completions" cmd-completions
      "code-actions" cmd-code-actions
      "rename" cmd-rename
      "refactor" cmd-refactor
      "format" cmd-format
      "help" cmd-help})

(defn -main
  "Entry point for the clojure-lsp CLI."
  [& args]
  (try
   (let [opts (parse-args args)
         subcommand (:subcommand opts)]
     (if-let [cmd-fn (get commands subcommand)]
             (cmd-fn opts)
             (do
              (when subcommand
                (println (format "Unknown command: %s\n" subcommand)))
              (cmd-help opts)
              (when subcommand (System/exit 1)))))
   (catch clojure.lang.ExceptionInfo e
          (println (format "Error: %s" (ex-message e)))
          (when-let [data (ex-data e)]
                    (println (json/generate-string data {:pretty true})))
          (System/exit 1))
   (catch Exception e
          (println (format "Error: %s" (.getMessage e)))
          (System/exit 1))))

;; Run main on load
(apply -main *command-line-args*)
