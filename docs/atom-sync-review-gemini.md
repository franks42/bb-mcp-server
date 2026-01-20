# Atom Sync & Code Browser Design Review

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** January 13, 2026

## Executive Summary

The current implementation of the Code Browser module uses an "Atom Sync" pattern where the entire application state (including navigation selection and source code content) is shared via a server-side atom to all connected clients. While this enables a collaborative "shared view" experience, it introduces significant issues regarding scalability, memory usage, and UI responsiveness for a general-purpose tool.

We recommend refactoring towards a **Local-State, Remote-Data** model using standard RPC for heavy data (Source Code, Symbol Lists) and lightweight signals for reactivity.

---

## 1. Architecture Analysis

### Current State
*   **Model:** Shared State / Collaborative.
*   **Mechanism:** Server atom `!code-browser-state` accumulates all data. `atom-sync` propagates diffs to all clients.
*   **Flow:** `Client Click -> Server Event -> Server Fetch -> Update Atom -> Sync -> Client Re-render`.

### Critical Issues

#### A. Coupled Selection State & "The Phantom Driver"
Because `selected-ns` and `selected-symbol` are stored in the server-side atom, **all connected browsers are forced to view the same thing**.
*   If User A selects a namespace, User B's view instantly jumps to that namespace.
*   This is excellent for pair programming code-alongs but problematic for a multi-user tool or independent tabs.
*   **Ui Latency:** The user must wait for a full server round-trip before seeing their selection highlighted.

#### B. State Accumulation (Memory Leak)
The server atom acts as an unbounded cache:
*   The `:source-by-var` map accumulates every file ever viewed.
*   **Impact:** Server memory usage grows indefinitely.
*   **Sync Cost:** A new browser connecting hours later receives the entire history of every file ever browsed (via the initial sync snapshot).

#### C. Performance: Brute-Force LSP Fetching
The current implementation calls `workspace/symbol` (query "") for every interaction.
*   This fetches **every symbol in the entire project** and filters them in memory.
*   Time complexity is **O(Project Size)** for every click, instead of **O(File Size)**.

---

## 2. Recommendations & Proposed Architecture

Refactor to a **Local-State, Remote-Data** model.

### A. Decouple Selection State
Move ephemeral UI state to the **Client**.

*   **Client (`code_browser.cljs`):**
    *   Holds `!ui-state`: `{:selected-ns "..." :selected-symbol "..." :cache {...}}`.
    *   **Action:** On selection, update local state immediately (instant UI feedback).

### B. Shift to RPC + Signal/Invalidation Pattern
Don't use `atom-sync` to push heavy content like source code. Use it only for lightweight signals.

**The "Signal & Refetch" Pattern:**
1.  **Client:** Requests data explicitly via RPC.
    *   `[:code-browser/get-source {:file "..."}]` -> Server responds with code.
    *   Client stores this in a local reagent atom or cache.
2.  **Server (Watcher):** Detects file changes (via LSP `publishDiagnostics`).
3.  **Server (Signal):** Broadcasts a tiny invalidation event.
    *   `[:code-browser/invalidated {:uri "file:///..."}]`
4.  **Client (Reactive):**
    *   Listens for invalidation.
    *   Logic: "Am I currently displaying `file:///...`?"
    *   If **Yes**: Trigger new RPC fetch immediately.
    *   If **No**: Clear local cache for that file (lazy update).

**Benefits:**
*   **Bandwidth:** No sending code for files nobody is looking at.
*   **Memory:** Server becomes stateless regarding views.
*   **Speed:** UI is optimistic; fetches are on-demand.

### C. Optimize LSP Integration

**1. Use Targeted Methods**
Replace `workspace/symbol` with:
*   `textDocument/documentSymbol`: Fetches symbols **only** for the target file.
*   Significantly reduces latency on large repos.

**2. Rich Data (clj-kondo)**
*   **Retain the Shell-out approach:** The current method of shelling out to `clj-kondo` CLI for metadata (like `:defined-by` or macro origins) is pragmatic and sound.
    *   Standard LSP protocols are "lossy" and map rich Clojure concepts to generic types (e.g., `deftest` -> `Function`).
    *   Since we cannot easily access `clojure-lsp`'s internal Java DB objects from Babashka, the CLI is the most robust way to get this specific metadata.
*   **Optimization:** Ensure this analysis is cached on the Client side and only run on-demand (when a namespace is expanded), not on every file save.

---

## 3. Implementation Plan Summary

1.  **Refactor Client:** Introduce `!ui-state` for selections. Remove reliance on `get-synced-atom` for source/symbols.
2.  **Refactor Server:**
    *   Remove accumulation maps from global atoms.
    *   Create standard request/response handlers for `get-symbols` and `get-source`.
    *   Change the LSP Watcher to broadcast simple `:invalidated` signals instead of updating state.
3.  **Update Handlers:** Implement the "check-and-refetch" logic in the client event loop.
