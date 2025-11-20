# MCP Protocol Specification - bb-mcp-server

**Version:** 1.0
**Status:** Draft
**Date:** 2025-11-20

## Overview

This MCP (Model Context Protocol) server implements JSON-RPC 2.0 for communication between AI models and tool servers. The protocol supports three core methods: `initialize`, `tools/list`, and `tools/call`.

---

## JSON-RPC 2.0 Foundation

All messages follow [JSON-RPC 2.0](https://www.jsonrpc.org/specification) specification.

### Request Format

```json
{
  "jsonrpc": "2.0",
  "method": "method_name",
  "params": { },
  "id": 1
}
```

**Fields:**
- `jsonrpc` (required): Must be exactly `"2.0"`
- `method` (required): String naming the method to invoke
- `params` (optional): Object containing method parameters
- `id` (required): String or number identifying the request

### Success Response Format

```json
{
  "jsonrpc": "2.0",
  "result": { },
  "id": 1
}
```

**Fields:**
- `jsonrpc` (required): Must be exactly `"2.0"`
- `result` (required): Method result (object, array, string, number, boolean, or null)
- `id` (required): Matches the request id

### Error Response Format

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32600,
    "message": "Invalid Request",
    "data": { }
  },
  "id": 1
}
```

**Fields:**
- `jsonrpc` (required): Must be exactly `"2.0"`
- `error` (required): Error object
  - `code` (required): Integer error code (see Error Codes below)
  - `message` (required): Short error description
  - `data` (optional): Additional error information
- `id` (required): Matches request id, or `null` if request id couldn't be determined

---

## Error Codes

### Standard JSON-RPC 2.0 Errors

| Code | Message | Meaning |
|------|---------|---------|
| -32700 | Parse error | Invalid JSON received |
| -32600 | Invalid Request | JSON-RPC structure invalid |
| -32601 | Method not found | Method does not exist |
| -32602 | Invalid params | Invalid method parameters |
| -32603 | Internal error | Internal server error |

### MCP-Specific Errors

| Code | Message | Meaning |
|------|---------|---------|
| -32000 | Tool not found | Requested tool does not exist |
| -32001 | Tool execution failed | Tool threw an exception |
| -32002 | Invalid tool params | Tool parameters invalid |
| -32003 | Server not initialized | `initialize` must be called first |

---

## MCP Methods

### 1. initialize

**Purpose:** Handshake between client and server. Must be called before any other methods.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "protocolVersion": "1.0",
    "clientInfo": {
      "name": "claude-code",
      "version": "1.0.0"
    }
  },
  "id": 1
}
```

**Parameters:**
- `protocolVersion` (required): Protocol version client supports (string)
- `clientInfo` (required): Client identification
  - `name` (required): Client name (string)
  - `version` (optional): Client version (string)

**Success Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "protocolVersion": "1.0",
    "serverInfo": {
      "name": "bb-mcp-server",
      "version": "0.1.0"
    },
    "capabilities": {
      "tools": true,
      "dynamicToolRegistration": false
    }
  },
  "id": 1
}
```

**Response Fields:**
- `protocolVersion` (required): Protocol version server supports
- `serverInfo` (required): Server identification
  - `name` (required): Server name
  - `version` (required): Server version
- `capabilities` (required): Server capabilities
  - `tools` (required): Whether server supports tools (boolean)
  - `dynamicToolRegistration` (optional): Whether tools can be registered at runtime (boolean)

**Errors:**
- `-32602` Invalid params: Missing or invalid `protocolVersion` or `clientInfo`

---

### 2. tools/list

**Purpose:** Retrieve list of available tools from the server.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "params": {},
  "id": 2
}
```

**Parameters:** None (empty object or omit)

**Success Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "tools": [
      {
        "name": "hello",
        "description": "Returns a greeting message",
        "inputSchema": {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Name to greet"
            }
          },
          "required": ["name"]
        }
      }
    ]
  },
  "id": 2
}
```

**Response Fields:**
- `tools` (required): Array of tool definitions
  - `name` (required): Tool identifier (string)
  - `description` (required): Human-readable description (string)
  - `inputSchema` (required): JSON Schema defining tool parameters (object)

**Errors:**
- `-32003` Server not initialized: Must call `initialize` first

---

### 3. tools/call

**Purpose:** Execute a specific tool with given parameters.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "hello",
    "arguments": {
      "name": "World"
    }
  },
  "id": 3
}
```

**Parameters:**
- `name` (required): Tool name to call (string)
- `arguments` (required): Tool parameters matching tool's `inputSchema` (object)

**Success Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Hello, World!"
      }
    ]
  },
  "id": 3
}
```

**Response Fields:**
- `content` (required): Array of content items
  - `type` (required): Content type (`"text"`, `"image"`, etc.)
  - `text` (required for type="text"): Text content (string)
  - Additional fields based on type

**Errors:**
- `-32003` Server not initialized: Must call `initialize` first
- `-32000` Tool not found: Tool with given name does not exist
- `-32002` Invalid tool params: Arguments don't match tool's `inputSchema`
- `-32001` Tool execution failed: Tool threw an exception
  - `data` field contains error details

**Example Error Response:**
```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32001,
    "message": "Tool execution failed",
    "data": {
      "tool": "hello",
      "error": "NullPointerException: name cannot be null",
      "stacktrace": "..."
    }
  },
  "id": 3
}
```

---

## Message Flow Examples

### Successful Session

```
Client -> Server: initialize
Server -> Client: capabilities

Client -> Server: tools/list
Server -> Client: [hello tool]

Client -> Server: tools/call(hello, {name: "World"})
Server -> Client: "Hello, World!"
```

### Error: Calling Tool Before Initialize

```
Client -> Server: tools/list
Server -> Client: ERROR -32003 "Server not initialized"
```

### Error: Invalid Tool

```
Client -> Server: tools/call(nonexistent, {})
Server -> Client: ERROR -32000 "Tool not found"
```

---

## Implementation Notes

### State Management

- Server maintains `initialized` flag (boolean)
- All methods except `initialize` check this flag
- Tool registry populated during server startup

### Input Validation

1. Parse JSON (error -32700 if invalid)
2. Validate JSON-RPC 2.0 structure (error -32600 if invalid)
3. Validate method exists (error -32601 if not)
4. Validate parameters (error -32602 if invalid)
5. For `tools/call`: Validate against tool's `inputSchema` (error -32002 if invalid)

### Error Handling

- All exceptions during tool execution caught and returned as error -32001
- Error `data` field includes:
  - `tool`: Tool name
  - `error`: Exception message
  - `stacktrace`: (optional, only in debug mode)

### Telemetry

Every request/response must emit telemetry:
- Request received: method, id, timestamp
- Request completed: method, id, duration, success/error
- Tool invocation: tool name, duration, success/error

---

## Testing Strategy

### Unit Tests

1. **Message Parsing**
   - Valid JSON-RPC messages
   - Invalid JSON
   - Invalid JSON-RPC structure
   - Missing required fields

2. **Handler Router**
   - Route to correct handler
   - Unknown method error
   - Parameter validation

3. **Individual Handlers**
   - `initialize`: valid params, invalid params
   - `tools/list`: returns tools, checks initialized
   - `tools/call`: valid tool, invalid tool, execution error

### Integration Tests

1. Full session via stdio
2. Error scenarios end-to-end
3. Multiple tool calls in sequence

---

## Future Extensions

### Potential Additions (Not in v1.0)

- **Notifications:** One-way messages (no response expected)
- **Progress reporting:** Long-running tool progress updates
- **Tool cancellation:** Cancel in-progress tool execution
- **Streaming responses:** Stream large tool outputs
- **Batch requests:** Multiple requests in single message

---

**Status:** Ready for implementation
**Next Phase:** Implement message parsing (Task 1.2.2)
