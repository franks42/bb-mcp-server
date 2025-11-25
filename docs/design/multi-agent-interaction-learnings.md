# Multi-Agent Interaction Learnings

**Created:** 2025-11-25
**Status:** Active Learning Document

---

## Overview

This document captures learnings from multi-agent orchestration experiments.
It serves as a reference for effective agent prompting and coordination patterns.

---

## Iteration 1: Too Thorough Review

**Problem:** Reviewer kept finding issues indefinitely (3 iterations, then max reached).

**Root Cause:** Prompt said "If the code is PERFECT, respond with APPROVED."
- Nothing is ever perfect
- Reviewer interpreted this as "find everything wrong"
- Resulted in endless critique loop

**Example Response:** Reviewer found:
1. Off-by-one error in retry logic
2. Documentation mismatch
3. Questioned library name

While these were legitimate observations, they prevented progress.

---

## Iteration 3-4: Subprocess vs HTTP Performance

**Problem:** Test-writer (Haiku subprocess) timed out at 120s, but direct test showed Haiku responds in 3s.

**Root Cause:** Subprocess agents have file access. When given a code generation task, they may:
- Read additional context from disk
- Write output to files
- Run lint/format checks
- Execute tests

This extra work adds significant overhead.

**Fix:** Switch test-writer from `claude-subprocess` to `anthropic-http`:
- Before: 120s timeout (subprocess doing file operations)
- After: 12s response (isolated, prompt-only)

**Learning:** Use subprocess only when file access is REQUIRED. Use HTTP API for pure text generation tasks.

---

## Iteration 2: "Good Enough" Prompt

**Fix:** Changed reviewer prompt to focus on blocking issues only.

**Before (problematic):**
```
If the code is PERFECT, respond with exactly: APPROVED
If there are issues, list each issue...
```

**After (working):**
```
Focus ONLY on these critical issues:
1. BLOCKER - Code won't work at all
2. BUG - Logic errors that cause incorrect behavior
3. CRASH - Unhandled exceptions that will crash

Do NOT report: style preferences, minor improvements, documentation suggestions.

If the code will work correctly (even if not perfect):
APPROVED

Remember: Working code is better than perfect code.
```

**Result:** Approved on first review in 5 seconds!

---

## Key Learnings

### 1. Define "Done" Explicitly

**Lesson:** AI reviewers are thorough by default. If you want "good enough" approval, you must explicitly define what counts as blocking vs nice-to-have.

**Pattern:**
```
Focus ONLY on: [critical criteria]
Do NOT report: [things to ignore]
If [condition], respond: APPROVED
```

### 2. Timeout Budget Matters

**Lesson:** Claude subprocess has ~11-12s startup overhead. Factor this into timeouts.

| Operation | Expected Time |
|-----------|---------------|
| HTTP API warmup | <1s |
| Subprocess startup | 11-12s |
| Simple response | 2-5s |
| Complex code generation | 30-60s |

**Pattern:** Set timeout = startup + expected_work + buffer
- HTTP API: 30-60s
- Subprocess: 60-120s

### 3. Response Format Structure

**Lesson:** Explicit response format reduces parsing ambiguity.

**Pattern:**
```
## Response Format
If [condition A]:
```
KEYWORD_A
- Detail: ...
```

If [condition B]:
```
KEYWORD_B
```
```

### 4. Isolation Affects Context

**Lesson:** HTTP API agents only see what you pass. They cannot:
- Read files
- Access project context
- Know about previous iterations (unless told)

**Pattern:** Include all necessary context in each prompt:
```
Here is the code to review:
```clojure
[full code]
```

Here is the task description:
[task]

Here is the previous review feedback (if revising):
[feedback]
```

### 5. Subprocess vs HTTP Trade-offs

| Aspect | claude-subprocess | anthropic-http |
|--------|-------------------|----------------|
| Startup | ~11s | <1s |
| File access | Full project | None (isolated) |
| Tools | Can use Bash, Read, Write | None |
| Fresh perspective | No (sees project) | Yes (only prompt) |
| Cost | Per CLI session | Per API call |
| Predictable time | No (may do extra work) | Yes (prompt only) |

**Pattern:**
- Use subprocess ONLY when file access is REQUIRED (code that needs to write files)
- Use HTTP for review, test generation, and any task where you want predictable timing
- HTTP is faster and more predictable for pure text generation

---

## Anti-Patterns

### 1. "Perfect" Requirements
❌ "If the code is perfect..."
✅ "If the code will work correctly..."

### 2. Open-Ended Review
❌ "Review this code for any issues"
✅ "Check ONLY for bugs that would cause failures"

### 3. Ambiguous Approval Signal
❌ "APPROVED" somewhere in response with other text
✅ Clear format: first line must be APPROVED or CHANGES_REQUIRED

### 4. Insufficient Timeout
❌ 30s timeout for subprocess task
✅ 120s timeout (11s startup + work + buffer)

---

## Future Experiments

1. **Parallel review** - Multiple reviewers concurrently, synthesize results
2. **Streaming responses** - Show progress as agents work
3. **Adaptive prompts** - Adjust strictness based on iteration count
4. **Cost tracking** - Monitor token usage per agent
5. **Quality metrics** - Automated scoring of generated code

---

## Prompt Templates

### Code Generation (Subprocess)
```
You are an expert Clojure developer. Implement this function:

[task description]

Requirements:
- [requirement 1]
- [requirement 2]

Output ONLY the complete source file, no explanations.
```

### Code Review (HTTP/Isolated)
```
You are an expert Clojure code reviewer. Review this code.

```clojure
[code]
```

## Review Criteria
Focus ONLY on critical issues:
1. BLOCKER - Code won't work at all
2. BUG - Logic errors that cause incorrect behavior
3. CRASH - Unhandled exceptions

Do NOT report: style preferences, minor improvements.

## Response Format
If critical issues exist:
CHANGES_REQUIRED
- BUG: [description]

If code will work correctly:
APPROVED

Remember: Working code is better than perfect code.
```

### Test Generation (Subprocess)
```
Write comprehensive clojure.test tests for this function:

```clojure
[code]
```

Requirements:
- Happy path (success on first try)
- Retry scenarios (success after N retries)
- Exhaustion scenario (all retries fail)
- Edge cases (zero retries, negative delay)

Output ONLY the test file, no explanations.
```

---

*This is a living document. Update as we learn more.*
