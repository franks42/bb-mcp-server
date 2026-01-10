# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> Also read `docs/claude-cookbook-suggestions.md` for interface patterns and recommendations.

**Last Updated:** 2026-01-10
**Version:** v1.11.1

---

## Current State

**v1.11.1 released** - AI productivity patterns and lint-fix task.

### What's New in v1.11.x

| Feature | Description |
|---------|-------------|
| `bb server:start-wait` | Start server + wait for health (no more `& sleep && curl`) |
| `bb lint-fix <file>` | Lint, auto-fix paren errors, re-lint (use after editing!) |
| `docs/bb-tasks-reference.md` | Comprehensive CLI reference (check before writing curl/bash) |
| `docs/agent-delegation-guide.md` | Subagent workflow guide for multi-file work |
| Checkpoint-in-todos pattern | Added to CLAUDE.md and IMPLEMENTATION_PLAN.md |
| Scittle guide updated | Uses new patterns throughout |

---

## Quick Resume

```bash
# Start server for browser development (RECOMMENDED - waits for health)
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# After editing Clojure files (catches and fixes paren errors)
bb lint-fix <file>

# List running servers
bb server:list

# Stop server
bb server:stop code-browser-dev

# Initialize clojure-lsp (required for code-browser)
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev
```

---

## Key Documentation

| Doc | When to Read |
|-----|--------------|
| `docs/bb-tasks-reference.md` | Before writing curl/bash commands |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Before Scittle/browser work |
| `docs/agent-delegation-guide.md` | For multi-file tasks with subagents |
| `IMPLEMENTATION_PLAN.md` | For task tracking and planning |

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
- **Playwright/DevTools MCP** - Browser automation tools available for testing
- **Agent delegation** - Use Task tool with subagents for multi-file work
- **Checkpoints in todos** - Always include checkpoint tasks in phase plans to survive compaction
- **lint-fix workflow** - Use `bb lint-fix <file>` after editing Clojure (auto-fixes paren errors)
- **parmezan** - Tool that fixes unbalanced parens heuristically; lint-fix uses it automatically

---

## Recent Commits

```
5478a19 docs: Update Scittle guide with new patterns
24c21ec feat: Add bb lint-fix task for auto-fixing paren errors  <- v1.11.1
d06a8ff docs: Make bb fix-parens more discoverable
eacd87a feat: Add AI productivity patterns and server:start-wait task  <- v1.11.0
89ac610 docs: Update context.md and IMPLEMENTATION_PLAN.md
```

---

## Browser MCP Tools Available

**Playwright MCP** and **Chrome DevTools MCP** are configured.

```
# Playwright tools: mcp__playwright__browser_navigate, browser_click, browser_snapshot, etc.
# Chrome DevTools: mcp__chrome-devtools__navigate_page, click, take_snapshot, etc.
```

Use these for browser automation instead of writing JavaScript test files.
