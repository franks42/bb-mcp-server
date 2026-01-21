#!/usr/bin/env bb

(ns nrepl-direct-cli
    "Direct nREPL CLI - Communicate with nREPL servers via bencode protocol.

   This CLI bypasses MCP entirely, connecting directly to nREPL servers.

   Usage: bb nrepl-direct <subcommand> [args] [options]

   Subcommands:
     eval <code>              Evaluate Clojure code
     load-file <path>         Load file from server's filesystem
     load-local-file <path>   Read file locally, send as code (for browser)
     describe                 Show nREPL server capabilities
     help                     Show this help

   Options:
     --port PORT              nREPL port (required unless --nickname)
     --host HOST              nREPL host (default: localhost)
     --nickname NAME          Discover port from .ports/<NAME>.json
     --service SERVICE        Service name in port file (default: nrepl-server)
     --ns NAMESPACE           Namespace to eval in
     --timeout MS             Timeout in milliseconds (default: 30000)
     --output MODE            Output mode: result (default), full, pipe
     --pprint                 Pretty-print output

   Port Discovery:
     The CLI can auto-discover ports from .ports/<nickname>.json files.
     Use --service to specify which service port to use:
       nrepl-server  - Direct bb nREPL server (default)
       nrepl-proxy   - nREPL proxy for browser connections

   Examples:
     # Eval with explicit port
     bb nrepl-direct eval \"(+ 1 2 3)\" --port 7888

     # Eval using port discovery
     bb nrepl-direct eval \"(+ 1 2 3)\" --nickname scittle-dev

     # Load local file to browser via proxy
     bb nrepl-direct load-local-file src/browser/app.cljs \\
       --nickname scittle-dev --service nrepl-proxy

     # Describe server capabilities
     bb nrepl-direct describe --port 7888"
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
               :ns nil
               :timeout 30000
               :output :result
               :pprint false}]
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

              (= arg "--ns")
              (recur (rest rest-args) (assoc opts :ns (first rest-args)))

              (= arg "--timeout")
              (recur (rest rest-args) (assoc opts :timeout (Integer/parseInt (first rest-args))))

              (= arg "--output")
              (recur (rest rest-args) (assoc opts :output (keyword (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

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
  "Output result based on mode."
  [{:keys [output pprint]} result]
  (case output
    :result
    (if (= :success (:status result))
      (let [response (:response result)
            value (try-parse-edn (:value response))]
        (if value
          (format-value value pprint)
          (when (:out response)
            (print (:out response)))))
      (do
       (binding [*out* *err*]
                (println "Error:" (or (:error result)
                                      (format-error (:response result))
                                      "Unknown error")))
       (System/exit 1)))

    :full
    (if pprint
      (pp/pprint result)
      (println (json/generate-string result {:pretty true})))

    :pipe
    (let [response (:response result)]
      (when (:out response)
        (print (:out response)))
      (when (:err response)
        (binding [*out* *err*]
                 (print (:err response))))
      (if (= :success (:status result))
        (when-let [value (:value response)]
                  (format-value (try-parse-edn value) pprint))
        (do
         (binding [*out* *err*]
                  (println "Error:" (or (:error result) "Unknown error")))
         (System/exit 1))))

    ;; Default
    (do
     (binding [*out* *err*]
              (println "Unknown output mode:" output))
     (System/exit 1))))

;; =============================================================================
;; Subcommand Handlers
;; =============================================================================

(defn cmd-eval
  "Evaluate Clojure code."
  [opts]
  (let [arg (first (:positional opts))
        ;; Support stdin with "-"
        code (if (= "-" arg)
               (slurp *in*)
               arg)
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
                                :timeout-ms (:timeout opts))]
       (output-result opts result))
     (catch Exception e
            (binding [*out* *err*]
                     (println "Connection error:" (ex-message e)))
            (System/exit 1)))))

(defn cmd-load-file
  "Load file from server's filesystem."
  [opts]
  (let [file-path (first (:positional opts))
        port (resolve-port opts)]
    (when-not file-path
      (println "Usage: bb nrepl-direct load-file <path> --port PORT")
      (System/exit 1))
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
                                           :timeout-ms (:timeout opts))]
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

(defn cmd-help
  "Show help."
  [_]
  (println "bb nrepl-direct - Direct nREPL client (no MCP)")
  (println)
  (println "Usage: bb nrepl-direct <subcommand> [args] [options]")
  (println)
  (println "Subcommands:")
  (println "  eval <code>              Evaluate Clojure code")
  (println "  load-file <path>         Load file from server's filesystem")
  (println "  load-local-file <path>   Read file locally, send as code (for browser)")
  (println "  describe                 Show nREPL server capabilities")
  (println "  help                     Show this help")
  (println)
  (println "Options:")
  (println "  --port PORT              nREPL port (required unless --nickname)")
  (println "  --host HOST              nREPL host (default: localhost)")
  (println "  --nickname NAME          Discover port from .ports/<NAME>.json")
  (println "  --service SERVICE        Service name in port file:")
  (println "                             nrepl-server  - Direct bb nREPL (default)")
  (println "                             nrepl-proxy   - Proxy for browser")
  (println "  --ns NAMESPACE           Namespace to eval in")
  (println "  --timeout MS             Timeout in milliseconds (default: 30000)")
  (println "  --output MODE            Output mode: result, full, pipe")
  (println "  --pprint                 Pretty-print output")
  (println)
  (println "Examples:")
  (println "  # Eval with explicit port")
  (println "  bb nrepl-direct eval \"(+ 1 2 3)\" --port 7888")
  (println)
  (println "  # Eval using port discovery from scittle-dev server")
  (println "  bb nrepl-direct eval \"(+ 1 2 3)\" --nickname scittle-dev")
  (println)
  (println "  # Load local file to browser via proxy")
  (println "  bb nrepl-direct load-local-file src/browser/app.cljs \\")
  (println "    --nickname scittle-dev --service nrepl-proxy")
  (println)
  (println "  # Read code from stdin")
  (println "  echo \"(range 5)\" | bb nrepl-direct eval - --port 7888")
  (println)
  (println "  # Load file that exists on server filesystem")
  (println "  bb nrepl-direct load-file /path/to/file.clj --port 7888"))

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
      "describe" (cmd-describe opts)
      ("help" "-h" "--help" nil) (cmd-help opts)
      (do
       (println "Unknown subcommand:" (:subcommand opts))
       (println "Run 'bb nrepl-direct help' for usage")
       (System/exit 1)))))

(-main)
