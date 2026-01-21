# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-20
**Version:** v1.14.9
**Focus:** Unified Port Registry & Server Management ✅

---

## 🟢 Unified Port Registry (2026-01-20) - COMPLETE

### What's New in v1.14.9

| Feature | Description |
|---------|-------------|
| **Ephemeral ports** | All services now default to port 0 (OS assigns) |
| **Unified port file** | Single `.ports/<nickname>.json` with all service ports |
| **Collision detection** | Server refuses to start if nickname already running |
| **Restart task** | `bb server:restart` stops + starts same nickname |
| **Stale cleanup** | Dead process port files auto-cleaned |

### Server Management Commands

```bash
bb server:start-wait --nickname NAME --config FILE  # Start + health check
bb server:stop NAME                                  # Stop by nickname
bb server:restart NAME                               # Stop + start
bb server:list                                       # List running servers
bb server:ports NAME                                 # Show all ports
```

### Files Changed

| File | Changes |
|------|---------|
| `src/bb_mcp_server/port_registry.clj` | New unified registry with collision detection |
| `src/bb_mcp_server/main.clj` | Collision check before startup |
| `scripts/start_wait_server.clj` | Ephemeral port + port file discovery |
| `scripts/restart_server.clj` | New restart script |
| `modules/sente-browser/*` | Default to ephemeral ports |
| `modules/nrepl-test-server/*` | Default to ephemeral port |

### How It Works

1. Server starts with port 0 (ephemeral)
2. OS assigns actual port
3. Server writes `.ports/<nickname>.json` with all ports
4. Scripts/clients discover ports from port file
5. On startup, collision detection checks if nickname already running
6. On shutdown, port file is deleted

---

## 🟢 v2 Status (2026-01-19) - WORKING

### What WORKS ✅

| Component | Evidence |
|-----------|----------|
| **Server-side v2 code** | 30 unit tests pass (459 assertions) |
| **Query deadlock fix** | Args wrapped in vectors in `handlers.clj:73,90,104,118` |
| **Datalevin backend** | Unit tests verify all CRUD operations |
| **atom-sync wiring** | Unit tests verify state sync |
| **v2 browser loading** | Use `nrepl.nrepl-eval-local-file` (NOT `bb nrepl load-file`) |
| **Full navigation flow** | Project → Namespace → Symbol → Source all working |
| **Source display** | File path and line numbers shown correctly |

### Verified Browser Flow (2026-01-19)

| Step | Result |
|------|--------|
| Load v2 browser code | ✅ Namespace created in browser |
| Mount v2 UI | ✅ 4-panel layout displays |
| Click project | ✅ 201 namespaces load |
| Click namespace | ✅ 13 symbols load for `code-browser.core` |
| Click symbol | ✅ Source displays with file path + line numbers |

### Key Discovery: Stale Database Issue

**Problem:** Clicking symbols showed "Select a symbol to view source" even though selection worked.

**Root cause:** Datalevin stores URIs with commit hashes (e.g., `dir://.@ceb04b4/ns/symbol`). After commits, the source adapter registers symbols with the NEW hash, but database has OLD URIs. Lookup fails.

**Fix:** Delete database directory before init:
```bash
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock
```

### Working v2 Setup Commands

```bash
# 1. Start fresh (MUST delete stale database!)
bb server:stop cb-v2-test 2>/dev/null || true
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# 2. Initialize v2 backend
cat > /tmp/init-v2.json << 'EOF'
{"code": "(require '[code-browser.core :as cb-v2]) (cb-v2/init! {:db-path \"/tmp/cb-v2-test\" :sources [{:type :dir :path \".\"}]})"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/init-v2.json --mcp cb-v2-test

# 3. Open browser, get connection nickname
open http://localhost:8091
bb nrepl list --mcp cb-v2-test  # Note browser-N

# 4. Load v2 code (use nrepl-eval-local-file, NOT bb nrepl load-file!)
bb mcp call nrepl.nrepl-eval-local-file \
  '{"file-path": "modules/sente-browser/src/browser/code_browser_v2.cljs", "connection": "browser-N"}' \
  --mcp cb-v2-test

# 5. Mount v2 (use --args-file for ! character)
echo '{"code":"(code-browser-v2/mount!)","connection":"browser-N"}' > /tmp/mount-v2.json
bb mcp call nrepl.nrepl-eval --args-file /tmp/mount-v2.json --mcp cb-v2-test
```

### Server-Side Fixes Applied (2026-01-18/19)

| Issue | Fix Applied | File Changed |
|-------|-------------|--------------|
| **Error persists after init** | `init!` now clears previous error state | `core.clj:165` |
| **Browser connects before init** | `enable!` now defensive - skips project load if no DB | `core.clj:122-127` |
| **Manual enable needed** | `init!` now auto-enables via `:auto-enable? true` | `core.clj:180-184` |
| **Server deadlock on query** | `db-proto/q` args wrapped in vector for `apply` | `handlers.clj:73,90,104,118` |

### Query Deadlock Fix Details

The server was deadlocking when selecting a project. Root cause: `db-proto/q` uses `apply` to spread args, but handlers passed bare strings instead of vectors. This caused strings to be spread as character sequences, corrupting Datalevin queries.

**Fix:** Wrapped all query arguments in vectors:
```clojure
;; Before (BROKEN - string spread as chars):
(db-proto/q db '[:find ...] project-uri)

;; After (CORRECT):
(db-proto/q db '[:find ...] [project-uri])
```

### Recommended Next Steps

1. **For v2 development:** Use unit tests only (`bb test:module code-browser-v2`)
2. **For browser testing v1:** Use existing "Load Code Browser" button
3. **To fix v2 browser loading:** Investigate why `bb nrepl load-file` doesn't create namespace in browser context

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

> ✅ **v2 browser testing is WORKING** as of 2026-01-19.
> See `docs/SCITTLE_DEV_ENVIRONMENT.md` for complete setup guide.

### Quick Test

```bash
bb test:module code-browser-v2   # 30 tests, 459 assertions - server-side tests
```

### Browser Testing Options

| Option | How |
|--------|-----|
| **v1** | Click "Load Code Browser" button |
| **v2** | Use `nrepl.nrepl-eval-local-file` to load code, then mount (see setup commands above) |

### Important Notes

- **"Load Code Browser" button loads v1, NOT v2** - this is expected
- **Must clear database** when code changes commit (stale URI issue)
- **Use `nrepl.nrepl-eval-local-file`** NOT `bb nrepl load-file` for browser

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

### What Works
1. **Unit tests:** 30 tests, 459 assertions all pass - server-side v2 code is solid
2. **R3.1-R3.3 features:** Symbol inspector tabs, Aliases panel, Multi-file support (in code)
3. **Server-side fixes:** Query deadlock fixed, error handling improved, auto-enable works
4. **v1 browser:** "Load Code Browser" button works for v1

### What's Fixed ✅ (2026-01-19)
5. **v2 browser loading now works:**
   - Use `nrepl.nrepl-eval-local-file` (NOT `bb nrepl load-file`)
   - Reads file locally, sends content as code to browser's Scittle context
   - See "Working v2 browser loading" section above for commands
6. **Docs need update:** `docs/SCITTLE_DEV_ENVIRONMENT.md` should use `nrepl-eval-local-file`

### Next Steps (Priority Order)
1. **Verify full v2 flow:** Click events, navigation, source display
2. **Update SCITTLE_DEV_ENVIRONMENT.md** with correct tool
3. **Continue with R3.4** (file watching) or remaining browser verification

### Technical Notes
- Functions with `!` need `--args-file` workaround for MCP CLI
- Deps/Callers tabs are placeholder views - need server-side support

---

*Last Updated: 2026-01-20*
