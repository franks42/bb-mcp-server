# Prototype Indexer Results (Updated)

**Date:** November 26, 2025
**Script:** `scripts/index_codebase.clj`

The prototype successfully parsed the codebase using `edamame` with full reader options enabled (`:deref`, `:quote`, `:fn`, `:set`, `:read-eval`).

## Sample Output

### File: `src/bb_mcp_server/module/ns_loader.clj`

This file was previously failing due to `deref` usage. It now parses correctly.

| ID | Type | Line | Purity | Docstring |
| :--- | :--- | :--- | :--- | :--- |
| `bb-mcp-server.module.ns-loader` | `ns` | 1 | :n/a | Elegant module loader using babashka's native require... |
| `find-module-dir` | `defn` | 54 | **:pure** | Find module directory by name... |
| `load-module-edn` | `defn` | 111 | **:impure** | Load module.edn from a module directory. |
| `load-module` | `defn` | 193 | **:impure** | Dynamically load a module using babashka's native require... |
| `reload-module` | `defn` | 323 | **:impure** | Hot-reload a module by re-requiring its namespace. |
| `start-module!` | `defn` | 393 | **:impure** | Start a loaded module. |
| `stop-module!` | `defn` | 424 | **:impure** | Stop a running module. |
| `reset-state!` | `defn` | 531 | **:impure** | Reset all loader state. Use for testing. |

## Key Findings

1.  **Robustness Achieved:** The indexer now handles complex Clojure files including those with atoms (`reset!`, `deref`) and system interactions.
2.  **Purity Heuristic Validation:**
    *   `find-module-dir` (pure logic) -> **:pure**
    *   `load-module` (I/O) -> **:impure**
    *   `reset-state!` (atom mutation) -> **:impure**
    *   This confirms the heuristic is providing useful signals.

## Next Steps

1.  **Connect to Datalevin:** Instead of printing to stdout, transact these entities into the DB.
2.  **Build the MCP Tool:** Create the `get_code_context` tool that queries this data.
