# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-18
**Version:** v1.14.4
**Focus:** Code Browser v2 - Phase R3 (Feature Parity)

---

## Current Focus: Code Browser v2 - Phase R3

**Status:** R0-R2 Complete. R3.1-R3.3 Done. Continuing R3.

### What's Done (R0-R3.3)

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

> **WARNING:** Port 8091 shows **Code Browser v1** by default! v1 also has multi-file
> namespace support, so you might think you're testing v2 when you're actually seeing v1.

### How to Test v2 in Browser

1. **Start the v2 test server** (if not running):
   ```bash
   bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn
   ```

2. **Initialize Code Browser v2** (required every server restart):
   ```bash
   cat > /tmp/init-v2.json << 'EOF'
   {"code": "(require '[code-browser.core :as cb-v2]) (cb-v2/init! {:db-path \"/tmp/cb-v2-test\" :sources [{:type :dir :path \".\"}]})"}
   EOF
   bb mcp call local-eval.local-eval --args-file /tmp/init-v2.json --mcp cb-v2-test
   ```

3. **Open browser** at http://localhost:8091

4. **Click "Load Code Browser"** - This loads the UI

### How to Tell v1 vs v2 Apart

| Feature | v1 | v2 |
|---------|----|----|
| Project selector | Dropdown + git controls | List panel (no git yet) |
| Add project | Text input + buttons | Not implemented |
| Git branch display | Shows branch + dirty status | Not implemented (R3.5) |
| Namespace list source | `sente-browser.code-browser` server | `code-browser.handlers` + Datalevin |
| Synced atom key | `:code-browser-state` | `:code-browser-v2` |

### Verify You're Testing v2

Check the atom-sync state via MCP:
```bash
cat > /tmp/check-v2.json << 'EOF'
{"code": "(keys @code-browser.sync/!state)"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/check-v2.json --mcp cb-v2-test
```

Expected v2 keys: `(:projects :selected-project :namespaces :selected-ns :symbols :aliases :refers :selected-symbol :source :sort-mode :loading? :error)`

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
2. **All tests pass:** 30 tests, 459 assertions (10 test namespaces)
3. **Aliases working:** 588 aliases extracted from bb-mcp-server project
4. **Multi-file:** Sort mode toggle, file dividers/badges implemented
5. **Next task:** R3.4 File watching / cache invalidation
6. **Key gotcha:** Functions with `!` need `--args-file` workaround for MCP CLI
7. **Deps/Callers:** Placeholder views only - need server-side support

---

*Last Updated: 2026-01-18*
