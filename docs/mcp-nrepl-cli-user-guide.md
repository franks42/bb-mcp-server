# MCP nREPL CLI User Guide

Command-line interface for remote nREPL operations via MCP server.

## Overview

The `bb nrepl` CLI provides shell access to nREPL servers through MCP. This enables:
- Connecting to running Clojure/Babashka/ClojureScript REPLs
- Evaluating code from the command line or scripts
- Loading files into remote REPLs
- Managing multiple nREPL connections

## Quick Start

```bash
# 1. Start MCP server with nrepl module
bb server --http --port 3001 --config bb-nrepl-system.edn --nickname nrepl-mcp

# 2. Start an nREPL server (or use existing one)
bb --nrepl-server 7888 &

# 3. Connect via CLI
bb nrepl connect 7888 --mcp nrepl-mcp

# 4. Evaluate code
bb nrepl eval "(+ 1 2 3)" --mcp nrepl-mcp
# => 6
```

## Subcommands

### connect

Connect to an nREPL server.

```bash
# By port (localhost)
bb nrepl connect 7888 --mcp nrepl-mcp

# By host:port
bb nrepl connect 192.168.1.100:7888 --mcp nrepl-mcp

# From .nrepl-port file
bb nrepl connect .nrepl-port --mcp nrepl-mcp

# With nickname
bb nrepl connect 7888 --nickname my-app --mcp nrepl-mcp
```

### disconnect

Disconnect from an nREPL server.

```bash
# Disconnect specific connection
bb nrepl disconnect my-app --mcp nrepl-mcp

# Disconnect active connection
bb nrepl disconnect --mcp nrepl-mcp
```

### list

List all nREPL connections.

```bash
bb nrepl list --mcp nrepl-mcp
# Connections: 2
# Active: my-app
#   conn-1: localhost:7888 (my-app) *ACTIVE*
#   conn-2: localhost:9999 (other)
```

### status

Show active connection status.

```bash
bb nrepl status --mcp nrepl-mcp
# Status: connected
# Server: localhost:7888
# Connections: 2
```

### eval

Evaluate Clojure code.

```bash
# Simple evaluation
bb nrepl eval "(+ 1 2 3)" --mcp nrepl-mcp
# => 6

# With specific connection
bb nrepl eval "(System/getenv \"HOME\")" --connection my-app --mcp nrepl-mcp

# Pretty-print result
bb nrepl eval "(ns-publics 'clojure.string)" --pprint --mcp nrepl-mcp

# Full response with stdout/stderr
bb nrepl eval "(do (println \"hello\") 42)" --output full --mcp nrepl-mcp
```

### load-file

Load and evaluate a Clojure file.

```bash
bb nrepl load-file src/my_app/core.clj --mcp nrepl-mcp
# Loaded: src/my_app/core.clj
```

### help

Show usage information.

```bash
bb nrepl help
```

## Options

| Option | Description | Default |
|--------|-------------|---------|
| `--mcp NAME` | MCP server nickname | `bb-nrepl-system-1` |
| `--connection NAME` | nREPL connection to use | active connection |
| `--nickname NAME` | Nickname for new connection | auto-generated |
| `--output MODE` | Output mode (see below) | `result` |
| `--pprint` | Pretty-print output | false |
| `--timeout MS` | Timeout in milliseconds | 30000 |

## Output Modes

### result (default)

Returns only the evaluation result.

```bash
bb nrepl eval "(do (println \"hello\") 42)" --mcp nrepl-mcp
# => 42
```

### full

Returns structured JSON with status, value, stdout, stderr.

```bash
bb nrepl eval "(do (println \"hello\") 42)" --output full --mcp nrepl-mcp
# {
#   "status": "success",
#   "value": "42",
#   "value-parsed": 42,
#   "out": "hello\n"
# }
```

### pipe

Routes stdout/stderr to their respective streams, then prints result.

```bash
bb nrepl eval "(do (println \"hello\") (.println System/err \"oops\") 42)" --output pipe --mcp nrepl-mcp
# hello      (to stdout)
# oops       (to stderr)
# 42         (result to stdout)
```

## Use Cases

### Interactive Development

Evaluate code in a running application:

```bash
# Check current state
bb nrepl eval "(count @app/users)" --mcp nrepl-mcp

# Reload a namespace
bb nrepl eval "(require 'my-app.core :reload)" --mcp nrepl-mcp

# Call a function
bb nrepl eval "(my-app.api/health-check)" --mcp nrepl-mcp
```

### Scripting

Use in shell scripts:

```bash
#!/bin/bash
MCP="nrepl-mcp"

# Get user count
count=$(bb nrepl eval "(count @app/users)" --mcp $MCP)
echo "Users: $count"

# Reload if needed
if [ "$1" = "--reload" ]; then
  bb nrepl eval "(require 'my-app.core :reload-all)" --mcp $MCP
fi
```

### AI Assistant Integration

AI assistants can use the CLI for code execution:

```bash
# Inspect namespaces
bb nrepl eval "(ns-publics 'my-app.core)" --pprint --mcp nrepl-mcp

# Check dependencies
bb nrepl eval "(keys (ns-aliases *ns*))" --mcp nrepl-mcp

# Run tests
bb nrepl eval "(clojure.test/run-tests 'my-app.core-test)" --output full --mcp nrepl-mcp
```

### Multiple Connections

Connect to multiple REPLs and switch between them:

```bash
# Connect to backend
bb nrepl connect 7888 --nickname backend --mcp nrepl-mcp

# Connect to worker
bb nrepl connect 7889 --nickname worker --mcp nrepl-mcp

# Eval on backend
bb nrepl eval "(db/query \"SELECT count(*) FROM users\")" --connection backend --mcp nrepl-mcp

# Eval on worker
bb nrepl eval "(count @job-queue)" --connection worker --mcp nrepl-mcp
```

## Architecture

```
┌──────────────┐     HTTP/MCP     ┌──────────────┐     TCP/nREPL     ┌──────────────┐
│  bb nrepl    │ ───────────────► │  MCP Server  │ ────────────────► │ nREPL Server │
│  CLI         │     JSON-RPC     │  (nrepl mod) │     bencode       │ (JVM/bb/cljs)│
└──────────────┘                  └──────────────┘                   └──────────────┘
```

The CLI communicates with the MCP server via HTTP JSON-RPC. The MCP server's nrepl module manages TCP connections to nREPL servers using the bencode protocol.

## Troubleshooting

### "Server not found"

The MCP server isn't running or the nickname is wrong:

```bash
# Check running servers
ls -la .ports/

# Verify server is running
bb server --http --port 3001 --config bb-nrepl-system.edn --nickname nrepl-mcp
```

### "No active connection"

Connect to an nREPL server first:

```bash
bb nrepl connect 7888 --mcp nrepl-mcp
```

### Timeout errors

Increase timeout for long-running operations:

```bash
bb nrepl eval "(slow-operation)" --timeout 60000 --mcp nrepl-mcp
```

### Connection refused

The nREPL server isn't running or wrong port:

```bash
# Check if nREPL is listening
lsof -i :7888

# Start an nREPL server
bb --nrepl-server 7888
```

## Configuration Files

### bb-nrepl-system.edn

Bootstrap config for nREPL CLI operations:

```clojure
{:modules ["nrepl" "local-eval"]}
```

### .ports/<nickname>

Port files are created automatically when starting with `--nickname`:

```json
{"port": 3001, "transport": "http", "pid": 12345}
```

## Related

- [README.md](../README.md) - Project overview
- [nrepl module](../modules/nrepl/README.md) - nREPL module details
- [mcp-eval CLI](../README.md#mcp-eval-cli-tool) - Local eval CLI
