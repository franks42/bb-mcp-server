# Multi-Agent Orchestration Test

**Status:** Design Phase
**Created:** 2025-11-25
**Goal:** Demonstrate multiple AI agents collaborating on a real task via message bus

---

## Use Case: Code Review Pipeline

**Scenario:** A user wants to implement a new Clojure function and have it reviewed by multiple domain experts before finalizing.

**Why this use case:**
- Realistic software development workflow
- Clear handoffs between agents
- Measurable quality (tests pass, code compiles)
- Demonstrates coordination, not just parallel execution

---

## The Agents

### 1. Coordinator Agent
- **Role:** Task decomposition, routing, synthesis
- **Provider:** `anthropic-http` (Claude Sonnet - good at orchestration)
- **Bus Topic:** `:coordinator`
- **Responsibilities:**
  - Receive initial task from user
  - Break down into subtasks
  - Route to appropriate experts
  - Collect responses
  - Synthesize final answer

### 2. Clojure Coder Agent
- **Role:** Write Clojure code
- **Provider:** `anthropic-http` (Claude Sonnet - excellent at Clojure)
- **Bus Topic:** `:clojure-coder`
- **Curriculum:** Clojure best practices, bb compatibility, project conventions
- **Responsibilities:**
  - Implement functions based on specs
  - Write idiomatic Clojure
  - Consider edge cases

### 3. Code Reviewer Agent
- **Role:** Review code quality
- **Provider:** `openai-http` (GPT-4 - different perspective)
- **Bus Topic:** `:code-reviewer`
- **Curriculum:** Code review checklist, security considerations
- **Responsibilities:**
  - Review for bugs, edge cases
  - Check idiomatic usage
  - Suggest improvements
  - Approve or request changes

### 4. Test Writer Agent
- **Role:** Write test cases
- **Provider:** `anthropic-http` (Claude Haiku - fast, good for focused tasks)
- **Bus Topic:** `:test-writer`
- **Curriculum:** clojure.test patterns, property-based testing
- **Responsibilities:**
  - Write comprehensive test cases
  - Cover edge cases
  - Ensure testability

---

## The Task

**User Request:**
> "Implement a `retry-with-backoff` function that retries a function call with exponential backoff. It should handle exceptions, support configurable max retries and initial delay, and return the successful result or throw after exhausting retries."

**Expected Output:**
1. Implementation in `src/utils/retry.clj`
2. Tests in `test/utils/retry_test.clj`
3. Code review approval

---

## Orchestration Flow

```
┌──────────┐     ┌─────────────┐     ┌───────────────┐
│   User   │────▶│ Coordinator │────▶│ Clojure Coder │
└──────────┘     └──────┬──────┘     └───────┬───────┘
                       │                     │
                       │    ┌────────────────┘
                       │    │ code
                       │    ▼
                       │  ┌─────────────┐
                       │  │   Reviewer  │
                       │  └──────┬──────┘
                       │         │ approved/changes
                       │    ┌────┘
                       │    ▼
                       │  ┌─────────────┐
                       └─▶│ Test Writer │
                          └──────┬──────┘
                                 │ tests
                                 ▼
                          ┌─────────────┐
                          │  Synthesis  │
                          └─────────────┘
```

---

## Detailed Steps

### Phase 1: Setup (Preparation)

#### 1.1 Create Expert Curricula

Create `.experts/` directory with domain knowledge:

```
.experts/
├── clojure-coder/
│   ├── manifest.edn
│   └── essential/
│       ├── clojure-style-guide.md
│       └── bb-compatibility.md
├── code-reviewer/
│   ├── manifest.edn
│   └── essential/
│       └── code-review-checklist.md
└── test-writer/
    ├── manifest.edn
    └── essential/
        └── testing-patterns.md
```

**manifest.edn for clojure-coder:**
```clojure
{:id :clojure-coder
 :name "Clojure Code Expert"
 :description "Expert in idiomatic Clojure development"
 :capabilities #{:code-generation :refactoring}
 :provider {:type :anthropic-http
            :model "claude-sonnet-4-5-20250929"}
 :system-prompt "You are an expert Clojure developer. Write clean, idiomatic code."}
```

#### 1.2 Start the Agents

```clojure
(require '[ai-orchestrator.core :as orch]
         '[message-bus.core :as bus]
         '[message-bus.teams :as teams])

;; Start 4 AI instances with different providers
(orch/start-instance! "coordinator"
  {:provider-type :anthropic-http
   :model "claude-sonnet-4-5-20250929"
   :api-key (System/getenv "ANTHROPIC_API_KEY")
   :max-tokens 4096})

(orch/start-instance! "clojure-coder"
  {:provider-type :anthropic-http
   :model "claude-sonnet-4-5-20250929"
   :api-key (System/getenv "ANTHROPIC_API_KEY")
   :max-tokens 4096})

(orch/start-instance! "code-reviewer"
  {:provider-type :openai-http
   :model "gpt-4o"
   :api-key (System/getenv "OPENAI_API_KEY")
   :max-tokens 2048})

(orch/start-instance! "test-writer"
  {:provider-type :anthropic-http
   :model "claude-3-5-haiku-20241022"
   :api-key (System/getenv "ANTHROPIC_API_KEY")
   :max-tokens 2048})
```

#### 1.3 Wire Agents to Message Bus

```clojure
(defn make-agent-handler
  "Create bus handler that forwards to AI and replies."
  [instance-name]
  (fn [{:keys [content request-id]}]
    (when content
      (let [response (orch/ask instance-name content)]
        (when request-id
          (bus/reply! request-id (:content response)))))))

;; Subscribe each agent to their topic
(def unsub-coder (bus/subscribe! :clojure-coder (make-agent-handler "clojure-coder")))
(def unsub-reviewer (bus/subscribe! :code-reviewer (make-agent-handler "code-reviewer")))
(def unsub-tester (bus/subscribe! :test-writer (make-agent-handler "test-writer")))
```

#### 1.4 Create Team (Optional)

```clojure
;; For broadcast coordination
(def code-team (teams/create-team! :code-pipeline
                                   #{:coordinator :clojure-coder :code-reviewer :test-writer}))
```

---

### Phase 2: Execution (The Orchestration)

#### Step 1: User Submits Task to Coordinator

```clojure
(def task "Implement a `retry-with-backoff` function that retries a function call
with exponential backoff. It should:
- Accept a function to retry
- Support configurable max-retries (default 3)
- Support configurable initial-delay-ms (default 100)
- Use exponential backoff (delay doubles each retry)
- Return successful result or throw after exhausting retries
- Log each retry attempt")

;; Coordinator analyzes and creates plan
(def plan (orch/ask "coordinator"
  (str "You are a software project coordinator. Analyze this task and create a plan
that assigns subtasks to these specialists:
- clojure-coder: writes the implementation
- code-reviewer: reviews for bugs and improvements
- test-writer: creates comprehensive tests

Task: " task "

Output a JSON plan with steps, assigned agent, and expected deliverable for each step.")))
```

#### Step 2: Coordinator Routes to Clojure Coder

```clojure
;; Ask clojure-coder via message bus (async, with timeout)
(def code-result
  (bus/ask :clojure-coder
    (str "Implement this Clojure function:\n\n" task "\n\n"
         "Requirements:\n"
         "- Use standard Clojure (Babashka compatible)\n"
         "- Include docstring\n"
         "- Use taoensso.trove for logging\n"
         "Output ONLY the code, no explanation.")
    :timeout-ms 60000))

;; Extract the code
(def implementation (:content code-result))
```

#### Step 3: Coordinator Routes to Reviewer

```clojure
;; Send implementation to reviewer
(def review-result
  (bus/ask :code-reviewer
    (str "Review this Clojure code for:\n"
         "1. Correctness - any bugs or edge cases missed?\n"
         "2. Idiomatic usage - is it good Clojure?\n"
         "3. Error handling - robust enough?\n"
         "4. Performance - any concerns?\n\n"
         "Code:\n```clojure\n" implementation "\n```\n\n"
         "Output: APPROVED if good, or list specific changes needed.")
    :timeout-ms 60000))

(def review-feedback (:content review-result))
```

#### Step 4: Handle Review Feedback (Loop if Needed)

```clojure
(defn review-loop [code max-iterations]
  (loop [current-code code
         iteration 0]
    (if (>= iteration max-iterations)
      {:status :max-iterations :code current-code}

      (let [review (bus/ask :code-reviewer
                     (str "Review this code:\n```clojure\n" current-code "\n```")
                     :timeout-ms 60000)]
        (if (clojure.string/includes? (:content review) "APPROVED")
          {:status :approved :code current-code}

          ;; Request changes from coder
          (let [revised (bus/ask :clojure-coder
                          (str "Revise this code based on feedback:\n\n"
                               "Current code:\n```clojure\n" current-code "\n```\n\n"
                               "Feedback:\n" (:content review))
                          :timeout-ms 60000)]
            (recur (:content revised) (inc iteration))))))))
```

#### Step 5: Generate Tests

```clojure
;; Once code is approved, generate tests
(def test-result
  (bus/ask :test-writer
    (str "Write comprehensive clojure.test tests for this function:\n\n"
         "```clojure\n" (:code approved-code) "\n```\n\n"
         "Include:\n"
         "- Happy path (success on first try)\n"
         "- Retry scenarios (success after N retries)\n"
         "- Exhaustion scenario (all retries fail)\n"
         "- Edge cases (zero retries, negative delay)\n"
         "Output ONLY the test code.")
    :timeout-ms 60000))
```

#### Step 6: Synthesize Final Output

```clojure
;; Coordinator synthesizes everything
(def final-output
  (orch/ask "coordinator"
    (str "Synthesize the following into a final deliverable:\n\n"
         "IMPLEMENTATION:\n```clojure\n" (:code approved-code) "\n```\n\n"
         "TESTS:\n```clojure\n" (:content test-result) "\n```\n\n"
         "Format as a summary with both code blocks.")))
```

---

### Phase 3: Cleanup

```clojure
;; Unsubscribe handlers
(unsub-coder)
(unsub-reviewer)
(unsub-tester)

;; Stop all instances
(orch/stop-instance! "coordinator")
(orch/stop-instance! "clojure-coder")
(orch/stop-instance! "code-reviewer")
(orch/stop-instance! "test-writer")
```

---

## Test Script Structure

```
scripts/
└── multi_agent_test.clj
    ├── setup-agents!      ;; Start instances, wire to bus
    ├── run-pipeline!      ;; Execute the orchestration
    ├── teardown-agents!   ;; Cleanup
    └── -main              ;; Entry point
```

---

## Success Criteria

1. **All agents start successfully** - 4 instances running
2. **Messages route correctly** - Bus delivers to right agents
3. **Code is generated** - Valid Clojure syntax
4. **Review feedback works** - At least one review cycle
5. **Tests are generated** - Valid clojure.test structure
6. **No timeouts** - All requests complete within limits
7. **Clean shutdown** - All instances stopped, no orphans

---

## Metrics to Capture

| Metric | Description |
|--------|-------------|
| `total-duration-ms` | End-to-end pipeline time |
| `agent-start-time-ms` | Time to start all 4 agents |
| `code-generation-ms` | Clojure coder response time |
| `review-iterations` | Number of review cycles needed |
| `review-time-ms` | Total time in review loop |
| `test-generation-ms` | Test writer response time |
| `tokens-used` | Approximate token count (if available) |
| `cost-estimate` | API cost estimate |

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| API rate limits | Use different providers (Anthropic + OpenAI) |
| Long response times | Set generous timeouts (60s), use Haiku for simple tasks |
| Review loop infinite | Max 3 iterations, then force proceed |
| Agent generates invalid code | Syntax check before proceeding |
| Message bus timeout | 60s default, retry once |
| Cost runaway | Token limits per agent, kill switch |

---

## Environment Requirements

```bash
# Required environment variables
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."

# Optional
export GEMINI_API_KEY="..."  # For alternative provider testing
```

---

## Execution Command

```bash
# Load API keys
source .cak.sh

# Run the multi-agent test
bb scripts/multi_agent_test.clj
```

---

## Expected Output

```
=== Multi-Agent Orchestration Test ===

[1/6] Starting agents...
  ✓ coordinator (anthropic-http, claude-sonnet-4-5-20250929)
  ✓ clojure-coder (anthropic-http, claude-sonnet-4-5-20250929)
  ✓ code-reviewer (openai-http, gpt-4o)
  ✓ test-writer (anthropic-http, claude-3-5-haiku-20241022)
  → 4 agents started in 1,234ms

[2/6] Wiring message bus...
  ✓ :clojure-coder subscribed
  ✓ :code-reviewer subscribed
  ✓ :test-writer subscribed

[3/6] Submitting task to coordinator...
  → Plan created with 4 steps

[4/6] Executing pipeline...
  → Routing to clojure-coder... (3,421ms)
  → Routing to code-reviewer... (2,891ms)
  → Review: CHANGES_REQUESTED
  → Routing revision to clojure-coder... (2,156ms)
  → Routing to code-reviewer... (1,987ms)
  → Review: APPROVED
  → Routing to test-writer... (1,543ms)

[5/6] Synthesizing output...
  → Final synthesis complete (1,234ms)

[6/6] Cleanup...
  ✓ All agents stopped

=== Results ===
Total duration: 14,466ms
Review iterations: 2
Generated files:
  - retry.clj (47 lines)
  - retry_test.clj (62 lines)

=== Generated Code ===
[code output here]
```

---

## Future Enhancements

1. **Parallel execution** - Run reviewer and test-writer concurrently
2. **Streaming** - Show agent responses as they arrive
3. **Checkpointing** - Save state between steps for recovery
4. **Cost tracking** - Real-time API cost monitoring
5. **Quality scoring** - Rate the generated code automatically
6. **Alternative flows** - Different agent combinations for different tasks

---

*Ready to implement! This will be the first real multi-agent orchestration test for bb-mcp-server.*
