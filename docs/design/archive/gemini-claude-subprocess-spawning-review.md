# Claude Subprocess Spawning - Review & Recommendations

## 1. Review of Prototype (`clay-noj-ai`)
The prototype in `clay-noj-ai/src/claude_service.clj` provides a strong foundation.

### Strengths
*   **Process Management**: Uses `babashka.process` effectively for lifecycle (spawn/kill).
*   **Session Management**: Successfully implements `fork!` and `spawn-from-session!` using Claude's `--resume` flag. This is a critical feature for the "Expert" patterns.
*   **JSONL Handling**: Correctly parses the stream-json format.

### Weaknesses / Risks
*   **Concurrency Model**: The `ask` function blocks reading from stdout. If multiple threads call `ask` on the same instance, or if the instance sends an unsolicited message (like a cost update or error) while we're waiting for a response, the stream reading could get desynchronized.
*   **Thread-per-Request**: `ask-async` uses `future`, which spawns a thread. While fine for low volume, a dedicated I/O loop per instance is safer.
*   **Error Handling**: If the Claude process dies unexpectedly, the reader thread might hang or throw obscure errors.

## 2. Architecture Recommendations

### A. Module Structure
Create a new module `modules/claude-manager` (or `claude-spawner`).
*   **`src/claude_manager/core.clj`**: Public API (spawn, ask, list).
*   **`src/claude_manager/process.clj`**: Low-level process & I/O handling.
*   **`src/claude_manager/registry.clj`**: State management (atoms).

### B. Improved I/O Model (The "Reader Loop")
Instead of `ask` reading directly, implement a **dedicated reader loop** for each spawned Claude instance.

1.  **Spawn**: Start process + Start a background `future` (Reader Loop).
2.  **Reader Loop**:
    *   Continuously reads lines from `stdout`.
    *   Parses JSONL.
    *   **Dispatches** based on message type:
        *   `result`: Finds the matching pending request (by ID) and delivers the result (promise/callback).
        *   `assistant`: Accumulates content for the current turn.
        *   `error`: Delivers error to pending request.
3.  **Ask**:
    *   Generates Request ID.
    *   Creates a `promise`.
    *   Registers `request-id -> promise` in a map.
    *   Writes to `stdin`.
    *   Returns the promise (or blocks on it).

This solves the concurrency issue: only *one* thing reads from stdout, ensuring messages are never lost or interleaved incorrectly.

### C. MCP Tool Mapping
Expose the following tools in `module.edn`:

| Tool Name | Clojure Function | Description |
| :--- | :--- | :--- |
| `claude_spawn` | `spawn!` | Start a new instance (optional: from session) |
| `claude_ask` | `ask` | Send message and wait for response |
| `claude_list` | `list-services` | List active instances |
| `claude_kill` | `kill!` | Stop an instance |
| `claude_fork` | `fork!` | Fork an existing instance |

### D. Configuration
*   **Claude Path**: Make configurable via `config.edn` (don't hardcode `~/.claude/...`).
*   **Models**: Allow passing model strings, but provide defaults.

## 3. Implementation Plan

1.  **Scaffold Module**: `bb scaffold claude-manager`
2.  **Port Core Logic**: Copy `spawn!` logic but refactor I/O to use the **Reader Loop** pattern.
3.  **Implement Registry**: Use an atom to track instances and their reader futures.
4.  **Expose Tools**: Define `module.edn`.
5.  **Test**: Create an integration test that spawns a real Claude (or a mock echo process) and verifies async communication.

## 4. Alternative: "Claude-as-a-Server"
Instead of managing raw processes, we could wrap Claude in its *own* MCP server. However, given Claude CLI is a text-stream tool, the "Subprocess Manager" approach (current design) is more flexible for orchestration and session management. Stick with the current design.
