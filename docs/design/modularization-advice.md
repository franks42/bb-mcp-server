# Architecture Review & Modularization Advice

**Date:** 2025-11-22
**Topic:** Modularizing MCP JSON-RPC (Stdio/HTTP) and REST API

## 1. The Core Problem: "Transport Agnosticism"

Currently, you have logic duplicated between `test-harness.clj` (used by Stdio/Old HTTP) and `streamable-http/handlers/post.clj`. Both are doing the "Parse JSON -> Check Errors -> Route -> Format Response" dance.

To fix this, we need to extract the **Core Processor** that sits *between* the Transport and the Router.

### Proposed Architecture

```
┌──────────────┐      ┌──────────────┐
│  Stdio       │      │  HTTP (SSE)  │
│  Transport   │      │  Transport   │
└──────┬───────┘      └──────┬───────┘
       │                     │
       ▼                     ▼
┌────────────────────────────────────┐
│         Unified Processor          │
│ (bb-mcp-server.protocol.processor) │
└────────────────┬───────────────────┘
                 │
                 ▼
┌────────────────────────────────────┐
│              Router                │
│ (bb-mcp-server.protocol.router)    │
└────────────────┬───────────────────┘
                 │
                 ▼
┌──────────────┐      ┌──────────────┐
│  Handlers    │      │  Registry    │
└──────────────┘      └──────────────┘
```

## 2. Recommendation: Create `bb-mcp-server.protocol.processor`

Create a new namespace `bb-mcp-server.protocol.processor` that contains the logic currently in `test-harness/process-json-rpc` and `streamable-http/handlers/post.clj`.

```clojure
(ns bb-mcp-server.protocol.processor
  (:require [bb-mcp-server.protocol.message :as msg]
            [bb-mcp-server.protocol.router :as router]))

(defn process-request
  "Process a JSON-RPC request (string or map).
   Returns: Response map (or nil for notification)."
  [request-or-str ctx]
  ;; 1. Parse (if string)
  ;; 2. Validate JSON-RPC
  ;; 3. Route to handler (passing ctx!)
  ;; 4. Handle errors
  ;; 5. Return response
  ...)
```

**Key Change: The `ctx` (Context) Object**
Pass a `ctx` map to the processor (and then to handlers). This is how you solve the notification problem.

*   **Stdio Context**: `{:transport :stdio, :send-notification! (fn [msg] (println (json/generate-string msg)))}`
*   **HTTP Context**: `{:transport :http, :session-id "...", :send-notification! (fn [msg] (sse/send-json-rpc! channel msg))}`

## 3. Modularizing the Handlers

Your handlers (`initialize`, `tools/call`) currently take just `request`. Update them to take `[ctx request]`.

**Example: `tools-call.clj`**
```clojure
(defn handle-tools-call [{:keys [send-notification!]} request]
  ;; ... execute tool ...
  ;; If tool takes a long time, send progress:
  (send-notification! {:method "notifications/progress" :params {...}})
  ;; Return result
  {:result ...})
```

## 4. The "REST API" Question

If you want a REST API (e.g., `GET /api/tools` returning simple JSON, not JSON-RPC), do **not** mix it with the MCP JSON-RPC handlers.

**Recommendation:**
Keep the REST API as a separate set of Ring handlers in `streamable-http` (or a new `rest-api` module).

*   **MCP Endpoint (`POST /mcp`)**: Strict JSON-RPC. Uses `processor/process-request`.
*   **REST Endpoint (`GET /api/tools`)**: Standard Ring handler. Calls `registry/list-tools` directly and returns JSON.

## 5. Refactoring Steps

1.  **Create `bb-mcp-server.protocol.processor`**: Move the parsing/dispatch logic here.
2.  **Update Router**: Allow passing `ctx` to handlers.
3.  **Update Handlers**: Accept `ctx` as the first argument.
4.  **Update Transports**:
    *   **Stdio**: Update `scripts/stdio_server.clj` to use `processor/process-request` with a stdio-specific context.
    *   **Streamable HTTP**: Update `handlers/post.clj` to use `processor/process-request` with an SSE-specific context.

This approach gives you maximum code reuse while handling the specific needs (SSE vs Stdout) of each transport.
