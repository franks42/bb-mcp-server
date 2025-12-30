# Design Review: Live + Static State Integration
**Date:** 2025-12-29
**Reviewer:** Cascade
**Target:** `docs/design/live-static-state-design-implementation.md`

## 1. Executive Summary

The proposed design addresses a critical blind spot for AI coding agents: the disconnect between "what I wrote" (static file content) and "what is running" (live REPL state). By bridging this gap, the system will significantly reduce hallucinations where the agent assumes code is loaded/active when it isn't.

The design is sound and well-structured, identifying the key sources of divergence. The proposed "Unified Query" interface is the correct abstraction level for an AI agent.

## 2. Architectural Recommendations

### 2.1 Dedicated Orchestrator Module
**Recommendation:** Do **not** implement the unification logic inside the `clojure-lsp` module or the `nrepl` module.

*   **Why:** This logic depends on *both* modules. Implementing it in one creates a circular or awkward dependency.
*   **Proposal:** Create a new module (e.g., `state-monitor`, `live-context`, or `repl-observer`) that consumes tools from both `clojure-lsp` (static) and `nrepl` (live). This keeps the lower-level modules focused on their specific domains and allows the "Unified Query" logic to evolve independently.

### 2.2 Dependency Direction
*   `state-monitor` → `clojure-lsp` (via MCP tools or internal API)
*   `state-monitor` → `nrepl` (via MCP tools or internal API)

## 3. Implementation Advice

### 3.1 Scope: Focus vs. Global Diff
**Recommendation:** Prioritize `query-namespace` (focused view) over global `state-diff`.

*   **Risk:** A global diff that scans *all* namespaces will be slow (network round-trips to nREPL) and noisy (many libraries differ slightly in runtime vs. source).
*   **Benefit:** The agent works primarily in one file/namespace at a time. Checking divergence for *that specific namespace* provides high value with low latency.

### 3.2 Divergence Detection Challenges
**Recommendation:** Be cautious with signature comparisons.

*   **Challenge:** Static analysis sees literal text `(defn foo [x] ...)`. Runtime introspection (`meta`, `arglists`) returns evaluated data structures.
*   **Strategy:** You need a robust normalization layer.
    *   Ignore metadata differences that don't affect behavior (e.g., line numbers).
    *   Normalize fully qualified symbols vs. aliases.
    *   Handle macro expansions which might obscure the original definition.

### 3.3 "Killer Feature": Value Inspection
**Recommendation:** Elevate **Value Inspection** to a first-class use case.

*   **Insight:** The design mentions retrieving values, but this should be highlighted. Being able to see `@#'my-app.state/config` allows the agent to debug logic errors that static analysis simply cannot see.
*   **Tool Idea:** `inspect-value` tool that takes a symbol, resolves it via LSP to ensure it exists, then fetches its EDN value via nREPL.

## 4. Tooling & Phasing

### 4.1 "Phase 0": Immediate Wins
**Recommendation:** Start by simply exposing the **Runtime Introspection** tools in the `nrepl` module. You don't need the full unification logic to get value.

*   `nrepl-introspect-ns`: List loaded vars in a namespace.
*   `nrepl-get-value`: Get the EDN value of a specific var.

These tools alone allow the agent to manually verify its assumptions ("Is this var loaded?", "What is its value?").

### 4.2 CLI Alignment
The proposed `bb state ...` CLI aligns perfectly with the existing `bb clojure-lsp` pattern and is a good user interface for these features.

### 4.3 Fallback Strategy
While `cider-nrepl` offers superior introspection (`info`, `eldoc`), ensure the core introspection works with vanilla `clojure.core` functions (`ns-publics`, `meta`, `source`). This ensures the feature works in minimal environments without requiring the user to inject middleware.

## 5. Conclusion

The roadmap is solid. The primary recommendation is to decouple the "unification" logic into its own module and to start by shipping the basic nREPL introspection tools immediately. This will provide immediate utility while the more complex state merging logic is developed.
