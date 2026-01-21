# bb-mcp-server

Modular MCP server in Clojure/Babashka.

## Quick Start

```bash
# stdio (Claude Code)
bb server

# HTTP (Streamable HTTP spec 2025-03-26)
bb server --http --nickname my-server --config system.edn

# Server management
bb server:start-wait --nickname my-server --config system.edn  # Start + wait for health
bb server:stop my-server                                        # Stop by nickname
bb server:restart my-server                                     # Stop + start
bb server:list                                                  # List running servers
bb server:ports my-server                                       # Show all ports
```

All services use **ephemeral ports** by default (OS-assigned). Port discovery via `.ports/<nickname>.json`.

## Modules

| Module | Description |
|--------|-------------|
| `calculate` | Math expressions (100+ functions) |
| `mcp-nrepl` | Remote Clojure REPL via nREPL protocol |
| `mcp-local-eval` | Server-side code execution |
| `echo` | Echo for testing |
| `hello` | Greeting tool |
| `strings` | String concat |
| `math` | Basic arithmetic |

See `modules/*/README.md` for details.

## Configuration

`system.edn`:
```clojure
{:modules ["hello" "echo" "calculate" "mcp-nrepl" "mcp-local-eval"]}
```

## External Modules

Load modules from outside the project directory:

```bash
# Single module or collection of modules
BB_MCP_EXTERNAL_MODULES=/path/to/my-module bb server --http

# Multiple paths (colon-separated)
BB_MCP_EXTERNAL_MODULES=/path/to/module1:/path/to/modules-collection bb server --http
```

Auto-detects:
- **Single module**: Directory containing `module.edn`
- **Collection**: Directory with subdirectories containing `module.edn`

Modules must be listed in `system.edn` to be loaded at startup.

## Dynamic Module Loading

Load modules at runtime (after server is already running):

```clojure
;; Via mcp-local-eval, nrepl-eval, or direct REPL
(require '[bb-mcp-server.module.system :as system])
(system/load-new-module! "/path/to/external-module")
```

This enables a **minimal bootstrap pattern**:
1. Start server with just `mcp-local-eval` (or `mcp-nrepl`)
2. Load other modules dynamically via `mcp-local-eval`
3. Tools are registered immediately after loading

```clojure
;; system.edn - minimal bootstrap
{:modules ["mcp-local-eval"]}  ; Just local-eval!
```

Both `local-eval` and `nrepl` have full server access (no sandbox restrictions).

See `scripts/test_dynamic_load.clj` for a working example.

## mcp-eval CLI Tool

The `mcp-eval` script evaluates Clojure code on a running MCP server via the `local-eval` tool.

### Basic Usage

```bash
# Start server with ephemeral port and nickname
bb server --http 0 --nickname dev

# Evaluate code (auto-discovers server by nickname)
bb mcp-eval "(+ 1 2 3)"                    # => 6
bb mcp-eval "(range 5)" --nickname dev     # => (0 1 2 3 4)
bb mcp-eval "(System/getenv \"HOME\")"     # => "/Users/..."
```

### Output Modes

```bash
# result (default): Just the result value
bb mcp-eval "(do (println \"hi\") 42)"
# => 42

# full: Structured map with status, result, stdout, stderr
bb mcp-eval "(do (println \"hi\") 42)" --output full
# => {:status :ok, :result 42, :stdout "hi\n", :stderr ""}

# pipe: stdout→stdout, stderr→stderr, result to stdout
bb mcp-eval "(do (println \"hi\") 42)" --output pipe
# hi
# 42
```

### Options

| Option | Description |
|--------|-------------|
| `--nickname NAME` | Server nickname (default: `bb-bootstrap-system-1`) |
| `--port PORT` | Server port number |
| `--output MODE` | Output mode: `result`, `full`, `pipe` |
| `--pprint` | Pretty-print EDN output |

### Examples

```bash
# Pretty-print complex data
bb mcp-eval "(ns-publics 'clojure.string)" --pprint

# Check registered tools
bb mcp-eval "(require '[bb-mcp-server.registry :as r]) (mapv :name (r/list-tools))"

# Inspect server state
bb mcp-eval "*ns*"  # => #object[sci.lang.Namespace user]

# Error handling (exits with code 1)
bb mcp-eval "(/ 1 0)" --output pipe  # Prints error to stderr
```

### Programmatic Use

```clojure
(require '[bb-mcp-server.mcp-client :as client])

;; Simple eval
(client/eval-code! "dev" "(+ 1 2)")  ; => 3

;; With EDN parsing (preserves sets, keywords)
(client/eval-code! 3000 "#{:a :b}" {:output-format :edn})  ; => #{:a :b}

;; Full response with stdout/stderr
(client/eval-code-full! "dev" "(do (println \"x\") 42)")
; => {:status :ok, :result 42, :stdout "x\n", :stderr ""}
```

## MCP CLI (Exploration & Testing)

Generic CLI for exploring and testing any MCP tool:

```bash
bb mcp servers                           # List running servers
bb mcp tools --mcp dev                   # List available tools
bb mcp call echo.echo '{"message":"hi"}' # Call any tool with JSON args
bb mcp init                              # Get server info
```

Run `bb mcp help` for all subcommands. See also `bb test:e2e` for automated E2E tests.

## nrepl CLI Tool

The `nrepl` CLI provides command-line access to remote nREPL servers via MCP.
Connect to any nREPL server (JVM Clojure, Babashka, ClojureScript) and evaluate code.

### Setup

```bash
# Start MCP server with nrepl module
bb server --http --port 3001 --config bb-nrepl-system.edn --nickname nrepl-mcp

# Start your nREPL server (or use an existing one)
bb --nrepl-server 7888 &
```

### Basic Usage

```bash
# Connect to nREPL server
bb nrepl connect 7888 --nickname my-app --mcp nrepl-mcp

# Evaluate code
bb nrepl eval "(+ 1 2 3)" --mcp nrepl-mcp
# => 6

# Load a file
bb nrepl load-file src/my_app/core.clj --mcp nrepl-mcp

# List connections
bb nrepl list --mcp nrepl-mcp

# Disconnect
bb nrepl disconnect my-app --mcp nrepl-mcp
```

### Subcommands

| Subcommand | Description |
|------------|-------------|
| `connect <target>` | Connect to nREPL server (port, host:port, or .nrepl-port) |
| `disconnect [name]` | Disconnect from nREPL server |
| `list` | List all connections |
| `status` | Show active connection status |
| `eval <code>` | Evaluate Clojure code |
| `load-file <path>` | Load and evaluate a Clojure file |
| `help` | Show help |

### Options

| Option | Description |
|--------|-------------|
| `--mcp NAME` | MCP server nickname (default: `bb-nrepl-system-1`) |
| `--connection NAME` | nREPL connection nickname (for eval/load-file) |
| `--nickname NAME` | Nickname for new connection (for connect) |
| `--output MODE` | Output mode: `result`, `full`, `pipe` |
| `--pprint` | Pretty-print output |
| `--timeout MS` | Timeout in milliseconds (default: 30000) |

### Output Modes

```bash
# result (default): Just the value
bb nrepl eval "(do (println \"hi\") 42)" --mcp nrepl-mcp
# => 42

# full: Complete response with stdout/stderr
bb nrepl eval "(do (println \"hi\") 42)" --mcp nrepl-mcp --output full --pprint
# => {:status "success", :value "42", :out "hi\n", ...}

# pipe: stdout→stdout, stderr→stderr, value to stdout
bb nrepl eval "(do (println \"hi\") 42)" --mcp nrepl-mcp --output pipe
# hi
# 42
```

## rebel-nrepl-client

Open iTerm2 with rebel-readline connected to an nREPL server:

```bash
# Start MCP server with nREPL test server
bb server --http --config bb-nrepl-server-system.edn

# Open iTerm2 with rebel-readline
bb rebel-nrepl-client 7888
```

## Scittle-nREPL (Browser REPL)

Eval ClojureScript in the browser from rebel-readline, shadow-cljs style.

**Architecture:**
```
rebel-readline → nrepl-proxy:1667 → sente-browser:8090 → Browser (Scittle)
```

### Setup

```bash
# Start scittle-dev server (requires sente-lite bundle)
bb server --http --config bb-scittle-dev-system.edn --nickname scittle-dev

# Open browser to bootstrap page
open http://127.0.0.1:8091

# Connect rebel-readline to proxy
bb rebel-nrepl-client 1667
```

### Usage (in rebel)

```clojure
(browser/list)            ; List connected browsers
(browser/repl :browser-1) ; Switch to browser REPL

;; Now you're in the browser!
(+ 1 2 3)                 ; => 6
(js/alert "Hello!")       ; Shows browser alert
js/navigator.userAgent    ; Access browser APIs

:cljs/quit                ; Return to bb
```

### Configuration

`bb-scittle-dev-system.edn`:
```clojure
{:modules ["mcp-local-eval" "nrepl" "nrepl-test-server"
           "sente-browser" "nrepl-proxy-server"]
 :config {"sente-browser" {:ws-port 8090
                           :bootstrap-port 8091
                           :bundle-path "/path/to/sente-lite-nrepl.cljs"}
          "nrepl-proxy-server" {:port 1667
                                :write-port-file? true}}}
```

## Claude Code

```json
{
  "mcpServers": {
    "bb-mcp": {
      "command": "bb",
      "args": ["server:stdio"],
      "cwd": "/path/to/bb-mcp-server"
    }
  }
}
```

## Development

```bash
bb test:all       # Run all tests (modules + bootstrap)
bb test:modules   # Run module tests only
bb test:bootstrap # Run bootstrap configuration tests
bb test:e2e       # Run E2E MCP client tests (requires running server)
bb lint           # clj-kondo (0 errors, 0 warnings)
bb format         # cljfmt
bb server:stop my-server  # Stop server by nickname
bb server:list            # List running servers
```

### Bootstrap Configuration

The server supports minimal bootstrap configurations for quick startup:

```bash
# Bootstrap with minimal module set (local-eval only)
bb bootstrap-server

# Custom bootstrap config
bb server --config bb-bootstrap-system.edn

# With nickname for port file identification
bb server --config bb-bootstrap-system.edn --nickname my-server
```

Bootstrap config file (`bb-bootstrap-system.edn`):
```clojure
{:modules ["mcp-local-eval"]}  ; Minimal setup for local evaluation
```

## Status

Phase 20 complete. MCP CLI & E2E testing infrastructure in place.

## License

See LICENSE.
