# Review: Streamable HTTP Transport Design

**Date:** 2025-11-22
**Reviewer:** GitHub Copilot (Gemini 3 Pro)
**Target:** `docs/design/streamable-http-transport-design.md`

## Executive Summary

The proposed design for **Streamable HTTP Transport** is **technically sound, feasible in Babashka, and recommended** for implementation if full MCP compliance (specifically server-to-client notifications) is a goal.

## Technical Feasibility in Babashka

The design relies on `org.httpkit.server`, which is already a dependency in `bb.edn`.

*   **Async/Streaming**: `http-kit`'s `with-channel`, `send!`, and `on-close` APIs are fully supported in Babashka. The proposed `sse.clj` implementation uses these standard patterns correctly.
*   **State Management**: Using `atoms` for session management is idiomatic and thread-safe in Babashka's single-process model.
*   **Dependencies**: No new dependencies are required. `cheshire` and `http-kit` are sufficient.

## Alternatives & Trade-offs

### 1. WebSockets (The "Real" Bidirectional Alternative)
*   **Pros**: True bidirectional binary/text framing, lower overhead than HTTP POST + SSE. `http-kit` supports this natively.
*   **Cons**: Requires a different handshake. If the MCP spec specifically defines "Streamable HTTP" as SSE-based, implementing WebSockets would be a *different* transport (e.g., `ws://`), not an implementation of this specific spec.
*   **Verdict**: Stick to the design to match the "Streamable HTTP" spec. WebSockets could be added later as a separate transport type if needed.

### 2. Basic HTTP (Current Implementation)
*   **Pros**: Extremely simple. Stateless.
*   **Cons**: **Unidirectional**. The server cannot send logs, progress updates, or "sampling" requests to the client.
*   **Verdict**: Insufficient for a "Pro" level MCP server.

### 3. Babashka Pods (For Isolation, not Transport)
*   The user asked about "alternatives for babashka". While Pods are great for *module isolation* (running tools in separate processes), they are not a transport alternative. The HTTP server *must* run in the main process to handle connections efficiently.

## "Is it worthwhile?"

**YES**, if you want:
1.  **Server Logs in Client**: The ability to send `notifications/message` so the user sees server logs in their MCP client (Claude Desktop, etc.).
2.  **Progress Indicators**: Sending progress updates for long-running tools.
3.  **LLM Sampling**: The server asking the client's LLM to generate text (a feature of MCP).

**NO**, if you only want:
1.  **Simple Tool Execution**: If the server is just a passive "function caller", the current `http.clj` is sufficient.

## Recommendations

1.  **Proceed with Implementation**: The design is solid.
2.  **Refine Session Cleanup**: The design mentions `session-timeout-ms`. Ensure the cleanup task runs periodically (e.g., via a `future` loop or a simple timer) to prevent memory leaks from abandoned sessions.
3.  **Concurrency**: Be careful with `session/update-session!`. Ensure that adding/removing SSE channels is atomic to avoid race conditions if a client reconnects rapidly.

## Code Snippet Validation

The provided snippets in the design doc are valid Clojure and should work in Babashka without modification.

```clojure
;; Validated: This pattern works in BB + http-kit
(http/with-channel request channel
  (http/send! channel {:headers {"Content-Type" "text/event-stream"} ...} false)
  ...)
```
