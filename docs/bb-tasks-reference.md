# bb Tasks & CLI Reference

> **AI Directive:** Check this file BEFORE writing curl commands, bash scripts, or JSON-RPC calls.
> There's probably a task for what you need.

**Last Updated:** 2026-01-10

---

## Quick Decision Tree

```
I want to...
│
├── Start/stop a server? ──────────► Server Management
├── Run tests? ────────────────────► Testing
├── Check code quality? ───────────► Verification
├── Call an MCP tool? ─────────────► bb mcp CLI
├── Eval code in nREPL? ───────────► bb nrepl CLI
├── Navigate code (definitions)? ──► bb clojure-lsp CLI
└── Something else? ───────────────► Run `bb tasks`
```

---

## DON'T DO THIS

| Instead of... | Use... |
|---------------|--------|
| `curl http://localhost:3000/mcp ...` | `bb mcp call <tool> '<json>'` |
| Writing bash to parse MCP responses | `bb mcp ... --pprint` |
| Manual JSON-RPC construction | `bb mcp call` or `bb nrepl eval` |
| `cat file.clj \| ...` for testing | `bb nrepl load-file <path>` |
| Custom HTTP health checks | `bb http:health` or `curl localhost:3000/health` |
| Writing test scripts | Check `bb test:*` tasks first |
| `bb server & sleep 2 && curl health` | `bb server:start-wait --nickname X` |
| `cmd1 && cmd2 && cmd3` chains | Separate tool calls or bb tasks |
| `cmd &` (backgrounding) | Bash tool with `run_in_background=true` |
| Manually fixing unbalanced parens | `bb fix-parens <file>` |

---

## Fixing Paren/Bracket Errors

**AI assistants frequently make paren/bracket mistakes.** Use `bb fix-parens` to auto-fix them.

### When to Use

| Situation | Action |
|-----------|--------|
| clj-kondo reports "Unmatched bracket" | Run `bb fix-parens <file>` |
| clj-kondo reports "Missing delimiter" | Run `bb fix-parens <file>` |
| Code won't parse | Run `bb fix-parens <file>` |
| After writing new Clojure code | Consider running `bb fix-parens <file>` |

### Usage

```bash
bb fix-parens <file>            # Fix parens in file (writes in place)
bb fix-parens src/foo.clj       # Example
```

**Output:**
- `✓ No changes needed` - File was already balanced
- `✓ Fixed parens in <file>` - Parens were fixed
- `✗ Failed to fix` - Couldn't auto-fix (manual intervention needed)

### Recommended: Use `bb lint-fix`

```bash
bb lint-fix <file>              # Lint, auto-fix parens if needed, re-lint
bb lint-fix src/foo.clj         # Example
bb lint-fix src/*.clj           # Multiple files
```

**What it does:**
1. Runs clj-kondo on file(s)
2. If paren/bracket errors found → runs parmezan to fix
3. Re-runs clj-kondo and shows final result
4. Exit code from final lint (so you see if manual fix still needed)

**Note:** `parmezan` uses heuristics to fix unbalanced delimiters. If the final lint still shows paren errors, manual intervention is needed.

---

## Command Chaining: Avoid It

**Problem:** Chained commands like `cmd && sleep && curl` require extra permission approval and are fragile.

**Solutions:**

### 1. Use `server:start-wait` for Server Startup

```bash
# Instead of:
bb server --http --nickname test & sleep 3 && curl localhost:3000/health

# Use:
bb server:start-wait --nickname test
```

### 2. Use Separate Tool Calls for Sequential Commands

```bash
# Instead of chaining:
git add . && git commit -m "msg" && git push

# Make 3 separate Bash calls:
# 1. git add .
# 2. git commit -m "msg"
# 3. git push
```

### 3. Use `run_in_background` for Background Tasks

When using the Bash tool, set `run_in_background=true` instead of using `&`:

```
# Instead of:
Bash: bb server --http &

# Use:
Bash with run_in_background=true: bb server --http
```

Then use `TaskOutput` tool to check on the background task later.

---

## Server Management

### Start Server (Foreground)

```bash
bb server                           # Stdio only (Claude Desktop)
bb server --http                    # HTTP on port 3000
bb server --http --port 8080        # HTTP on custom port
bb server --stdio --http            # Both transports
bb server --config <file>           # Custom config
bb server --nickname <name>         # Set nickname for CLI access
```

### Start Server and Wait for Health (Recommended)

```bash
bb server:start-wait --nickname <name>                    # Start and wait
bb server:start-wait --nickname <name> --config <file>    # With config
bb server:start-wait --nickname <name> --port 8080        # Custom port
bb server:start-wait --nickname <name> --timeout 60       # Custom timeout
```

**This is the preferred way to start servers** - it handles backgrounding and health checks automatically.

**Common patterns:**
```bash
bb server:start-wait --nickname code-browser --config bb-code-browser-dev-system.edn
bb server:start-wait --nickname scittle-dev --config bb-scittle-dev-system.edn
bb server:start-wait --nickname e2e-test --port 3001
```

### Stop Server

```bash
bb server:stop <nickname>           # Stop by nickname
bb server:stop <port>               # Stop by port
```

### Restart Server

```bash
bb server:restart <nickname>        # Stop + start same nickname
bb server:restart <nickname> --config <file>  # With new config
```

### List Servers & Ports

```bash
bb server:list                      # List running servers
bb server:ports <nickname>          # Show all ports for a server
bb server:ports --all               # Show ports for all servers
```

### Check Health

```bash
bb http:health                      # Quick health check
curl -s localhost:3000/health       # Direct (if needed)
```

---

## Verification (Before Commit)

```bash
bb lint                             # clj-kondo (MUST be 0 warnings)
bb lint-fix <file>                  # Lint + auto-fix parens + re-lint (RECOMMENDED)
bb format                           # cljfmt check
bb check                            # Both lint + format
bb fix-parens <file>                # Fix unbalanced parens only (manual)
```

**After editing a file:**
```bash
bb lint-fix <file>                  # Catches and fixes paren errors automatically
```

**Full verification workflow:**
```bash
bb lint && bb format && bb test:modules
```

---

## Testing

### Unit Tests (No Server Required)

```bash
bb test:modules                     # ALL module tests (use this!)
bb test:all                         # modules + bootstrap tests
```

**Specific modules:**
```bash
bb test:nrepl                       # nrepl module
bb test:http-core                   # http-core module
bb test:mcp-http                    # mcp-http module
bb test:mcp-stdio                   # mcp-stdio module
bb test:rest-api                    # rest-api module
bb test:bootstrap                   # CLI/bootstrap tests
bb test:sente-browser               # sente-browser module
bb test:clojure-lsp                 # clojure-lsp module (if exists)
```

### Integration Tests (Server Required)

```bash
# Start server first with --nickname e2e-test
bb server --http --nickname e2e-test

# Then run:
bb test:e2e                         # E2E MCP client tests
bb test:http                        # HTTP integration tests
```

---

## bb mcp CLI (MCP Tool Interaction)

**The primary way to interact with MCP tools from command line.**

### Discovery

```bash
bb mcp servers                      # List running servers
bb mcp tools --mcp <nick>           # List all tools
bb mcp tool <name> --mcp <nick>     # Show tool schema
bb mcp init --mcp <nick>            # Server info
```

### Calling Tools

```bash
bb mcp call <tool> '<json>' --mcp <nick>
```

**Examples:**
```bash
# Echo
bb mcp call echo.echo '{"message":"hello"}' --mcp my-server

# Calculate
bb mcp call calculate.calculate '{"expr":"(+ 1 2 3)"}' --mcp my-server

# Local eval (in MCP server's runtime)
bb mcp call local-eval.local-eval '{"code":"(+ 1 2)"}' --mcp my-server

# nREPL eval (in connected nREPL)
bb mcp call nrepl.nrepl-eval '{"code":"(+ 1 2)","connection":"browser-1"}' --mcp my-server

# clojure-lsp
bb mcp call clojure-lsp.clj-init '{"project-root":"/path/to/project"}' --mcp my-server
```

### Options

```bash
--mcp NAME        # Server nickname (required if multiple servers)
--port PORT       # Server port (alternative to nickname)
--pprint          # Pretty-print output
```

---

## bb nrepl CLI (nREPL Operations)

**For connecting to and evaluating in nREPL servers (JVM, browser, etc.).**

### Connection Management

```bash
bb nrepl list --mcp <nick>                    # List connections
bb nrepl connect <port> --mcp <nick>          # Connect to external nREPL
bb nrepl connect <port> --nickname app        # Connect with nickname
bb nrepl disconnect <name> --mcp <nick>       # Disconnect
bb nrepl status --mcp <nick>                  # Connection status
```

### Code Evaluation

```bash
bb nrepl eval "<code>" --mcp <nick>                         # Eval in default connection
bb nrepl eval "<code>" --connection <conn> --mcp <nick>     # Eval in specific connection
bb nrepl load-file <path> --mcp <nick>                      # Load file
bb nrepl load-file <path> --connection <conn> --mcp <nick>  # Load into specific connection
```

**Examples:**
```bash
# Simple eval
bb nrepl eval "(+ 1 2 3)" --mcp code-browser-dev

# Eval in browser
bb nrepl eval "(js/alert \"hi\")" --connection browser-1 --mcp code-browser-dev

# Load .cljs into browser
bb nrepl load-file modules/sente-browser/src/browser/code_browser.cljs --connection browser-1 --mcp code-browser-dev
```

### Introspection

```bash
bb nrepl namespaces --mcp <nick>              # List loaded namespaces
bb nrepl namespaces --prefix clojure --mcp <nick>  # Filter by prefix
bb nrepl vars <ns> --mcp <nick>               # List vars in namespace
bb nrepl meta <symbol> --mcp <nick>           # Get var metadata
bb nrepl value <symbol> --mcp <nick>          # Get var value
```

### Options

```bash
--mcp NAME          # MCP server nickname
--connection NAME   # nREPL connection nickname
--nickname NAME     # For naming new connections
--timeout MS        # Timeout (default 30000)
--pprint            # Pretty-print output
```

---

## bb clojure-lsp CLI (Code Navigation)

**For LSP operations: definitions, references, diagnostics.**

### Lifecycle

```bash
bb clojure-lsp start <project-root> --mcp <nick>  # Start LSP
bb clojure-lsp stop --mcp <nick>                   # Stop LSP
bb clojure-lsp status --mcp <nick>                 # Check status
bb clojure-lsp watch --mcp <nick>                  # Watch file changes
```

### Navigation

```bash
bb clojure-lsp definition <file> <line> <col> --mcp <nick>
bb clojure-lsp references <file> <line> <col> --mcp <nick>
bb clojure-lsp hover <file> <line> <col> --mcp <nick>
bb clojure-lsp implementations <file> <line> <col> --mcp <nick>
```

### Search & Analysis

```bash
bb clojure-lsp find-symbol <query> --mcp <nick>      # Search symbols
bb clojure-lsp symbols <file> --mcp <nick>           # Symbols in file
bb clojure-lsp diagnostics --mcp <nick>              # All diagnostics
bb clojure-lsp diagnostics <file> --mcp <nick>       # File diagnostics
bb clojure-lsp call-hierarchy <file> <line> <col> --mcp <nick>
```

### Refactoring

```bash
bb clojure-lsp completions <file> <line> <col> --mcp <nick>
bb clojure-lsp rename <file> <line> <col> <new-name> --mcp <nick>
bb clojure-lsp format <file> --mcp <nick>
bb clojure-lsp code-actions <file> <line> <col> --mcp <nick>
bb clojure-lsp refactor <cmd> <file> <line> <col> --mcp <nick>
```

**Refactor commands:** cycle-privacy, extract-function, introduce-let, thread-first, thread-last, clean-ns

---

## Utility Tasks

```bash
bb pprint                           # Pretty-print EDN from stdin
bb pprint <file>                    # Pretty-print EDN file
bb rebel-nrepl-client [port]        # Open iTerm2 with rebel-readline to nREPL
bb mcp-eval "<code>"                # Quick eval on running server
```

---

## Common Workflows

### Start Browser Dev Environment

```bash
# Terminal 1: Start server
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev

# Terminal 2: Open browser to http://localhost:8091

# Terminal 3: Find browser connection
bb nrepl list --mcp code-browser-dev

# Eval in browser
bb nrepl eval "(+ 1 2)" --connection browser-1 --mcp code-browser-dev
```

### Debug a Running Server

```bash
# List what's running
bb mcp servers

# See what tools are available
bb mcp tools --mcp <nick>

# Eval in the server's runtime
bb mcp call local-eval.local-eval '{"code":"(keys @bb-mcp-server.system/!state)"}' --mcp <nick>
```

### Test Code Changes

```bash
# 1. Verify code quality
bb lint && bb format

# 2. Run unit tests
bb test:modules

# 3. (Optional) Run E2E if server changes
bb server --http --nickname e2e-test &
bb test:e2e
bb server:stop e2e-test
```

### Initialize clojure-lsp for Code Browser

```bash
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev
```

---

## Task Categories Quick Reference

| Prefix | Purpose |
|--------|---------|
| `server:*` | Server lifecycle |
| `test:*` | Testing |
| `http:*` | HTTP integration helpers |
| `lint`, `format`, `check` | Code quality |
| `mcp`, `nrepl`, `clojure-lsp` | CLI tools |

---

## When All Else Fails

```bash
bb tasks                            # See all available tasks
bb mcp --help                       # MCP CLI help
bb nrepl --help                     # nREPL CLI help
bb clojure-lsp --help               # clojure-lsp CLI help
bb server --help                    # Server help
```

---

*This reference exists because the AI tends to forget about bb tasks and starts writing curl commands. Check here first!*
