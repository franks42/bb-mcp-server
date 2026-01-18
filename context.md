# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-18
**Version:** v1.14.4
**Focus:** Code Browser v2 - Phase R3 (Feature Parity)

---

## Current Focus: Code Browser v2 - Phase R3

**Status:** R0-R2 Complete. R3.1-R3.2 Done. Continuing R3.

### What's Done (R0-R3.2)

| Phase | Summary |
|-------|---------|
| **R0** | URI module, IDatalogDB protocol, Datalevin backend, schema, tests |
| **R1** | Directory source adapter, IProjectSource protocol, clj-kondo integration |
| **R2** | atom-sync wiring, browser state/events, generic list, navigation flow |
| **R3.1** | Symbol inspector tabs (Source, Doc, Deps placeholder, Callers placeholder) |
| **R3.2** | Aliases panel - separate alias/refer entities, browser UI with filter |

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

# MCP CLI with ! functions (use --args-file)
echo '{"code": "(code-browser.core/init!)"}' > /tmp/cmd.json
bb mcp call local-eval --args-file /tmp/cmd.json --mcp cb-v2-test
```

---

## Phase R3: Feature Parity (In Progress)

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done |
| R3.3 | Multi-file namespace support | Pending |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

### R3.2 Implementation Summary

Used **Option 1 (separate entities)**:
- Created `:alias/from-ns`, `:alias/name`, `:alias/to-ns` attributes for aliases
- Created `:refer/from-ns`, `:refer/symbol`, `:refer/from-ns-source` for refers
- URI fragment syntax: `dir://proj@v/ns.name#alias:str`, `dir://proj@v/ns.name#refer:join`
- Browser "Aliases" tab shows both aliases and refers with filtering

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

1. **R3.1-R3.2 complete:** Symbol inspector tabs + Aliases panel
2. **All tests pass:** 30 tests, 459 assertions
3. **Aliases working:** 588 aliases extracted from bb-mcp-server project (0 refers - project doesn't use `:refer`)
4. **Next task:** R3.3 Multi-file namespace support
5. **Key gotcha:** Functions with `!` need `--args-file` workaround for MCP CLI
6. **Deps/Callers:** Placeholder views only - need server-side support to fetch data

---

*Last Updated: 2026-01-18*
