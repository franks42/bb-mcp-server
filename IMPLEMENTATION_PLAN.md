# bb-mcp-server Implementation Plan

**Status:** Code Browser v2 Redesign - Architecture Finalized
**Version:** v1.14.3
**Last Updated:** 2026-01-17

---

## Current State

Production-ready MCP server with complete Code Browser v1 feature set. Now starting clean-slate redesign for v2.

**Code Browser v1 (Complete):** 3,500 lines across server + client with synced atoms, live file watching, JAR exploration, git cloning, multi-file namespace support, symbol inspector with deps/callers tabs.

**Code Browser v2 (Starting):** Clean slate redesign with URI-centric architecture, Datalevin backend, and modular design.

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

#### Phase R0: Foundation (Current Focus)

| Task | Description | Status |
|------|-------------|--------|
| R0.1 | Create `modules/code-browser-v2/` directory structure | Pending |
| R0.2 | Create `docs/design/code-browser-schema.md` | Pending |
| R0.3 | Implement `code_browser.uri` (parse, generate, validate) | Pending |
| R0.4 | Implement `code_browser.db.protocol` (IDatalogDB) | Pending |
| R0.5 | Implement Datalevin backend | Pending |
| R0.6 | Write unit tests for URI + DB protocol | Pending |

#### Phase R1: Directory Source Adapter

| Task | Description | Status |
|------|-------------|--------|
| R1.1 | Define `IProjectSource` protocol | Pending |
| R1.2 | Implement directory adapter (port clj-kondo logic) | Pending |
| R1.3 | Populate Datalevin with namespace/symbol metadata | Pending |
| R1.4 | Implement content cache for source fetching | Pending |
| R1.5 | Write integration tests | Pending |

#### Phase R2: Minimal End-to-End

| Task | Description | Status |
|------|-------------|--------|
| R2.1 | Wire atom-sync exports from Datalevin views | Pending |
| R2.2 | Build browser state + events | Pending |
| R2.3 | Build generic list component | Pending |
| R2.4 | Wire project → namespace → symbol → source flow | Pending |
| R2.5 | Manual testing: basic navigation works | Pending |

#### Phase R3: Feature Parity

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | Pending |
| R3.2 | Aliases panel | Pending |
| R3.3 | Multi-file namespace support | Pending |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

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

*Last Updated: 2026-01-17*
