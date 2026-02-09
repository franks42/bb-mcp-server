#!/usr/bin/env bb
;; Statechart static validation CLI
;; Usage: bb statechart:validate <ns/var>
;;   e.g. bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-machine

(require '[clojure.string :as str])

;; Bootstrap module paths
((requiring-resolve 'bb-mcp-server.bootstrap/ensure-module-paths!))

(require '[statecharts.validate :as validate])

;; =============================================================================
;; ANSI Colors
;; =============================================================================

(def ^:private red "\u001b[31m")
(def ^:private yellow "\u001b[33m")
(def ^:private green "\u001b[32m")
(def ^:private blue "\u001b[34m")
(def ^:private bold "\u001b[1m")
(def ^:private reset "\u001b[0m")

(defn- colorize
  "Apply ANSI color to text."
  [color text]
  (str color text reset))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn- format-edge
  "Format a single edge for display."
  [{:keys [source target event]}]
  (let [src-str  (str source)
        evt-str  (str event)
        pad-src  (apply str (repeat (max 0 (- 12 (count src-str))) " "))
        pad-evt  (apply str (repeat (max 0 (- 12 (count evt-str))) " "))]
    (str "  " src-str pad-src " --" evt-str pad-evt "--> " target)))

(defn- print-graph
  "Print the transition graph."
  [{:keys [edges]}]
  (println)
  (println (colorize bold "Graph:"))
  (doseq [edge (sort-by (juxt :source :event) edges)]
         (println (format-edge edge))))

(defn- print-issues
  "Print a list of issues with appropriate colors."
  [label color issues]
  (when (seq issues)
    (println)
    (println (colorize color (str label ":")))
    (doseq [{:keys [type message]} issues]
           (println (str "  [" (name type) "] " message)))))

(defn- print-result
  "Print the full validation result."
  [var-name result]
  (let [{:keys [errors warnings info summary graph]} result
        pass? (zero? (:errors summary))]
    (println (str (colorize bold "Validating: ") var-name))
    (println (str "Machine: " (:id graph)
                  " (" (:states summary) " states, "
                  (:edges summary) " edges)"))

    (print-graph graph)

    (print-issues "Errors" red errors)
    (print-issues "Warnings" yellow warnings)
    (print-issues "Info" blue info)

    (println)
    (if pass?
      (println (colorize green
                         (str "Validation: PASS ("
                              (:errors summary) " errors, "
                              (:warnings summary) " warnings)")))
      (println (colorize red
                         (str "Validation: FAIL ("
                              (:errors summary) " error"
                              (when (not= 1 (:errors summary)) "s")
                              ", "
                              (:warnings summary) " warning"
                              (when (not= 1 (:warnings summary)) "s")
                              ")"))))

    (when-not pass?
      (System/exit 1))))

;; =============================================================================
;; Main
;; =============================================================================

(defn- usage
  "Print usage and exit."
  []
  (println "Usage: bb statechart:validate <ns/var>")
  (println)
  (println "Examples:")
  (println "  bb statechart:validate mcp-nrepl.state.local-nrepl-server/nrepl-server-machine")
  (System/exit 1))

(let [args *command-line-args*]
  (when (or (empty? args) (= "--help" (first args)))
    (usage))

  (let [qualified-name (first args)
        [ns-str var-str] (str/split qualified-name #"/")]
    (when-not (and ns-str var-str)
      (println "Error: expected fully qualified var name (ns/var)")
      (usage))

    (let [ns-sym  (symbol ns-str)
          var-sym (symbol var-str)]
      ;; Require the namespace
      (try
       (require ns-sym)
       (catch Exception e
              (println (colorize red (str "Error loading namespace " ns-sym ": " (.getMessage e))))
              (System/exit 1)))

      ;; Resolve and validate
      (let [the-var (ns-resolve (find-ns ns-sym) var-sym)]
        (when-not the-var
          (println (colorize red (str "Error: var " qualified-name " not found")))
          (System/exit 1))

        (let [machine @the-var
              result  (validate/validate machine)]
          (print-result qualified-name result))))))
