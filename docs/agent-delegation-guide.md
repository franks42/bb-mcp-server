# Agent Delegation Guide

> **Purpose:** Working guide for using subagent delegation in bb-mcp-server development.
> This document is for both the main agent (architect) and subagents to reference.

**Last Updated:** 2026-01-10

---

## Overview

The agent delegation pattern keeps the main conversation focused on architecture and coordination while subagents handle focused implementation tasks.

```
Architect (Main Agent)
│
├── Plans approach, creates todos with checkpoints
├── Maintains big picture context
├── Delegates specific tasks to subagents
├── Reviews results, handles integration
└── Updates context.md at checkpoints
    │
    ├── Implementation Agent(s)
    │   └── Focused on specific files/functions
    │
    ├── Test Agent
    │   └── Writes tests, runs verification
    │
    └── Explore Agent
        └── Research, codebase exploration
```

---

## When to Use Delegation

### Use Delegation

| Scenario | Why |
|----------|-----|
| Multi-file feature | Each agent owns specific files |
| Feature + tests | Parallel implementation and testing |
| Complex refactor | Clear boundaries between changes |
| Research + implement | Explore agent gathers context, impl agent executes |

### Don't Use Delegation

| Scenario | Why |
|----------|-----|
| Quick bug fix | Overhead exceeds benefit |
| Single-file edit | No isolation benefit |
| Exploratory/unclear work | Need tight feedback loop |
| Under 10 minutes work | Just do it directly |

---

## Available Subagent Types

| Type | Use For | Context |
|------|---------|---------|
| `general-purpose` | Implementation, testing, multi-step tasks | Has conversation history |
| `Explore` | Codebase exploration, finding files | Fast, focused on search |
| `Plan` | Architecture design, implementation planning | Returns structured plans |
| `Bash` | Command execution, git operations | Terminal only |

---

## Architect Responsibilities

### Before Delegating

1. **Plan the approach** - Know what needs to change and why
2. **Create todos with checkpoints** - Make work visible
3. **Update context.md** - Document starting state
4. **Define clear boundaries** - Which files, which functions, what behavior

### When Delegating

1. **Provide specific instructions** - Not "implement feature" but "add function X to file Y"
2. **Include success criteria** - "Tests pass, lint clean, function returns Z"
3. **Reference existing patterns** - "Follow the pattern in src/existing.clj"
4. **Set scope limits** - "Only modify these files: ..."

### After Delegation

1. **Review results** - Don't blindly trust
2. **Run verification** - `bb lint && bb format && bb test:modules`
3. **Update context.md** - Document what changed
4. **Handle integration** - Wire pieces together if needed

---

## Subagent Instructions Template

Use this template when spawning subagents:

```markdown
## Task: [Short Description]

### Context
- Project: bb-mcp-server (Babashka MCP server)
- Working directory: /Users/franksiebenlist/Development/bb-mcp-server

### Files to Modify
- `src/path/to/file.clj` - [what to change]
- `test/path/to/test.clj` - [what to add]

### Requirements
1. [Specific requirement 1]
2. [Specific requirement 2]
3. [Specific requirement 3]

### Patterns to Follow
- See `src/example/existing.clj` for similar implementation
- Use `taoensso.trove` for logging (see docs/AI_TELEMETRY_GUIDE.md)
- Follow threading macro style from existing code

### Success Criteria
- [ ] Function X exists and does Y
- [ ] clj-kondo reports 0 errors, 0 warnings
- [ ] cljfmt check passes
- [ ] Tests pass (bb test:modules)

### Constraints
- Do NOT modify files outside the listed scope
- Do NOT add dependencies
- Do NOT refactor unrelated code
```

---

## Implementation Agent Guide

**You are an implementation agent.** Your job is focused code changes.

### Your Workflow

1. **Read the task** - Understand exactly what's requested
2. **Read referenced files** - Understand existing patterns
3. **Make minimal changes** - Only what's required
4. **Run verification** - `clj-kondo --lint <files>` and `cljfmt check <files>`
5. **Report results** - What you changed, verification output

### Rules

- **Stay in scope** - Only touch files explicitly listed
- **Follow patterns** - Match existing code style
- **Verify before reporting** - Run lint/format
- **Be specific in output** - File paths, line numbers, what changed

### Output Format

```markdown
## Implementation Complete

### Changes Made
- `src/foo.clj:45-67` - Added `bar` function
- `src/foo.clj:12` - Added require for `clojure.string`

### Verification
$ clj-kondo --lint src/foo.clj
linting took 42ms, no warnings found

$ cljfmt check src/foo.clj
All files formatted correctly.

### Notes
- Used threading macro pattern from `src/existing.clj`
- [Any decisions or assumptions made]
```

---

## Test Agent Guide

**You are a test agent.** Your job is writing and running tests.

### Your Workflow

1. **Understand what to test** - Read the implementation or requirements
2. **Find test patterns** - Look at existing tests in `test/` or `modules/*/test/`
3. **Write focused tests** - Cover the specific functionality
4. **Run tests** - `bb test:modules` or specific test file
5. **Fix failures** - If tests fail, fix them
6. **Report results** - Test count, assertions, pass/fail

### Rules

- **Test behavior, not implementation** - Focus on inputs/outputs
- **Use existing test patterns** - Match project style
- **Include edge cases** - nil, empty, error conditions
- **Run verification** - Lint and format test files too

### Output Format

```markdown
## Tests Complete

### Tests Added
- `test/foo_test.clj`
  - `test-bar-basic` - Tests normal case
  - `test-bar-nil-input` - Tests nil handling
  - `test-bar-empty` - Tests empty input

### Results
$ bb test:modules
...
3 tests, 5 assertions, 0 failures.

### Verification
$ clj-kondo --lint test/foo_test.clj
linting took 38ms, no warnings found

### Coverage Notes
- Covers happy path and error cases
- [Any gaps or future test ideas]
```

---

## Parallel Delegation

When tasks are independent, delegate in parallel:

```clojure
;; Architect spawns both at once (single message, multiple Task calls)

;; Task 1: Implementation Agent
"Implement the bar function in src/foo.clj..."

;; Task 2: Test Agent (if requirements are clear)
"Write tests for bar function that will be in src/foo.clj.
 Expected behavior: [describe]. Tests go in test/foo_test.clj..."
```

**When to parallelize:**
- Implementation and tests (if spec is clear)
- Independent file changes
- Research + planning

**When NOT to parallelize:**
- Tests depend on seeing implementation
- Changes to same file
- Sequential dependencies

---

## Handoff Protocol

### Architect → Implementation Agent

```markdown
Task: Implement [feature]

Context: [Why we're doing this]

Files: [Exact list]

Requirements: [Numbered list]

Success: [Specific criteria]

Reference: [Files to look at for patterns]
```

### Implementation Agent → Architect

```markdown
Done: [What was accomplished]

Changes: [File:line summaries]

Verification: [Lint/format output]

Questions: [Any ambiguities encountered]
```

### Architect → Test Agent

```markdown
Task: Test [feature]

Implementation: [File:function that was added]

Behavior: [What it should do]

Edge cases: [What to test]

Test file: [Where to put tests]
```

### Test Agent → Architect

```markdown
Done: [Test count and coverage]

Results: [Pass/fail, assertion count]

Verification: [Lint output]

Gaps: [Anything not covered]
```

---

## Example: Adding a New Tool

### Phase Plan (Architect)

```
- [ ] Checkpoint: Document starting state
- [ ] Explore: Find similar tool implementations
- [ ] Plan: Design tool interface
- [ ] Delegate: Implementation agent adds tool
- [ ] Delegate: Test agent writes tests
- [ ] Review: Verify integration
- [ ] Checkpoint: Document completion
```

### Delegation 1: Implementation

```markdown
## Task: Add `foo` tool to calculate module

### Context
Adding a new calculation tool that computes X.

### Files to Modify
- `modules/calculate/src/calculate/tools.clj` - Add tool definition
- `modules/calculate/src/calculate/core.clj` - Add implementation function

### Requirements
1. Tool named `calculate.foo`
2. Takes `{:a number, :b number}` input
3. Returns `{:result number}`
4. Follow pattern of existing `percent-change` tool

### Success Criteria
- [ ] Tool appears in `bb mcp tools`
- [ ] clj-kondo clean
- [ ] cljfmt clean
```

### Delegation 2: Tests

```markdown
## Task: Test `foo` tool

### Implementation
- `modules/calculate/src/calculate/core.clj` - `foo` function
- `modules/calculate/src/calculate/tools.clj` - tool registration

### Test File
- `modules/calculate/test/calculate/core_test.clj`

### Test Cases
1. Basic calculation: (foo 10 5) => expected result
2. Zero handling: (foo 0 5) => expected behavior
3. Negative numbers: (foo -10 5) => expected behavior

### Success Criteria
- [ ] 3+ tests
- [ ] All passing
- [ ] clj-kondo clean
```

---

## Troubleshooting

### Subagent Returns Incomplete Work

**Cause:** Instructions weren't specific enough
**Fix:** Re-delegate with more detail, reference specific files/patterns

### Subagent Modified Wrong Files

**Cause:** Scope wasn't explicit
**Fix:** Always list exact files in "Files to Modify" section

### Tests Fail After Implementation

**Cause:** Implementation and test agents had different understanding
**Fix:** Have test agent read actual implementation before writing tests

### Context Lost Between Delegations

**Cause:** Subagents don't share context with each other
**Fix:** Architect must bridge context in delegation prompts

---

## Quick Reference

### Spawning Subagents

```
Task tool with subagent_type:
- "general-purpose" - implementation, testing
- "Explore" - codebase search
- "Plan" - architecture design
- "Bash" - commands only
```

### Verification Commands

```bash
clj-kondo --lint <file>     # Must be 0 errors, 0 warnings
cljfmt check <file>         # Must pass
bb test:modules             # Must pass
```

### Key Files to Reference

- `docs/CLOJURE_EXPERT_CONTEXT.md` - Coding standards
- `docs/AI_TELEMETRY_GUIDE.md` - Logging patterns
- `CLAUDE.md` - Project instructions
- `context.md` - Current session state

---

*This guide is for AI agents working on bb-mcp-server. Humans may find it useful for understanding the delegation workflow.*
