# bb-mcp-server Project Context

**Current State: December 28, 2025**

## Next Up: Phase 20 - MCP CLI & E2E Testing

### Problem Identified
Current tests use mock handlers - no automated testing of real tool invocations via MCP protocol. We have:
- `bb mcp-eval` → `local-eval` (specific)
- `bb nrepl` → nREPL tools (specific)

But no generic MCP CLI, and no E2E tests using real tools.

### Goal
1. **Generic MCP CLI** (`bb mcp`)
   - `bb mcp init` - Show server info
   - `bb mcp tools` - List all tools
   - `bb mcp tool <name>` - Show tool schema
   - `bb mcp call <name> <args>` - Call any tool

2. **E2E Test Suite** using `mcp_client.clj`
   - Start real server with real modules
   - Call real tools via MCP protocol
   - Verify real results

### Testing Gap
| Layer | Tested? |
|-------|---------|
| Handler functions | ✅ Unit tests |
| HTTP transport | ✅ Mock handlers |
| **Real tools via MCP** | ❌ Not tested |
| **Tool registration** | ❌ Not tested |
| **Module → tool availability** | ❌ Not tested |

---

## Just Completed: v1.7.0 - Scittle-nREPL Dev Environment

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

## Deferred: Session Persistence

**Issue**: Currently each eval gets a new nREPL session, so `*1`/`*2`/`*3` don't persist between evals.

**Fix**: Would require storing session-id per connection and auto-including in subsequent evals.

**Status**: Deferred - basic functionality works, session persistence is a future enhancement.

---

## Previously Completed: v1.5.0 - mcp-eval CLI

- Fixed local-eval stderr capture
- Fixed double-wrapping bug in tools_call.clj
- MCP Client Library (`src/bb_mcp_server/mcp_client.clj`)
- mcp-eval CLI (`scripts/mcp_eval_script.clj`)

---

## Important Reminders
- **Verification**: Run `clj-kondo --lint <files>` and `cljfmt check <files>` before commit
- **Zero warnings required**: Do NOT commit with lint warnings
- **macOS**: Do NOT use `timeout` command (doesn't exist)

## Testing Commands
```bash
# mcp-eval
bb mcp-eval "(+ 1 2 3)"
bb mcp-eval "(range 5)" --output full --pprint

# nrepl CLI
bb nrepl help
bb nrepl list --mcp nrepl-mcp
bb nrepl eval "(+ 1 2)" --mcp nrepl-mcp

# Server commands
bb server --http --port 3001 --config bb-nrepl-system.edn --nickname nrepl-mcp
bb server:stop 3001
```
