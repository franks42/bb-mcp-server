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
;; Via local-eval, nrepl-eval, or direct REPL
(require '[bb-mcp-server.module.system :as system])
(system/load-new-module! "/path/to/external-module")
```

This enables a **minimal bootstrap pattern**:
1. Start server with just `local-eval` (or `nrepl`)
2. Load other modules dynamically via `local-eval`
3. Tools are registered immediately after loading

```clojure
;; system.edn - minimal bootstrap
{:modules ["local-eval"]}  ; Just local-eval!
```

Both `local-eval` and `nrepl` have full server access (no sandbox restrictions).

See `scripts/test_dynamic_load.clj` for a working example.

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
bb test           # Run all tests (modules + bootstrap)
bb test:modules  # Run module tests only
bb test:bootstrap # Run bootstrap configuration and CLI tests
bb lint           # clj-kondo (0 errors, 0 warnings)
bb format         # cljfmt
bb server:stop 3000  # Stop HTTP server
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
{:modules ["local-eval"]}  ; Minimal setup for local evaluation
```

## Status

Phase 5.5 complete. See `docs/design/streamable-http-implementation-plan.md`.

## License

See LICENSE.
