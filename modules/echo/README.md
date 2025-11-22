# echo

Simple echo tool that returns input unchanged.

## Overview

Minimal MCP tool module for testing and debugging. Returns the input message exactly as provided.

## Tool: `echo`

```clojure
{:message "Hello, World!"}
;; => "Hello, World!"
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `message` | string | Yes | Message to echo back |

## Use Cases

- **Testing** - Verify MCP tool invocation works
- **Debugging** - Trace message flow through the system
- **Template** - Simple module structure to copy for new tools

## Module Structure

```
modules/echo/
├── module.edn
├── README.md
└── src/echo/
    └── core.clj
```

## License

Same as bb-mcp-server project.
