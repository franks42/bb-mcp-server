# Implementation Plan Review & Roadmap Suggestions

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** January 13, 2026

## Context: Use Case Clarification
The user has clarified the primary use cases for the Code Browser:
1.  **Single User System:** A personal tool for browsing code.
2.  **Teaching / Buddy-Programming:** A shared context where multiple users *intentionally* see the same view.

**Impact on Architecture:**
*   **"Shared View" (Coupled Selection) is a Feature, not a Bug:** For teaching/buddy programming, the fact that User A drives User B's view is desirable. The previous critique about decoupling selection state should be re-evaluated. We might want a **"Follow Mode" toggle** rather than permanent decoupling.
*   **Scalability Concerns Reduced:** If the user count is low (1-5 users) and the project size is manageable, the memory overhead of "Accumulated State" might be acceptable for the simplicity it buys. However, for large method bodies or large projects, fetching `documentSymbol` (per file) is still objectively better than `workspace/symbol` (whole project) for latency.

---

## 1. Architectural Pivot: "The Hybrid State Model"

Given the "Buddy Programming" requirement, a pure "Local State" model invalidates the use case. Instead, we propose a **Hybrid Model**:

*   **Mode A: Independent (Default):** Users browse freely. Selections are local.
*   **Mode B: Presenter/Follow:** Users can "sync" their view to a host.

**Refined Scalability Recommendation:**
Even for single users, the **source code accumulation** (`source-by-var`) is risky for long-running server processes.
*   **Suggestion:** Keep the "Shared Atom" for *pointers* (who is looking at what file), but use **RPC** to fetch the actual heavy *content* (source code strings).
*   **Benefit:** The atom remains lightweight (kB size), while the heavy data (MB size) acts as ephemeral response data, garbage collected after the request.

---

## 2. Feedback on Existing Phases

### Phase 1.5-Acc (Accumulated State)
*   **Critique:** While accumulation enables instant back-navigation, storing full source strings in a synced atom is an anti-pattern for long sessions.
*   **Recommendation:** Implement an **LRU Cache** on the *Server* side for source code, or rely on the OS disk cache (which is already excellent). Don't sync the full cache to every new client. Sync only the *current* view.

### Phase 1.5-Watch (Live File Watching)
*   **Critique:** Good plan.
*   **Enhancement:** Use the **"Invalidation Signal"** pattern. When a file changes, don't auto-push the new content immediately. Push an `{:invalidated "uri"}` signal. If the client is still viewing it, *they* request the update. This handles "rapid fire" saves better.

---

## 3. Proposed Enhancements (Missing Features)

### A. Interaction & REPL Integration (High Value)
Since we have an nREPL connection in the browser:
1.  **"Eval Selection":** Allow highlighting code in the Source View (CM6) and evaluating it.
2.  **"Eval Top-Level Form":** Context menu to evaluate the current function definition.
3.  **"Open in Editor":** Button to open the current file/line in VS Code/Emacs (via `vscode://` URI schemes).

### B. Navigation & Analysis (Medium Value)
1.  **"Find References":** Context menu on a symbol to show usages (using existing clojure-lsp/kondo data).
2.  **"Goto Definition" in Source:** Make symbols in the CodeMirror view clickable to jump to their definition.
3.  **"Jump to Line":** Simple UI to jump to a specific line number.

### C. Teaching Tools (Specific to stated use case)
1.  **"Follow Me" Toggle:** A UI switch.
    *   *Leader:* Broadcasts their local selection to the server.
    *   *Follower:* Listens to server selection updates and auto-navigates.
2.  **Cursor Tracking:** (Advanced) Show the leader's cursor position or highlighted range in the follower's view.

### D. Multi-Project / Context Support
ref: Phase 1.5E.3
*   **Recommendation:** Instead of building complex "Switch Project" logic inside the module, leverage the `dynamic-module-loading` architecture.
    *   Action: "Switch Project" -> Unloads current `code-browser` module -> Loads new `code-browser` module configured for the new root.

---

## 4. Implementation Plan Updates

**Add Phase 1.6: Hybrid Architecture & Optimization**
*   **1.6.1:** Refactor: Move source code strings out of the synced atom (use RPC).
*   **1.6.2:** Feature: Implement "Follow Mode" (opt-in shared state).
*   **1.6.3:** Opt: Replace `workspace/symbol` with `documentSymbol` for browsing specific files.

**Add Phase 1.7: REPL Integration**
*   **1.7.1:** Add "Eval Selection" to CM6 context menu.
*   **1.7.2:** visual feedback for evaluation results in the browser.
