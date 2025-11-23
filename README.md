# bb-mcp-server

Modular MCP server in Clojure/Babashka.

## Quick Start

```bash
# stdio (Claude Code)
bb server:stdio

# HTTP (Streamable HTTP spec 2025-03-26)
bb server:streamable 3000
```

## Modules

| Module | Description |
|--------|-------------|
| `calculate` | Math expressions (100+ functions) |
| `nrepl` | Remote Clojure REPL |
| `local-eval` | Server-side code execution |
| `echo` | Echo for testing |
| `hello` | Greeting tool |
| `strings` | String concat |
| `math` | Basic arithmetic |

See `modules/*/README.md` for details.

## Configuration

`system.edn`:
```clojure
{:modules ["hello" "echo" "calculate" "nrepl" "local-eval"]}
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
bb test           # Run tests
bb lint           # clj-kondo
bb server:stop 3000  # Stop HTTP server
```

## Status

Phase 5.5 complete. See `docs/design/streamable-http-implementation-plan.md`.

## License

See LICENSE.
