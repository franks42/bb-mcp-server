# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-18
**Version:** v1.14.7
**Focus:** Code Browser v2 - Fix Browser Integration Issues (R3.x)

---

## 🔴 IMMEDIATE TASK: Fix v2 Browser Issues

**Status:** R3.1-R3.3 Done. Browser integration broken. Unit tests pass.

### What's Wrong (Discovered 2026-01-18)

The v2 core logic works (30 unit tests pass), but browser integration has issues:

| Issue | File to Check | What to Look For |
|-------|---------------|------------------|
| **Slow queries** | `handlers.clj:62-77` | `query-namespaces` - N+1 queries? Missing indexes? |
| **Error persists** | `sync.clj` | `clear-error!` not syncing to browser? |
| **Click events fail** | `code_browser_v2.cljs:300-340` | `on-click` handlers in list items |

### How to Debug

```bash
# 1. Run unit tests (should all pass)
bb test:module code-browser-v2

# 2. Start server
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# 3. Initialize backend
cat > /tmp/init-v2.json << 'EOF'
{"code": "(code-browser.core/init! {:db-path \"/tmp/cb-v2-test\" :sources [{:type :dir :path \".\"}]})"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/init-v2.json --mcp cb-v2-test

# 4. Test query directly (should be fast)
cat > /tmp/test-query.json << 'EOF'
{"code": "(time (count (code-browser.db.protocol/q (code-browser.handlers/get-db) '[:find ?e :where [?e :ns/name _]])))"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/test-query.json --mcp cb-v2-test
```

---

## What's Done (R0-R3.3)

| Phase | Summary |
|-------|---------|
| **R0** | URI module, IDatalogDB protocol, Datalevin backend, schema, tests |
| **R1** | Directory source adapter, IProjectSource protocol, clj-kondo integration |
| **R2** | atom-sync wiring, browser state/events, generic list, navigation flow |
| **R3.1** | Symbol inspector tabs (Source, Doc, Deps placeholder, Callers placeholder) |
| **R3.2** | Aliases panel - separate alias/refer entities, browser UI with filter |
| **R3.3** | Multi-file namespace support - file count badges, sort modes, dividers |

### Infrastructure Improvements (This Session)

| Item | Description |
|------|-------------|
| `--args-file` option | Added to `bb mcp call` to bypass bash `!` escaping |
| `bb datalevin:status` | Show running Datalevin pod processes |
| `bb datalevin:stop` | Stop all Datalevin pod processes |
| `bb datalevin:cleanup` | Stop pods + remove lock files |

### Known Issues

| Issue | Description | Workaround |
|-------|-------------|------------|
| **Multiple pods** | Multiple servers can spawn multiple Datalevin pods → resource contention | Use `bb datalevin:status` to check, `bb datalevin:stop` to cleanup |

---

## Quick Resume

```bash
# Check server status
bb server:list
bb datalevin:status

# Start test server for v2
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# Run tests
bb test:module code-browser-v2
bb lint && bb format
```

---

## Browser Testing for Code Browser v2

> ⚠️ **CRITICAL:** The "Load Code Browser" button loads **v1**, NOT v2!
> v2 browser code must be loaded via nREPL. See `docs/SCITTLE_DEV_ENVIRONMENT.md` for full setup.

### Quick v2 Test (Unit Tests Recommended)

**Due to performance issues with browser testing, use unit tests:**
```bash
bb test:module code-browser-v2   # 30 tests, 459 assertions - tests all core functionality
```

### v2 Browser Setup (Has Known Issues)

See `docs/SCITTLE_DEV_ENVIRONMENT.md` section "Code Browser v2 Testing" for:
- Complete 6-step setup process
- Known issues and workarounds
- v1 vs v2 visual differences

### Known Issues (v2 Browser)

| Issue | Workaround |
|-------|------------|
| Slow namespace queries (>30s) | Test with smaller project or use unit tests |
| "No database configured" error persists | Clear manually via `(code-browser.sync/clear-error!)` |
| Click events not triggering | Call functions directly via nREPL |

**Recommendation:** Rely on unit tests for v2 development until browser issues are resolved.

---

## Phase R3: Feature Parity (In Progress)

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done |
| R3.3 | Multi-file namespace support | ✅ Done |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

### R3.3 Implementation Summary

- `:ns/files` and `:symbol/file` already populated from clj-kondo analysis
- Added `:sort-mode` to sync state with `toggle-sort-mode!` handler
- Browser features:
  - File count badge on multi-file namespaces: `(3 files)`
  - Sort mode toggle button (↓ for file-order, A→Z for alpha)
  - File dividers in file-order mode separating symbols by file
  - File badges in alpha mode showing source file on each symbol
  - Footer shows total files for multi-file namespaces

---

## Key Files (v2)

| File | Purpose |
|------|---------|
| `modules/code-browser-v2/src/code_browser/uri.cljc` | URI parsing/generation |
| `modules/code-browser-v2/src/code_browser/db/protocol.clj` | IDatalogDB interface |
| `modules/code-browser-v2/src/code_browser/db/datalevin.clj` | Datalevin backend |
| `modules/code-browser-v2/src/code_browser/sources/directory.clj` | Directory source adapter |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | Event handlers |
| `modules/code-browser-v2/src/code_browser/sync.clj` | atom-sync exports |
| `modules/code-browser-v2/src/code_browser/core.clj` | Public API |
| `scripts/datalevin_manager.clj` | Pod lifecycle management |
| `scripts/mcp_cli.clj` | MCP CLI (has --args-file) |

---

## Key Commands

```bash
# Datalevin management (NEW)
bb datalevin:status    # Show pod processes
bb datalevin:stop      # Stop all pods
bb datalevin:cleanup   # Stop + remove locks

# Server management
bb server:start-wait --nickname NAME --config FILE
bb server:stop NAME
bb server:list

# Testing
bb test:module code-browser-v2  # 30 tests, 459 assertions
bb lint && bb format
```

---

## Handoff Notes

1. **R3.1-R3.3 complete:** Symbol inspector tabs + Aliases panel + Multi-file support
2. **All unit tests pass:** 30 tests, 459 assertions (10 test namespaces)
3. **Aliases working:** 588 aliases extracted from bb-mcp-server project
4. **Multi-file:** Sort mode toggle, file dividers/badges implemented
5. **v2 Browser issues discovered:** Slow queries, error state management, click events - see Known Issues
6. **Recommendation:** Use unit tests for v2 development, not browser testing
7. **Next task:** Fix v2 performance issues OR proceed with R3.4 (file watching)
8. **Key gotcha:** Functions with `!` need `--args-file` workaround for MCP CLI
9. **Deps/Callers:** Placeholder views only - need server-side support

---

*Last Updated: 2026-01-18*
