# bb-mcp-server Project Context

**Current State: December 29, 2025**

## Just Completed: Phase 20 - MCP CLI & E2E Testing

### What Was Built

1. **Generic MCP CLI** (`bb mcp`)
   - `bb mcp servers` - List running servers
   - `bb mcp init` - Get server info and capabilities
   - `bb mcp tools` - List all available tools
   - `bb mcp tool <name>` - Show specific tool metadata
   - `bb mcp call <name> <args>` - Call any tool with JSON args

2. **E2E Test Suite** (`bb test:e2e`)
   - 11 tests, 42 assertions
   - Tests local-eval, calculate, echo, hello, math, strings
   - Real tool execution via MCP protocol

3. **Bug Fixes**
   - Ephemeral port (`--port 0`) now correctly reports actual assigned port
   - Port files now use `.json` extension

### Testing Coverage Now
| Layer | Tested? |
|-------|---------|
| Handler functions | ✅ Unit tests |
| HTTP transport | ✅ Mock handlers |
| **Real tools via MCP** | ✅ E2E tests |
| **Tool registration** | ✅ E2E tests |
| **Module → tool availability** | ✅ E2E tests |

---

## Future: Convenience CLI Tasks

Create higher-level CLI wrappers for specific tools (like `bb mcp-eval` and `bb nrepl`):

- `bb calc "(+ 1 2 3)"` → calculator without JSON args
- `bb echo "hello"` → simple echo without JSON
- etc.

---

## Previously Completed: v1.7.0 - Scittle-nREPL Dev Environment

- Browser-based ClojureScript REPL via Scittle + sente-lite
- Shadow-cljs style API: `(browser/repl :id)`, `:cljs/quit`
- Full workflow verified: rebel → nrepl-proxy → browser

---

## Previously Completed: v1.6.0 - nrepl CLI

- Bootstrap config (`bb-nrepl-system.edn`)
- Generic tool calling (`src/bb_mcp_server/mcp_client.clj`)
- nrepl CLI dispatcher (`scripts/nrepl_cli.clj`)
- Subcommands: connect, disconnect, list, status, eval, load-file, help

---

## Testing Commands
```bash
# MCP CLI (exploration & testing)
bb mcp servers                           # List running servers
bb mcp tools --mcp dev                   # List tools
bb mcp call echo.echo '{"message":"hi"}' # Call any tool

# E2E Tests (requires running server)
bb server --http 0 --nickname e2e-test --config bb-e2e-test-system.edn &
bb test:e2e

# mcp-eval (code evaluation)
bb mcp-eval "(+ 1 2 3)"
bb mcp-eval "(range 5)" --output full --pprint

# nrepl CLI
bb nrepl help
bb nrepl list --mcp nrepl-mcp
bb nrepl eval "(+ 1 2)" --mcp nrepl-mcp
```

---

## Important Reminders
- **Verification**: Run `clj-kondo --lint <files>` and `cljfmt check <files>` before commit
- **Zero warnings required**: Do NOT commit with lint warnings
- **macOS**: Do NOT use `timeout` command (doesn't exist)
