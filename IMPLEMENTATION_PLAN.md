# bb-mcp-server Implementation Plan

**Status:** Code Browser v2 - Server-side WORKS ✅, Browser loading FIXED ✅
**Version:** v1.14.8
**Last Updated:** 2026-01-19

---

## Current State

Production-ready MCP server with complete Code Browser v1 feature set. Code Browser v2 core logic complete (R0-R3.3) with **30 passing unit tests**. **Browser integration is WORKING** - full navigation flow verified (project → namespace → symbol → source).

**Code Browser v1 (Complete):** 3,500 lines across server + client with synced atoms, live file watching, JAR exploration, git cloning, multi-file namespace support, symbol inspector with deps/callers tabs.

**Code Browser v2 (Core Complete):** Clean slate redesign with URI-centric architecture, Datalevin backend, and modular design. **Unit tests pass, browser loading works, full navigation flow verified.**

### ✅ Server-Side Fixes Applied (2026-01-18/19)

| Issue | Status | Root Cause | Fix Applied |
|-------|--------|------------|-------------|
| **Server deadlock on queries** | ✅ Fixed | `db-proto/q` args passed as bare strings, `apply` spread them as chars | Wrapped query args in vectors: `[project-uri]` |
| **Error state persists** | ✅ Fixed | Error set before init, not cleared | `init!` now clears error state |
| **Enable defensive** | ✅ Fixed | `enable!` failed if no DB | Now skips project load if no DB |
| **Manual enable needed** | ✅ Fixed | Had to call enable manually | `init!` auto-enables via `:auto-enable? true` |

### ✅ v2 Browser Loading FIXED (2026-01-19)

**Solution:** Use `nrepl.nrepl-eval-local-file` instead of `bb nrepl load-file`

| Tool | How it works | Result |
|------|--------------|--------|
| `bb nrepl load-file` ❌ | Uses nREPL `load-file` op (requires filesystem) | Code goes to server, not browser |
| `nrepl.nrepl-eval-local-file` ✅ | Reads file locally, sends content as code | Code evaluated in browser Scittle |

**Working v2 browser loading commands:**
```bash
# 1. Load v2 code into browser
bb mcp call nrepl.nrepl-eval-local-file \
  '{"file-path": "/path/to/modules/sente-browser/src/browser/code_browser_v2.cljs", "connection": "browser-X"}' \
  --mcp <server>

# 2. Mount v2 (use --args-file for ! character)
echo '{"code":"(code-browser-v2/mount!)","connection":"browser-X"}' > /tmp/mount-v2.json
bb mcp call nrepl.nrepl-eval --args-file /tmp/mount-v2.json --mcp <server>
```

**✅ Done:** `docs/SCITTLE_DEV_ENVIRONMENT.md` updated with correct tool and stale database fix.

---

## Dev Environment Quick Reference

```bash
# Run tests (ALWAYS before committing)
bb test:modules          # All module tests
bb lint                  # clj-kondo (must be 0 errors, 0 warnings)
bb format                # cljfmt check

# Start servers
bb server                           # Stdio (Claude Desktop)
bb server --http                    # HTTP on port 3000
bb server --http --config <file>    # Custom config

# Code browser dev (v1)
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
# Then open http://localhost:8091

# nREPL CLI
bb nrepl list --mcp <nickname>      # List connections
bb nrepl eval "<code>" --mcp <nick> # Eval code

# MCP CLI
bb mcp servers                      # List running servers
bb mcp tools --mcp <nickname>       # List tools
bb mcp call <tool> '<json>' --mcp <nick>  # Call tool
```

**Verification workflow (run before every commit):**
```bash
bb lint && bb format && bb test:modules
```

---

## Code Browser v2 Redesign

**Design docs:**
- `docs/design/code_browser-review-redesign.md` - Primary design document
- `docs/design/code_browser-review-redesign-gemini.md` - Gemini 3 Pro review
- `docs/design/code_browser-review-redesign-gpt52codex.md` - GPT-5.2 Codex review
- `docs/design/code_browser-review-redesign-grok.md` - Grok review

### Why Redesign?

The current implementation has grown to ~3,500 lines with:
- **God Object anti-pattern**: Single `!code-browser-state` atom with ~20 keys
- **Handler spaghetti**: Complex interdependencies and scattered side effects
- **Mixed concerns**: Analysis, LSP, JAR, Git, file watching all in one file
- **Unclear caching**: Multiple strategies without clear invalidation policy

### Architecture Decisions (D1-D13)

| Decision | Summary |
|----------|---------|
| **D1** | Clean slate rewrite in `modules/code-browser-v2/` |
| **D2** | Datalevin backend with portable `IDatalogDB` interface |
| **D3** | URI-centric design: `<source>://<project>@<version>/<ns>/<symbol>` |
| **D4** | Static vs Temporal sources (git SHA vs UUIDv7 snapshots) |
| **D5** | Separate metadata (Datalevin) from content (LRU cache) |
| **D6** | Layered server architecture + feature slices for UI |
| **D7** | Isolate volatile (nREPL) sources from static caches |
| **D8** | Hybrid sync: atom-sync for lists, query protocol for content |
| **D9** | Futures for async (Babashka-compatible) |
| **D10** | Specs for event protocol (type-safe events) |
| **D11** | Layered testing with automated browser tests |
| **D12** | Minimal schema upfront in `docs/design/code-browser-schema.md` |
| **D13** | Browser module loading order explicitly documented |

### New Module Structure

```
modules/code-browser-v2/
├── src/code_browser/
│   ├── uri.cljc              # URI parsing/generation/validation
│   ├── schema.cljc           # Datalevin schema definition
│   ├── db/
│   │   ├── protocol.clj      # IDatalogDB interface
│   │   └── datalevin.clj     # Datalevin implementation
│   ├── content.clj           # Content cache (source, docs) - LRU
│   ├── sources/
│   │   ├── protocol.clj      # IProjectSource interface
│   │   ├── directory.clj     # Local directory (clj-kondo)
│   │   ├── jar.clj           # JAR file analysis
│   │   ├── github.clj        # GitHub clone + analyze
│   │   └── nrepl.clj         # Live nREPL introspection
│   ├── handlers.clj          # Event handlers
│   ├── sync.clj              # atom-sync exports
│   └── core.clj              # Public API, init
├── resources/public/js/
│   └── code_browser/         # Browser Scittle code
│       ├── uri.cljs
│       ├── state.cljs
│       ├── events.cljs
│       ├── components/
│       │   ├── list.cljs     # Generic selectable list
│       │   ├── projects.cljs
│       │   ├── namespaces.cljs
│       │   ├── symbols.cljs
│       │   └── detail.cljs
│       └── main.cljs
├── test/code_browser/
└── module.edn
```

### Implementation Phases

#### Phase R0: Foundation ✅ COMPLETE

| Task | Description | Status |
|------|-------------|--------|
| R0.1 | Create `modules/code-browser-v2/` directory structure | ✅ Done |
| R0.2 | Create `docs/design/code-browser-schema.md` | ✅ Done |
| R0.3 | Implement `code_browser.uri` (parse, generate, validate) | ✅ Done |
| R0.4 | Implement `code_browser.db.protocol` (IDatalogDB) | ✅ Done |
| R0.5 | Implement Datalevin backend | ✅ Done |
| R0.6 | Write unit tests for URI + DB protocol | ✅ Done |

#### Phase R1: Directory Source Adapter ✅ COMPLETE

| Task | Description | Status |
|------|-------------|--------|
| R1.1 | Define `IProjectSource` protocol | ✅ Done |
| R1.2 | Implement directory adapter (port clj-kondo logic) | ✅ Done |
| R1.3 | Populate Datalevin with namespace/symbol metadata | ✅ Done |
| R1.4 | Implement content cache for source fetching | ✅ Done |
| R1.5 | Write integration tests | ✅ Done |

#### Phase R2: Minimal End-to-End ✅ COMPLETE

| Task | Description | Status |
|------|-------------|--------|
| R2.1 | Wire atom-sync exports from Datalevin views | ✅ Done |
| R2.2 | Build browser state + events | ✅ Done |
| R2.3 | Build generic list component | ✅ Done |
| R2.4 | Wire project → namespace → symbol → source flow | ✅ Done |
| R2.5 | Manual testing: basic navigation works | ✅ Done |

**Notes from R2:**
- `--args-file` option added to `bb mcp call` to bypass bash `!` escaping
- `bb datalevin:status/stop/cleanup` tasks added for pod management

#### Phase R3: Feature Parity

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done (unit tests) |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done (unit tests) |
| R3.3 | Multi-file namespace support | ✅ Done (unit tests) |
| R3.x | Fix v2 browser loading | ✅ Done (use `nrepl-eval-local-file`) |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

**Notes from R3.1-R3.3:**
- All features implemented and unit tested (30 tests, 459 assertions)
- Browser loading works (use `nrepl-eval-local-file`)
- Full browser verification COMPLETE (2026-01-19): project → namespace → symbol → source all working

**Notes from R3.x (Browser Loading - FIXED 2026-01-19):**

**Problem:** `bb nrepl load-file` doesn't work for browser code (requires filesystem access).

**Solution:** Use `nrepl.nrepl-eval-local-file` MCP tool instead:
- Reads file locally on the machine running the CLI
- Sends file content as code to be evaluated
- Works for browser Scittle because it doesn't require filesystem on target

**Key insight:** Browser has no filesystem. The correct tool reads file locally and sends content as eval code, not as a load-file operation.

See "✅ v2 Browser Loading FIXED" section above for commands.

#### Phase R4: Additional Sources

| Task | Description | Status |
|------|-------------|--------|
| R4.1 | JAR source adapter | Pending |
| R4.2 | GitHub source adapter | Pending |
| R4.3 | nREPL source adapter (Live Mode) | Pending |

#### Phase R5: Polish & Switchover

| Task | Description | Status |
|------|-------------|--------|
| R5.1 | Full test coverage (unit + integration + browser) | Pending |
| R5.2 | Performance testing with large codebases | Pending |
| R5.3 | Update documentation | Pending |
| R5.4 | Switch config to use v2 | Pending |
| R5.5 | Remove old code browser | Pending |

---

## Code Browser v1 (Complete - Reference Only)

All Phase 1.5 features complete. See `context.md` for session notes.

**Key features:**
- Synced atoms with epoch detection
- clj-kondo rich var classification
- defmethod/protocol implementation display
- Symbol inspector with tabs
- Multi-file namespace support
- Lazy JAR exploration
- Git repo cloning
- Live file watching

**Files (DO NOT MODIFY during v2 development):**
- `modules/sente-browser/src/sente_browser/code_browser.clj` (2,458 lines)
- `modules/sente-browser/src/browser/code_browser.cljs` (1,101 lines)

---

## Future Work (Post v2)

### Phase 2: Live Mode (nREPL Introspection)

Connect to running nREPL and introspect live system:
- Loaded vs unloaded namespaces
- Live var values and metadata
- tools.trace integration
- ClojureDocs examples

**Reference:** `../clj-ns-browser` for feature inspiration.

### Phase 3: Symbol-at-Point

Click any symbol in source viewer → navigate to definition:
- LSP hover integration
- Special form detection
- Cross-project navigation

---

## Completed Infrastructure (Reference)

| Phase | Description |
|-------|-------------|
| 1-7 | Foundation, MCP server, tool registry, module system |
| 8-10 | Transport modularization (stdio, http, rest-api) |
| 11-12 | Unified entry point, telemetry |
| 13 | AI orchestration (multi-provider, experts) |
| 14-15 | Dynamic module loading, Datalevin integration |
| 16-20 | nREPL proxy, Scittle browser, MCP CLI, E2E tests |

---

## References

- [Code Browser Redesign](docs/design/code_browser-review-redesign.md)
- [Atom Sync Design](docs/design/atom-sync-design.md)
- [Static Code Analysis](docs/design/static-code-analysis.md)
- [Module System Design](docs/design/module-system-design.md)

---

*Last Updated: 2026-01-19*
