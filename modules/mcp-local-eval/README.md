# mcp-local-eval

MCP-based local code evaluation with full server access.

> **Note:** This module is named "mcp-local-eval" to clarify it uses MCP protocol for tool invocation, NOT nREPL. Code executes directly in the bb-mcp-server process.

## Overview

Execute Clojure code and load files directly within the MCP server's runtime environment using native Babashka `eval`. Provides **full access** to server state, namespaces, and all Clojure capabilities. Useful for server introspection, debugging, dynamic module loading, and configuration.

## Tools

| Tool | Full Name | Description |
|------|-----------|-------------|
| `local-eval` | `mcp-local-eval.local-eval` | Evaluate Clojure code with full server access |
| `local-load-file` | `mcp-local-eval.local-load-file` | Load and evaluate Clojure files |

## Tool: `local-eval`

Execute Clojure code with full access to the server runtime (no sandbox restrictions).

```clojure
;; Basic evaluation
{:code "(+ 1 2 3)"}
;; => {:result 6}

;; Access server state
{:code "(keys @bb-mcp-server.registry/registry)"}

;; With base64 encoding
{:code "base64-encoded" :input-base64 true :output-base64 true}
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `code` | string | Yes | Clojure code to evaluate |
| `input-base64` | boolean | No | Interpret code as base64-encoded |
| `output-base64` | boolean | No | Return results as base64 |

## Tool: `local-load-file`

Load and execute Clojure files in the server runtime.

```clojure
{:file-path "/path/to/script.clj"}
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file-path` | string | Yes | Absolute path to Clojure file |

## Use Cases

- **Server introspection** - Inspect registry, connections, state
- **Dynamic tool loading** - Load new modules at runtime
- **Debugging** - Test code within server context
- **Configuration** - Modify server state dynamically

## MCP vs nREPL

| Aspect | mcp-local-eval | nrepl module |
|--------|---------------|--------------|
| Protocol | MCP tool call | nREPL protocol |
| Where code runs | bb-mcp-server process | Remote nREPL server |
| Use case | Server introspection | Remote evaluation |

## Module Structure

```
modules/mcp-local-eval/
├── module.edn
├── README.md
└── src/mcp_local_eval/
    ├── core.clj       # Module entry
    ├── eval.clj       # local-eval tool
    ├── load_file.clj  # local-load-file tool
    └── shared.clj     # Shared utilities
```

## License

Same as bb-mcp-server project.
