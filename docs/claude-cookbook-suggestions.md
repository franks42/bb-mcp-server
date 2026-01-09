# Claude Cookbook Patterns for bb-mcp-server

> **Purpose:** Capture learnings from Anthropic's Claude Cookbook that could improve AI assistant productivity with this project.

**Last Updated:** 2026-01-08

---

## Patterns to Adopt

### 1. Programmatic Tool Calling (PTC)

**Source:** [Anthropic Cookbook - PTC](https://platform.claude.com/cookbook/agentic-ptc)

**Current State:** We make separate tool calls for each operation:
```
1. Call nrepl-connection list → get browser-1
2. Call nrepl-eval (+ 1 2) → get 3
3. Call nrepl-eval (load-file ...) → get result
```

**PTC Pattern:** Write code that chains operations:
```clojure
;; Single eval that does everything
(let [conn (first (filter #(= "browser" (:type %)) (list-connections)))
      test-result (eval-in-browser conn "(+ 1 2)")]
  (if (= "3" test-result)
    (eval-in-browser conn (slurp "path/to/file.cljs"))
    (throw (ex-info "nREPL test failed" {}))))
```

**Benefits:**
- Fewer round-trips = lower latency
- Logic stays in one place
- Easier to debug

**Action:** Document patterns for chaining nREPL operations in single evals.

---

### 2. Evaluator-Optimizer Pattern

**Source:** [Anthropic Cookbook - Evaluator Optimizer](https://platform.claude.com/cookbook/patterns-agents-evaluator-optimizer)

**Pattern:** Use one LLM for generation, another for evaluation, in a feedback loop.

**For bb-mcp-server:**
- After generating code, eval it in browser to verify
- After making changes, run tests to validate
- Use test output to guide fixes

**Current Example (already doing this):**
```javascript
// test_cm6_update.mjs does this pattern:
// 1. Generate test component
// 2. Eval in browser
// 3. Verify result
// 4. Report pass/fail
```

**Action:** Formalize this as a standard pattern for all Scittle development.

---

### 3. Context Compaction Checkpoints

**Source:** [Anthropic Cookbook - Automatic Context Compaction](https://platform.claude.com/cookbook/automatic-context-compaction)

**Problem:** After auto-compaction, Claude loses critical context.

**Pattern:** Write explicit checkpoints before complex operations:

```markdown
## Checkpoint (before multi-file refactor)
- Working on: CM6 update fix
- Files modified: scittle_cm6.cljs, config_test.clj
- Tests passing: Yes
- Next step: Add Playwright test
```

**Current:** We have context.md but don't checkpoint systematically.

**Action:** Add checkpoint discipline to CLAUDE.md directives.

---

### 4. Parallel Tool Evaluation

**Source:** [Anthropic Cookbook - Tool Evaluation](https://platform.claude.com/cookbook/tool-evaluation)

**Pattern:** Run independent tests in parallel rather than sequentially.

**For bb-mcp-server:**
```javascript
// Instead of sequential:
await testScenario1();
await testScenario2();
await testScenario3();

// Parallel:
await Promise.all([
  testScenario1(),
  testScenario2(),
  testScenario3()
]);
```

**Action:** Update test patterns to use parallel execution where possible.

---

### 5. Subagent Delegation

**Source:** [Anthropic Cookbook - Chief of Staff Agent](https://platform.claude.com/cookbook/chief-of-staff-agent)

**Pattern:** Delegate specialized tasks to purpose-built subagents.

**Already Using:**
- `Task tool with subagent_type=Explore` for codebase exploration
- Background tasks for long-running operations

**Could Add:**
- "Verify" subagent for running lint/format/test
- "Document" subagent for updating docs
- "Debug" subagent for investigating failures

---

### 6. Extended Thinking for Complex Decisions

**Source:** [Anthropic Cookbook - Extended Thinking](https://platform.claude.com/cookbook/extended-thinking-tips)

**Pattern:** Use extended thinking for:
- Architecture decisions
- Multi-file refactors
- Debugging complex issues

**For bb-mcp-server:** When facing complex decisions (e.g., "should we use ratom or plain atom?"), explicitly reason through tradeoffs before implementing.

---

## Directives to Add to CLAUDE.md

```markdown
## Checkpoint Before Complex Work
Before starting multi-file changes or complex operations, update context.md
with current state so compaction doesn't lose critical context.

## Chain Operations When Possible
When multiple nREPL operations are needed, prefer writing a single Clojure
expression that chains them rather than separate tool calls.

## Verify After Generate
After implementing a feature, immediately run verification:
1. Lint (clj-kondo)
2. Format (cljfmt)
3. Tests (bb test:modules)
4. E2E if applicable (Playwright)

## Use Parallel Calls
When calling multiple independent tools, make them in a single response
to maximize parallelism and reduce latency.
```

---

## Interface Comparison: CLI vs MCP vs Hooks

> **Key Insight:** The bb-mcp-server provides MCP tools, but the most productive way
> for AI assistants to use them is often via CLI wrappers. This suggests the value
> isn't the protocol itself, but the capability exposure and how it's consumed.

### Command Line (bb tasks)
**Strengths:**
- Immediate text feedback - see raw output, grep it, pipe it
- Familiar patterns (pipes, &&, grep) deeply embedded in training
- Easy to chain and compose
- Error messages are plain text - easy to read and act on
- Can run in background
- "Exploratory" - `bb mcp tools` lets me discover what's available
- Lower cognitive overhead - no schema to remember

**Weaknesses:**
- Less structured output
- Requires parsing text for programmatic use
- No type safety

**When AI Prefers CLI:**
```bash
# This feels natural:
bb mcp call nrepl.nrepl-eval '{"code":"(+ 1 2)"}' --mcp code-browser-dev

# Easy to chain:
bb server:stop code-browser-dev && bb server --http --config ... --nickname code-browser-dev

# Easy to verify:
curl -s http://localhost:3000/health | grep ok
```

### MCP Tools (native)
**Strengths:**
- Structured JSON responses
- Type-safe schemas
- Designed for programmatic use
- Better for complex operations
- Consistent interface across tools

**Weaknesses:**
- More verbose invocation
- Round-trip overhead per call
- Schema learning curve
- Harder to "explore" interactively
- Each call is isolated (can't easily chain)

**When MCP Shines:**
- Building automated pipelines
- Integration with other MCP clients
- When type safety matters
- Reliable, repeatable operations

### Hooks
**Strengths:**
- Automatic triggers on events
- No explicit invocation needed
- Good for side effects (logging, validation)
- Guardrails that "just work"

**Weaknesses:**
- Less control over timing
- Harder to debug
- Fixed trigger points

### The Irony

The bb-mcp-server project provides MCP tools, but the most productive access pattern
is often the CLI wrappers (`bb mcp`, `bb nrepl`, `bb server`). This reveals:

1. **The value is capability exposure, not the protocol**
2. **AI assistants have strong CLI intuitions from training**
3. **Text interfaces are more "explorable" than structured APIs**
4. **CLI wrappers are an "adapter layer" to preferred consumption format**

### Recommendation: Hybrid Approach

1. **CLI for exploration** - quick tests, debugging, ad-hoc operations
2. **MCP for automation** - structured workflows, reliable pipelines, other clients
3. **Hooks for guardrails** - verification, cleanup, notifications
4. **Document the CLI "happy path"** for each workflow

### Best Practices for AI Productivity

```markdown
## For Each Capability, Provide:
1. MCP tool (for programmatic access)
2. CLI wrapper (for AI/human interactive use)
3. Example commands in documentation
4. Clear error messages in plain text
```

The CLI wrappers aren't a workaround - they're a **first-class interface** for AI consumption.

---

## Language Choice: Babashka/Clojure vs Python

> **User Question:** "Is bb the right tool? I like Clojure more than Python, but you've
> been trained on Python much more than Clojure, right?"

### Honest Assessment

**Yes, I have significantly more Python training data.** This means:
- Fewer syntax errors in Python
- More idioms readily available
- Larger library ecosystem in my training
- More Stack Overflow answers, blog posts, examples

**But that doesn't make Python the right choice for this project.**

### Why Babashka/Clojure Works Well Here

1. **Fast Startup**
   - bb starts in ~10ms vs Python's ~100ms+
   - Critical for CLI tools that run frequently
   - No virtual environment overhead

2. **REPL-Driven Development**
   - The entire point of this project is nREPL integration
   - Clojure's REPL is first-class, not bolted on
   - Same language client ↔ server ↔ browser (Scittle)

3. **Data-Oriented**
   - EDN is native (vs JSON parsing in Python)
   - Immutable data structures match MCP's request/response model
   - Maps everywhere = natural for tool schemas

4. **Single Binary Distribution**
   - bb is one binary, no dependencies
   - No `pip install`, no virtualenv, no version conflicts

5. **The nREPL Ecosystem**
   - This IS a Clojure tool - nREPL is Clojure's protocol
   - Would be weird to write Python that wraps Clojure's REPL

### My Clojure Proficiency - Honest Evaluation

Looking at this session:
- **CM6 component bug:** Logic error (ratom vs atom), not syntax
- **config_test.clj:** Missing parens - caught immediately by clj-kondo
- **Most code:** Working on first try

The errors I make in Clojure are **conceptual** (Reagent lifecycle, Scittle environment),
not **syntactic** (wrong keywords, bad structure). That's actually a good sign.

### Where Python Would Help

If this project needed:
- Heavy ML/AI integration (PyTorch, transformers)
- Async web scraping (aiohttp, playwright-python)
- Rapid prototyping with many libraries
- Team unfamiliar with Clojure

### The Real Question

> "What makes me productive?"

1. **Good documentation** - SCITTLE_DEV_ENVIRONMENT.md was crucial
2. **Fast feedback loops** - clj-kondo catches errors instantly
3. **Clear error messages** - Plain text I can read
4. **Working examples** - test_cm6_update.mjs as reference
5. **CLI access** - bb tasks over MCP tools

**Language matters less than tooling and documentation.**

### Conclusion

Babashka is the right choice for bb-mcp-server because:
- It fits the domain (Clojure tooling)
- It's fast (CLI tools)
- It's self-contained (single binary)
- The documentation/tooling compensates for my training gap

**My Python training advantage doesn't outweigh the domain fit.**

If you wanted to maximize my raw coding speed, Python would win.
If you want the right tool for this job, Babashka wins.

---

## Alternative Interface: REPL-First Instead of Bash

> **User Question:** "What if I ask you to do all work through a clj-repl interface
> as THE cli? More structured, more powerful than bash."

### Current Pattern: Bash as CLI
```bash
bb server:list
bb mcp call nrepl.nrepl-eval '{"code":"(+ 1 2)"}' --mcp code-browser-dev
bb server:stop code-browser-dev
```

### Proposed Pattern: REPL as CLI
```clojure
(server/list)
(mcp/call :nrepl.nrepl-eval {:code "(+ 1 2)"} :mcp "code-browser-dev")
(server/stop "code-browser-dev")
```

### Honest Assessment: Pros

1. **More Structured Output**
   - Get Clojure data structures, not text to parse
   - `{:status :ok :connections [...]}` vs parsing text output
   - No grep/jq needed

2. **More Powerful Composition**
   ```clojure
   ;; In REPL - real composition
   (->> (server/connections)
        (filter #(= :browser (:type %)))
        first
        :nickname
        (mcp/eval "(+ 1 2)"))

   ;; vs Bash - string manipulation
   bb mcp call ... | jq '.connections[] | select(.type=="browser")' | ...
   ```

3. **Stateful Session**
   - Define helper functions once, use throughout session
   - Build up abstractions as we work
   - `(def conn (find-browser-connection))` then reuse

4. **Same Language as Project**
   - No context switching between Clojure and Bash
   - Errors are Clojure exceptions, not shell errors
   - Can inspect/modify running state directly

5. **Full Language Power**
   - Threading macros, destructuring, let bindings
   - Higher-order functions
   - Macros for repetitive patterns

### Honest Assessment: Cons

1. **My Training Gap**
   - Vastly more Bash examples in training data
   - REPL-first workflows are less common in my training
   - Would need to build new patterns

2. **Error Handling Differences**
   ```bash
   # Bash: simple, text-based
   bb server:stop foo
   # Error: Server 'foo' not found

   # REPL: stack traces
   (server/stop "foo")
   # ExceptionInfo: Server not found {:name "foo"}
   #   at bb_mcp_server.server/stop (server.clj:42)
   #   ...
   ```
   Stack traces are more informative but noisier.

3. **Statefulness Risk**
   - REPL state can get corrupted
   - Bash commands are stateless (fresh each time)
   - Would need "reset" capability

4. **Tool Integration**
   - Claude Code's Bash tool is well-integrated
   - Would need to use `nrepl-eval` or `local-eval` tools instead
   - Less "native" feeling in the interface

5. **Multiline Complexity**
   - REPL expects well-formed expressions
   - Bash can be one-liners more easily
   - Though Clojure threading helps

### What Already Exists

We actually have the infrastructure:
```clojure
;; local-eval - runs in MCP server's bb runtime
(mcp__nrepl_mcp_server__local-eval {:code "(+ 1 2)"})

;; nrepl-eval - runs in connected nREPL (browser, JVM, etc.)
(mcp__nrepl_mcp_server__nrepl-eval {:code "(+ 1 2)" :connection "browser-1"})
```

The question is: should this be PRIMARY or supplementary?

### My Recommendation: Hybrid Approach

| Task | Best Interface | Why |
|------|---------------|-----|
| File operations | Bash | git, file system native to shell |
| Server start/stop | Bash | Simple commands, no state needed |
| nREPL evaluation | REPL | Natural fit, structured output |
| Complex queries | REPL | Composition, data manipulation |
| Quick one-offs | Bash | Lower overhead, familiar |
| Debugging state | REPL | Can inspect, modify, experiment |

**Proposed hybrid:**
```bash
# Server lifecycle stays bash
bb server --http --config ... --nickname code-browser-dev

# But evaluation moves to REPL
(require '[bb-mcp-server.repl :as r])
(r/eval "(+ 1 2)" :conn (r/browser-conn))

# Complex operations in REPL
(let [conn (r/browser-conn)]
  (r/load-file "path/to/file.cljs" conn)
  (r/eval "(mount!)" conn))
```

### What Would Make REPL-First Work

1. **Good wrapper functions** - Not raw nrepl-eval, but `(r/eval code)` helpers
2. **Clear error messages** - Format exceptions nicely, not raw stack traces
3. **Reset capability** - `(r/reset!)` to clear state when things go wrong
4. **Documentation** - Show REPL patterns, not just bash
5. **Autocomplete/discovery** - `(r/help)` to see available functions

### Would I Use It?

**Honestly:** I'd use it for nREPL/evaluation work, but probably keep Bash for:
- git operations
- file system operations
- server lifecycle
- quick one-liners

The cognitive overhead of switching to REPL-first is non-trivial, but the power is real. If the wrapper functions were good enough (like `(r/browser-conn)` instead of parsing JSON), I could adapt.

**The key insight:** It's not Bash vs REPL, it's about having the right abstraction layer. Both can work if the helper functions are good.

---

## Next Steps

1. [ ] Add checkpoint discipline to CLAUDE.md
2. [ ] Document PTC-style chaining patterns
3. [ ] Create parallel test runner pattern
4. [ ] Evaluate hook-based verification (pre-commit lint/format)
5. [ ] Consider "Verify" subagent for automated checks
