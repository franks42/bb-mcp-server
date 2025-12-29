# clojure-lsp CLI Examples

This document shows a complete CLI session demonstrating all clojure-lsp MCP tools using the bb-mcp-server project as the target codebase.

---

## Prerequisites

```bash
# Start a bb-mcp-server with HTTP transport
bb server --http --config system.edn --nickname my-server

# The examples below use --mcp my-server to connect
```

---

## 1. Check Status (Before Initialization)

```bash
$ bb mcp call clojure-lsp.clj-status '{}' --mcp my-server
```

```json
{
  "running" : false,
  "initialized" : false
}
```

---

## 2. Tool Error Before Initialization

Calling any tool before `clj-init` returns a helpful error:

```bash
$ bb mcp call clojure-lsp.clj-hover '{"file":"/path/to/file.clj","line":1,"column":1}' --mcp my-server
```

```json
{
  "error" : "clojure-lsp not initialized. Call clj-init first."
}
```

---

## 3. Initialize clojure-lsp

```bash
$ bb mcp call clojure-lsp.clj-init '{"project-root":"/path/to/bb-mcp-server"}' --mcp my-server
```

```clojure
{:status "initialized",
 :capabilities {:workspaceSymbolProvider true,
                :documentFormattingProvider true,
                :referencesProvider true,
                :renameProvider {:prepareProvider true},
                :hoverProvider true,
                :definitionProvider true,
                :completionProvider {:resolveProvider true},
                ;; ... many more capabilities
                }}
```

Initial analysis takes ~1-2 seconds for small projects, longer for large codebases.

---

## 4. Check Status (After Initialization)

```bash
$ bb mcp call clojure-lsp.clj-status '{}' --mcp my-server
```

```json
{
  "running" : true,
  "initialized" : true
}
```

---

## 5. Hover - Get Documentation

Get documentation and type info for a symbol. Here we hover over `register!` in registry.clj:

```bash
$ bb mcp call clojure-lsp.clj-hover '{"file":"/path/to/bb-mcp-server/src/bb_mcp_server/registry.clj","line":191,"column":10}' --mcp my-server
```

```json
{
  "range" : {
    "start" : { "line" : 190, "character" : 6 },
    "end" : { "line" : 190, "character" : 15 }
  },
  "contents" : {
    "kind" : "markdown",
    "value" : "```clojure\nbb-mcp-server.registry/register!\n[tool-record]\n```\n\nRegister a tool in the registry.\n\nArgs:\n  tool-record - Map with :name, :module, :description, :inputSchema, :handler\n                :module is REQUIRED\n\nReturns: The registered tool record (with :name updated to full name)\n\nThrows: ex-info if:\n  - validation fails\n  - :module is missing\n  - module name or tool name contains a dot (reserved separator)\n  - tool with same full name already exists\n\n..."
  }
}
```

---

## 6. Go to Definition

Find where a symbol is defined:

```bash
$ bb mcp call clojure-lsp.clj-definition '{"file":"/path/to/core.clj","line":267,"column":15}' --mcp my-server
```

```json
{
  "uri" : "file:///path/to/bb-mcp-server/src/bb_mcp_server/registry.clj",
  "range" : {
    "start" : { "line" : 190, "character" : 6 },
    "end" : { "line" : 190, "character" : 15 }
  }
}
```

The response shows the exact file and position where `register!` is defined.

---

## 7. Find References

Find all usages of a symbol across the project:

```bash
$ bb mcp call clojure-lsp.clj-references '{"file":"/path/to/registry.clj","line":191,"column":10}' --mcp my-server
```

```json
[ {
  "uri" : "file:///path/to/modules/clojure-lsp/src/.../core.clj",
  "range" : { "start" : { "line" : 266, "character" : 10 }, "end" : { "line" : 266, "character" : 28 } }
}, {
  "uri" : "file:///path/to/src/bb_mcp_server/tools/hello.clj",
  "range" : { "start" : { "line" : 73, "character" : 4 }, "end" : { "line" : 73, "character" : 22 } }
}, {
  "uri" : "file:///path/to/src/bb_mcp_server/registry.clj",
  "range" : { "start" : { "line" : 190, "character" : 6 }, "end" : { "line" : 190, "character" : 15 } }
}, {
  "uri" : "file:///path/to/src/bb_mcp_server/registry.clj",
  "range" : { "start" : { "line" : 311, "character" : 10 }, "end" : { "line" : 311, "character" : 19 } }
} ]
```

Shows 4 references: the definition itself plus 3 call sites.

---

## 8. Document Symbols

List all symbols (functions, vars, etc.) in a file:

```bash
$ bb mcp call clojure-lsp.clj-document-symbols '{"file":"/path/to/modules/echo/src/echo/core.clj"}' --mcp my-server
```

```json
[ {
  "name" : "echo.core",
  "kind" : 3,
  "range" : { "start" : { "line" : 0 }, "end" : { "line" : 3 } }
}, {
  "name" : "echo-handler",
  "kind" : 12,
  "range" : { "start" : { "line" : 9 }, "end" : { "line" : 16 } }
}, {
  "name" : "echo-tool",
  "kind" : 13,
  "range" : { "start" : { "line" : 22 }, "end" : { "line" : 31 } }
}, {
  "name" : "start",
  "kind" : 12,
  "range" : { "start" : { "line" : 37 }, "end" : { "line" : 48 } }
}, {
  "name" : "stop",
  "kind" : 12,
  "range" : { "start" : { "line" : 50 }, "end" : { "line" : 57 } }
}, {
  "name" : "status",
  "kind" : 12,
  "range" : { "start" : { "line" : 59 }, "end" : { "line" : 63 } }
}, {
  "name" : "module",
  "kind" : 13,
  "range" : { "start" : { "line" : 69 }, "end" : { "line" : 73 } }
} ]
```

Symbol kinds: 3=Namespace, 12=Function, 13=Variable

---

## 9. Code Actions

Get available refactorings at a position:

```bash
$ bb mcp call clojure-lsp.clj-code-actions '{"file":"/path/to/registry.clj","line":191,"column":10}' --mcp my-server
```

```json
[ {
  "title" : "Move to let",
  "kind" : "refactor.extract",
  "command" : {
    "title" : "Move to let",
    "command" : "move-to-let",
    "arguments" : [ "file:///...", 190, 9, "new-binding" ]
  }
}, {
  "title" : "Cycle privacy",
  "kind" : "refactor.rewrite",
  "command" : { "command" : "cycle-privacy", "arguments" : [ "...", 190, 9 ] }
}, {
  "title" : "Extract function",
  "kind" : "refactor.extract",
  "command" : { "command" : "extract-function", "arguments" : [ "...", 190, 9, "new-function" ] }
}, {
  "title" : "Extract to def",
  "kind" : "refactor.extract",
  "command" : { "command" : "extract-to-def", "arguments" : [ "...", 190, 9, null ] }
}, {
  "title" : "Introduce let",
  "kind" : "refactor.extract",
  "command" : { "command" : "introduce-let", "arguments" : [ "...", 190, 9, "new-binding" ] }
} ]
```

---

## 10. Call Hierarchy

Find what functions call a given function (incoming calls):

```bash
$ bb mcp call clojure-lsp.clj-call-hierarchy '{"file":"/path/to/registry.clj","line":191,"column":10,"direction":"incoming"}' --mcp my-server
```

```json
[ {
  "from" : {
    "name" : "start [_deps config]",
    "kind" : 12,
    "detail" : "echo.core",
    "uri" : "file:///path/to/modules/echo/src/echo/core.clj"
  }
}, {
  "from" : {
    "name" : "start [_deps config]",
    "kind" : 12,
    "detail" : "bb-mcp-server.modules.clojure-lsp.core",
    "uri" : "file:///path/to/modules/clojure-lsp/src/.../core.clj"
  }
},
  // ... more callers
]
```

Use `"direction":"outgoing"` to find what a function calls.

---

## 11. Diagnostics

Get all warnings and errors in the project:

```bash
$ bb mcp call clojure-lsp.clj-diagnostics '{}' --mcp my-server
```

```json
{
  "file:///path/to/test/some_test.clj" : [ {
    "range" : { "start" : { "line" : 6, "character" : 0 } },
    "message" : "Unused import...",
    "severity" : 2
  } ],
  // ... more files with diagnostics
}
```

For a specific file:

```bash
$ bb mcp call clojure-lsp.clj-diagnostics '{"file":"/path/to/file.clj"}' --mcp my-server
```

---

## 12. Rename (Preview)

Preview a rename refactoring:

```bash
$ bb mcp call clojure-lsp.clj-rename '{"file":"/path/to/file.clj","line":10,"column":5,"new-name":"better-name"}' --mcp my-server
```

Returns a workspace edit showing all files and changes that would be made.

---

## Summary

| Tool | Purpose | Key Args |
|------|---------|----------|
| `clj-init` | Initialize for a project | `project-root` |
| `clj-status` | Check server state | - |
| `clj-hover` | Get documentation | `file`, `line`, `column` |
| `clj-definition` | Go to definition | `file`, `line`, `column` |
| `clj-references` | Find all usages | `file`, `line`, `column` |
| `clj-document-symbols` | List symbols in file | `file` |
| `clj-code-actions` | Get refactorings | `file`, `line`, `column` |
| `clj-call-hierarchy` | Callers/callees | `file`, `line`, `column`, `direction` |
| `clj-diagnostics` | Errors/warnings | `file` (optional) |
| `clj-completions` | Completion suggestions | `file`, `line`, `column` |
| `clj-rename` | Rename symbol | `file`, `line`, `column`, `new-name` |

All positions are **1-indexed** (line 1, column 1 is the first character).

---

*Last Updated: 2025-12-29*
