# Scittle Runtime Code Browser - Implementation Summary

> **Date**: February 12, 2026  
> **Project**: bb-mcp-server  
> **Focus**: Code browsing for live Scittle runtime via nREPL interface

---

## Executive Summary

I've reviewed the bb-mcp-server project and analyzed the existing code-browser-v2 implementation for bb-runtime. Based on this analysis, I've created a comprehensive plan to implement similar capabilities for **Scittle** (ClojureScript running in browser via SCI) runtimes.

### Key Findings

1. **Existing Implementation**: The `code-browser-v2` module already supports nREPL sources for Babashka runtimes via `code-browser.sources.nrepl` and `code-browser.sources.runtime.babashka`

2. **Architecture Pattern**: The module uses:
   - **Datalevin** as the database backend (URI-centric schema)
   - **Protocol-based source adapters** (`IProjectSource`)
   - **Multimethod dispatch** for runtime-specific introspection
   - **Sente-browser** for WebSocket connections to Scittle browsers

3. **Scittle Differences**: Scittle requires different introspection because:
   - No JVM reflection available
   - Uses `goog` namespace registry instead of `all-ns`
   - Different var metadata structure
   - Browser context with Reagent, statecharts, etc.

---

## Implementation Plan

### Phase 1: Scittle Runtime Introspection Layer

**Files to Create**:
- `modules/code-browser-v2/src/code_browser/sources/runtime/scittle.cljs`

**Tasks**:
1. Add `detect-runtime` method for Scittle
2. Implement `list-namespaces` using `goog.global.namespaces`
3. Implement `introspect-namespace` for Scittle vars
4. Implement `fetch-var-source` with Scittle source retrieval
5. Extend `fetch-var-value` with Scittle-specific value detection (Reagent, statecharts, services, stores)

**Key Code Pattern**:
```clojure
(defmethod runtime/list-namespaces :scittle [_ eval-fn opts]
  (let [code "(.-namespaces goog.global)"]
    (-> (eval-fn code)
        (js->clj :keywordize-keys true)
        (keys)
        (sort-by str)
        (map (fn [ns-name] {:name ns-name :doc "..."}))
        vec)))
```

### Phase 2: Scittle Source Adapter

**Files to Create**:
- `modules/code-browser-v2/src/code_browser/sources/scittle.cljs`

**Tasks**:
1. Create `ScittleSource` record implementing `IProjectSource`
2. Implement `scan-project` using Scittle runtime methods
3. Implement `fetch-source` for Scittle var source retrieval
4. Create `create-scittle-source` constructor

**Integration Points**:
- Update `code-browser.handlers/fetch-var-value` to handle `:scittle` type
- Update `code-browser.core/add-source!` to handle `:scittle` type

### Phase 3: Browser UI Components

**Files to Create**:
- `modules/sente-browser/src/browser/scittle_browser.cljs`
- `modules/sente-browser/src/browser/scittle_ui.cljs`

**Tasks**:
1. Create connection panel for Scittle runtime
2. Implement project load/unload functions
3. Add Scittle project widget to browser UI

### Phase 4: nREPL Proxy Integration

**Files to Modify**:
- `modules/nrepl-proxy-server/src/nrepl_proxy/router.cljs`

**Tasks**:
1. Add routing for Scittle-specific queries
2. Integrate with existing sente-browser connection system

---

## Technical Specifications

### Runtime Detection

Scittle is detected via:
- `js/scittle` global object exists
- `goog.global.namespaces` exists
- `scittle/version` provides version string

### Namespace Introspection

Scittle maintains namespaces in `goog.global.namespaces`:
```javascript
{
  "cljs.core": {docstring: "...", vars: {...}},
  "reagent.core": {...},
  ...
}
```

### Var Introspection

Scittle vars use `ns-interns` instead of `ns-publics`:
```clojure
(ns-interns 'cljs.core)  ;; Returns map of var symbols to vars
```

### Value Introspection

Scittle-specific value types:
- **Reagent Components**: `reagent.core/ILifecycle`
- **Statecharts**: `statecharts.types/statechart?`
- **Services**: `statecharts.service/IService`
- **Stores**: `statecharts.store/IStore`

---

## Files Created

1. **`docs/SCITTLE_CODE_BROWSER_IMPLEMENTATION_PLAN.md`**
   - High-level implementation plan
   - Architecture diagrams
   - Migration path with weekly breakdown
   - Success criteria

2. **`docs/SCITTLE_RUNTIME_INTROSPECTION_SPEC.md`**
   - Detailed technical specification
   - Code examples for each component
   - Testing strategy
   - Performance considerations

---

## Integration Points

### Existing Modules to Extend

| Module | File | Change |
|--------|------|--------|
| code-browser-v2 | `src/code_browser/core.clj` | Add `:scittle` case to `add-source!` |
| code-browser-v2 | `src/code_browser/handlers.clj` | Extend `fetch-var-value` for Scittle |
| sente-browser | `src/sente_browser/core.clj` | No changes needed (already supports nREPL) |
| nrepl-proxy-server | `src/nrepl_proxy/router.cljs` | Add Scittle routing |

### New Modules to Create

| Module | Location | Purpose |
|--------|----------|---------|
| scittle runtime | `code-browser/sources/runtime/scittle.cljs` | Runtime introspection methods |
| scittle source | `code-browser/sources/scittle.cljs` | ScittleSource adapter |
| browser module | `sente-browser/src/browser/scittle_browser.cljs` | Browser UI integration |

---

## Testing Strategy

### Unit Tests
- Runtime detection
- Namespace listing
- Var introspection
- Source fetching
- Value introspection

### Integration Tests
- End-to-end Scittle project loading
- Browser UI interaction
- nREPL proxy routing

### Manual Testing
1. Start Scittle browser with nREPL server
2. Connect via code browser UI
3. Browse namespaces and symbols
4. Fetch source code
5. Inspect runtime values

---

## Success Criteria

### Phase 1 Complete When:
- [ ] Scittle runtime detection works reliably
- [ ] Namespace listing includes goog namespaces
- [ ] Var introspection handles macros
- [ ] Source fetching works for inline definitions

### Phase 2 Complete When:
- [ ] ScittleSource adapter works end-to-end
- [ ] Datalevin storage works for Scittle metadata
- [ ] Browser UI shows Scittle projects

### Phase 3 Complete When:
- [ ] nREPL proxy routes Scittle queries correctly
- [ ] Performance is acceptable (< 5s for 100 symbols)
- [ ] Documentation is complete

---

## Open Questions

1. **Multiple Scittle Runtimes**: Should we support connecting to multiple Scittle runtimes simultaneously?

2. **Scittle Plugin Support**: How to integrate with Scittle plugins like re-frisk?

3. **Source Code Storage**: Should Scittle source code be stored in the database for offline browsing?

4. **Dynamic Namespaces**: How to handle namespaces created at runtime in Scittle?

---

## Next Steps

1. **Review**: Review the implementation plan and technical specification
2. **Setup**: Set up Scittle test environment
3. **Phase 1**: Implement runtime detection and namespace introspection
4. **Phase 2**: Implement ScittleSource adapter
5. **Phase 3**: Integrate with browser UI
6. **Phase 4**: Integrate with nREPL proxy

---

## References

- **code-browser-v2**: `modules/code-browser-v2/`
- **Scittle**: https://github.com/scittle/scittle
- **clj-statecharts**: `clj-statecharts/`
- **sente-browser**: `modules/sente-browser/`
- **nREPL Protocol**: `src/bb_mcp_server/nrepl_direct/client.clj`

---

**Document Status**: Complete - Ready for Implementation  
**Estimated Effort**: 4-6 weeks for full implementation  
**Risk Level**: Low - Extends existing, well-tested architecture
