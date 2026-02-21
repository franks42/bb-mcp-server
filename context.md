# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-21
**Version:** v1.38.0
**Focus:** Scittle runtime browsing — unit tests done, integration testing next

---

## Current State — STABLE + UNIT TESTS PASSING

All new code lints clean (0 errors, 0 warnings). 16 new Scittle tests pass.
5 pre-existing failures in `handlers_test.clj` (aliases/refers version-agnostic, ns-info-basic) — NOT caused by recent work.

**Recent feature work (v1.38.0):**
- **v1.38.0** — Scittle runtime browsing: multi-stage runtime detection, 9 multimethods for :scittle dispatch, SCI-safe eval code, demo infrastructure, dev script
- **v1.37.0** — Namespace Info panel: doc, file, symbol count, clickable requires/required-by navigation
- **v1.36.0** — Project-level inspector panel with runtime info (nREPL/dir/JAR)
- **v1.35.0** — FQN URI in browser title bar, async JAR scanning, deduplication fixes
- **v1.34.0** — Auto-scroll, navigation history, symbol-at-point (Cmd+Click), drag-drop
- **v1.33.0** — Pharo-style 4-pane browser, runtime type inference, keyboard nav, sort modes

**MCP Memory Service — Upgraded (2026-02-19):**
- Upgraded from v3.3.3 (ChromaDB) → v10.16.1 (SQLite-vec)
- Config: `~/.mcp.json` now uses `sqlite_vec` backend

---

## NEXT STEPS — Integration Testing with Playwright

The Scittle runtime browsing code is implemented and unit-tested. **Integration testing is the next step:**

### Step 1: Start the Demo Scittle App (Process A)
```bash
bb dev:demo-scittle start    # Starts server, opens browser at http://localhost:8191
# Wait for green "Connected" in browser
bb nrepl-direct load-local-file demo/scittle-app/demo_app.cljs -t demo-scittle/browser-1
```

### Step 2: Start the Code Browser (Process B)
```bash
bb dev:cb-v2 start           # Starts code browser, connects to demo-scittle port 2667
```

### Step 3: Playwright Verification
Use Playwright MCP tools to verify:
1. Select "demo-scittle" project → verify project info shows "scittle" type
2. Select `demo.core` namespace → verify symbols (`increment!`, `decrement!`, `!app-state`)
3. Select `!app-state` → verify Value tab shows atom contents
4. Click + button in demo app → verify Value tab detects change via polling
5. Verify Source tab shows "Source not available" message

### Key Files for Scittle Runtime
| File | Purpose |
|------|---------|
| `modules/code-browser-v2/src/code_browser/sources/runtime/scittle.clj` | 9 multimethods for :scittle |
| `modules/code-browser-v2/src/code_browser/sources/runtime.clj` | Multi-stage detect-runtime |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | Project info display (3-way cond) |
| `system-demo-scittle.edn` | Demo app server config |
| `demo/scittle-app/demo_app.cljs` | Demo app (3 namespaces) |
| `scripts/demo_scittle_dev.clj` | Dev script |
| `system-cb-v2-test.edn` | Code browser config (includes demo-scittle source) |

### Scittle vs Babashka — Key Differences in Eval Code
- `(instance? Atom val)` instead of `clojure.lang.IAtom`
- `(hash val)` instead of `System/identityHashCode` (value-based, not identity-based)
- `(catch :default _)` instead of `(catch Exception _)`
- `pr-str` instead of `java.io.StringWriter` + `clojure.pprint/pprint`
- No `clojure.repl/source-fn` — source unavailable
- No `clojure.lang.MultiFn` — simplified type detection (macro/fn/def)
- No `ratio?`, `uri?`, `delay?`, `future?` predicates
- No statechart/Service/Store detection (JVM class-based)

---

## Dev Environment Setup

```bash
# Standard dev environment (ALWAYS use this):
bb dev:cb-v2                    # Full start: target + server + browser
bb dev:cb-v2 start --no-open    # Same but skip browser
bb dev:cb-v2 stop               # Stop server + nREPL target
bb dev:cb-v2 status             # Check both processes

# Demo Scittle app (browsing subject):
bb dev:demo-scittle start       # Start demo app server
bb dev:demo-scittle stop        # Stop it
bb dev:demo-scittle status      # Check status

# Two-process architecture (code browser):
# Process 1: nREPL target on port 9876 (auto-managed by dev:cb-v2)
# Process 2: Main server — fixed MCP port 54321, nREPL 7888
# Browser:   http://localhost:8091

# Three-process architecture (scittle browsing):
# Process A: Demo Scittle app — ports 8190/8191/2667/7988
# Process B: Code Browser — ports 8090/8091/54321/7888/9876
# Process A's nREPL proxy (2667) is listed as source in system-cb-v2-test.edn

# Testing:
bb nrepl-direct eval "(+ 1 2)" -t cb-v2-test
bb nrepl-direct eval "(+ 1 2)" -t demo-scittle/browser-1
```

### MCP Servers Installed (user scope — all projects)

| Server | Package | Purpose |
|--------|---------|---------|
| `playwright` | `@playwright/mcp@latest` | Browser automation via accessibility tree |
| `chrome-devtools` | `chrome-devtools-mcp@latest` | Chrome DevTools (navigate, screenshot, evaluate JS) |
| `bb-mcp-nrepl` | `mcp-remote → localhost:54321/mcp` | nREPL tools from running bb-mcp-server |
| `memory` | `mcp-memory-service` v10.16.1 (uv, sqlite_vec) | Persistent memory across sessions |

**bb-mcp-nrepl requires `bb dev:cb-v2` running** — it connects to the fixed MCP port 54321.

### Key Ports (fixed)

| Port | Service | Process |
|------|---------|---------|
| 54321 | MCP HTTP endpoint (for Claude Code MCP client) | Code Browser |
| 9876 | nREPL target (introspection subject) | Code Browser |
| 7888 | nREPL server (main server) | Code Browser |
| 8090 | Sente WebSocket | Code Browser |
| 8091 | Browser bootstrap | Code Browser |
| 1667 | nREPL proxy | Code Browser |
| 8190 | Sente WebSocket | Demo Scittle |
| 8191 | Browser bootstrap | Demo Scittle |
| 2667 | nREPL proxy | Demo Scittle |
| 7988 | nREPL server | Demo Scittle |

---

## Fingerprint Polling — Known Behavior

The fingerprint-based change detection requires **two calls** to detect a change after the initial scan:
1. First call with no stored fingerprint → stores baseline, returns `changed? false`
2. Second call → compares against stored baseline, detects actual changes

**Scittle note:** Uses `(hash val)` which is value-based (not identity-based like JVM's `System/identityHashCode`). This means changes are detected when values change, not just when identity changes. Acceptable tradeoff for SCI runtime.

---

## Fixed Issues

### Fixed: Datalevin Pod transact! Hang — ROOT CAUSE (2026-02-15)

**Two root causes found and fixed:**

1. **Nil values in tx-data (handlers.clj):** `update-namespace-symbols!` sent entity maps with nil-valued attributes. Pod's transit serialization failed on nils, exception handler also failed, no response written → client blocked forever. **Fix:** Added `clean-entity` to strip nil values.

2. **`pod-call-with-timeout` future wrapper (datalevin.clj):** Scattered pod I/O across random threads via `(future ...)`. Timed-out futures left zombie threads on pod stdout, desynchronizing bencode protocol. **Fix:** Removed wrapper, reverted to direct calls.

### Fixed: `!` Escaping in All CLI Scripts (2026-02-15)

Claude Code's Bash tool escapes `!` → `\!` in single-quoted strings. All four CLI scripts auto-unescape.
**Best practice (still in CLAUDE.md):** Always use double quotes for eval strings containing `!`.

### Fixed: Self-Introspection Deadlock (2026-02-14)

External nREPL target on port 9876. `bb dev:cb-v2` manages both processes.

---

## Recent Commits

- `(pending)` — Feat: Scittle runtime browsing — multi-stage detection, 9 multimethods, demo infrastructure
- `87c8fd1` — Feat: Clickable dependency chips in ns-info panel
- `ee9a94f` — Test: Add ns-info inspector panel screenshot
- `fad90b8` — Feat: Namespace Info panel in code browser inspector
- `d38877e` — Feat: Project-level inspector panel with runtime info
- `5b9bf0d` — Feat: Show FQN URI in browser title bar with Live indicator

---

## Next Session Priorities

1. **Integration test Scittle browsing with Playwright** — see "NEXT STEPS" section above
2. **Consider `datalevin-pod` module locking** — its functions bypass `db-lock`, potential concurrent access
3. **Fingerprint first-check gap** — first baseline stores current state, but additions between scan and check are missed
4. **Long-term: Datalevin MCP memory module** — replace Python memory service with pure Clojure

---

*For detailed debugging history, see git log.*
