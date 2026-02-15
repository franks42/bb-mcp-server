# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-15 (late evening)
**Version:** v1.31.4
**Focus:** Stable — All CLI `!` escaping fixed, MCP tools installed, ready for browser E2E

---

## Current State — STABLE

All tests pass (163 tests, 503 assertions, 0 failures). Lint and format clean.

**E2E drum roll test passed (2026-02-15):**
- INSERT: Created `my-test-ns` with 3 symbols (greet, add, multiply) → stored in Datalevin
- UPDATE: Added `divide` (with docstring) → fingerprint check detected change → retracted 3, inserted 4 → committed in <15ms
- Pod stays alive after insert + update cycles
- Server shuts down gracefully (no SIGKILL needed)
- Database healthy: 3 projects, 93 nREPL namespaces, 225 bb-mcp-server namespaces

**Not yet verified:** Visual confirmation in browser (needs Playwright/Chrome MCP — see below)

---

## Dev Environment Setup

```bash
# Standard dev environment (ALWAYS use this):
bb dev:cb-v2                    # Full start: target + server + browser
bb dev:cb-v2 start --no-open    # Same but skip browser
bb dev:cb-v2 stop               # Stop server + nREPL target
bb dev:cb-v2 status             # Check both processes

# Two-process architecture:
# Process 1: nREPL target on port 9876 (auto-managed by dev:cb-v2)
# Process 2: Main server — fixed MCP port 54321, nREPL 7888
# Browser:   http://localhost:8091

# Testing:
bb nrepl-direct eval "(+ 1 2)" -t cb-v2-test
bb nrepl-direct eval "(ns my-test-ns) (defn greet [name] (str \"Hello, \" name))" --port 9876
```

### MCP Servers Installed (user scope — all projects)

| Server | Package | Purpose |
|--------|---------|---------|
| `playwright` | `@playwright/mcp@latest` | Browser automation via accessibility tree |
| `chrome-devtools` | `chrome-devtools-mcp@latest` | Chrome DevTools (navigate, screenshot, evaluate JS) |
| `bb-mcp-nrepl` | `mcp-remote → localhost:54321/mcp` | nREPL tools from running bb-mcp-server |
| `memory` | `mcp-memory-service` (uv) | Persistent memory across sessions |

**bb-mcp-nrepl requires `bb dev:cb-v2` running** — it connects to the fixed MCP port 54321.

**To use Playwright/Chrome in a session:** Restart Claude Code after install. Use `browser_navigate`, `browser_snapshot`, `browser_take_screenshot`, `browser_click`, `browser_evaluate` etc.

### Key Ports (fixed)

| Port | Service |
|------|---------|
| 54321 | MCP HTTP endpoint (for Claude Code MCP client) |
| 9876 | nREPL target (introspection subject) |
| 7888 | nREPL server (main server) |
| 8090 | Sente WebSocket |
| 8091 | Browser bootstrap (open this in browser) |
| 1667 | nREPL proxy |

---

## Fingerprint Polling — Known Behavior

The fingerprint-based change detection requires **two calls** to detect a change after the initial scan:
1. First call with no stored fingerprint → stores baseline, returns `changed? false`
2. Second call → compares against stored baseline, detects actual changes

When the browser polls automatically, this happens naturally. For manual testing, trigger the check twice or use `core/rescan-project!` for immediate effect.

---

## Fixed Issues

### Fixed: Datalevin Pod transact! Hang — ROOT CAUSE (2026-02-15)

**Two root causes found and fixed:**

1. **Nil values in tx-data (handlers.clj):** `update-namespace-symbols!` sent entity maps with nil-valued attributes. Pod's transit serialization failed on nils, exception handler also failed, no response written → client blocked forever. **Fix:** Added `clean-entity` to strip nil values.

2. **`pod-call-with-timeout` future wrapper (datalevin.clj):** Scattered pod I/O across random threads via `(future ...)`. Timed-out futures left zombie threads on pod stdout, desynchronizing bencode protocol. **Fix:** Removed wrapper, reverted to direct calls.

**Research findings (for future reference):**
- babashka/pods #60: Pod read errors cause hangs (fixed Dec 2022)
- datalevin #274: Hangs on unknown attributes (nil → no response)
- datalevin #331: Query threading in write tx (fixed v0.10.1)
- Pod exception handler: non-transit-serializable `ex-data` → no response → hang
- No runtime logging for datalevin pod (hardcoded `debug? false`). Alternatives: bencode proxy, macOS `sample <pid>`

### Fixed: Datalevin 0.9.27 → 0.10.5 (2026-02-15)

Updated across all files. Old databases deleted and recreated.

### Fixed: db-lock for Pod Serialization (2026-02-14–15)

`(defonce db-lock (Object.))` in handlers.clj. All pod-calling functions use `(locking db-lock ...)`. `dispatch-event` itself NOT locked (nREPL evals can take 30s+).

**Note:** `datalevin-pod` module's functions do NOT use `db-lock` — concern if sharing pod process.

### Fixed: `!` Escaping in All CLI Scripts (2026-02-15)

Claude Code's Bash tool escapes `!` → `\!` in single-quoted strings (known bug: anthropics/claude-code#2941, closed NOT_PLANNED). Since `\!` is never valid Clojure, all four CLI scripts now auto-unescape `\!` → `!`:

| Script | CLI command | Committed |
|--------|------------|-----------|
| `scripts/nrepl_direct_cli.clj` | `bb nrepl-direct eval` | `5d09b64` |
| `scripts/nrepl_cli.clj` | `bb nrepl eval` | `82934b3` |
| `scripts/mcp_eval_script.clj` | `bb mcp-eval` | `82934b3` |
| `scripts/mcp_cli.clj` | `bb mcp call` | `82934b3` |

**MCP nrepl tools do NOT need this fix** — they receive params via JSON-RPC over HTTP, not shell args.

**Best practice (still in CLAUDE.md):** Always use double quotes for eval strings containing `!`.

### Fixed: Self-Introspection Deadlock (2026-02-14)

External nREPL target on port 9876. `bb dev:cb-v2` manages both processes.

### Fixed: nrepl-direct Silent Error Swallowing (2026-02-14)

Check `:ex`/`:root-ex` in response. v1.31.0.

---

## Recent Commits

- `82934b3` — Fix: Auto-unescape `\!` in all MCP CLI scripts (nrepl, mcp-eval, mcp call)
- `5d09b64` — Fix: Auto-unescape `\!` in nrepl-direct eval strings
- `601f939` — Docs: Update context.md with E2E results, MCP setup, port reference
- `b8a9d58` — Fix MCP port to 54321 for dev environment
- `47cc2b8` — Clean up diagnostic scripts, bogus URL directories, empty .mcp.json
- `2da6f71` — Fix Datalevin pod transact! hang — nil values + future wrapper

---

## Next Session Priorities

1. **Browser visual E2E test** — Use Playwright MCP to navigate to http://localhost:8091, verify projects/namespaces/symbols render correctly, take screenshots
2. **Monitor server stability** — confirm fingerprint polling works without hangs over extended period
3. **Consider `datalevin-pod` module locking** — its functions bypass `db-lock`, potential concurrent access if sharing pod process with code-browser-v2
4. **Fingerprint first-check gap** — The first fingerprint baseline stores the current nREPL state, but if namespaces were added between initial scan and first fingerprint check, the DB is out of sync until the next change. Consider comparing baseline against DB during first check.

---

*For detailed debugging history, see git log.*
