# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.

**Last Updated:** 2026-01-17
**Version:** v1.14.3
**Focus:** Code Browser v2 Redesign

---

## Current Focus: Code Browser v2 Redesign

**Status:** Architecture finalized, ready for prototyping

The v1 code browser grew to ~3,500 lines with coupling issues. A clean-slate redesign has been architected with 13 key decisions (D1-D13).

### Design Documents (MUST READ)

| Document | Purpose |
|----------|---------|
| `docs/design/code_browser-review-redesign.md` | **Primary design doc** - all decisions, architecture, schemas |
| `docs/design/code_browser-review-redesign-gemini.md` | Gemini 3 Pro review |
| `docs/design/code_browser-review-redesign-gpt52codex.md` | GPT-5.2 Codex review |
| `docs/design/code_browser-review-redesign-grok.md` | Grok review |

### Architecture Decisions Summary

| # | Decision |
|---|----------|
| D1 | Clean slate in `modules/code-browser-v2/` (old code untouched) |
| D2 | Datalevin with portable `IDatalogDB` interface |
| D3 | URI-centric: `<source>://<project>@<version>/<ns>/<symbol>` |
| D4 | Static (git SHA) vs Temporal (UUIDv7) sources |
| D5 | Metadata in Datalevin, content in LRU cache |
| D6 | Layered server + feature slices UI |
| D7 | Isolate volatile (nREPL) sources |
| D8 | Hybrid sync: atom-sync + query protocol |
| D9 | Futures for async |
| D10 | Specs for event protocol |
| D11 | Automated browser tests |
| D12 | Schema in `docs/design/code-browser-schema.md` |
| D13 | Browser module load order documented |

### What's Next

1. **Create `modules/code-browser-v2/`** directory structure
2. **Create `docs/design/code-browser-schema.md`** with Datalevin schema
3. **Implement `code_browser.uri`** - parsing, generation, validation
4. **Implement `code_browser.db.protocol`** - portable Datalog interface
5. **Implement Datalevin backend**
6. **Build directory source adapter** - port clj-kondo logic

---

## Quick Resume

```bash
# Check if server running (v1 still works for reference)
bb server:list

# Start v1 server if needed for reference
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Open browser
open http://localhost:8091

# Run tests
bb test:modules
bb lint && bb format
```

---

## Code Browser v1 (Complete - Reference Only)

All Phase 1.5 features complete. DO NOT MODIFY these files during v2 development:
- `modules/sente-browser/src/sente_browser/code_browser.clj` (2,458 lines)
- `modules/sente-browser/src/browser/code_browser.cljs` (1,101 lines)

**v1 Features:**
- Synced atoms with epoch detection
- clj-kondo rich var classification (64+ var types)
- defmethod/protocol implementation display
- Symbol inspector (Source, Doc, Deps, Callers tabs)
- Multi-file namespace support with file dividers
- Lazy JAR dependency exploration
- Git repo cloning from URL
- Live file watching with debounced refresh
- Aliases & refers panel with shadow detection

---

## Key APIs (v1 - for reference)

| Module | Function | Purpose |
|--------|----------|---------|
| `atom-sync.server` | `register-on-connect!` | Run callback when browser connects |
| `atom-sync.core` | `register-synced-atom!` | Register atom for sync |
| `atom-sync.core` | `get-server-epoch` | Get current epoch |
| `code-browser` | `analyze-file-with-kondo` | Rich var classification |
| `bootstrap` (browser) | `get-synced-atom` | Get Reagent atom by key |

---

## Key Documentation

| Doc | Purpose |
|-----|---------|
| `IMPLEMENTATION_PLAN.md` | Task checklists (v2 redesign phases) |
| `docs/design/code_browser-review-redesign.md` | **v2 architecture** |
| `docs/design/atom-sync-design.md` | Sync architecture |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Browser dev setup |

---

## Handoff Notes

**Server may be running** on ports 3000 (MCP), 8090 (WebSocket), 8091 (Browser UI). Check with `bb server:list`.

**v2 Redesign is ready to start:**
- 13 architectural decisions finalized (D1-D13)
- 3 external reviews (Gemini, GPT-5.2, Grok) all endorse approach
- Clean slate in new module (`modules/code-browser-v2/`)
- Old code stays untouched until v2 is ready

**Key design insights:**
- URI-centric design unifies navigation, history, bookmarks
- Datalevin for metadata (indexed, queryable), LRU cache for content (large blobs)
- Portable `IDatalogDB` allows Datascript for tests
- Static vs Temporal distinction is fundamental

**Key gotchas from v1 (avoid in v2):**
- React keys must be globally unique across namespace switches
- clj-kondo exit code 2 = warnings (use `:continue true`)
- Multi-file NS: kondo's `:ns-files` mapping is authoritative (LSP misses test files)

---

## v1 Session Notes (Archive)

Detailed session notes from v1 development are archived. Key highlights:

- **Phase 1.5E.11 (in-ns):** Fixed via clj-kondo project analysis
- **Phase 1.5E.18 (JAR):** Lazy exploration with NS→JAR mapping
- **Phase 1.5E.20 (Aliases):** Refers from var-usages where `:refer true`
- **Multi-file dividers:** Include 3 path segments for disambiguation
- **Ghost artifacts:** React keys need `selected-ns` + `filename` + `name` + `line`

---

*Last Updated: 2026-01-17*
