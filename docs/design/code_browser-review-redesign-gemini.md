# Code Browser Redesign Review

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** January 17, 2026
**Reference Design:** `docs/design/code_browser-review-redesign.md`

## 1. Problem Validation

I have reviewed `code_browser.clj` (server) and `code_browser.cljs` (client) and fully agree with the assessment that a clean-slate redesign is necessary.

*   **Server Complexity:** `code_browser.clj` (~2.5k lines) is effectively a "God Object". It manages process lifecycles (LSP), file IO (Kondo), Git operations, and global state management in a single namespace.
*   **State Sprawl:** The `!code-browser-state` map has become a grab-bag of unrelated concerns (Git status next to JAR analysis next to selection state).
*   **Coupling:** The "Accumulated State" pattern (Phase 1.5-Acc) has inextricably linked *caching* strategy with *selection* state, making it impossible to clear caches without breaking the UI.

## 2. Architecture Review

### The Datascript Decision
**Verdict: Strong Endorse**
Moving the server-side state to Datascript is the correct architectural pivot.
*   **Structure:** It replaces ad-hoc nested maps with a defined schema.
*   **Relationships:** Datalog is perfect for modeling the "Graph" nature of code (Callers, Callees, Defs, Refs) which map implementation struggles with.
*   **Uniformity:** It allows treating `Projects`, `Namespaces`, and `Symbols` as uniform entities distinguished by attributes, rather than specific map keys.

### The URI Schema
**Verdict: Strong Endorse**
Using URIs (`jar://...`, `file://...`, `nrepl://...`) as the unifying identifier key is excellent.
*   It solves the "Source Identity" problem elegantly.
*   It simplifies the frontend: The UI just needs to know "How to render a URI", regardless of whether it came from a JAR or a local file.

### Hybrid Sync Strategy
**Verdict: Endorse with Caveat**
The proposal to use `atom-sync` for "Coarse" views (Project List, Namespace List) and RPC/Subscription for "Fine" views (Source Code, detailed relationships) is the right balance.
*   **Caveat:** Ensure that `atom-sync` is not used for frequent, high-bandwidth updates.

## 3. Critical Recommendations

### A. Data Separation (Metadata vs. Content)
I strongly advise **against** storing full source code strings in the Datascript DB (as proposed in `symbol/source {}`).
*   **Risk:** Loading full source text for entire projects will explode the JVM heap and degrade Datascript query performance.
*   **Recommendation:**
    *   Store **Metadata** in Datascript (Name, Arity, Line Number, File Path, Git SHA).
    *   Store **Content** (Source strings, Markdown docs) in a separate "Blob Store" or simple LRU Cache map, or fetch from disk on demand.
    *   **Why:** You rarely query *on* the source code text itself in Datalog. You query on metadata. Keep the indices light.

### B. Module Structure & "Feature Slices"
The design doc proposes "Feature Slices" (Option C). This is generally good, but be careful of circular dependencies.
*   I recommend a **Layered Architecture (Option B)** for the *Server Core* (Datascript, Analysis, Git), but **Feature Slices** for the *Frontend Component Tree*.
*   **Reason:** Server logic often needs cross-feature access (e.g., Git module needs to invalidate Analysis module cache). Layers handle this better than peer features.

### C. The "Live" vs "Static" Distinction
The design correctly identifies Static vs Temporal sources.
*   **Recommendation:** Treat `nrepl://` sources as "Volatile". Do not persist them to the same long-lived Datascript indices as static projects unless tagged with a specific Snapshot ID (UUIDv7).
*   **UI Implication:** The UI needs a visual indicator for "Volatile" sources (e.g., a "Live" badge) to warn users that links might rot.

## 4. Implementation Sandbox
Since we are doing a "Clean Slate" rewrite, I recommend creating the new structure in a completely isolated namespace hierarchy to allow parallel development:

*   **Old:** `sente-browser.code-browser`
*   **New:** `code-browser.core` (or `bb-mcp-server.code-browser`)

We can mount the new system on a different HTTP route or specialized "Dev" system config (`bb-new-browser-dev-system.edn`) to iterate without breaking the production tool.

## 5. Summary

The proposed design in `docs/design/code_browser-review-redesign.md` is sound and addresses the core scalability and maintainability issues.

**Approved Next Steps:**
1.  Scaffold the `code-browser/` directory structure.
2.  Implement `code-browser.db` (Datascript instance).
3.  Implement `code-browser.uri` (Parser/Generator).
4.  Begin porting the "Directory Source" adapter (migrating clj-kondo logic).
