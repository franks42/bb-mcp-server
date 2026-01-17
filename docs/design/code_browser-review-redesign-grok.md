# Code Browser Redesign Review (Grok)

**Date:** 2026-01-17  
**Scope:** Review of current `code_browser.clj` and `code_browser.cljs`, plus `docs/design/code_browser-review-redesign.md`.

---

## 1. Summary (Does a Clean-Slate Rewrite Make Sense?)

**Absolutely.** The current implementation has evolved into a monolithic mess. The server file (`code_browser.clj`) is ~2,500 lines of spaghetti code mixing LSP integration, file watching, Git operations, JAR analysis, state management, and event handling. The client (`code_browser.cljs`) is similarly bloated with UI components, event logic, and domain concerns all tangled together. A clean-slate rewrite will be faster, cleaner, and more maintainable than trying to untangle this organically grown codebase. Since no backwards compatibility is required, we can design for the future without legacy constraints.

---

## 2. Observations from Current Code

### Server (`code_browser.clj`)
- **God Object Anti-Pattern:** One namespace handles everything—LSP client management, clj-kondo shell-outs, JAR file parsing, Git commands, file system watching, state atoms, event routing, and caching. This violates single responsibility principle.
- **State Explosion:** The `!code-browser-state` atom is a kitchen sink with 20+ keys (e.g., `:namespaces`, `:symbols-by-ns`, `:source-by-var`, `:git`, `:projects`, `:jar-analyses`). Changes to one part trigger full atom-sync diffs, and ownership is unclear.
- **Handler Complexity:** Functions like `handle-set-project-root` perform multiple side effects (reset state, spawn LSP init, refresh caches, update git). This makes testing and debugging nightmare.
- **Caching Without Bounds:** Accumulates data forever (`symbols-by-ns`, `source-by-var`) with no eviction policy. Memory leaks are inevitable for long-running sessions.
- **Embedded Concerns:** Debouncing logic, file watching, and async operations are scattered throughout handlers instead of being centralized.

### Client (`code_browser.cljs`)
- **Mixed Responsibilities:** UI rendering, event sending/receiving, local state management, and domain logic (e.g., directory browsing, git cloning) are all in one file.
- **Implicit Reactivity:** Reagent components deref atoms directly, making it hard to trace what triggers re-renders or why.
- **Feature Bloat:** Directory browser, git clone UI, JAR exploration—all share the same namespace, leading to cognitive overload.
- **State Coupling:** Local `!ui-state` and synced `!server-state` are not clearly separated, leading to bugs when one changes without the other.

---

## 3. Review of the Redesign Document

### Strengths
- **URI-Centric Design:** Brilliant idea. Using URIs as universal identifiers (`dir://`, `jar://`, `nrepl://`) unifies sources and enables cross-referencing, history, and bookmarks.
- **Datascript Backend:** Smart choice for modeling code relationships (deps, callers). Datalog queries will make complex traversals trivial compared to nested maps.
- **Clean Slate Approach:** Correct decision. No incremental migration needed.
- **Hybrid Sync Strategy:** Pragmatic mix of atom-sync for lists and RPC for heavy data/content.

### Weaknesses
- **Source in Datascript:** Storing full source code strings in the DB is risky. Datascript is optimized for metadata and relations, not large blobs. This could bloat the DB and slow queries. Recommend separating content (source text) from metadata.
- **Module Granularity:** The proposed "Feature Slices" for server might lead to circular dependencies (e.g., Git module needing to invalidate Analysis caches). Consider layered architecture for server core.
- **Volatile Sources:** The distinction between static (immutable) and temporal (live) sources is good, but needs stronger isolation—don't mix live snapshots with static caches.

---

## 4. Concrete Suggestions

### A. Data Architecture
- **Separate Metadata from Content:** Use Datascript for metadata (names, types, relations, URIs). Store source code, docs, etc., in a separate LRU cache or fetch on-demand. This keeps the DB lean and queryable.
- **Schema Refinement:** Ensure URIs are `:db/unique` and use refs for hierarchy (project → ns → symbol).

### B. Server Module Structure
- **Layered Approach:** Infrastructure (LSP, file IO) → Domain (analysis, git) → Application (handlers, state) → Presentation (atom-sync exports).
- **Source Adapters:** Implement a protocol for each source type (dir, jar, github, nrepl) with methods like `list-namespaces`, `get-symbols`, `fetch-source`.

### C. Client Componentization
- **Generic Components:** Build a reusable `list-panel` for selectable lists (projects, namespaces, symbols). Use a `detail-panel` with tabs for source/doc/deps.
- **State Separation:** Keep local UI state (filters, expanded panels) separate from synced server state.

### D. Sync and Communication
- **Hybrid Protocol:** Use atom-sync for coarse views (lists). Use RPC for fine-grained data (source text, relationships).
- **Event Protocol:** Formalize events with specs/schemas to prevent typos and ensure type safety.

### E. Testing and Development
- **Parallel Development:** Build new code in isolated namespaces (e.g., `code-browser-new`). Use a separate config for testing.
- **Incremental Rollout:** Start with directory source only, then add others.

---

## 5. Minimal Recommended Rewrite Plan

1. **Scaffold Structure:** Create `modules/sente-browser/src/code_browser/` with subdirs for `uri/`, `db/`, `sources/`, `handlers/`, `ui/`.
2. **URI Module:** Implement parsing, generation, and validation.
3. **Datascript Setup:** Define schema and basic queries.
4. **Directory Adapter:** Port clj-kondo logic to a clean adapter.
5. **Minimal UI:** Wire up project → namespace → symbol → source display.
6. **Expand:** Add git, jar, nrepl adapters; refine UI components.

---

## 6. Final Recommendation

The redesign is sound and necessary. Proceed with clean-slate, but prioritize separating metadata from content in Datascript. This will ensure scalability and maintainability as the codebase grows. The URI-centric model and Datascript backend are strong foundations for future features like cross-project queries and advanced navigation.