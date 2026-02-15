#!/usr/bin/env bb

(ns nrepl-direct-cli
    "Direct nREPL CLI - Communicate with nREPL servers via bencode protocol.

   This CLI bypasses MCP entirely, connecting directly to nREPL servers.

   IMPORTANT - load-file vs load-local-file:
   ==========================================
   load-file:       Sends PATH to server -> server reads file -> FAILS for browser!
   load-local-file: Reads file HERE -> sends CONTENT -> WORKS for browser!

   For browser/Scittle: ALWAYS use load-local-file
   For bb nREPL server: Either works (load-local-file is safer)

   Usage: bb nrepl-direct <subcommand> [args] -t <target>

   Subcommands:
     eval <code>              Evaluate Clojure code
     load-file <path>         Load file from SERVER's filesystem (NOT for browser!)
     load-local-file <path>   Read file HERE, send CONTENT (USE THIS for browser!)
     load-local-js-file <path> Import JS file as ES module in browser
     list                     List connected browsers
     describe                 Show nREPL server capabilities
     help                     Show this help

   Target (-t, --target):
     server           Eval on server (uses nrepl-server port)
     server/browser   Eval in browser (uses nrepl-proxy port)

   Examples:
     bb nrepl-direct list -t myserver
     bb nrepl-direct eval \"(+ 1 2 3)\" -t myserver/browser-1
     bb nrepl-direct load-local-file src/app.cljs -t myserver/browser-1
     bb nrepl-direct eval \"(+ 1 2 3)\" -t myserver"
    (:require [bb-mcp-server.nrepl-direct.client :as client]
              [cheshire.core :as json]
              [clojure.pprint :as pp]
              [clojure.edn :as edn]
              [clojure.string :as str]))

;; =============================================================================
;; Port Discovery
;; =============================================================================

(defn discover-port
  "Discover port from .ports/<nickname>.json file."
  [nickname service]
  (let [port-file (str ".ports/" nickname ".json")]
    (when (.exists (java.io.File. port-file))
      (let [data (json/parse-string (slurp port-file) true)
            ports (:ports data)]
        (get ports (keyword service))))))

;; =============================================================================
;; Argument Parsing
;; =============================================================================

(defn- parse-target
  "Parse --target shorthand into :nickname, :service, :connection.

   Format:
     server           -> {:nickname server :service nrepl-server}
     server/browser   -> {:nickname server :service nrepl-proxy :connection browser}"
  [target]
  (let [parts (str/split target #"/" 2)]
    (if (= 2 (count parts))
      {:nickname (first parts)
       :service "nrepl-proxy"
       :connection (second parts)}
      {:nickname (first parts)
       :service "nrepl-server"})))

(defn parse-args
  "Parse command line arguments."
  [args]
  (loop [args args
         opts {:subcommand nil
               :positional []
               :port nil
               :host "localhost"
               :nickname nil
               :service "nrepl-server"
               :connection nil
               :ns nil
               :timeout 30000
               :output :result
               :pprint false
               :stdout2stderr false}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (Integer/parseInt (first rest-args))))

              (= arg "--host")
              (recur (rest rest-args) (assoc opts :host (first rest-args)))

              (= arg "--nickname")
              (recur (rest rest-args) (assoc opts :nickname (first rest-args)))

              (= arg "--service")
              (recur (rest rest-args) (assoc opts :service (first rest-args)))

              (= arg "--connection")
              (recur (rest rest-args) (assoc opts :connection (first rest-args)))

              ;; --target / -t shorthand
              (or (= arg "--target") (= arg "-t"))
              (let [target-opts (parse-target (first rest-args))]
                (recur (rest rest-args) (merge opts target-opts)))

              (= arg "--ns")
              (recur (rest rest-args) (assoc opts :ns (first rest-args)))

              (= arg "--timeout")
              (recur (rest rest-args) (assoc opts :timeout (Integer/parseInt (first rest-args))))

              (= arg "--output")
              (recur (rest rest-args) (assoc opts :output (keyword (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              (= arg "--stdout2stderr")
              (recur rest-args (assoc opts :stdout2stderr true))

              ;; Help flags can appear anywhere
              (or (= arg "-h") (= arg "--help"))
              (assoc opts :subcommand "help")

              ;; First non-option is subcommand
              (nil? (:subcommand opts))
              (recur rest-args (assoc opts :subcommand arg))

              ;; Remaining args are positional
              :else
              (recur rest-args (update opts :positional conj arg)))))))

(defn resolve-port
  "Resolve port from options (explicit or discovered)."
  [{:keys [port nickname service]}]
  (or port
      (when nickname
        (discover-port nickname service))
      (do
       (binding [*out* *err*]
                (println "Error: --port or --nickname required"))
       (System/exit 1))))

;; =============================================================================
;; Output Helpers
;; =============================================================================

(defn try-parse-edn
  "Try to parse value as EDN, return original if fails."
  [s]
  (try
   (edn/read-string s)
   (catch Exception _
          s)))

(defn format-value
  "Format value for output."
  [value pprint?]
  (if pprint?
    (pp/pprint value)
    (prn value)))

(defn format-error
  "Format error for output."
  [response]
  (let [err (:err response)
        ex (:ex response)
        root-ex (:root-ex response)]
    (str/join "\n"
              (remove nil?
                      [(when err (str "stderr: " err))
                       (when ex (str "exception: " ex))
                       (when root-ex (str "root-exception: " root-ex))]))))

(defn output-result
  "Output result based on mode.
   Note: nrepl-direct client returns flat response maps with string status.

   Modes:
     :result - Default, shows parsed value or stdout
     :full   - Full JSON response
     :pipe   - stdout/stderr/value for piping
     :edn    - Clean EDN output: value on success (exit 0), full result on error (exit 1)
               Eval's stdout is suppressed unless --stdout2stderr is set"
  [{:keys [output pprint stdout2stderr]} result]
  (case output
    :result
    (if (= "success" (:status result))
      (let [value (try-parse-edn (:value result))]
        (if value
          (format-value value pprint)
          (when (:out result)
            (print (:out result)))))
      (do
       (binding [*out* *err*]
                (println "Error:" (or (:error result)
                                      (format-error result)
                                      "Unknown error")))
       (System/exit 1)))

    :full
    (if pprint
      (pp/pprint result)
      (println (json/generate-string result {:pretty true})))

    :pipe
    (do
     (when (:out result)
       (print (:out result)))
     (when (:err result)
       (binding [*out* *err*]
                (print (:err result))))
     (if (= "success" (:status result))
       (when-let [value (:value result)]
                 (format-value (try-parse-edn value) pprint))
       (do
        (binding [*out* *err*]
                 (println "Error:" (or (:error result) "Unknown error")))
        (System/exit 1))))

    :edn
    (let [has-exception? (or (:ex result) (:root-ex result))
          protocol-ok? (= "success" (:status result))]
      (if (and protocol-ok? (not has-exception?))
        ;; Success: output just the EDN value, optionally show stdout on stderr
        (do
         (when (and stdout2stderr (:out result))
           (binding [*out* *err*]
                    (print (:out result))
                    (flush)))
         (when (and stdout2stderr (:err result))
           (binding [*out* *err*]
                    (print (:err result))
                    (flush)))
          ;; Output the parsed value (or raw string if unparseable)
         (let [value (or (:value-parsed result)
                         (try-parse-edn (:value result))
                         (:value result))]
           (if pprint
             (pp/pprint value)
             (prn value))))
        ;; Error: output full result as EDN, exit 1
        (do
         (if pprint
           (pp/pprint result)
           (prn result))
         (System/exit 1))))

    ;; Default
    (do
     (binding [*out* *err*]
              (println "Unknown output mode:" output))
     (System/exit 1))))

;; =============================================================================
;; Subcommand Handlers
;; =============================================================================

(defn- unescape-bangs
  "Fix shell-mangled exclamation marks in Clojure code.
   Claude Code's Bash tool escapes ! to \\! in single-quoted strings.
   Since \\! is never valid Clojure syntax, this is safe to reverse."
  [code]
  (str/replace code "\\!" "!"))

(defn cmd-eval
  "Evaluate Clojure code."
  [opts]
  (let [arg (first (:positional opts))
        ;; Support stdin with "-"
        code (-> (if (= "-" arg)
                   (slurp *in*)
                   arg)
                 unescape-bangs)
        port (resolve-port opts)]
    (when-not code
      (println "Usage: bb nrepl-direct eval <code> --port PORT")
      (println "       bb nrepl-direct eval - --port PORT  # Read from stdin")
      (System/exit 1))
    (try
     (let [result (client/eval! code
                                :host (:host opts)
                                :port port
                                :ns (:ns opts)
                                :timeout-ms (:timeout opts)
                                :connection (:connection opts))]
       (output-result opts result))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Connection error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-load-file
  "Load file from server's filesystem."
  [opts]
  (let [file-path (first (:positional opts))
        service (:service opts)
        port (resolve-port opts)]
    (when-not file-path
      (println "Usage: bb nrepl-direct load-file <path> --port PORT")
      (System/exit 1))

    ;; IMPORTANT: Warn when using load-file with browser proxy
    ;; This is a common mistake - browsers can't access local filesystem!
    (when (= service "nrepl-proxy")
      (binding [*out* *err*]
               (println "")
               (println "WARNING: 'load-file' sends the FILE PATH to the server.")
               (println "         Browser/Scittle environments CANNOT access local files!")
               (println "")
               (println "         Did you mean 'load-local-file' instead?")
               (println "         load-local-file reads the file HERE and sends the CONTENT.")
               (println "")
               (println "         Suggested command:")
               (println (str "         bb nrepl-direct load-local-file " file-path
                             " --nickname " (:nickname opts) " --service " service))
               (println "")
               (println "         Continuing anyway (will likely fail)...")
               (println "")))

    (try
     (let [result (client/load-file! file-path
                                     :host (:host opts)
                                     :port port
                                     :timeout-ms (:timeout opts))]
       (output-result opts result))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-load-local-file
  "Read file locally and send as code."
  [opts]
  (let [file-path (first (:positional opts))
        port (resolve-port opts)]
    (when-not file-path
      (println "Usage: bb nrepl-direct load-local-file <path> --port PORT")
      (System/exit 1))
    (when-not (.exists (java.io.File. file-path))
      (binding [*out* *err*]
               (println "Error: File not found:" file-path))
      (System/exit 1))
    (try
     (let [result (client/load-local-file! file-path
                                           :host (:host opts)
                                           :port port
                                           :ns (:ns opts)
                                           :timeout-ms (:timeout opts)
                                           :connection (:connection opts))]
       (output-result opts result))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-load-local-js-file
  "Read JS file locally and import as ES module in browser."
  [opts]
  (let [file-path (first (:positional opts))
        port (resolve-port opts)]
    (when-not file-path
      (println "Usage: bb nrepl-direct load-local-js-file <path> -t server/browser")
      (System/exit 1))
    (when-not (.exists (java.io.File. file-path))
      (binding [*out* *err*]
               (println "Error: File not found:" file-path))
      (System/exit 1))
    (when-not (:connection opts)
      (binding [*out* *err*]
               (println "Error: load-local-js-file requires a browser target")
               (println "  Example: bb nrepl-direct load-local-js-file app.js -t myserver/browser-1"))
      (System/exit 1))
    (try
     (let [result (client/load-local-js-file! file-path
                                              :host (:host opts)
                                              :port port
                                              :timeout-ms (:timeout opts)
                                              :connection (:connection opts))]
       (output-result opts result))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-describe
  "Show nREPL server capabilities."
  [opts]
  (let [port (resolve-port opts)]
    (try
     (client/with-connection {:host (:host opts) :port port}
                             (fn [conn]
                               (let [result (client/describe conn :timeout-ms (:timeout opts))]
                                 (output-result (assoc opts :output :full) result))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-list
  "List connected browsers via nrepl-proxy.
   Evals (browser/list) which the proxy intercepts and handles."
  [opts]
  ;; Force nrepl-proxy service for list command
  (let [opts (assoc opts :service "nrepl-proxy")
        port (resolve-port opts)]
    (try
     (let [result (client/eval! "(browser/list)"
                                :host (:host opts)
                                :port port
                                :timeout-ms (:timeout opts))]
       (if (= "success" (:status result))
         (let [browsers (try-parse-edn (:value result))]
           (if (empty? browsers)
             (println "No browsers connected")
             (do
              (println (format "%-15s %-12s %s" "NICKNAME" "STATUS" "CONNECTED"))
              (println (str/join "" (repeat 50 "-")))
              (doseq [b browsers]
                     (println (format "%-15s %-12s %s"
                                      (or (:nickname b) "-")
                                      (name (or (:status b) :unknown))
                                      (or (:connected-at b) "-")))))))
         (do
          (binding [*out* *err*]
                   (println "Error:" (or (:err result) (:error result) "Unknown error")))
          (System/exit 1))))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-help
  "Show help."
  [_]
  (println "bb nrepl-direct - Direct nREPL client (no MCP)")
  (println)
  (println "Usage: bb nrepl-direct <subcommand> [args] [options]")
  (println)
  (println "Subcommands:")
  (println "  eval <code>              Evaluate Clojure code")
  (println "  load-file <path>         Load file from SERVER's filesystem (NOT for browser!)")
  (println "  load-local-file <path>   Read file HERE, send CONTENT (USE THIS for browser!)")
  (println "  load-local-js-file <path> Import JS file as ES module in browser")
  (println "  list                     List connected browsers (via nrepl-proxy)")
  (println "  describe                 Show nREPL server capabilities")
  (println "  help                     Show this help")
  (println)
  (println "IMPORTANT - load-file vs load-local-file:")
  (println "  load-file:       Sends PATH to server -> server reads file -> FAILS for browser!")
  (println "  load-local-file: Reads file HERE -> sends CONTENT -> WORKS for browser!")
  (println)
  (println "  For browser/Scittle: ALWAYS use load-local-file")
  (println "  For bb nREPL server: Either works (load-local-file is safer)")
  (println)
  (println "  load-local-js-file: Reads JS HERE -> imports as ES module in browser")
  (println "                      Uses Blob URL + js/import (Scittle 0.8.31+)")
  (println)
  (println "Target (recommended):")
  (println "  -t, --target TARGET      Server/browser target (shorthand)")
  (println "                             server          - eval on server (nrepl-server)")
  (println "                             server/browser  - eval in browser (nrepl-proxy)")
  (println)
  (println "Options:")
  (println "  --port PORT              nREPL port (use instead of --target for explicit port)")
  (println "  --host HOST              nREPL host (default: localhost)")
  (println "  --nickname NAME          Server name (expanded form of --target)")
  (println "  --service SERVICE        nrepl-server | nrepl-proxy (expanded form)")
  (println "  --connection BROWSER     Browser connection (expanded form)")
  (println "  --ns NAMESPACE           Namespace to eval in")
  (println "  --timeout MS             Timeout in milliseconds (default: 30000)")
  (println "  --output MODE            result | full | pipe | edn")
  (println "  --pprint                 Pretty-print output")
  (println "  --stdout2stderr          With --output edn: show eval's stdout on stderr")
  (println)
  (println "Examples:")
  (println "  # List connected browsers")
  (println "  bb nrepl-direct list -t myserver")
  (println)
  (println "  # Eval in browser")
  (println "  bb nrepl-direct eval \"(+ 1 2 3)\" -t myserver/browser-1")
  (println)
  (println "  # Load ClojureScript file to browser")
  (println "  bb nrepl-direct load-local-file src/app.cljs -t myserver/browser-1")
  (println)
  (println "  # Import JS file as ES module in browser")
  (println "  bb nrepl-direct load-local-js-file lib/utils.js -t myserver/browser-1")
  (println)
  (println "  # Eval on server (no browser)")
  (println "  bb nrepl-direct eval \"(+ 1 2 3)\" -t myserver")
  (println)
  (println "  # EDN output for scripting")
  (println "  bb nrepl-direct eval \"(range 5)\" -t myserver --output edn")
  (println)
  (println "  # Read code from stdin")
  (println "  echo \"(range 5)\" | bb nrepl-direct eval - -t myserver")
  (println)
  (println "  # Explicit port (bypasses port discovery)")
  (println "  bb nrepl-direct eval \"(+ 1 2 3)\" --port 7888"))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main
  "Main entry point for nrepl-direct CLI."
  []
  (let [opts (parse-args *command-line-args*)]
    (case (:subcommand opts)
      "eval" (cmd-eval opts)
      "load-file" (cmd-load-file opts)
      "load-local-file" (cmd-load-local-file opts)
      "load-local-js-file" (cmd-load-local-js-file opts)
      "list" (cmd-list opts)
      "describe" (cmd-describe opts)
      ("help" "-h" "--help" nil) (cmd-help opts)
      (do
       (println "Unknown subcommand:" (:subcommand opts))
       (println "Run 'bb nrepl-direct help' for usage")
       (System/exit 1)))))

(-main)
