# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-17
**Version:** v1.32.0
**Focus:** JLine3-based nREPL REPL client (`bb nrepl-repl`) — fully working

---

## Current State — STABLE + E2E VERIFIED

All tests pass (10 module tests, 33 assertions, 0 failures). Lint and format clean.

**Playwright E2E demo passed (2026-02-15):**
- Created `demo.hello` (greet fn) → appeared in browser (94 ns)
- Added `goodbye` fn → symbol list updated to 2 symbols
- Updated `greet` docstring → reflected after rescan
- Created `demo.math-utils` (add, multiply) → 95 ns in browser
- Removed `demo.hello` via `remove-ns` → rescan retracted it → **gone from browser** (94 ns)
- Confirmed bug reproduction: old code left stale ns with 0 symbols; fixed code removes it entirely
- Screenshots saved as `demo-step[1-7]-*.png`

**Previous E2E (2026-02-15):**
- INSERT/UPDATE cycle with `my-test-ns` verified in Datalevin
- Pod stays alive, server shuts down gracefully
- Database healthy: 3 projects, 93+ nREPL namespaces

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

- `8c9f6fd` — Fix: Make nREPL session optional for tab completions
- `e8a0d14` — Fix: Proper JLine3 Parser/Completer with type hints and word-at-cursor
- `95e5f2a` — Fix: Use 'completions' op and raw bencode for nrepl-repl tab completion
- `26cb554` — Fix: Use reflection for ParsedLine.word() in nrepl-repl completer
- `2ba48ae` — Fix: Use reify instead of proxy for ParsedLine in nrepl-repl
- `ea3c75d` — Feat: Add JLine3-based nREPL REPL client for Babashka
- `5b72686` — Demo: Playwright integration test of rescan-project! fix (7 screenshots)
- `fc24a25` — Fix: Use source project-name for retraction in rescan-project!

---

### Fixed: Stale Namespace Retraction in rescan-project! (2026-02-15)

**Bug:** `rescan-project!` called `retract-project-entities!` with the caller-supplied URI string (e.g. `"nrepl://localhost:9876@..."`) but DB entities store `:uri/project` as the source's project-name (e.g. `"localhost:9876"`). Query found zero entities → nothing retracted → stale namespaces persisted.

**Fix:** Changed line 442 of `core.clj` to use `(:project-name source)` instead of `project-name`. Commit `fc24a25`.

**Verified:** Playwright E2E demo confirmed namespace disappears from browser after `remove-ns` + rescan.

---

## nREPL REPL — `bb nrepl-repl` (2026-02-17)

**Pure Babashka JLine3 REPL client** — replaces JVM-based `bb rebel-nrepl-client` (~50ms startup vs 2-5s).

**File:** `scripts/nrepl_repl.clj` (~630 lines)

**Features (all 3 phases implemented):**
- Multi-line editing (edamame-based incomplete form detection)
- Tab completion from nREPL server (`"completions"` op, raw bencode)
- Syntax highlighting (parens, keywords, strings, special forms)
- Persistent history (`~/.bb-nrepl-repl-history`)
- Special commands: `:quit`, `:doc`, `:source`, `:ns`
- Doc-at-point widget (Ctrl+X Ctrl+D)
- Force-accept widget (Ctrl+X Ctrl+A) for submitting incomplete forms

**Usage:**
```bash
bb nrepl-repl -t cb-v2-test          # Connect via target nickname
bb nrepl-repl --port 9876            # Connect via explicit port
bb nrepl-repl --host remote --port 9876  # Remote host
```

**Key lessons learned (Babashka + JLine3):**
- **Type hints are MANDATORY** — `^ParsedLine`, `^String`, `^int`, `^Parser$ParseContext` on all reify methods; SCI can't resolve methods without them
- **One interface per reify** — SCI/GraalVM limitation
- **`reify` not `proxy`** — proxy doesn't work for ParsedLine in bb
- **CompletingParsedLine NOT reifiable** from user bb scripts (only ParsedLine)
- **Parser must handle `COMPLETE` context separately** — extract word-at-cursor for tab completion, not just ACCEPT_LINE for multi-line
- **Babashka nREPL uses `"completions"` op** (not cider-nrepl's `"complete"`)
- **Babashka nREPL doesn't support session cloning** — `clone-session` returns nil; session must be optional
- **`client/send-message` → `merge-responses` drops `:completions`** — must read raw bencode directly

**Deprecation:** `bb rebel-nrepl-client` now prints deprecation notice pointing to `bb nrepl-repl`.

---

## Next Session Priorities

1. **Monitor server stability** — confirm fingerprint polling works without hangs over extended period
2. **Consider `datalevin-pod` module locking** — its functions bypass `db-lock`, potential concurrent access if sharing pod process with code-browser-v2
3. **Fingerprint first-check gap** — The first fingerprint baseline stores the current nREPL state, but if namespaces were added between initial scan and first fingerprint check, the DB is out of sync until the next change
4. **WinBox z-index overlap** — toolbar buttons become unclickable when another widget overlaps (workaround: `dispatchEvent('click')` via JS)
5. **nrepl-repl polish** — Consider: interrupt during eval (SIGINT → `client/interrupt`), pprint flag, startup banner with server info

---

*For detailed debugging history, see git log.*
