# Code Browser Redesign Review (GPT‑5.2‑Codex)

**Date:** 2026‑01‑17
**Scope:** Review current `code_browser.clj` and `code_browser.cljs` plus `docs/design/code_browser-review-redesign.md`.

---

## 1. Summary (Does a clean‑slate make sense?)

**Yes.** The current server and client files have grown into “god files” with intertwined concerns (analysis, caching, UI, navigation, git, JARs, directory browsing). A clean‑slate rewrite is justified and likely faster/safer than incremental refactoring.

---

## 2. Observations from Current Code

### Server (`code_browser.clj`)
* **Single mega‑namespace** handles everything: LSP, clj‑kondo, JAR analysis, Git ops, file watcher, state, events.
* **State map sprawl** (`!code-browser-state`) with many unrelated keys causes: implicit coupling, unclear ownership, hard testability.
* **Handlers are heavy & side‑effectful** (e.g., project root change triggers multiple async tasks, cache resets, git refreshes).
* **Caching without bounded policy**: `symbols-by-ns`, `source-by-var`, `jar-analyses` etc. grow indefinitely.
* **Debounce logic embedded** in handlers rather than centralized.

### Client (`code_browser.cljs`)
* **UI + event handling + domain logic mixed** in one namespace.
* **Local UI state** is not strongly separated from server state consumption.
* **Features bloated into one file**: directory browsing, git clone, jar exploration, etc., share the same module.
* **Reactivity is implicit**: hard to reason about which UI depends on which data.

---

## 3. Review of the Redesign Document

### ✅ Strong Points
1. **URI‑centric model** is excellent. It unifies local, jar, GitHub, nREPL sources with a single identity scheme.
2. **Datascript as server DB** is a strong fit for code browsing relationships (deps, callers, refs).
3. **Clean‑slate approach** is the right choice; no backward compatibility constraint is a major advantage.
4. **Explicit architecture options** are well thought out, especially the **Hybrid Sync** approach.

### ⚠️ Areas to Adjust
1. **Source strings in Datascript:** Avoid storing raw source text in the DB. Datascript is best for metadata + relationships. Large strings will degrade indexing and increase memory. Fetch source lazily (file read, jar read, or on‑demand cache).
2. **Module boundaries:** Favor **layered architecture** for server core (analysis, storage, infra), but keep **feature slices** for UI components.
3. **Volatile sources:** nREPL (live) sources should be treated as temporal snapshots and isolated from static caches.

---

## 4. Concrete Suggestions

### A. Data Separation (critical)
Split data into:
* **Metadata DB (Datascript):** Projects, namespaces, symbols, relationships, file paths, git SHA.
* **Content Store:** Source code, docs, examples. Use an LRU cache or file/jar fetch.

### B. Explicit “Source Adapters”
The design doc’s `sources/` idea is good; each adapter should implement:
* `enumerate-namespaces`
* `list-symbols`
* `get-source`
* `get-metadata`

This makes “project type” (dir/jar/github/nrepl) interchangeable.

### C. State Ownership Clarity
Split state into explicit domains:
* `!project-state` (current project selection)
* `!nav-state` (selected ns/symbol)
* `!cache-state` (symbols/source caches)
* `!ui-state` (client only)

### D. Client Componentization
Break UI into re‑usable “list panels” with minimal props:
* A generic `list-panel` for projects/namespaces/symbols
* A `detail-panel` that is tab‑driven (source/doc/deps/callers)

### E. Sync Protocol
Keep **atom‑sync** for global lists (projects, namespaces). Use **RPC/subscription** for:
* Source text
* Callers/deps
* Symbol doc

---

## 5. Minimal Recommended Rewrite Plan

1. **Scaffold new namespace tree** alongside old code.
2. **Implement URI module** (parser, formatter, validators).
3. **Implement Datascript schema + basic queries**.
4. **Build only 1 adapter first** (directory). End‑to‑end:
   * list namespaces
   * list symbols
   * fetch source
5. **Wire minimal UI**: project → namespace → symbol → source.
6. **Expand with git/jar/nrepl adapters afterwards.**

---

## 6. Final Recommendation

Proceed with the clean‑slate redesign. The design doc is strong, but adjust it to:

* **Keep Datascript for metadata only.**
* **Separate static vs. temporal source caches.**
* **Use hybrid sync (atom‑sync + query protocol).**

This will reduce coupling, make testing possible, and keep performance predictable as features grow.