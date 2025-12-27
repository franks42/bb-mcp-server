# MCP Eval CLI User Guide

Command-line interface for evaluating code **within** the MCP server process.

## Overview

The `bb mcp-eval` CLI executes Clojure code directly inside the running MCP server's Babashka runtime. This enables:
- Inspecting and managing server state
- Loading modules dynamically
- Testing tools without external dependencies
- Debugging server behavior

## Architecture: mcp-eval vs nrepl

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              mcp-eval                                       │
│                   (Evaluate INSIDE the MCP Server)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐         HTTP          ┌──────────────────────────────┐  │
│   │              │       JSON-RPC        │        MCP Server            │  │
│   │  bb mcp-eval │ ───────────────────►  │  ┌────────────────────────┐  │  │
│   │              │                       │  │    local-eval tool     │  │  │
│   └──────────────┘                       │  │  ┌──────────────────┐  │  │  │
│                                          │  │  │ Babashka Runtime │  │  │  │
│                                          │  │  │   (same JVM)     │◄─┼──┼──┤
│                                          │  │  │                  │  │  │  │
│                                          │  │  │  YOUR CODE RUNS  │  │  │  │
│                                          │  │  │      HERE        │  │  │  │
│                                          │  │  └──────────────────┘  │  │  │
│                                          │  └────────────────────────┘  │  │
│                                          └──────────────────────────────┘  │
│                                                                             │
│   Use for: Server introspection, module management, tool testing           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              nrepl CLI                                      │
│                   (Evaluate in EXTERNAL Runtimes)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐   HTTP    ┌─────────────┐   TCP    ┌─────────────────┐  │
│   │              │  JSON-RPC │ MCP Server  │  nREPL   │ External REPL   │  │
│   │  bb nrepl    │ ────────► │             │ ───────► │                 │  │
│   │              │           │ nrepl module│ bencode  │  JVM Clojure    │  │
│   └──────────────┘           └─────────────┘          │  Babashka       │  │
│                                    │                  │  ClojureScript  │  │
│                               (proxy only)            │                 │  │
│                                                       │  YOUR CODE RUNS │  │
│                                                       │      HERE       │  │
│                                                       └─────────────────┘  │
│                                                                             │
│   Use for: Application debugging, remote eval, multi-runtime workflows     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key Difference:**
- **mcp-eval**: Code runs in the MCP server's own Babashka process
- **nrepl**: Code runs in separate Clojure/Babashka/ClojureScript processes

## Quick Start

```bash
# 1. Start MCP server (ephemeral port with nickname)
bb server --http 0 --nickname dev

# 2. Evaluate code in the server
bb mcp-eval "(+ 1 2 3)"
# => 6

# 3. Inspect server state
bb mcp-eval "(require '[bb-mcp-server.registry :as r]) (count (r/list-tools))"
# => 16
```

## Usage

```bash
bb mcp-eval "<code>" [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--nickname NAME` | Server nickname | `bb-bootstrap-system-1` |
| `--port PORT` | Server port number | auto-discover |
| `--output MODE` | Output mode (see below) | `result` |
| `--pprint` | Pretty-print output | false |

## Output Modes

### result (default)

Returns only the evaluation result as EDN.

```bash
bb mcp-eval "(do (println \"hello\") 42)"
# => 42
```

### full

Returns structured map with status, result, stdout, stderr.

```bash
bb mcp-eval "(do (println \"hello\") 42)" --output full --pprint
# {:status :ok,
#  :result 42,
#  :stdout "hello\n",
#  :stderr ""}
```

### pipe

Routes stdout/stderr to their respective streams, then prints result.

```bash
bb mcp-eval "(do (println \"hello\") (.println System/err \"warning\") 42)" --output pipe
# hello      (to stdout)
# warning    (to stderr)
# 42         (result to stdout)
```

## Use Cases

### Server Introspection

Inspect the MCP server's internal state:

```bash
# List registered tools
bb mcp-eval "(require '[bb-mcp-server.registry :as r]) (mapv :name (r/list-tools))"

# Check loaded modules
bb mcp-eval "(require '[bb-mcp-server.module.system :as sys]) (sys/list-modules)"

# View server configuration
bb mcp-eval "@bb-mcp-server.config/*config*"
```

### Dynamic Module Loading

Load modules at runtime without restarting:

```bash
# Load a module dynamically
bb mcp-eval "(require '[bb-mcp-server.module.system :as sys]) (sys/load-new-module! \"/path/to/module\")"

# Verify it loaded
bb mcp-eval "(require '[bb-mcp-server.registry :as r]) (mapv :name (r/list-tools))"
```

### Tool Testing

Test tools directly within the server:

```bash
# Call a tool's implementation directly
bb mcp-eval "(require '[calculate.core :as calc]) (calc/calculate {:expr \"(+ 1 2 3)\"})"

# Inspect tool schema
bb mcp-eval "(require '[bb-mcp-server.registry :as r]) (r/get-tool \"calculate.calculate\")"
```

### Debugging

Debug server behavior:

```bash
# Check current namespace
bb mcp-eval "*ns*"

# Inspect vars
bb mcp-eval "(ns-publics 'bb-mcp-server.registry)"

# Test expressions interactively
bb mcp-eval "(require '[clojure.string :as str]) (str/upper-case \"hello\")"
```

### Error Handling

Errors are reported clearly:

```bash
# Division by zero
bb mcp-eval "(/ 1 0)" --output full
# {:status :error,
#  :error "Divide by zero",
#  :stdout "",
#  :stderr "",
#  :stacktrace "..."}

# With pipe mode (error to stderr, exit code 1)
bb mcp-eval "(throw (ex-info \"oops\" {}))" --output pipe
# Error: oops
# (exit code 1)
```

## Programmatic Use

Use the MCP client library in your own scripts:

```clojure
(require '[bb-mcp-server.mcp-client :as client])

;; Simple eval
(client/eval-code! "dev" "(+ 1 2)")  ; => 3

;; With EDN parsing (preserves sets, keywords)
(client/eval-code! 3000 "#{:a :b}" {:output-format :edn})  ; => #{:a :b}

;; Full response with stdout/stderr
(client/eval-code-full! "dev" "(do (println \"x\") 42)")
; => {:status :ok, :result 42, :stdout "x\n", :stderr ""}

;; Load a file
(client/load-file! "dev" "src/my_app/core.clj")
```

## Server Discovery

The CLI automatically discovers servers via port files:

```bash
# List running servers
ls -la .ports/

# Port files contain JSON
cat .ports/.dev
# {"port": 12345, "transport": "http", "pid": 67890}
```

### Discovery Priority

1. `--port PORT` - Use explicit port
2. `--nickname NAME` - Look up in `.ports/.NAME`
3. Default - Look for `bb-bootstrap-system-1`

## Comparison: When to Use Each CLI

| Task | Use `mcp-eval` | Use `nrepl` |
|------|----------------|-------------|
| Inspect MCP server state | ✓ | |
| Load modules dynamically | ✓ | |
| Test tool implementations | ✓ | |
| Debug server issues | ✓ | |
| Evaluate in JVM Clojure | | ✓ |
| Connect to running app | | ✓ |
| Multiple runtime targets | | ✓ |
| ClojureScript eval | | ✓ |

## Troubleshooting

### "Server not found"

The MCP server isn't running or the nickname is wrong:

```bash
# Check running servers
ls -la .ports/

# Start a server
bb server --http 0 --nickname dev
```

### "Evaluation Error"

The code threw an exception:

```bash
# Use full output to see details
bb mcp-eval "(bad-code)" --output full --pprint
```

### Timeout

Long-running code may timeout. Currently no `--timeout` option (use nrepl for long operations).

## Configuration Files

### bb-bootstrap-system.edn

Minimal config for local evaluation:

```clojure
{:modules ["local-eval"]}
```

### .ports/<nickname>

Port files are created when starting with `--nickname`:

```json
{"port": 12345, "transport": "http", "pid": 67890}
```

## Related

- [README.md](../README.md) - Project overview
- [local-eval module](../modules/local-eval/README.md) - Local eval tool details
- [nrepl CLI guide](./mcp-nrepl-cli-user-guide.md) - Remote nREPL operations
