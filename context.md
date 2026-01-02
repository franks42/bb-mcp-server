# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-02
**Version:** v1.10.0-code-browser-phase1

---

## Current State

Code Browser Phase 1 complete. Event dispatch working. sente-lite warnings fixed.

**Next:** Test with clojure-lsp running to verify namespaces populate.

---

## Recent Commits

```
fb21f78 docs: Add SCITTLE_DEV_ENVIRONMENT.md to required reading
dadd7d1 feat(code-browser): Phase 1 complete with sente-lite fix
bb79ed3 fix(code-browser): Add React/ReactDOM scripts required for Reagent
e11cce8 feat(code-browser): Complete Phase 0 dev infrastructure
```

---

## Session Notes

Things not in CLAUDE.md or other docs:

- **sente-lite on-message callback** - MUST return `nil`; truthy values get echoed to client
- **Scittle reagent.dom** - Not available via nREPL eval; use `bootstrap/mount-root!`
- **nrepl-eval-local-file** - Correct tool for loading .cljs into Scittle
- **clojure-lsp must be initialized** - Call `clj-init` before code-browser works
- **LSP Symbol Kinds** - 3=namespace, 12=function, 13=variable
- **SCI metadata** - User vars have metadata; built-ins are plain JS functions (nil metadata)
- **Safari throttling** - Background tabs throttle JS, breaking WebSocket heartbeats

---

## Quick Resume

```bash
# See docs/SCITTLE_DEV_ENVIRONMENT.md for full guide
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev
node test/scripts/code_browser_event_test.mjs
```
