# Transport Modularity Review - Phase 3

## 1. Review of Recent Changes
The "Claude assistant" has made significant progress in cleaning up the codebase.

*   **✅ Legacy Wrappers Removed**: The `src/bb_mcp_server/transport/` directory has been deleted. This is a major step forward, removing the ambiguity between legacy and modular code.
*   **✅ Entry Point Updated**: `server.clj` has been updated to use `mcp-stdio.core` directly. It now has a proper namespace declaration (`ns server`).

## 2. Architecture Assessment
The project is now firmly in the "Modular" state. The legacy transport layer is gone. However, the "Triple Interface" vision is not yet realized because the entry point is still hardcoded to Stdio.

### Critical Findings
1.  **Coupling Persists**: `mcp-stdio.core` *still* hardcodes the dependency on `bb-mcp-server.protocol.processor`. It calls `processor/make-stdio-ctx` and `processor/process-request-str` directly. This prevents the module from being a generic transport library.
2.  **Single Interface**: `server.clj` is a Stdio-only server. There is no mechanism to start the HTTP server from the main entry point.

## 3. Further Recommendations

### A. Decouple Stdio (High Priority)
Refactor `mcp-stdio.core` to accept a handler function, exactly like `mcp-http` does.
*   **Current**: `(run-stdio-loop!)` -> calls `processor/process-request-str`
*   **Proposed**: `(run-stdio-loop! handler-fn)` -> calls `(handler-fn ctx msg)`
*   **Why**: This makes `mcp-stdio` a true peer to `mcp-http`. Both should just be "dumb pipes" that frame messages and pass them to a handler.

### B. Implement the "Triple Interface" Entry Point
Create a new `src/bb_mcp_server/main.clj` (or update `server.clj`) to parse command line arguments.
*   **Goal**: `bb -m bb-mcp-server.main --transport http --port 3000`
*   **Logic**:
    1.  Parse args (e.g., using `babashka.cli`).
    2.  Initialize the Registry/Processor.
    3.  If `stdio` requested: Start `stdio/run-stdio-loop!` with the processor.
    4.  If `http` requested: Start `http/start-server!` with the processor.
    5.  If both: Run HTTP in background, Stdio in foreground (or block on promise if only HTTP).

### C. Standardize Context Creation
Both transports need to create a "Context" for the request (containing session ID, transport type, etc.).
*   `mcp-http` does this in its router/handler.
*   `mcp-stdio` does this via `processor/make-stdio-ctx`.
*   **Advice**: Ensure the `ctx` map structure is identical across both transports so the processor doesn't need to know which transport the message came from.

## 4. Next Steps
1.  **Refactor `mcp-stdio`**: Change the signature of `run-stdio-loop!`.
2.  **Update `server.clj`**: Pass the processor function to the new `run-stdio-loop!`.
3.  **Build `main.clj`**: Implement the argument parsing and multi-transport startup logic.
