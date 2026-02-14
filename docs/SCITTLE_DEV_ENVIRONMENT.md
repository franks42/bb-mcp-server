# Code Browser v2 — Scittle Dev Environment

> **Single command startup:** `bb dev:cb-v2`

## Quick Start

```bash
bb dev:cb-v2
```

This task:
1. Stops any existing `cb-v2-test` server and nREPL target
2. Starts a separate nREPL target process (port 9876) for introspection
3. Deletes stale Datalevin database (`/tmp/cb-v2-test`)
4. Starts server with `system-cb-v2-test.edn` (auto-initializes code-browser-v2)
5. Opens browser to `http://localhost:8091`

After the browser loads:
1. Wait for green "Connected as browser-N" status
2. Click **"Load Code Browser"** button
3. Projects panel appears — click a project to browse namespaces and symbols

That's it. No manual nREPL commands needed.

### Dev Task Commands

```bash
bb dev:cb-v2                  # Clean start + open browser (default)
bb dev:cb-v2 start --no-open  # Clean start without opening browser
bb dev:cb-v2 stop             # Stop server + nREPL target
bb dev:cb-v2 restart          # Restart with fresh database
bb dev:cb-v2 status           # Show server, nREPL target, database status
bb dev:cb-v2 --help           # Show all options
```

### What the Task Does

The `dev:cb-v2` task (`scripts/cb_v2_dev.clj`) is a Babashka script registered in `bb.edn`. Each subcommand maps to a function:

| Command | What it does |
|---------|-------------|
| `start` (default) | 1. Calls `bb server:stop cb-v2-test` if running. 2. Starts nREPL target on port 9876. 3. Deletes `/tmp/cb-v2-test`. 4. Calls `bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn`. 5. Opens browser via `open http://localhost:8091`. |
| `stop` | Stops both the cb-v2-test server and the nREPL target process. |
| `restart` | Same as `start` but skips opening the browser. |
| `status` | Shows nREPL target status, server status, DB status. |

The key is the **`start` command**: it delegates to `bb server:start-wait` which starts the server process, waits for the port file to appear, then polls the health endpoint. During server startup, the code-browser-v2 module's `:start` function reads its config (`db-path` and `sources`), calls `init!`, which creates the Datalevin database, runs clj-kondo analysis on the source directories, and populates the database. By the time the health check passes, the database is ready and projects are loaded.

The database is cleaned on every `start` and `restart` because URIs contain git commit hashes (e.g., `dir://.@cef8def/ns/sym`). After code changes the hash changes, so stale URIs would fail source lookups.

---

## How It Works

### Architecture: Two-Process Design

Code Browser v2 uses a **two-process architecture** to avoid self-introspection deadlocks:

```
Process 1: nREPL Target (port 9876)
    └── bb --nrepl-server 9876
        (simple bb process to introspect — no other modules)

Process 2: Main Server (port 7888)
    ├── nREPL server (port 7888)        ← serves external eval requests
    ├── datalevin-pod                    ← stores symbol data
    ├── sente-browser (ports 8090/8091)  ← WebSocket + HTTP
    └── code-browser-v2                  ← introspects Process 1
          └── source: nrepl://localhost:9876  ← points to target, NOT self
```

**Why two processes?** When code-browser-v2 introspects its own nREPL server (self-introspection), the introspection eval calls consume nREPL threads that are also needed for other operations (pod calls, browser events). This creates a thread-pool deadlock: internal introspection evals wait for threads that are waiting for pod responses that are waiting for... more nREPL threads. With a separate target, introspection evals go to Process 1's thread pool, leaving Process 2's threads free.

**Symptoms of self-introspection (if misconfigured):**
- All Datalevin pod calls hang after initialization
- Server requires SIGKILL to stop (never graceful shutdown)
- Browser widgets stuck in perpetual loading state

### Module Config

The `system-cb-v2-test.edn` config points code-browser-v2 to the **external** nREPL target:

```edn
"code-browser-v2" {:enabled true
                   :db-path "/tmp/cb-v2-test"
                   :sources [{:type :dir :path "."}
                             {:type :dir :path "../hasch"}
                             ;; IMPORTANT: External target, NOT self (port 7888)
                             {:type :nrepl :host "localhost" :port 9876}]}
```

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

# 1. Start nREPL target in background (separate process)
bb --nrepl-server 9876 &
TARGET_PID=$!
echo "nREPL target started (PID $TARGET_PID)"

# 2. Stop existing server
bb server:stop cb-v2-test 2>/dev/null || true

# 3. Clean stale database (commit hashes in URIs change with code changes)
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock

# 4. Start server (auto-initializes code-browser-v2, introspects port 9876)
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

# 5. Open browser
open http://localhost:8091

# 6. Click "Load Code Browser" button in browser

# When done:
bb server:stop cb-v2-test
kill $TARGET_PID
```

---

## nREPL Live Runtime Browsing

Code Browser v2 can browse **live Babashka runtimes** via nREPL, not just static files. This lets you:
- Introspect all loaded namespaces and vars in a running BB process
- View live var values with auto-refresh (polls every 3 seconds)
- Detect statecharts, Services, and Stores with FSM state display
- Browse source code from the running system

### Default: Browse the nREPL Target

With `bb dev:cb-v2`, the nREPL target (port 9876) is automatically configured as a source. In the browser, you'll see `localhost:9876` as a project alongside directory projects.

### Browse a Custom External BB Process

To browse a different Babashka process instead:

```bash
# 1. Start your BB process with nREPL
bb --nrepl-server 9999
# Or any BB server with nREPL enabled

# 2. Add it to the running Code Browser
bb nrepl-direct eval "(code-browser.core/add-source! {:type :nrepl :host \"localhost\" :port 9999})" -t cb-v2-test

# 3. Browser shows "localhost:9999" project
```

### The "+ Value" Button

When browsing symbols from an nREPL source (not directory sources), the toolbar shows a **"+ Value"** button:

1. Click it to open a live value viewer widget
2. The widget shows:
   - **Container type** (atom indicator if it holds an `IAtom`)
   - **Type badges** (map?, vector?, fn?, etc.)
   - **Statechart/Service/Store detection** — displays FSM state, context, instances
   - **Pretty-printed value** (truncated at 4096 chars)
   - **Metadata** (var metadata + value metadata)
3. **Auto-polling**: The value refreshes every 3 seconds if changed (uses identity hash comparison)
4. **Visual flash** when value changes

### URI Scheme for nREPL Sources

nREPL sources use a different URI scheme than directory sources:

- **Directory**: `dir://.@<git-sha>/<namespace>/<symbol>`
- **nREPL**: `nrepl://localhost:9876@<uuidv7>/<namespace>/<symbol>`

The `@<uuidv7>` version is generated at scan time (temporal, not content-based). Queries are version-agnostic, so stale URIs still work.

### Limitations

- **No file watching** — nREPL sources don't support live updates when code changes. Use the browser "Refresh" button (if implemented) or restart the scan.
- **No aliases/refers** — Runtime introspection only sees public vars, not `require`/`alias` forms.
- **Namespace exclusions** — By default filters out `clojure.spec.*`, `nrepl.*`, `borkdude.*`, `sci.*`, `edamame.*` to reduce noise.

### How It Works

```
Browser: Click "+ Value" button
    |
    v
send-event! :code-browser-v2/fetch {:query-type :var-value, :uri "nrepl://..."}
    |
    v
Server: handlers.clj -> NreplSource.fetch-var-value
    |
    v
nrepl-direct TCP -> eval on target BB server (port 9876):
    - Resolve var
    - Detect type (atom? statechart? service? store?)
    - Auto-deref if atom
    - Probe ~30 predicates (map?, fn?, vector?, ...)
    - pprint with truncation
    - Return EDN map with value + metadata
    |
    v
Browser: Render in var-value widget
    - Display badges, FSM state, pprinted value
    - Start 3-second polling loop (check identity hash)
    - If changed, fetch full value again + flash animation
```

### Debugging nREPL Sources

```bash
# Check if nREPL source was registered
bb nrepl-direct eval "(keys (:sources @code-browser.handlers/!module-state))" -t cb-v2-test

# Query projects in the database
bb nrepl-direct eval "(->> @code-browser.sync/!state :projects (map :uri/project))" -t cb-v2-test

# Manually trigger a scan (if source exists but DB is empty)
bb nrepl-direct eval "(code-browser.core/scan-all-sources!)" -t cb-v2-test
```

---

## Useful Commands

```bash
# Server management
bb dev:cb-v2 status                             # Quick status check
bb dev:cb-v2 stop                               # Stop server + nREPL target
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
lsof -ti:9876 | xargs kill -9 2>/dev/null
bb dev:cb-v2
```

### Datalevin pod calls hang / server needs SIGKILL
**Cause:** Self-introspection deadlock. The nREPL source in `system-cb-v2-test.edn` points to the server's own nREPL port (7888) instead of the external target (9876).
**Fix:** Verify `system-cb-v2-test.edn` has `:port 9876` (not 7888) in the nREPL source. Use `bb dev:cb-v2` which handles this automatically.

### nrepl-direct eval times out
**Cause:** Wrong connection nickname (nicknames change on reconnect).
**Fix:** Always query first: `bb nrepl-direct list -t cb-v2-test`

### nrepl-direct eval shows no output (silent failure)
**Cause:** Pre-v1.31.0 bug — nrepl-direct swallowed all eval errors.
**Fix:** Update to v1.31.0+. Errors now show error messages and exit with code 1.

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

*Last Updated: 2026-02-14*
