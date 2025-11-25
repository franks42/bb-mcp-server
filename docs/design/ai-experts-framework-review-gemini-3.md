# AI Experts Framework Review (Iteration 3)

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** 2025-11-25
**Scope:** Implementation Review (Phases 13B, 13C, 13D, 13E, 13-Port)

## 1. Executive Summary

The implementation of the AI Experts Framework infrastructure is **high quality** and closely adheres to the architectural design. The separation of concerns between `ai-orchestrator`, `port-registry`, and `expert-registry` is clean and effective.

The system is now capable of:
*   Managing multiple AI providers (Claude Subprocess, OpenAI HTTP, Anthropic HTTP).
*   Allocating and tracking ports for future dedicated MCP servers.
*   Loading expert definitions and curricula from disk.
*   Exposing these capabilities via MCP tools.

However, I have identified a **concurrency race condition** in the HTTP provider implementation that limits it to single-threaded usage per instance, and I note that the "Expert" implementation is currently in a transitional state (ports allocated but unused).

## 2. Component Analysis

### A. AI Orchestrator & Router (`ai-orchestrator`)
*   **Strengths**: The `protocol` abstraction is excellent. The `registry` management is robust.
*   **Critical Issue (Concurrency)**:
    In `ai-orchestrator.router`, the `current-request-id` atom is used to track the active request ID for an instance.
    ```clojure
    ;; router.clj
    (defn- register-pending-request! [instance request-id promise]
      ...
      (when-let [current-id (:current-request-id instance)]
        (reset! current-id request-id)))
    ```
    This design assumes that an instance handles only one request at a time (valid for `claude-subprocess` JSONL). However, for `openai-http`, which supports concurrent requests, this creates a race condition. If two threads call `ask` on the same instance, the second call overwrites `current-request-id` before the first call's `send-message` reads it.
    *   **Impact**: Concurrent HTTP requests to the same instance will fail or cross-talk.
    *   **Recommendation**: The `router` should pass the `request-id` directly to `proto/send-message` (e.g., by assoc-ing it into the instance map or changing the protocol signature).

### B. Port Registry (`port-registry`)
*   **Verdict**: **Excellent**.
*   **Strengths**:
    *   `validate-registry!` correctly handles zombie cleanup on startup.
    *   `check-port-health!` uses real socket connections.
    *   Persistence to `.ports/registry.edn` works as intended.
*   **Minor Note**: `process-alive?` uses `kill -0`, which is Unix-specific. Acceptable for now given the environment.

### C. Expert Registry (`expert-registry`)
*   **Verdict**: **Good MVP**.
*   **Observation**: `spawn-expert!` allocates a port but does not yet start the dedicated MCP server (marked as TODO for Phase 13F).
*   **Critique**:
    *   Hardcoded `:provider-type :claude-subprocess` and mock command in `spawn-expert!`.
    *   Hardcoded `:domain :clojure-tools`.
    *   **Action**: These need to be parameterized based on the expert's `manifest.edn` before this module is production-ready.

### D. Providers (`claude-subprocess`, `openai-http`)
*   **Claude Subprocess**: The refactor successfully preserved the "Dedicated Reader Loop" pattern. The CLI argument handling fix looks correct.
*   **OpenAI HTTP**: Implemented correctly using `babashka.http-client` and `future` for async handling. Affected by the concurrency issue mentioned above.

## 3. Recommendations

### Immediate Fixes
1.  **Fix Concurrency**: Modify `ai-orchestrator.router/send-and-await` to pass the `request-id` to the provider.
    *   *Quick Fix*: `(proto/send-message (assoc instance :active-request-id request-id) message)`
    *   Update `openai-http` to read `:active-request-id` instead of `@(:current-request-id instance)`.
2.  **Parameterize Expert Spawning**: Update `spawn-expert!` to read the provider config and domain from the expert manifest instead of hardcoding them.

### Strategic Next Steps
1.  **Phase 13F (Dedicated MCP Servers)**: This is the missing link. You have the port, now you need to spawn the `bb server` on that port.
2.  **Message Bus**: Once experts are running with real tools, the message bus will be needed for them to communicate.

## 4. Conclusion

The foundation is solid. The code is clean, well-tested, and modular. Fixing the concurrency issue in the router is the only critical technical debt to address before scaling up usage.

**Status**: **Approved** (with concurrency fix required).
