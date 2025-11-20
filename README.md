# bb-mcp-server

A production-ready Model Context Protocol (MCP) server implemented in Clojure/Babashka.

## Features

✅ **Phase 1.2 Complete - Minimal MCP Server:**
- JSON-RPC 2.0 message parsing and validation
- Core handler router with initialization state management
- MCP protocol handlers: `initialize`, `tools/list`, `tools/call`
- Example tool: `hello` (greeting tool)
- stdio transport for Claude Code integration
- Comprehensive telemetry (structured logging on all paths)
- 94 tests, 393 assertions, all passing

## Quick Start

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) installed

### Run the Server

```bash
# Make the server script executable
chmod +x server.clj

# Run the server (stdio mode for Claude Code)
./server.clj
```

The server reads JSON-RPC requests from stdin and writes responses to stdout.

### Testing the Server

```bash
# Test with echo and pipes
echo '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"1.0","clientInfo":{"name":"test","version":"1.0"}},"id":1}' | ./server.clj 2>/dev/null

# Run the full test suite
bb test

# Run the test harness (tests all RPC handlers)
bb test_rpc_handlers.clj
```

## Claude Code Configuration

To use this server with Claude Code, add it to your MCP settings:

### Option 1: Using bb server command

In your Claude Code MCP configuration file (e.g., `~/.config/claude-code/mcp.json` or your project's `.claude/mcp.json`):

```json
{
  "mcpServers": {
    "bb-mcp-server": {
      "command": "bb",
      "args": ["server.clj"],
      "cwd": "/path/to/bb-mcp-server"
    }
  }
}
```

### Option 2: Using the executable script

```json
{
  "mcpServers": {
    "bb-mcp-server": {
      "command": "/path/to/bb-mcp-server/server.clj"
    }
  }
}
```

### Verify Connection

Once configured, you can verify the server is working in Claude Code by:

1. Starting Claude Code
2. The server should appear in your MCP servers list
3. Try using the `hello` tool:
   ```
   Use the hello tool to greet "World"
   ```

Expected response:
```
Hello, World!
```

## Available Tools

### hello

Returns a greeting message.

**Parameters:**
- `name` (string, required): Name to greet

**Example:**
```json
{
  "name": "hello",
  "arguments": {
    "name": "Alice"
  }
}
```

**Response:**
```
Hello, Alice!
```

## Architecture

```
bb-mcp-server/
├── src/bb_mcp_server/
│   ├── protocol/
│   │   ├── message.clj        # JSON-RPC 2.0 parsing and formatting
│   │   └── router.clj          # Request routing and state management
│   ├── handlers/
│   │   ├── initialize.clj      # initialize method handler
│   │   ├── tools_list.clj      # tools/list method handler
│   │   └── tools_call.clj      # tools/call method dispatcher
│   ├── tools/
│   │   └── hello.clj           # Example hello tool
│   ├── transport/
│   │   └── stdio.clj           # stdio transport for Claude Code
│   └── test_harness.clj        # Test harness for RPC handlers
├── test/                       # Comprehensive test suite
├── server.clj                  # Main server entry point
├── test_rpc_handlers.clj       # RPC handler test script
└── bb.edn                      # Babashka project config
```

## Development

### Running Tests

```bash
# Run all tests
bb test

# Lint code (check bb.edn for lint task)
bb tasks

# Run all checks
bb check
```

### Adding New Tools

1. Create a new file in `src/bb_mcp_server/tools/`
2. Define the tool definition (name, description, inputSchema)
3. Implement the handler function
4. Create an `init!` function that registers both:
   ```clojure
   (tools-list/register-tool! tool-definition)
   (tools-call/register-handler! "tool-name" handler-fn)
   ```
5. Call `init!` in `test_harness.clj` setup function
6. Write comprehensive tests

See `src/bb_mcp_server/tools/hello.clj` for a reference implementation.

## Telemetry

The server uses structured logging (via Timbre) for all operations:

- **INFO**: Normal operations (startup, requests, completions)
- **DEBUG**: Detailed trace info (parsing, routing, handler dispatch)
- **WARN**: Non-fatal errors (validation failures, not initialized)
- **ERROR**: Fatal errors (exceptions, setup failures)

Logs are written to stderr and include structured data (maps) for easy parsing.

## Project Status

**Phase 1.2 - Minimal MCP Server: COMPLETE ✅**

- ✅ JSON-RPC 2.0 message protocol
- ✅ Message parsing and validation
- ✅ Core handler router
- ✅ initialize handler
- ✅ tools/list handler
- ✅ tools/call dispatcher
- ✅ Example hello tool
- ✅ Telemetry on all paths
- ✅ stdio transport
- ✅ Comprehensive test suite (94 tests, 393 assertions)

**Next Phases:**
- Phase 2: Tool Registry & Error Handling
- Phase 3: Multi-Transport (HTTP, REST)
- Phase 4: Security & Production Features
- Phase 5: Production Readiness

See `IMPLEMENTATION_PLAN.md` for detailed task breakdown.

## License

See LICENSE file for details.