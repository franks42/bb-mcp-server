# Review: Sente-Browser Module Proposal

**Date:** 2025-12-23
**Document Reviewed:** `docs/sente-browser-module.md`

## 1. Overall Assessment
The proposal is excellent. It correctly identifies `bb-mcp-server` as the right home for this functionality due to its modular architecture. The plan enables a powerful new workflow (evaluating code in the browser via Claude) without requiring a separate proxy process.

## 2. Strengths
*   **Architecture Fit**: Leveraging the `module` system is the correct approach. It keeps the core server clean.
*   **User Flow**: The "discovery" pattern (browser connects -> Claude lists -> Claude evals) is intuitive and maps well to the existing nREPL toolset.
*   **Zero-Install Client**: The HTTP bootstrap server serving HTML+Scittle is a great usability win.

## 3. Critical Feedback & Suggestions

### 3.1. Coupling with `nrepl` Module
The proposal suggests modifying `modules/nrepl` source code directly (`nrepl/state/connection.clj`, `nrepl/state/messages.clj`) to handle `:browser` types.
*   **Concern**: This creates a circular concept dependency. `sente-browser` depends on `nrepl`, but `nrepl` now has hardcoded logic for `sente-browser` logic (handling `:type :browser`).
*   **Suggestion**: Instead of `case` statements in `nrepl`, consider a **Registry Pattern for Connection Adapters**.
    *   **Current Proposal**:
        ```clojure
        ;; modules/nrepl/src/nrepl/state/messages.clj
        (case (:type conn)
          :browser (adapt-browser...) ;; Hard dependency
          :socket  (adapt-socket...))
        ```
    *   **Recommended**:
        *   `nrepl` module exposes a `register-adapter!` function.
        *   `sente-browser` module calls this on startup.
        *   This keeps `nrepl` unaware of `sente-browser` internals.

### 3.2. Lifecycle & Cleanup
*   **Question**: What happens if the `sente-browser` module is stopped/unloaded?
*   **Observation**: The `stop` function clears `!browser-connections`.
*   **Suggestion**: Ensure the `stop` function also removes any registered adapters from the `nrepl` module to prevent "zombie" handlers.

### 3.3. Security (Minor)
*   **Observation**: The WebSocket and HTTP servers bind to all interfaces (implied default).
*   **Suggestion**: Default to `localhost` (127.0.0.1) unless configured otherwise, to prevent exposing the REPL to the local network unexpectedly.

### 3.4. Dependency Management
*   **Observation**: The proposal adds `sente-lite` to `bb.edn`.
*   **Confirmation**: Ensure `sente-lite` is indeed compatible with Babashka (it seems to be, as it's "lite", but verifying strict compatibility is good practice).

## 4. Implementation Phase Refinement
The "Phase 1: Minimal Integration" steps are solid. I recommend splitting "Add `:type` field" into "Refactor `nrepl` to support pluggable types" if you want to be architecturally pure. If MVP speed is the goal, the hardcoded approach is acceptable but incurs technical debt.

## 5. Conclusion
This is a solid plan. Proceeding with the "Hardcoded MVP" approach is likely fine for now given the controlled scope of `bb-mcp-server`, but keep the "Adapter Registry" pattern in mind for future refactoring if more connection types (e.g., specific heavy clients, remote proxies) are added.
