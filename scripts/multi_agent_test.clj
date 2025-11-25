#!/usr/bin/env bb
;; Multi-Agent Orchestration Test
;;
;; This script demonstrates multiple AI agents collaborating on a task:
;; - clojure-coder (Claude subprocess) - writes code with file access
;; - code-reviewer (GPT-4o via OpenAI API) - isolated review, fresh eyes
;; - test-writer (Claude Haiku subprocess) - writes tests with file access
;;
;; Usage: source .cak.sh && bb scripts/multi_agent_test.clj

(ns multi-agent-test
  (:require [ai-orchestrator.core :as orch]
            [ai-orchestrator.protocol]
            [claude-subprocess.core]
            [anthropic-http.core]
            [message-bus.core :as bus]
            [clojure.string :as str]
            [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def claude-cmd "/Users/franksiebenlist/.claude/local/claude")

(def task-description
  "Implement a `retry-with-backoff` function that retries a function call
with exponential backoff. It should:
- Accept a function to retry (no args)
- Support configurable max-retries (default 3)
- Support configurable initial-delay-ms (default 100)
- Use exponential backoff (delay doubles each retry)
- Return successful result or throw after exhausting retries
- Log each retry attempt using taoensso.trove")

;; =============================================================================
;; Test Log - captures everything for documentation
;; =============================================================================

(def test-log (atom []))

(defn log-step! [step-name message & [data]]
  (let [entry {:timestamp (System/currentTimeMillis)
               :step step-name
               :message message
               :data data}]
    (swap! test-log conj entry)
    (println (str "\n[" step-name "] " message))
    (when data
      (println (str "  Data: " (pr-str (if (string? data)
                                         (if (> (count data) 200)
                                           (str (subs data 0 200) "...")
                                           data)
                                         data)))))))

(defn save-test-log! []
  (let [log-content (str "# Multi-Agent Orchestration Test Log\n\n"
                         "**Generated:** " (java.util.Date.) "\n\n"
                         "---\n\n"
                         (str/join "\n\n"
                           (map (fn [{:keys [step message data]}]
                                  (str "## " step "\n\n"
                                       message "\n\n"
                                       (when data
                                         (str "```\n"
                                              (if (string? data) data (pr-str data))
                                              "\n```\n"))))
                                @test-log)))]
    (spit "docs/design/multi-agent-test-log.md" log-content)
    (println "\n[LOG] Test log saved to docs/design/multi-agent-test-log.md")))

;; =============================================================================
;; Agent Message Handlers
;; =============================================================================

(defn make-agent-handler
  "Create a bus handler that forwards messages to an AI instance."
  [instance-name]
  (fn [{:keys [content request-id from]}]
    (log/log! {:level :debug
               :id ::agent-handler
               :msg "Agent received message"
               :data {:instance instance-name :from from}})
    (when (and content request-id)
      (try
        (let [response (orch/ask instance-name content)]
          (if (:error response)
            (bus/reply! request-id {:error (:message response)})
            (bus/reply! request-id (:content response))))
        (catch Exception e
          (bus/reply! request-id {:error (.getMessage e)}))))))

;; =============================================================================
;; Phase 1: Setup
;; =============================================================================

(defn start-agents! []
  (log-step! "SETUP" "Starting AI agents...")

  ;; Verify API keys
  (when-not (System/getenv "CLAUDE_API_KEY")
    (throw (ex-info "CLAUDE_API_KEY not set" {})))

  (let [results (atom {:started [] :failed []})]

    ;; Start clojure-coder
    (log-step! "SETUP" "Starting clojure-coder (Claude subprocess)...")
    (try
      (let [start-time (System/currentTimeMillis)]
        (orch/start-instance! "clojure-coder"
          {:provider-type :claude-subprocess
           :cmd claude-cmd
           :model "claude-sonnet-4-5-20250929"})
        (swap! results update :started conj
               {:name "clojure-coder"
                :provider :claude-subprocess
                :time-ms (- (System/currentTimeMillis) start-time)}))
      (catch Exception e
        (swap! results update :failed conj {:name "clojure-coder" :error (.getMessage e)})))

    ;; Start code-reviewer (Claude via Anthropic HTTP - isolated, no file access)
    (log-step! "SETUP" "Starting code-reviewer (Claude Sonnet via Anthropic HTTP API)...")
    (try
      (let [start-time (System/currentTimeMillis)]
        (orch/start-instance! "code-reviewer"
          {:provider-type :anthropic-http
           :api-key (System/getenv "CLAUDE_API_KEY")
           :model "claude-sonnet-4-5-20250929"
           :max-tokens 2048})
        (swap! results update :started conj
               {:name "code-reviewer"
                :provider :anthropic-http
                :time-ms (- (System/currentTimeMillis) start-time)}))
      (catch Exception e
        (swap! results update :failed conj {:name "code-reviewer" :error (.getMessage e)})))

    ;; Start test-writer (Claude Haiku)
    (log-step! "SETUP" "Starting test-writer (Claude Haiku subprocess)...")
    (try
      (let [start-time (System/currentTimeMillis)]
        (orch/start-instance! "test-writer"
          {:provider-type :claude-subprocess
           :cmd claude-cmd
           :model "claude-3-5-haiku-20241022"})
        (swap! results update :started conj
               {:name "test-writer"
                :provider :claude-subprocess
                :time-ms (- (System/currentTimeMillis) start-time)}))
      (catch Exception e
        (swap! results update :failed conj {:name "test-writer" :error (.getMessage e)})))

    (log-step! "SETUP" "Agent startup complete" @results)
    @results))

(defn wire-message-bus! []
  (log-step! "WIRING" "Connecting agents to message bus...")

  (let [unsub-coder (bus/subscribe! :clojure-coder (make-agent-handler "clojure-coder"))
        unsub-reviewer (bus/subscribe! :code-reviewer (make-agent-handler "code-reviewer"))
        unsub-tester (bus/subscribe! :test-writer (make-agent-handler "test-writer"))]

    (log-step! "WIRING" "Message bus topics active" (bus/list-topics))

    ;; Return unsubscribe functions for cleanup
    {:unsub-coder unsub-coder
     :unsub-reviewer unsub-reviewer
     :unsub-tester unsub-tester}))

;; =============================================================================
;; Phase 2: Execute Pipeline
;; =============================================================================

(defn request-implementation! []
  (log-step! "CODER" "Requesting implementation from clojure-coder...")
  (log-step! "CODER" "Task:" task-description)

  (let [prompt (str "You are an expert Clojure developer. Implement this function:\n\n"
                    task-description
                    "\n\nRequirements:\n"
                    "- Use standard Clojure (Babashka compatible)\n"
                    "- Include a proper docstring\n"
                    "- Use taoensso.trove for logging (log/log! with :level, :id, :msg, :data)\n"
                    "- Put it in namespace `bb-mcp-server.utils.retry`\n"
                    "\nOutput ONLY the complete source file, no explanations.")
        start-time (System/currentTimeMillis)
        result (bus/ask :clojure-coder prompt :timeout-ms 120000)
        duration (- (System/currentTimeMillis) start-time)]

    (log-step! "CODER" (str "Response received in " duration "ms"))

    (if (:success result)
      (do
        (log-step! "CODER" "Implementation received:" (:content result))
        {:success true :code (:content result) :duration-ms duration})
      (do
        (log-step! "CODER" "FAILED" result)
        {:success false :error result}))))

(defn inject-bug! [code]
  "Inject a subtle bug for the reviewer to find."
  (log-step! "BUG-INJECTION" "Injecting deliberate bug for reviewer to catch...")

  ;; Bug: Change retry count check from < to <= (off-by-one, retries one extra time)
  ;; Or: Change exponential backoff to linear (multiply becomes add)
  (let [buggy-code (str/replace code
                                #"\(\* delay 2\)"
                                "(+ delay initial-delay-ms)")]
    (if (= code buggy-code)
      ;; First replacement didn't work, try another bug
      (let [buggy-code-2 (str/replace code
                                      #"\(< attempt max-retries\)"
                                      "(<= attempt max-retries)")]
        (if (= code buggy-code-2)
          (do
            (log-step! "BUG-INJECTION" "WARNING: Could not inject bug, using original code")
            code)
          (do
            (log-step! "BUG-INJECTION" "Injected off-by-one bug in retry count check")
            buggy-code-2)))
      (do
        (log-step! "BUG-INJECTION" "Injected linear backoff bug (should be exponential)")
        buggy-code))))

(defn request-review! [code]
  (log-step! "REVIEWER" "Requesting code review...")
  (log-step! "REVIEWER" "Note: Reviewer is ISOLATED - only sees code passed in prompt")

  (let [prompt (str "You are an expert Clojure code reviewer. Review this code.\n\n"
                    "```clojure\n" code "\n```\n\n"
                    "## Review Criteria\n"
                    "Focus ONLY on these critical issues:\n"
                    "1. **BLOCKER** - Code won't work at all (syntax errors, missing requires)\n"
                    "2. **BUG** - Logic errors that cause incorrect behavior\n"
                    "3. **CRASH** - Unhandled exceptions that will crash\n\n"
                    "Do NOT report: style preferences, minor improvements, documentation suggestions.\n\n"
                    "## Response Format\n"
                    "If there are BLOCKER/BUG/CRASH issues:\n"
                    "```\n"
                    "CHANGES_REQUIRED\n"
                    "- BUG: <description of the bug>\n"
                    "```\n\n"
                    "If the code will work correctly (even if not perfect):\n"
                    "```\n"
                    "APPROVED\n"
                    "```\n\n"
                    "Remember: Working code is better than perfect code. Approve if it works.")
        start-time (System/currentTimeMillis)
        result (bus/ask :code-reviewer prompt :timeout-ms 60000)
        duration (- (System/currentTimeMillis) start-time)]

    (log-step! "REVIEWER" (str "Review received in " duration "ms"))

    (if (:success result)
      (let [review (:content result)
            review-upper (str/upper-case (str review))
            ;; Look for explicit CHANGES_REQUIRED or critical markers
            changes-required? (or (str/includes? review-upper "CHANGES_REQUIRED")
                                  (str/includes? review-upper "BLOCKER:")
                                  (str/includes? review-upper "- BUG:")
                                  (str/includes? review-upper "- CRASH:"))
            ;; APPROVED if says APPROVED and no critical issues
            approved? (and (str/includes? review-upper "APPROVED")
                           (not changes-required?))]
        (log-step! "REVIEWER" (str "Review verdict: " (if approved? "APPROVED" "CHANGES_REQUIRED")))
        (log-step! "REVIEWER" "Review content:" review)
        {:success true
         :approved approved?
         :review review
         :duration-ms duration})
      (do
        (log-step! "REVIEWER" "FAILED" result)
        {:success false :error result}))))

(defn request-revision! [code feedback]
  (log-step! "REVISION" "Requesting code revision from clojure-coder...")

  (let [prompt (str "Revise this Clojure code based on the reviewer's feedback.\n\n"
                    "Current code:\n```clojure\n" code "\n```\n\n"
                    "Reviewer feedback:\n" feedback "\n\n"
                    "Output ONLY the revised complete source file, no explanations.")
        start-time (System/currentTimeMillis)
        result (bus/ask :clojure-coder prompt :timeout-ms 120000)
        duration (- (System/currentTimeMillis) start-time)]

    (log-step! "REVISION" (str "Revision received in " duration "ms"))

    (if (:success result)
      (do
        (log-step! "REVISION" "Revised code:" (:content result))
        {:success true :code (:content result) :duration-ms duration})
      (do
        (log-step! "REVISION" "FAILED" result)
        {:success false :error result}))))

(defn review-loop! [initial-code max-iterations]
  (log-step! "REVIEW-LOOP" (str "Starting review loop (max " max-iterations " iterations)"))

  (loop [code initial-code
         iteration 1
         history []]
    (log-step! "REVIEW-LOOP" (str "Iteration " iteration "/" max-iterations))

    (if (> iteration max-iterations)
      {:status :max-iterations
       :code code
       :iterations iteration
       :history history}

      (let [review-result (request-review! code)]
        (if-not (:success review-result)
          {:status :review-failed
           :code code
           :iterations iteration
           :history (conj history review-result)}

          (if (:approved review-result)
            {:status :approved
             :code code
             :iterations iteration
             :history (conj history review-result)}

            ;; Request revision
            (let [revision-result (request-revision! code (:review review-result))]
              (if-not (:success revision-result)
                {:status :revision-failed
                 :code code
                 :iterations iteration
                 :history (conj history review-result revision-result)}

                (recur (:code revision-result)
                       (inc iteration)
                       (conj history review-result revision-result))))))))))

(defn request-tests! [code]
  (log-step! "TESTER" "Requesting tests from test-writer (Claude Haiku)...")

  (let [prompt (str "Write comprehensive clojure.test tests for this function:\n\n"
                    "```clojure\n" code "\n```\n\n"
                    "Include tests for:\n"
                    "- Happy path (success on first try)\n"
                    "- Retry success (fails N times then succeeds)\n"
                    "- Exhaustion (all retries fail, should throw)\n"
                    "- Edge cases (0 retries, custom delays)\n"
                    "- Backoff timing verification\n\n"
                    "Use namespace `bb-mcp-server.utils.retry-test`\n"
                    "Output ONLY the complete test file, no explanations.")
        start-time (System/currentTimeMillis)
        result (bus/ask :test-writer prompt :timeout-ms 120000)
        duration (- (System/currentTimeMillis) start-time)]

    (log-step! "TESTER" (str "Tests received in " duration "ms"))

    (if (:success result)
      (do
        (log-step! "TESTER" "Test code:" (:content result))
        {:success true :tests (:content result) :duration-ms duration})
      (do
        (log-step! "TESTER" "FAILED" result)
        {:success false :error result}))))

;; =============================================================================
;; Phase 3: Cleanup
;; =============================================================================

(defn cleanup! [unsubs]
  (log-step! "CLEANUP" "Stopping agents and cleaning up...")

  ;; Unsubscribe from bus
  (when-let [f (:unsub-coder unsubs)] (f))
  (when-let [f (:unsub-reviewer unsubs)] (f))
  (when-let [f (:unsub-tester unsubs)] (f))

  ;; Stop instances
  (orch/stop-instance! "clojure-coder")
  (orch/stop-instance! "code-reviewer")
  (orch/stop-instance! "test-writer")

  (log-step! "CLEANUP" "All agents stopped" {:remaining (count (orch/list-instances))}))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main []
  (println "\n" (str/join "" (repeat 60 "=")) "\n")
  (println "  MULTI-AGENT ORCHESTRATION TEST")
  (println "  Testing: clojure-coder + code-reviewer + test-writer")
  (println "\n" (str/join "" (repeat 60 "=")) "\n")

  (let [start-time (System/currentTimeMillis)
        unsubs (atom nil)]
    (try
      ;; Phase 1: Setup
      (start-agents!)
      (reset! unsubs (wire-message-bus!))

      ;; Wait for Claude subprocesses to initialize
      (log-step! "SETUP" "Waiting 12s for Claude subprocess initialization...")
      (Thread/sleep 12000)

      ;; Phase 2: Execute Pipeline
      (let [impl-result (request-implementation!)]
        (when (:success impl-result)
          (let [buggy-code (inject-bug! (:code impl-result))
                review-result (review-loop! buggy-code 3)]

            (log-step! "PIPELINE" "Review loop complete"
                       {:status (:status review-result)
                        :iterations (:iterations review-result)})

            (when (= :approved (:status review-result))
              (let [test-result (request-tests! (:code review-result))]
                (when (:success test-result)
                  (log-step! "SYNTHESIS" "Pipeline complete!")
                  (log-step! "SYNTHESIS" "Final implementation:" (:code review-result))
                  (log-step! "SYNTHESIS" "Tests:" (:tests test-result))))))))

      ;; Phase 3: Cleanup
      (cleanup! @unsubs)

      ;; Save log
      (save-test-log!)

      (let [total-time (- (System/currentTimeMillis) start-time)]
        (log-step! "COMPLETE" (str "Total execution time: " total-time "ms"))
        (println "\n" (str/join "" (repeat 60 "=")) "\n")
        (println "  TEST COMPLETE - See docs/design/multi-agent-test-log.md")
        (println "\n" (str/join "" (repeat 60 "=")) "\n"))

      (catch Exception e
        (log-step! "ERROR" (str "Test failed: " (.getMessage e)))
        (.printStackTrace e)
        (when @unsubs (cleanup! @unsubs))
        (save-test-log!)))))

;; Run if executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
