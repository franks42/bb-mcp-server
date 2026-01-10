# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-10
**Version:** v1.10.3

---

## NEW: Browser MCP Tools Available

**Playwright MCP** and **Chrome DevTools MCP** have been added to Claude Desktop config.

> ⚠️ **AI Directive:** Test these new MCP tools for browser automation instead of writing
> JavaScript test files. These should replace the `test_cm6_update.mjs` pattern.

### Try These First

```
# Instead of running node test scripts, try:
"Use playwright to navigate to localhost:8091 and click the 'Select fn2' button"

"Use chrome-devtools to evaluate (+ 1 2) in the browser console"
```

### Expected Tools (after Claude restart)
- `playwright_navigate`, `playwright_click`, `playwright_fill`, etc.
- `chrome-devtools` tools: `navigate_page`, `click`, `evaluate_script`, `list_console_messages`

**If tools not available:** User needs to restart Claude Desktop/Claude Code to load new MCP servers.

**Documentation:** See `docs/claude-cookbook-suggestions.md` → "Browser Automation: Better MCP Alternatives"

---

## Current State

**v1.10.3 released** - 64KB LSP buffer corruption fix verified and shipped.

### Recent Work
- **64KB buffer fix** - `BufferedReader.read()` may return fewer chars than requested; loop until all read
- CM6 editor update fix (Form-3 Reagent component pattern)
- Added Playwright MCP + Chrome DevTools MCP to config
- **Context checkpoints** - Added checkpoint-in-todos pattern to CLAUDE.md and IMPLEMENTATION_PLAN.md
- **Agent delegation guide** - Created `docs/agent-delegation-guide.md` for subagent workflow
- **bb tasks reference** - Created `docs/bb-tasks-reference.md` to prevent curl/bash reinvention
- **server:start-wait** - New task that starts server and waits for health (avoids `& sleep && curl` chains)

---

## Quick Resume

```bash
# Start server for browser development
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# Initialize clojure-lsp (required for code-browser)
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev

# Test with Playwright MCP (NEW - try this!)
# "Use playwright to navigate to localhost:8091"
```

---

## Session Notes

Things not in CLAUDE.md or other docs:

- **64KB buffer boundary** - macOS pipe buffer is 64KB; `BufferedReader.read()` may return partial data
- **sente-lite on-message callback** - MUST return `nil`; truthy values get echoed to client
- **Scittle reagent.dom** - Available via nREPL eval with `(require '[reagent.dom :as rdom])`
- **nrepl-eval-local-file** - Correct tool for loading .cljs into Scittle
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable
- **Reagent Form-3 gotcha** - Values in outer `let` are captured at mount time, not updated
- **CLI vs MCP** - CLI wrappers (`bb mcp`, `bb nrepl`) are often easier than native MCP tools
- **Playwright/DevTools MCP** - Browser automation tools for testing
- **Agent delegation** - Use Task tool with subagents for multi-file work; see `docs/agent-delegation-guide.md`
- **Checkpoints in todos** - Always include checkpoint tasks in phase plans to survive compaction

---

## Recent Commits

```
370344f chore: Remove broken stress test script
05a5a82 docs: Update context.md for v1.10.3
5229ef5 chore: Remove debug code after 64KB fix verification  <- v1.10.3
734d0f4 fix(clojure-lsp): Handle partial reads in JSON-RPC reader (64KB fix)
37be9cf fix(code-browser): CM6 editor updates when source changes  <- v1.10.2
```
