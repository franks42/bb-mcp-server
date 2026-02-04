# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-03
**Version:** v1.14.18
**Focus:** nrepl-direct --target shorthand + browser routing - Complete

---

## 🟢 nrepl-direct --target shorthand & browser routing (2026-02-03) - COMPLETE

### Goal

Simplify `bb nrepl-direct` CLI ergonomics by replacing verbose `--nickname X --service nrepl-proxy --connection browser-1` with compact `-t X/browser-1` shorthand. Add `list` subcommand and explicit `:connection` routing through the nrepl-proxy.

### Changes in v1.14.18

| Change | Description |
|--------|-------------|
| **`-t` / `--target`** | Compact target: `-t server` or `-t server/browser-1` |
| **`list` subcommand** | `bb nrepl-direct list -t server` lists connected browsers |
| **`:connection` routing** | Explicit browser routing in proxy (no session state needed) |
| **`resolve-browser-connection`** | Proxy resolves nickname/id to valid browser connection |
| **`scripts/open-browser.js`** | Headless Playwright browser launcher script |
| **SCITTLE guide updated** | Step 2 uses `open-browser.js`, added nrepl-direct section |
| **`bb.edn` fix** | Fixed `test:nrepl` path to `modules/mcp-nrepl/` |

### Target Format

| Target | Expands to |
|--------|-----------|
| `-t myserver` | `--nickname myserver --service nrepl-server` |
| `-t myserver/browser-1` | `--nickname myserver --service nrepl-proxy --connection browser-1` |

### Files Changed

| File | Changes |
|------|---------|
| `scripts/nrepl_direct_cli.clj` | Added `parse-target`, `cmd-list`, `-t`/`--target`, `--connection` |
| `src/bb_mcp_server/nrepl_direct/client.clj` | `:connection` param threaded through eval/load-file |
| `modules/nrepl-proxy-server/src/nrepl_proxy_server/server.clj` | `resolve-browser-connection`, explicit `:connection` in eval/load-file |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Step 2 uses `open-browser.js`, added nrepl-direct section |
| `scripts/open-browser.js` | New - headless Playwright browser launcher |
| `bb.edn` | Fixed test:nrepl module path |

### Verified End-to-End Flow (2026-02-03)

| Step | Command | Result |
|------|---------|--------|
| Start server | `bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn` | Running |
| Open browser | `node scripts/open-browser.js http://localhost:8091 10` | Connected as browser-3 |
| List browsers | `bb nrepl-direct list -t code-browser-dev` | Shows 3 browsers |
| Eval in browser | `bb nrepl-direct eval "(+ 1 2 3)" -t code-browser-dev/browser-3` | `6` |
| Load file | `bb nrepl-direct load-local-file /tmp/target-test.cljs -t code-browser-dev/browser-3` | `#'user/target-test` |
| Load CM6 | `bb nrepl-direct load-local-file .../scittle_cm6.cljs -t .../browser-3` | `#'scittle-cm6/focus!` |
| Load code browser | `bb nrepl-direct load-local-file .../code_browser.cljs -t .../browser-3` | `#'code-browser/unmount!` |
| Mount | `bb nrepl-direct eval "(code-browser/mount!)" -t .../browser-3` | 206 namespaces rendered |

---

## 🟢 nREPL Response Format Alignment (2026-01-20) - COMPLETE

### Changes in v1.14.17

| Change | Description |
|--------|-------------|
| **Flat response** | nrepl-direct now returns flat map (no nested `:response`) |
| **String status** | `:status` is now `"success"` not `:success` |
| **`:value-parsed`** | EDN-parsed result when possible |
| **`--output edn`** | Clean EDN output for scripting (exit 0/1) |
| **`--stdout2stderr`** | Show eval's stdout on stderr while piping |
| **base64 support** | `input-base64` and `output-base64` in nrepl-direct |

---

## 🟢 Direct nREPL Client (2026-01-20) - COMPLETE

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         bb nrepl-direct CLI                          │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐     ┌─────────────────┐     ┌──────────────┐  │
│  │   bencode.clj   │     │   client.clj    │     │ nrepl_direct │  │
│  │  encode/decode  │────▶│  session mgmt   │────▶│   _cli.clj   │  │
│  │                 │     │  eval/load-file │     │              │  │
│  └─────────────────┘     └─────────────────┘     └──────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Commands

| Command | Description |
|---------|-------------|
| `eval <code>` | Evaluate Clojure code |
| `load-file <path>` | Load file from server's filesystem |
| `load-local-file <path>` | Read file locally, send as code (for browser) |
| `list` | List connected browsers via nrepl-proxy |
| `describe` | Show nREPL server capabilities |

---

## 🟢 v2 Code Browser Status (2026-01-19) - WORKING

- 30 unit tests pass (459 assertions)
- Full browser navigation flow works (project → namespace → symbol → source)
- Must clear database on code changes (`rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock`)

---

## Quick Resume

```bash
# Check server status
bb server:list

# Start code browser dev server
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Open headless browser
node scripts/open-browser.js http://localhost:8091 10

# List connected browsers
bb nrepl-direct list -t code-browser-dev

# Eval in browser
bb nrepl-direct eval "(+ 1 2 3)" -t code-browser-dev/browser-1

# Load file to browser
bb nrepl-direct load-local-file path/to/file.cljs -t code-browser-dev/browser-1

# Run tests
bb test:module code-browser-v2
bb lint && bb format
```

---

## Phase R3: Feature Parity (In Progress)

| Task | Description | Status |
|------|-------------|--------|
| R3.1 | Symbol inspector (Source, Doc, Deps, Callers tabs) | ✅ Done |
| R3.2 | Aliases panel (separate alias/refer entities) | ✅ Done |
| R3.3 | Multi-file namespace support | ✅ Done |
| R3.4 | File watching / cache invalidation | Pending |
| R3.5 | Git status display | Pending |

---

## Key Files

| File | Purpose |
|------|---------|
| `scripts/nrepl_direct_cli.clj` | nrepl-direct CLI (eval, load-file, list, -t shorthand) |
| `src/bb_mcp_server/nrepl_direct/client.clj` | Standalone nREPL client library |
| `modules/nrepl-proxy-server/src/nrepl_proxy_server/server.clj` | nREPL proxy with browser routing |
| `scripts/open-browser.js` | Headless Playwright browser launcher |
| `docs/SCITTLE_DEV_ENVIRONMENT.md` | Scittle dev setup guide |
| `docs/bb-nrepl-direct-user-guide.md` | nrepl-direct usage guide |

---

*Last Updated: 2026-02-03*
