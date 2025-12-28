# bb-mcp-server Project Context

**Current State: December 27, 2025**

## Just Completed: v1.7.0 - Scittle-nREPL Dev Environment

### What Was Done
1. **Browser-based ClojureScript REPL** via Scittle + sente-lite
   - Bootstrap config `bb-scittle-dev-system.edn` with 5 modules
   - WebSocket browser connections via sente-browser module
   - nREPL proxy with browser routing

2. **Shadow-cljs style API**
   - `(browser/list)` - List connected browsers
   - `(browser/repl :browser-id)` - Switch to browser REPL
   - `:cljs/quit` - Return to bb

3. **Architecture**
   ```
   rebel-readline → nrepl-proxy:1667 → sente-browser:8090 → Browser (Scittle)
   ```

4. **Full workflow verified**
   - Playwright tests: Browser connects, eval works
   - Manual test: rebel → browser → eval → quit flow confirmed

### Example Usage
```bash
# Start scittle-dev server
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev

# Open browser to bootstrap page
open http://127.0.0.1:8091

# Connect rebel-readline to proxy
bb rebel-nrepl-client 1667

# In rebel:
(browser/list)           ; List connected browsers
(browser/repl :browser-1) ; Switch to browser REPL
(+ 1 2 3)                 ; Eval in browser → 6
(js/alert "Hello!")       ; Browser JS interop
:cljs/quit                ; Return to bb
```

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
