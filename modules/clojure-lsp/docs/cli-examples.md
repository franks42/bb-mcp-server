# clojure-lsp CLI Examples

This document shows a complete CLI session demonstrating all clojure-lsp commands using the bb-mcp-server project as the target codebase.

---

## Prerequisites

```bash
# Start a bb-mcp-server with HTTP transport
bb server --http --config system.edn --nickname my-server

# The examples below use --mcp my-server to connect
# If only one server is running, --mcp can be omitted
```

---

## 1. Check Status (Before Initialization)

```bash
$ bb clojure-lsp status --mcp my-server
```

```json
{
  "running" : false,
  "initialized" : false
}
```

With `--pprint` for EDN output:

```bash
$ bb clojure-lsp status --mcp my-server --pprint
```

```clojure
{:running false, :initialized false}
```

---

## 2. Initialize clojure-lsp

```bash
$ bb clojure-lsp start /path/to/bb-mcp-server --mcp my-server
```

```json
{
  "status" : "initialized",
  "capabilities" : {
    "workspaceSymbolProvider" : true,
    "documentFormattingProvider" : true,
    "referencesProvider" : true,
    "renameProvider" : {
      "prepareProvider" : true
    },
    "hoverProvider" : true,
    "definitionProvider" : true,
    "completionProvider" : {
      "resolveProvider" : true,
      "triggerCharacters" : [ ":", "/" ]
    }
    // ... many more capabilities
  }
}
```

Initial analysis takes ~1-2 seconds for small projects, longer for large codebases.

---

## 3. Check Status (After Initialization)

```bash
$ bb clojure-lsp status --mcp my-server
```

```json
{
  "running" : true,
  "initialized" : true
}
```

---

## 4. Hover - Get Documentation

Get documentation and type info for a symbol. Here we hover over `register!` in registry.clj:

```bash
$ bb clojure-lsp hover src/bb_mcp_server/registry.clj 191 10 --mcp my-server --pprint
```

```clojure
{:range
 {:start {:line 190, :character 6}, :end {:line 190, :character 15}},
 :contents
 {:kind "markdown",
  :value
  "```clojure
bb-mcp-server.registry/register!
[tool-record]
```

Register a tool in the registry.

Args:
  tool-record - Map with :name, :module, :description, :inputSchema, :handler
                :module is REQUIRED
..."}}
```

---

## 5. Go to Definition

Find where a symbol is defined:

```bash
$ bb clojure-lsp definition src/bb_mcp_server/module/system.clj 267 15 --mcp my-server
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

---

## 6. Find References

Find all usages of a symbol across the project:

```bash
$ bb clojure-lsp references src/bb_mcp_server/registry.clj 191 10 --mcp my-server
```

```json
[ {
  "uri" : "file:///path/to/modules/clojure-lsp/src/.../core.clj",
  "range" : { "start" : { "line" : 266, "character" : 10 }, "end" : { "line" : 266, "character" : 28 } }
}, {
  "uri" : "file:///path/to/src/bb_mcp_server/registry.clj",
  "range" : { "start" : { "line" : 190, "character" : 6 }, "end" : { "line" : 190, "character" : 15 } }
}, {
  "uri" : "file:///path/to/src/bb_mcp_server/registry.clj",
  "range" : { "start" : { "line" : 311, "character" : 10 }, "end" : { "line" : 311, "character" : 19 } }
} ]
```

---

## 7. Document Symbols

List all symbols (functions, vars, etc.) in a file:

```bash
$ bb clojure-lsp symbols modules/echo/src/echo/core.clj --mcp my-server --pprint
```

```clojure
[{:name "echo.core",
  :kind 3,
  :range {:start {:line 0}, :end {:line 3}}}
 {:name "echo-handler",
  :kind 12,
  :range {:start {:line 9}, :end {:line 16}}}
 {:name "echo-tool",
  :kind 13,
  :range {:start {:line 22}, :end {:line 31}}}
 {:name "start",
  :kind 12,
  :range {:start {:line 37}, :end {:line 48}}}
 {:name "stop",
  :kind 12,
  :range {:start {:line 50}, :end {:line 57}}}
 {:name "status",
  :kind 12,
  :range {:start {:line 59}, :end {:line 63}}}
 {:name "module",
  :kind 13,
  :range {:start {:line 69}, :end {:line 73}}}]
```

Symbol kinds: 3=Namespace, 12=Function, 13=Variable

---

## 8. Code Actions

Get available refactorings at a position:

```bash
$ bb clojure-lsp code-actions src/bb_mcp_server/registry.clj 191 10 --mcp my-server --pprint
```

```clojure
[{:title "Move to let",
  :kind "refactor.extract",
  :command
  {:title "Move to let",
   :command "move-to-let",
   :arguments ["file:///..." 190 9 "new-binding"]}}
 {:title "Cycle privacy",
  :kind "refactor.rewrite",
  :command {:command "cycle-privacy", :arguments ["..." 190 9]}}
 {:title "Extract function",
  :kind "refactor.extract",
  :command {:command "extract-function", :arguments ["..." 190 9 "new-function"]}}
 {:title "Extract to def",
  :kind "refactor.extract",
  :command {:command "extract-to-def", :arguments ["..." 190 9 nil]}}
 {:title "Introduce let",
  :kind "refactor.extract",
  :command {:command "introduce-let", :arguments ["..." 190 9 "new-binding"]}}]
```

---

## 9. Call Hierarchy

Find what functions call a given function (incoming calls):

```bash
$ bb clojure-lsp call-hierarchy src/bb_mcp_server/registry.clj 191 10 --mcp my-server --pprint
```

```clojure
[{:from
  {:name "start [_deps config]",
   :kind 12,
   :detail "echo.core",
   :uri "file:///path/to/modules/echo/src/echo/core.clj"}}
 {:from
  {:name "start [_deps config]",
   :kind 12,
   :detail "bb-mcp-server.modules.clojure-lsp.core",
   :uri "file:///path/to/modules/clojure-lsp/src/.../core.clj"}}
 ;; ... more callers
 ]
```

Use `--outgoing` to find what a function calls:

```bash
$ bb clojure-lsp call-hierarchy src/bb_mcp_server/registry.clj 191 10 --outgoing --mcp my-server
```

---

## 10. Diagnostics

Get all warnings and errors in the project:

```bash
$ bb clojure-lsp diagnostics --mcp my-server
```

```json
{
  "file:///path/to/test/some_test.clj" : [ {
    "range" : { "start" : { "line" : 6, "character" : 0 } },
    "message" : "Unused import...",
    "severity" : 2
  } ]
}
```

For a specific file:

```bash
$ bb clojure-lsp diagnostics src/bb_mcp_server/registry.clj --mcp my-server
```

---

## 11. Completions

Get completion suggestions at a position:

```bash
$ bb clojure-lsp completions src/bb_mcp_server/registry.clj 50 10 --mcp my-server --pprint
```

```clojure
{:isIncomplete true,
 :items
 [{:label "def",
   :kind 14,
   :detail "clojure.core/def"}
  {:label "defn",
   :kind 14,
   :detail "clojure.core/defn"}
  ;; ... more completions
  ]}
```

---

## 12. Rename (Preview)

Preview a rename refactoring:

```bash
$ bb clojure-lsp rename src/bb_mcp_server/registry.clj 10 5 better-name --mcp my-server
```

Returns a workspace edit showing all files and changes that would be made.

---

## 13. Stop Server

```bash
$ bb clojure-lsp stop --mcp my-server
```

---

## Summary

| Command | Purpose | Example |
|---------|---------|---------|
| `start <path>` | Initialize for a project | `bb clojure-lsp start .` |
| `stop` | Stop the server | `bb clojure-lsp stop` |
| `status` | Check server state | `bb clojure-lsp status` |
| `hover <f> <l> <c>` | Get documentation | `bb clojure-lsp hover f.clj 10 5` |
| `definition <f> <l> <c>` | Go to definition | `bb clojure-lsp definition f.clj 10 5` |
| `references <f> <l> <c>` | Find all usages | `bb clojure-lsp references f.clj 10 5` |
| `symbols <file>` | List symbols in file | `bb clojure-lsp symbols f.clj` |
| `code-actions <f> <l> <c>` | Get refactorings | `bb clojure-lsp code-actions f.clj 10 5` |
| `call-hierarchy <f> <l> <c>` | Callers/callees | `bb clojure-lsp call-hierarchy f.clj 10 5` |
| `diagnostics [file]` | Errors/warnings | `bb clojure-lsp diagnostics` |
| `completions <f> <l> <c>` | Completion suggestions | `bb clojure-lsp completions f.clj 10 5` |
| `rename <f> <l> <c> <name>` | Rename symbol | `bb clojure-lsp rename f.clj 10 5 new` |

**Options:**
- `--mcp NAME` - Server nickname (auto-detects if single server)
- `--port PORT` - Server port directly
- `--pprint` - Output as pretty-printed EDN instead of JSON
- `--outgoing` - Show outgoing calls (for call-hierarchy)
- `--executable PATH` - Custom clojure-lsp binary path (for start)

All positions are **1-indexed** (line 1, column 1 is the first character).

---

*Last Updated: 2025-12-29*
