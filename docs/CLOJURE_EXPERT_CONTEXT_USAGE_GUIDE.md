# Clojure Expert Context - Usage Guide

## Reality Check: Document Length Matters

**Important finding:** AI assistants start to "glaze over" once a system document exceeds 400-500 lines. While modern context windows can technically handle 1,400+ lines, effectiveness drops significantly.

**The paradox:** Comprehensive documentation may be less effective than focused, concise versions.

## Recommended Versions for AI Assistants

### 🎯 PRIMARY OPTIONS - LLM-Specific Versions (200-330 lines)

These are **optimized for actual AI consumption:**

#### 1️⃣ **GROK Edition** (~330 lines) - Most Comprehensive
**File:** `CLOJURE_EXPERT_CONTEXT_grok.md`

**Best for:**
- ✅ Production projects
- ✅ Complete workflows (includes git, testing, troubleshooting)
- ✅ Teams needing version control integration
- ✅ When you need structured checklists

**Unique strengths:**
- Troubleshooting section
- Git workflow integration
- Testing patterns with examples
- Workflow checklist

---

#### 2️⃣ **GPT Edition** (~251 lines) - Most Structured
**File:** `CLOJURE_EXPERT_CONTEXT_gpt.md`

**Best for:**
- ✅ Security-conscious projects
- ✅ Clear, organized workflows
- ✅ When you want verification templates
- ✅ Projects with strict compliance needs

**Unique strengths:**
- Security and secrets handling
- Graceful degradation for missing tools
- Sample output templates
- Tool cheatsheet appendix

---

#### 3️⃣ **GEMINI Edition** (~203 lines) - Most Concise
**File:** `CLOJURE_EXPERT_CONTEXT_gemini.md`

**Best for:**
- ✅ Quick tasks and scripts
- ✅ Minimal token budget
- ✅ When combined with large codebases
- ✅ Simple, standard workflows

**Unique strengths:**
- Most token-efficient
- Context awareness emphasis
- Graceful degradation
- Clean, scannable format

---

### 📚 REFERENCE ONLY - Archive Versions

#### FINAL - Comprehensive (~1,400 lines) ⚠️ TOO LONG FOR AI USE
**File:** `CLOJURE_EXPERT_CONTEXT_FINAL.md`

**Do NOT use for AI assistants** - Too long for effective reading
**Use for:** Human reference, documentation archive, completeness checking

**Contains:** Everything from all versions synthesized together

#### QUICKSTART - Condensed (~500 lines) ⚠️ STILL TOO LONG
**File:** `CLOJURE_EXPERT_CONTEXT_QUICKSTART.md`

**Status:** Experimental - may still be above optimal length
**Use for:** Human quick reference, not AI consumption

#### ORIGINAL - Baseline (~552 lines) ⚠️ SUPERSEDED
**File:** `CLOJURE_EXPERT_CONTEXT.md`

**Status:** Historical reference only
**Use for:** Comparing against enhanced versions

---

## Decision Matrix for AI Assistants

| Scenario | Recommended Version | Why |
|----------|-------------------|-----|
| **Simple script** | GEMINI | Minimal, focused, efficient |
| **Production backend** | GROK | Complete workflows + troubleshooting |
| **Quick bug fix** | GEMINI | Fast onboarding |
| **Security-critical** | GPT | Best security guidance |
| **Team development** | GROK | Git integration + checklists |
| **Large codebase** | GEMINI | Leaves room for code context |
| **Strict compliance** | GPT | Structured workflows + verification |
| **DevOps/automation** | GROK | Testing + troubleshooting |
| **Educational content** | GROK | Most examples |
| **Token-constrained** | GEMINI | Smallest footprint |

## Decision Matrix for Humans

| Scenario | Recommended Version | Why |
|----------|-------------------|-----|
| **Quick reference** | GROK/GPT/GEMINI | Pick style you prefer |
| **Comprehensive study** | FINAL | All details in one place |
| **Comparing approaches** | comparison.md | See what each LLM contributed |
| **Historical context** | ORIGINAL | See baseline requirements |

---

## Token Budget Considerations

### Context Window Sizes (as of 2025)
- **Claude Sonnet 4.5:** 200K tokens (~150K words)
- **GPT-4 Turbo:** 128K tokens (~96K words)
- **Gemini 2.0 Pro:** 1M tokens (~750K words)
- **Grok 2:** 131K tokens (~98K words)

### Document Sizes (Actual)
- **GEMINI:** ~203 lines ≈ 3K tokens ≈ 1.5% of Claude's context ✅
- **GPT:** ~251 lines ≈ 4K tokens ≈ 2% of Claude's context ✅
- **GROK:** ~330 lines ≈ 5K tokens ≈ 2.5% of Claude's context ✅
- **QUICKSTART:** ~500 lines ≈ 8K tokens ≈ 4% of Claude's context ⚠️
- **ORIGINAL:** ~552 lines ≈ 9K tokens ≈ 4.5% of Claude's context ⚠️
- **FINAL:** ~1,400 lines ≈ 22K tokens ≈ 11% of Claude's context ❌

### Effectiveness vs Size

**The Reality:**
- Context window size ≠ Effective reading length
- AI assistants read best with 200-400 line documents
- Beyond 500 lines, attention and adherence drops
- Comprehensive != Better for AI consumption

**Recommendation:** Use LLM-specific versions (GEMINI/GPT/GROK) for AI assistants. Reserve FINAL/QUICKSTART for human reference only.

---

## Version Evolution and Strategy Shift

### Initial Strategy (FAILED)
**Assumption:** "Comprehensive is better - synthesize everything into one document"
**Result:** Created FINAL (~1,400 lines) that's too long for AI assistants to effectively use
**Learning:** Context window capacity ≠ Effective attention span

### Revised Strategy (RECOMMENDED)
**Recognition:** The LLM-specific versions (200-330 lines) are already in the optimal range
**Action:** Use them directly instead of trying to consolidate
**Benefit:** Each version has unique strengths for different scenarios

### Why Keep FINAL?
- **Human reference:** Comprehensive documentation for manual review
- **Completeness checking:** Ensure no critical content lost
- **Archive:** Historical record of synthesis effort
- **Comparison baseline:** Shows what each version contributes

### The LLM Versions are Primary, Not Secondary
**Original assumption:** "LLM versions are drafts to be synthesized"
**Reality:** "LLM versions are production-ready, optimally-sized documents"

See `CLOJURE_EXPERT_CONTEXT_comparison.md` for detailed analysis of what each LLM contributed.

---

## Migration Path

### If Currently Using Original
**Switch to:** GROK (most similar in comprehensiveness, better organized)

### If Currently Using FINAL or QUICKSTART
**Switch to:** One of the LLM-specific versions:
- **GROK** if you need comprehensive workflows
- **GPT** if you need security focus
- **GEMINI** if you need minimal footprint

### If Currently Using LLM-Specific Versions
**Keep using them!** They are the recommended production versions.

---

## Combining with Other Contexts

### GEMINI (203 lines) Works Well With
- **Massive codebases:** 20K+ lines of code context
- **Multiple large documents:** API specs, requirements, architecture docs
- **Total budget:** ~3K tokens leaves ~197K for other content (Claude)

### GPT (251 lines) Works Well With
- **Large codebases:** 15K+ lines of code
- **Security documentation:** Compliance requirements, threat models
- **Total budget:** ~4K tokens leaves ~196K for other content (Claude)

### GROK (330 lines) Works Well With
- **Medium codebases:** 10K+ lines of code
- **Complete project context:** Architecture + testing + deployment docs
- **Total budget:** ~5K tokens leaves ~195K for other content (Claude)

**The advantage:** Small system prompts leave almost entire context window for actual work.

---

## Customization Recommendations

### For Specific Projects
1. **Start with:** QUICKSTART or FINAL (depending on complexity)
2. **Add section:** Project-specific exceptions to standard rules
3. **Document:** "This project uses X instead of Y because..."
4. **Example:**
   ```markdown
   ## Project-Specific Overrides

   - **Testing:** Use `kaocha` instead of `clojure.test`
     - Reason: Parallel test execution required
     - Command: `bb kaocha`

   - **Formatting:** Disable `:indents` rule
     - Reason: Custom DSL with non-standard indentation
     - Config: `.cljfmt.edn` line 42
   ```

### For Teams
- **Use FINAL as base**
- Add team-specific sections:
  - Code review checklist
  - Deployment procedures
  - Domain-specific validation rules
  - Team coding conventions

---

## Success Metrics

### How to Know It's Working

**Signs the AI assistant is following the context:**
- ✅ Runs verification commands after every change
- ✅ Reports actual output (not "it should work")
- ✅ Adds telemetry to new functions
- ✅ Fixes all clj-kondo warnings before proceeding
- ✅ Admits uncertainty when appropriate
- ✅ Checks project structure before running commands
- ✅ Uses Babashka for scripting tasks

**Signs the AI assistant is NOT following the context:**
- ❌ Says "this should work" without running code
- ❌ Ignores or skips clj-kondo warnings
- ❌ Creates functions without telemetry
- ❌ Uses bash/python instead of Babashka
- ❌ Fabricates tool output when tools are missing
- ❌ Runs wrong commands for project type

---

## Feedback and Iteration

### Version History
- **v1.0 (2025-11-20):**
  - Created QUICKSTART (condensed)
  - Created FINAL (comprehensive)
  - Synthesized from 4 LLM reviews
  - Added usage guide

### Future Enhancements (Potential)
- Language-specific variants (ClojureScript-focused, Babashka-focused)
- Domain-specific versions (web dev, data science, DevOps)
- Interactive examples with actual test cases
- Video walkthroughs of verification workflows

### Contributing Improvements
If you find gaps or issues:
1. Document the scenario where the context failed
2. Propose specific additions
3. Test with real AI assistants
4. Submit as enhancement to appropriate version (QUICKSTART vs FINAL)

---

## Quick Decision Tree (AI Assistants)

```
START: Choosing Clojure expert context for AI
  │
  ├─ Simple script or minimal token budget?
  │  └─ YES → Use GEMINI (203 lines)
  │
  ├─ Security-critical or structured workflows?
  │  └─ YES → Use GPT (251 lines)
  │
  ├─ Production project with full lifecycle?
  │  └─ YES → Use GROK (330 lines)
  │
  └─ Human reading/reference?
     └─ YES → Use FINAL (1,400 lines)
```

## Quick Decision Tree (Humans)

```
START: Reading Clojure expert context yourself
  │
  ├─ Quick reference for specific task?
  │  └─ YES → Pick GEMINI/GPT/GROK (read the one that fits)
  │
  ├─ Comprehensive study of all best practices?
  │  └─ YES → Read FINAL (everything in one place)
  │
  └─ Understanding the review process?
     └─ YES → Read comparison.md
```

---

## Summary

### For AI Assistants: Use LLM-Specific Versions

**Default recommendation:** Use **GROK** for production projects, **GEMINI** for simple tasks, **GPT** for security focus.

**Why:**
- Optimal length (200-330 lines) for AI attention span
- Each optimized for different scenarios
- Leaves ~195K+ tokens for actual work context
- AI assistants actually read and follow them

**Don't use for AI:** FINAL, QUICKSTART, ORIGINAL (too long, attention drops)

### For Humans: Use Whatever Fits

**Quick reference:** GEMINI/GPT/GROK (pick the style you like)
**Comprehensive study:** FINAL (everything synthesized)
**Historical context:** ORIGINAL + comparison.md

### Key Learning

**Context window capacity ≠ Effective reading length**

Just because an AI can technically fit 1,400 lines in context doesn't mean it will effectively follow all of them. The LLM-specific versions (200-330 lines) are the sweet spot.

---

## Revision History

**v1.1 (2025-11-20):** Strategy shift based on user feedback
- Recognized that LLM-specific versions are optimal for AI consumption
- Reclassified FINAL/QUICKSTART as human reference only
- Updated all recommendations to favor GEMINI/GPT/GROK versions
- Added "effectiveness vs size" analysis

**v1.0 (2025-11-20):** Initial version
- Attempted comprehensive synthesis (FINAL)
- Created condensed version (QUICKSTART)
- Learning: Comprehensive doesn't mean effective for AI

---

*Usage Guide v1.1*
*Last updated: 2025-11-20*
