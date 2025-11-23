# Research: Lifecycle/Component Libraries for Clojure & Babashka

**Date:** 2025-11-23  
**Requested by:** bb-mcp-server maintainers  
**Goal:** Identify lifecycle management patterns that offer start/stop/restart/load/unload orchestration with dependency support **and run inside Babashka**.

---

## Compatibility Matrix

| Library | Babashka Compatible? | Dependency Model | Notes |
| --- | --- | --- | --- |
| **Custom `ILifecycle` (existing)** | ✅ | Explicit protocol, manual graph via `module.system` | Already implemented; proven in repo. |
| **`com.stuartsierra/component`** | ⚠️ Partial | Directed acyclic graph via dependency map | Works in JVM Clojure. Babashka can instantiate records and protocols, but `component` relies on `clojure.tools.namespace` (unavailable in bb) for reload workflows. Core runtime is BB-friendly if you avoid `tools.namespace`. |
| **`mount`** | ⚠️ Partial | Global states ordered via `:requires` metadata | Uses macros and dynamic vars only; pure Clojure. Works in Babashka, but lacks fine-grained dependency graph (topology computed from load order) and has global mutable state (harder to sandbox modules). |
| **`juxt/clip`** | ❌ (as of 2025) | Declarative graph + integrant-style keys | Depends on `integrant` and `clojure.spec.alpha`; Babashka still lacks `spec`. |
| **`integrant`** | ❌ | Graph keyed by keywords, lifecycle defined via multimethods | Requires `clojure.spec.alpha`, `clojure.core.async` in some recipes. Not currently usable inside Babashka. |
| **`systemic` / `district0x/component` forks** | ⚠️ Partial | Variation of `component` | Same caveats as `component`. |
| **`reitit/ring` style `Lifecycle` (malli apps)** | ✅ | Minimal protocols, per-component maps | Already similar to your `module.protocol`. |

---

## Observations about Current Codebase

* `src/bb_mcp_server/module/system.clj` implements a **Component-style runtime** already:
  * Tracks status (`:starting`, `:running`, `:stopping`).
  * Resolves dependencies via `module.deps/resolve-order` (topological sort).
  * Provides `start-system!`, `stop-system!`, `restart-system!`, and `reload-module!`.
  * Records telemetry and durations.
* Modules implement `proto/start-module`, `proto/stop-module`, giving you standardized lifecycle hooks today.
* You already rely on **Babashka-compatible primitives**: atoms, futures, dynamic `require` via `ns-loader`.

**Conclusion:** You already possess a robust lifecycle framework tailored to Babashka. Adopting an external JVM-centric component library would add friction without clear benefit.

---

## Recommendations

### 1. Continue with Custom `ILifecycle`, but Formalize as `bb-mcp-server.lifecycle`
* Extract the lifecycle protocol and helper routines into a dedicated namespace (e.g., `bb-mcp-server.lifecycle.core`).
* Document the **contract** (`start-module`, `stop-module`, optional `status`/`health`).
* Provide dev tooling wrappers (`with-started-system`, `restart!`) for REPL/CLI workflows.

### 2. Borrow Good Ideas from `component` Without Importing It
* Component-style dependency maps (`{:db #fn [...], :api [:db] ...}`) could inform future config DSL.
* Reuse the **`using`** concept: when module is created, inject only the dependencies it declares.
* Implement a tiny helper macro similar to `component/system-map` that builds module descriptors from `module.edn`.

### 3. Offer `mount`-like Convenience for Scripts
* For quick scripts, expose a `bb module/run` helper that starts required modules automatically and tears them down with `with-open` style macros.
* Example: `(with-system [{:keys [streamable-http]} (system/from-config)] ...)`

### 4. Keep Tracking Babashka Improvements
* When Babashka ships `clojure.spec.alpha`, revisit `integrant`/`clip`.
* Monitor `component` forks targeting BB (there is ongoing work in community). Document compatibility notes in `docs/research`.

### 5. Add Regression Tests for Lifecycle Graphs
* You already have rich system telemetry. Add automated checks to ensure `start-order` respects declared dependencies.
* Use `test/run_stdio_tests.clj` pattern to simulate partial failures and verify rollback paths.

---

## Suggested Next Steps

1. **Document** the lifecycle protocol in `docs/` for module authors (expected functions, return values, error handling, telemetry).
2. **Introduce Context Injection**: Provide modules with a context map containing logging, telemetry, registry, and notification functions—similar to recommendations in `modularization-advice.md`.
3. **Prototype a Lightweight Config DSL**: Maybe `system.edn` entries like `{:module math :deps [local-eval] :config {...}}` -> compile into runtime graph.
4. **Explore `component` Interop**: If collaborators insist on `component`, wrap your lifecycle in adapters: `ComponentLifecycle -> ModuleLifecycle`.
5. **Publish Reusable Core**: Consider extracting `module/system.clj` + friends into `bb-lifecycle` library for community adoption.

---

### References & Footnotes

* Stuart Sierra Component (1.1.0) – limited by `tools.namespace` but runtime portion is small and BB-compatible if manually loaded.
* Mount (1.0.0) – works in BB; trading flexibility for simplicity, but not ideal for modular hot-reload.
* Integrant/Clip – blocked by missing `clojure.spec` in current Babashka releases.
* Existing repo docs: see `docs/design/module-system-design.md` & `module.system` implementation for baseline.
