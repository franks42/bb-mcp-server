# bb-mcp-server Project Context

**Current State: December 27, 2025**

## Just Completed: v1.6.0 - nrepl CLI

### What Was Done
1. **Bootstrap config** (`bb-nrepl-system.edn`)
   - Minimal config with `["nrepl" "local-eval"]` modules

2. **Generic tool calling** (`src/bb_mcp_server/mcp_client.clj`)
   - Added `build-tool-request` for any MCP tool
   - Added `call-tool!` for generic tool calls
   - Added `extract-tool-result` for response parsing

3. **nrepl CLI dispatcher** (`scripts/nrepl_cli.clj`, `scripts/nrepl-task.clj`)
   - `bb nrepl <subcommand> [args] [options]` command
   - Subcommands: connect, disconnect, list, status, eval, load-file, help
   - `--mcp NAME` for MCP server addressing
   - `--connection NAME` for nREPL connection selection
   - `--output result|full|pipe` modes
   - `--pprint` and `--timeout MS` options

4. **Task integration** (`bb.edn`)
   - Added `nrepl` task

5. **Documentation**
   - README: Comprehensive nrepl CLI howto section
   - CLAUDE.md: One-liner reference (item 8)

### Example Usage
```bash
# Start MCP server with nrepl module
bb server --http --port 3001 --config bb-nrepl-system.edn --nickname nrepl-mcp

# Connect to nREPL server
bb nrepl connect 7888 --nickname my-repl --mcp nrepl-mcp

# Evaluate code
bb nrepl eval "(+ 1 2 3)" --mcp nrepl-mcp

# Load file
bb nrepl load-file src/my_app/core.clj --mcp nrepl-mcp

# List/disconnect
bb nrepl list --mcp nrepl-mcp
bb nrepl disconnect my-repl --mcp nrepl-mcp
```

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
