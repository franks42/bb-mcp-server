# Code Browser v2 — Scittle Dev Environment

> **Single command startup:** `bb dev:cb-v2`

## Quick Start

```bash
bb dev:cb-v2
```

This task:
1. Stops any existing `cb-v2-test` server
2. Deletes stale Datalevin database (`/tmp/cb-v2-test`)
3. Starts server with `system-cb-v2-test.edn` (auto-initializes code-browser-v2)
4. Opens browser to `http://localhost:8091`

After the browser loads:
1. Wait for green "Connected as browser-N" status
2. Click **"Load Code Browser"** button
3. Projects panel appears — click a project to browse namespaces and symbols

That's it. No manual nREPL commands needed.

### Dev Task Commands

```bash
bb dev:cb-v2                  # Clean start + open browser (default)
bb dev:cb-v2 start --no-open  # Clean start without opening browser
bb dev:cb-v2 stop             # Stop server
bb dev:cb-v2 restart          # Restart with fresh database
bb dev:cb-v2 status           # Show server, database, and browser status
bb dev:cb-v2 --help           # Show all options
```

### What the Task Does

The `dev:cb-v2` task (`scripts/cb_v2_dev.clj`) is a Babashka script registered in `bb.edn`. Each subcommand maps to a function:

| Command | What it does |
|---------|-------------|
| `start` (default) | 1. Calls `bb server:stop cb-v2-test` if running. 2. Deletes `/tmp/cb-v2-test` and `.lock` dir. 3. Calls `bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn`. 4. Opens browser via `open http://localhost:8091`. |
| `stop` | Calls `bb server:stop cb-v2-test`. |
| `restart` | Same as `start` but skips opening the browser. |
| `status` | Reads `.ports/cb-v2-test.json` for port info, checks if server process is alive, checks if DB directory exists. |

The key is the **`start` command**: it delegates to `bb server:start-wait` which starts the server process, waits for the port file to appear, then polls the health endpoint. During server startup, the code-browser-v2 module's `:start` function reads its config (`db-path` and `sources`), calls `init!`, which creates the Datalevin database, runs clj-kondo analysis on the source directories, and populates the database. By the time the health check passes, the database is ready and projects are loaded.

The database is cleaned on every `start` and `restart` because URIs contain git commit hashes (e.g., `dir://.@cef8def/ns/sym`). After code changes the hash changes, so stale URIs would fail source lookups.

---

## How It Works

### Architecture

```
system-cb-v2-test.edn          # Module config with db-path + sources
    │
    ▼
Server startup                  # bb server:start-wait
    │
    ├── datalevin-pod module    # Loads Datalevin pod binary
    ├── atom-sync module        # Bidirectional state sync
    ├── sente-browser module    # WebSocket + HTTP bootstrap on :8090/:8091
    └── code-browser-v2 module  # Auto-initializes:
         ├── create-db          #   Creates Datalevin DB at /tmp/cb-v2-test
         ├── scan-and-populate  #   Runs clj-kondo analysis, populates DB
         ├── enable!            #   Registers sync atom
         └── load-projects!     #   Projects ready for browser
    │
    ▼
Browser connects                # open http://localhost:8091
    │
    ├── WebSocket handshake     # sente-lite client/server
    ├── on-browser-connect!     # Auto-enable (idempotent, already enabled)
    └── Click "Load Code Browser" button
         ├── scittle_cm6.cljs   # CodeMirror 6 wrapper
         ├── uri.cljc           # URI parsing/generation
         ├── code_browser_v2.cljs  # Widget-based UI
         └── mount!             # Opens Projects widget
```

### Key: Module Config Drives Init

The `system-cb-v2-test.edn` config includes `db-path` and `sources` for code-browser-v2:

```edn
"code-browser-v2" {:enabled true
                   :db-path "/tmp/cb-v2-test"
                   :sources [{:type :dir :path "."}]}
```

This makes `init!` run during module startup (in the server process), so:
- No manual `bb nrepl-direct eval 'init!'` calls needed
- No nREPL thread blocking (Datalevin ops run in the main thread)
- Database and projects are ready before any browser connects

### "Load Code Browser" Button

The button in the bootstrap page loads **v2** code:
1. `scittle_cm6.cljs` — CodeMirror 6 editor wrapper
2. `uri.cljc` — URI parsing/generation (shared code)
3. `code_browser_v2.cljs` — Widget-based browser UI
4. Calls `(code-browser-v2/mount!)` — opens Projects widget

All files are served from the running server (no external loading needed).

---

## Manual Setup (Without Task)

If you prefer manual steps or need to debug:

```bash
cd /Users/franksiebenlist/Development/bb-mcp-server

# 1. Stop existing server
bb server:stop cb-v2-test 2>/dev/null || true

# 2. Clean stale database (commit hashes in URIs change with code changes)
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock

# 3. Start server (auto-initializes code-browser-v2)
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# 4. Open browser
open http://localhost:8091

# 5. Click "Load Code Browser" button in browser
```

---

## Useful Commands

```bash
# Server management
bb dev:cb-v2 status                             # Quick status check
bb dev:cb-v2 stop                               # Stop server
bb dev:cb-v2 restart                            # Restart with fresh data
bb server:list                                  # List all running servers
bb server:ports cb-v2-test                      # Show ports

# Browser connections
bb nrepl-direct list -t cb-v2-test              # List connections
bb nrepl-direct eval '(+ 1 2)' -t cb-v2-test/browser-1  # Eval in browser

# Load files manually (alternative to button)
bb nrepl-direct load-local-file modules/sente-browser/src/browser/scittle_cm6.cljs \
  -t cb-v2-test/browser-1
bb nrepl-direct load-local-file modules/code-browser-v2/src/code_browser/uri.cljc \
  -t cb-v2-test/browser-1
bb nrepl-direct load-local-file modules/sente-browser/src/browser/code_browser_v2.cljs \
  -t cb-v2-test/browser-1
bb nrepl-direct eval '(code-browser-v2/mount!)' -t cb-v2-test/browser-1

# Server-side inspection
bb nrepl-direct eval '(some? (code-browser.handlers/get-db))' -t cb-v2-test
bb nrepl-direct eval '(count (:projects @code-browser.sync/!state))' -t cb-v2-test

# Run unit tests
bb test:module code-browser-v2
```

---

## Troubleshooting

### Browser shows "Loading..." / button stays disabled
**Cause:** WebSocket not connected yet.
**Fix:** Wait 3-5 seconds. Check browser console for connection errors. Verify server is running with `bb dev:cb-v2 status`.

### Projects panel empty after clicking button
**Cause:** Database not initialized (init! failed during startup).
**Fix:** Check server logs for errors. Verify database exists: `ls /tmp/cb-v2-test`. Restart: `bb dev:cb-v2 restart`

### Clicking symbols shows "No source available"
**Cause:** Stale database with old commit hashes in URIs.
**Fix:** Restart with fresh database: `bb dev:cb-v2 restart`

### Server won't start (port in use)
**Fix:**
```bash
bb dev:cb-v2 stop
lsof -ti:8090 | xargs kill -9 2>/dev/null
lsof -ti:8091 | xargs kill -9 2>/dev/null
bb dev:cb-v2
```

### nrepl-direct eval times out
**Cause:** Wrong connection nickname (nicknames change on reconnect).
**Fix:** Always query first: `bb nrepl-direct list -t cb-v2-test`

### Server-side code changes not picked up
**Cause:** Server-side `.clj` files are loaded at startup, not hot-reloaded.
**Fix:** `bb dev:cb-v2 restart`. Browser-side `.cljs` changes can be reloaded via nREPL `load-local-file` or browser refresh + button click.

---

## Files Reference

| File | Purpose |
|------|---------|
| `scripts/cb_v2_dev.clj` | Dev environment bb task script |
| `system-cb-v2-test.edn` | Server config (modules, db-path, sources) |
| `modules/code-browser-v2/src/code_browser/core.clj` | v2 public API, `init!`, module lifecycle |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | Stateless fetch API, event dispatch |
| `modules/code-browser-v2/src/code_browser/sync.clj` | Atom-sync state management |
| `modules/code-browser-v2/src/code_browser/db/datalevin.clj` | Datalevin DB wrapper |
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | Widget-based browser UI |
| `modules/sente-browser/src/browser/scittle_cm6.cljs` | CodeMirror 6 wrapper |
| `modules/code-browser-v2/src/code_browser/uri.cljc` | URI parsing (shared server/browser) |
| `modules/sente-browser/src/sente_browser/bootstrap.clj` | HTML bootstrap, "Load Code Browser" button |

---

## Datalevin Database

The v2 database lives at `/tmp/cb-v2-test` (configured in `system-cb-v2-test.edn`).

**When to clean:** After git commits or code changes that alter the commit hash. URIs contain commit hashes (e.g., `dir://.@cef8def/ns/sym`), so stale data causes source lookup failures.

**How to clean:**
```bash
bb dev:cb-v2 restart    # Cleans DB and restarts
```

Or manually:
```bash
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock
```

**Pod management:**
```bash
bb datalevin:status     # Show running Datalevin pods
bb datalevin:stop       # Stop all pods
bb datalevin:cleanup    # Stop pods + remove lock files
```

---

*Last Updated: 2026-02-07*
