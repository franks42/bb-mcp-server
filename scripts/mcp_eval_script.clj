#!/usr/bin/env bb

;; MCP Evaluation Script
;; Usage: bb scripts/mcp_eval_script.clj "[code]" [options]
;;
;; Options:
;;   --nickname NAME   Server nickname to connect to
;;   --port PORT       Server port to connect to
;;   --output MODE     Output mode: result (default), full, pipe
;;   --pprint          Pretty-print the output

(require '[bb-mcp-server.mcp-client :as client]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(defn parse-args
  "Parse command line arguments into options map."
  [args]
  (loop [args args
         opts {:code nil :nickname nil :port nil :output :result :pprint false}]
        (if (empty? args)
          opts
          (let [arg (first args)
                rest-args (rest args)]
            (cond
              (= arg "--nickname")
              (recur (rest rest-args) (assoc opts :nickname (first rest-args)))

              (= arg "--port")
              (recur (rest rest-args) (assoc opts :port (Integer/parseInt (first rest-args))))

              (= arg "--output")
              (recur (rest rest-args) (assoc opts :output (keyword (first rest-args))))

              (= arg "--pprint")
              (recur rest-args (assoc opts :pprint true))

              :else
              (recur rest-args (assoc opts :code arg)))))))

(defn output-value
  "Output a value, optionally pretty-printed."
  [value pprint?]
  (if pprint?
    (pp/pprint value)
    (prn value)))

(defn -main
  "Main entry point for mcp-eval script."
  []
  (let [args (parse-args *command-line-args*)
        code (some-> (:code args)
                     ;; Fix Claude Code Bash tool escaping ! to \! in single-quoted strings
                     (str/replace "\\!" "!"))
        nickname (:nickname args)
        port (:port args)
        output-mode (:output args)
        pprint? (:pprint args)]

    (when-not code
      (println "Usage: bb scripts/mcp_eval_script.clj \"[code]\" [options]")
      (println)
      (println "Options:")
      (println "  --nickname NAME   Server nickname to connect to")
      (println "  --port PORT       Server port to connect to")
      (println "  --output MODE     Output mode: result (default), full, pipe")
      (println "  --pprint          Pretty-print the output")
      (System/exit 1))

    (let [target (cond
                   port port
                   nickname nickname
                   :else "bb-bootstrap-system-1")]
      (try
       (case output-mode
          ;; Result-only mode: just EDN result, error to stderr
         :result
         (let [result (client/eval-code! target code {:output-format :edn})]
           (output-value result pprint?))

          ;; Full mode: return structured map with status, result, stdout, stderr
         :full
         (let [result (client/eval-code-full! target code {:output-format :edn})]
           (output-value result pprint?))

          ;; Pipe mode: stdout→stdout, stderr→stderr, result to stdout
         :pipe
         (let [result (client/eval-code-full! target code {:output-format :edn})]
           (when (seq (:stdout result))
             (print (:stdout result)))
           (when (seq (:stderr result))
             (binding [*out* *err*]
                      (print (:stderr result))))
           (if (= :ok (:status result))
             (output-value (:result result) pprint?)
             (do
              (binding [*out* *err*]
                       (println "Error:" (:error result)))
              (System/exit 1))))

          ;; Unknown mode
         (do
          (binding [*out* *err*]
                   (println "Unknown output mode:" output-mode))
          (System/exit 1)))
       (catch Exception e
              (binding [*out* *err*]
                       (println "Error:" (ex-message e)))
              (System/exit 1))))))

(-main)
