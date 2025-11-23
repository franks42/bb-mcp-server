# Naming Conventions and Module-Tool Separator

This document describes the naming conventions used in bb-mcp-server, particularly the module-tool separator design.

## Overview

bb-mcp-server organizes tools into **modules**. Each tool has:
- A **short name** within its module (e.g., `eval`, `add`, `echo`)
- A **fully qualified name** for MCP clients (e.g., `nrepl.eval`, `math.add`)

The **module-tool separator** (`.` by default) joins these to create unique, collision-free tool names.

## The Problem

Without namespacing, tool name collisions are inevitable:
- Multiple modules might have an `eval` tool
- Generic names like `list`, `get`, `call` are common
- Flat namespaces don't scale

## The Solution: Module-Tool Separator

```
fully-qualified-name = module-name + separator + tool-name
```

Example with `.` separator:
```
nrepl.eval          (module: nrepl, tool: eval)
local-eval.eval     (module: local-eval, tool: eval)
math.add            (module: math, tool: add)
```

### Single Source of Truth

The separator is defined once in `src/bb_mcp_server/registry.clj`:

```clojure
(def module-tool-separator
  "Separator character for fully-qualified tool names (module.tool).
   Single source of truth - used by registry, handlers, and REST API."
  ".")
```

All code references this constant:
- Tool registration uses it to build FQ names
- REST API uses it for URL routing
- Initialize response exposes it for client introspection

### Validation

Module and tool names are validated to prevent separator injection:

```clojure
(defn valid-name?
  "Check if a name is valid (non-empty, no separator character)."
  [name]
  (and (string? name)
       (not (str/blank? name))
       (not (str/includes? name module-tool-separator))))
```

This prevents:
- `foo.bar` as a module name (would create `foo.bar.tool`)
- `baz.qux` as a tool name (would create `module.baz.qux`)

## Client Introspection

Clients can discover the separator via:

### MCP Initialize Response

```json
{
  "protocolVersion": "2025-03-26",
  "serverInfo": {
    "name": "bb-mcp-server",
    "version": "0.1.0",
    "moduleToolSeparator": "."
  },
  "capabilities": {
    "tools": {"listChanged": true}
  }
}
```

### REST API Endpoint

```bash
GET /api/server
```

Returns:
```json
{
  "name": "bb-mcp-server",
  "version": "0.1.0",
  "moduleToolSeparator": ".",
  "mcpProtocolVersion": "2025-03-26"
}
```

## URL Routing

The REST API uses module-based URLs to avoid ambiguity:

```
GET  /api/modules                        - List all modules
GET  /api/modules/:module/tools          - List tools in module
GET  /api/modules/:module/tools/:name    - Get tool metadata
POST /api/modules/:module/tools/:name    - Call tool
```

Example:
```bash
# List tools in the nrepl module
curl http://localhost:3000/api/modules/nrepl/tools

# Call the eval tool in the nrepl module
curl -X POST http://localhost:3000/api/modules/nrepl/tools/eval \
  -H "Content-Type: application/json" \
  -d '{"code": "(+ 1 2)"}'
```

## Why "." as Default?

The period (`.`) was chosen because:

1. **Familiar** - Matches namespace conventions (Java, Clojure, Python)
2. **URL-safe** - Works in REST paths without encoding
3. **Readable** - `nrepl.eval` is clear and scannable
4. **MCP compatible** - Some MCP servers use this convention

### Alternatives Considered

| Separator | Pros | Cons |
|-----------|------|------|
| `.` | Familiar, readable | Could conflict with version numbers |
| `/` | REST-like | Conflicts with URL paths |
| `::` | Clojure-like | Verbose, needs URL encoding |
| `_` | Simple | Common in tool names already |
| `:` | Compact | URL encoding required |

## Changing the Separator

To change the separator (e.g., to `_`):

1. Edit `registry.clj`:
   ```clojure
   (def module-tool-separator "_")
   ```

2. Restart server - all references update automatically

3. Clients discover new separator via `/api/server` or MCP initialize

**Note**: Changing separator on a running system will break existing tool references.

## Summary

- Module-tool separator creates unique, hierarchical tool names
- Default: `.` (period)
- Single source of truth: `registry/module-tool-separator`
- Exposed to clients via MCP initialize and REST `/api/server`
- Names validated to prevent separator injection
- REST API uses explicit `/modules/:module/tools/:name` routing
