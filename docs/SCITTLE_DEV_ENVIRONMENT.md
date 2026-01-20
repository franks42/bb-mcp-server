# Scittle Dev Environment Guide

> **Purpose:** Step-by-step guide for setting up and verifying the Scittle browser nREPL development environment. Follow this EXACTLY to avoid configuration issues.

> ⚠️ **AI Assistant Directive:** ALWAYS test changes with Playwright MCP tools or headless Playwright before asking the user to try them in their browser. Use `mcp__playwright__*` tools when available, or headless Playwright scripts as fallback.

## Prerequisites

Before starting, ensure you have:
- Babashka installed (`bb --version`)
- Node.js with Playwright (`npx playwright --version`)
- Project cloned and in `/Users/franksiebenlist/Development/bb-mcp-server`

---

## Clean Restart (Start from Scratch)

Use this when inheriting a broken state or starting a fresh session:

```bash
cd /Users/franksiebenlist/Development/bb-mcp-server

# 1. Stop any existing servers
bb server:list                    # See what's running
bb server:stop code-browser-dev   # Stop by nickname (if running)

# 2. Kill any orphaned processes on the ports (if needed)
lsof -ti:3000 | xargs kill -9 2>/dev/null   # MCP HTTP
lsof -ti:8090 | xargs kill -9 2>/dev/null   # Sente WebSocket
lsof -ti:8091 | xargs kill -9 2>/dev/null   # Bootstrap HTTP

# 3. Start fresh (waits for health automatically)
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# 4. Open browser - code browser auto-initializes on first connect!
open http://localhost:8091   # Or use Playwright
```

**Note:** `server:start-wait` handles health checking automatically. Code browser and clojure-lsp now **auto-initialize** when the first browser connects (reactive initialization).

---

## Step 1: Start the Server

> ⚠️ **AI Directive:** Use `bb server:start-wait` to start servers - it handles health checking automatically. Use `bb server:stop` to stop. Never construct complex shell commands with chaining.

**Recommended (background with health check):**
```bash
cd /Users/franksiebenlist/Development/bb-mcp-server
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn
```

**Verify:** Output should show:
```
Starting server 'code-browser-dev' on port 3000...
  Waiting for health (timeout: 30s)...
  ✓ Server healthy after N attempts (Xs)
```

**Alternative (foreground for debugging):**
```bash
bb server --http --config bb-code-browser-dev-system.edn --nickname code-browser-dev
```
This keeps the terminal attached so you can see server logs.

**To stop the server:** `bb server:stop code-browser-dev`
**To list servers:** `bb server:list`

---

## Step 1.5: Initialize Code Browser Backend (Optional - Now Auto-Initializes!)

> ✅ **AUTO-INITIALIZATION:** As of Phase 1.5-Watch, the code browser now **auto-initializes** when the first browser connects. You typically don't need to run these commands manually.

**When auto-init happens:**
1. Browser connects via WebSocket
2. `on-browser-connected!` triggers registered callbacks
3. Code browser auto-enables and registers synced atom
4. clojure-lsp auto-starts in background (if not already running)
5. Browser receives initial state

**Manual initialization (if needed for debugging):**
```bash
# Force clojure-lsp init
bb mcp call clojure-lsp.clj-init '{}' --mcp code-browser-dev

# Force code-browser enable
bb mcp-eval "(require '[sente-browser.code-browser :as cb]) (cb/enable!)" --nickname code-browser-dev
```

**Note:** The first namespace list may take a few seconds while clojure-lsp initializes. Click "Refresh" if the list is empty after a few seconds.

---

## Step 2: Open Browser Connection

In a NEW terminal:

```bash
cd /Users/franksiebenlist/Development/bb-mcp-server
node -e "
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('console', msg => console.log('[browser]', msg.text()));
  await page.goto('http://localhost:8091');
  console.log('[test] Browser connected, waiting 5 minutes...');
  await page.waitForTimeout(300000);
  await browser.close();
})();
"
```

**Verify:** You should see:
```
[browser] [nrepl-adapter] "Browser adapter loaded..."
[browser] ... :sente-lite.client/handshake-received ...
[browser] [nrepl-adapter] "Connected to existing sente-lite client"
```

**Keep this terminal open.** Browser must stay connected.

---

## Step 3: Find Active Browser Connection

In a THIRD terminal:

```bash
cd /Users/franksiebenlist/Development/bb-mcp-server
bb mcp call nrepl.nrepl-connection '{"op":"list"}' --mcp code-browser-dev
```

**Verify:** Look for a connection with `"type": "browser"` and `"status": "connected"`. Note the `nickname` (e.g., `browser-1`, `browser-2`).

**CRITICAL:** The nickname changes with each new connection. ALWAYS run this command to get the CURRENT nickname before any eval.

Example output:
```json
{
  "connections": [
    {
      "nickname": "browser-1",
      "type": "browser",
      "status": "connected"
    }
  ]
}
```

---

## Step 4: Test Basic Eval

Using the nickname from Step 3 (replace `browser-1` with your actual nickname):

```bash
bb mcp call nrepl.nrepl-eval '{"code":"(+ 1 2 3)","connection":"browser-1","timeout":5000}' --mcp code-browser-dev
```

**Verify:** Response should contain `"value": "6"`:
```json
{
  "status": "success",
  "response": {
    "value": "6"
  }
}
```

**If you get timeout:** The connection nickname is wrong or browser disconnected. Go back to Step 3.

---

## Step 5: Load Files into Scittle

**IMPORTANT:** Use `nrepl.nrepl-eval-local-file` to load .cljs files. This tool:
1. Reads the file locally (on the bb server side)
2. Sends the content via nrepl-eval to the browser

```bash
# Load CM6 editor wrapper
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/Users/franksiebenlist/Development/bb-mcp-server/modules/sente-browser/src/browser/scittle_cm6.cljs","connection":"browser-1","timeout":30000}' --mcp code-browser-dev
```

**Verify:** Response should show a var like `"value": "#'scittle-cm6/focus!"`.

```bash
# Load code browser UI
bb mcp call nrepl.nrepl-eval-local-file '{"file-path":"/Users/franksiebenlist/Development/bb-mcp-server/modules/sente-browser/src/browser/code_browser.cljs","connection":"browser-1","timeout":30000}' --mcp code-browser-dev
```

**Verify:** Response should show `"value": "#'code-browser/unmount!"`.

---

## Step 6: Mount Code Browser

```bash
bb mcp call nrepl.nrepl-eval '{"code":"(code-browser/mount!)","connection":"browser-1","timeout":5000}' --mcp code-browser-dev
```

**Verify:** Response shows `"value": "nil"` and browser console logs:
```
[code-browser] Registering event handler...
[code-browser] Mounted
```

---

## Tool Reference

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `nrepl.nrepl-connection op=list` | List all connections | ALWAYS before any eval to get current nickname |
| `nrepl.nrepl-eval` | Evaluate code in browser | Short code snippets |
| `nrepl.nrepl-eval-local-file` | Load .cljs file into browser | Loading source files (reads locally, evals remotely) |

---

## Convenient CLI Commands

Instead of `bb mcp call nrepl.*`, use the shorter `bb nrepl` CLI:

```bash
# List connections (find browser nickname)
bb nrepl list --mcp code-browser-dev

# Evaluate code in browser
bb nrepl eval "(+ 1 2 3)" --connection browser-6 --mcp code-browser-dev

# Load a .cljs file into browser
bb nrepl load-file modules/sente-browser/src/browser/scittle_cm6.cljs --connection browser-6 --mcp code-browser-dev

# Introspection
bb nrepl namespaces --connection browser-6 --mcp code-browser-dev
bb nrepl vars user --connection browser-6 --mcp code-browser-dev
```

**Tip:** The Code Browser now has a "Load Code Browser" button - click it instead of running CLI commands!

---

**DO NOT:**
- Manually read files and eval the content (use `nrepl-eval-local-file`)
- Assume connection nicknames (always query first)
- Use `nrepl-load-file` for Scittle (it calls `load-file` which doesn't exist in SCI)

---

## Common Pitfalls

### 1. Server-Side Code Changes Not Picked Up
**Symptom:** Browser still shows old behavior after editing server-side Clojure files
**Cause:** Server-side code (`code_browser.clj`, handlers, etc.) is loaded at startup. Hot-reload via nREPL is unreliable.
**Fix:** **Restart the server** to pick up server-side changes:
```bash
bb server:stop code-browser-dev
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn
```

**What requires restart:**
- Any `.clj` file under `modules/sente-browser/src/sente_browser/`
- Handler code, protocol code, registry changes
- Any server-side logic

**What does NOT require restart:**
- Browser-side `.cljs` files - reload via nREPL `load-file` or refresh browser
- Static HTML/CSS - just refresh browser

### 2. Wrong Connection Nickname
**Symptom:** Timeout on eval
**Cause:** Using stale nickname (browser-3) when new connection is active (browser-4)
**Fix:** Run `nrepl.nrepl-connection op=list` BEFORE every debugging session

### 3. Browser Disconnected
**Symptom:** No browser connections in list
**Cause:** Browser tab closed, or Playwright process ended
**Fix:** Restart Step 2

### 4. Server Not Running
**Symptom:** Connection refused
**Cause:** Server crashed or wasn't started
**Fix:** Restart Step 1

### 5. reagent.dom Not Available
**Symptom:** "Unable to resolve symbol: rdom" when loading code_browser.cljs
**Cause:** Scittle nREPL eval doesn't load reagent.dom as separate namespace
**Fix:** Use `bootstrap/mount-root!` instead of `rdom/render`

### 6. Empty Namespaces in Code Browser
**Symptom:** Code browser shows 0 namespaces (empty panels)
**Causes:**
- clojure-lsp still initializing (wait a few seconds, click Refresh)
- clojure-lsp failed to start (check server logs)

**Fix:**
```bash
# Wait a few seconds and click Refresh in the browser
# Or force clojure-lsp init manually:
bb mcp call clojure-lsp.clj-init '{}' --mcp code-browser-dev
```

**Note:** Since Phase 1.5-Watch, code browser auto-initializes on browser connect. The timing issue (browser connecting before enable) is now handled automatically via reactive callbacks.

---

## Pre-Flight Checklist

Run these commands IN ORDER before any Scittle debugging session:

```bash
# 1. Is server running?
curl -s http://localhost:3000/health | grep ok

# 2. Is browser connected?
bb mcp call nrepl.nrepl-connection '{"op":"list"}' --mcp code-browser-dev | grep browser

# 3. Note the CURRENT nickname (e.g., browser-5)
# 4. Test eval with THAT nickname
bb mcp call nrepl.nrepl-eval '{"code":"(+ 1 2 3)","connection":"browser-N","timeout":5000}' --mcp code-browser-dev
```

---

## Troubleshooting

### Check Server Logs
```bash
# In server terminal, look for errors
# Or check background task output if running as task
```

### Check Browser Console
The Playwright terminal shows all browser console.log output prefixed with `[browser]`.

### Verify Connection Health
```bash
bb mcp call local-eval.local-eval '{"code":"(sente-browser.server/get-connection-health)"}' --mcp code-browser-dev
```

---

## Quick Test Script

Save time with this automated test:

```bash
node test/scripts/simple_eval_test.mjs
```

This script:
1. Launches headless browser
2. Waits for connection
3. Finds active browser nickname
4. Tests `(+ 1 2 3)` eval
5. Reports success/failure

---

## Automated Playwright Testing

> **AI Directive:** ALWAYS verify browser code changes before telling the user to manually test. Options:
> 1. **Playwright MCP tools** (preferred) - Use `mcp__playwright__browser_navigate`, `mcp__playwright__browser_click`, etc.
> 2. **Playwright scripts** (fallback) - Write .mjs test scripts when MCP tools aren't sufficient

### MCP Session Pattern

**CRITICAL:** All MCP calls require session initialization. The server returns a `Mcp-Session-Id` header that MUST be included in subsequent requests.

```javascript
// 1. Initialize MCP session
const initResult = await fetch('http://localhost:3000/mcp', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    jsonrpc: '2.0',
    method: 'initialize',
    params: {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'test-client', version: '1.0.0' }
    },
    id: 0
  })
});
const sessionId = initResult.headers.get('Mcp-Session-Id');

// 2. Create helper for subsequent calls
const mcpCall = async (method, params, id) => {
  const res = await fetch('http://localhost:3000/mcp', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Mcp-Session-Id': sessionId  // REQUIRED!
    },
    body: JSON.stringify({ jsonrpc: '2.0', method, params, id })
  });
  return res.json();
};

// 3. Make tool calls
const result = await mcpCall('tools/call', {
  name: 'nrepl.nrepl-eval',
  arguments: { code: '(+ 1 2)', connection: 'browser-1', timeout: 5000 }
}, 1);
```

### Response Format

MCP tool call responses have this structure:
```javascript
{
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"value\":\"3\",\"status\":\"success\"}"  // JSON string
      }
    ]
  }
}
```

To extract the value:
```javascript
const parsed = JSON.parse(data.result.content[0].text);
console.log(parsed.value);  // "3"
```

### Test Pattern: Always Test nREPL First

Before running complex operations, verify the nREPL connection works:

```javascript
// Simple test BEFORE complex operations
console.log('[test] Testing nREPL with (+ 1 2)...');
const testData = await mcpCall('tools/call', {
  name: 'nrepl.nrepl-eval',
  arguments: {
    code: '(+ 1 2)',
    connection: browserConn.nickname,
    timeout: 5000
  }
}, 2);
const testResult = JSON.parse(testData.result.content[0].text);
if (testResult.value !== '3') {
  throw new Error(`nREPL test failed: expected 3, got ${testResult.value}`);
}
console.log('[test] nREPL works!');
```

### Available Namespaces in Browser

When evaluating code in Scittle browser:

| Namespace | Available? | Notes |
|-----------|------------|-------|
| `reagent.core` | ✅ | Use `r/atom`, `r/create-class` |
| `reagent.dom` | ✅ | Use for `rdom/render` |
| `clojure.core` | ✅ | Standard functions |
| `bootstrap/*` | ⚠️ | Only via bootstrap HTML, not nREPL eval |

**Use `reagent.dom/render` directly:**
```clojure
(require '[reagent.dom :as rdom])
(rdom/render [my-component] (.getElementById js/document "app"))
```

### Example Test Script

See `test/scripts/test_cm6_update.mjs` for a complete example that:
1. Launches headless browser
2. Initializes MCP session with proper headers
3. Finds browser connection nickname
4. Tests simple nREPL eval first
5. Loads .cljs file into browser
6. Creates test component dynamically
7. Interacts with UI via Playwright clicks
8. Verifies expected behavior

### Running Tests

```bash
# Start server first (waits for health automatically)
bb server:start-wait --nickname code-browser-dev --config bb-code-browser-dev-system.edn

# Run the test (in separate terminal)
node test/scripts/test_cm6_update.mjs
```

### Common Test Errors

| Error | Cause | Fix |
|-------|-------|-----|
| "Invalid or missing session" | Missing `Mcp-Session-Id` header | Initialize session first, include header |
| Timeout on eval | Wrong connection nickname | Query `nrepl-connection op=list` first |
| "Unable to resolve symbol" | Namespace not loaded | Use `require` or load file first |
| "bootstrap not found" | Not available in nREPL eval | Use `reagent.dom` directly |

---

## Session Handoff

When ending a session, update `context.md` with:
1. Current browser nickname
2. Any loaded files
3. State of the code browser (mounted?)
4. Any errors encountered

---

## Code Browser v2 Testing

> ⚠️ **CRITICAL WARNING:** The "Load Code Browser" button loads **v1**, NOT v2!
> v1 ALSO has multi-file namespace support, sort modes, file dividers, etc.
> You MUST follow the exact steps below to test v2.

### v1 vs v2 Architecture

| Aspect | Code Browser v1 | Code Browser v2 |
|--------|-----------------|-----------------|
| **Server code** | `sente-browser/code_browser.clj` | `code-browser-v2/src/code_browser/*.clj` |
| **Browser code** | `sente-browser/browser/code_browser.cljs` | `sente-browser/browser/code_browser_v2.cljs` |
| **Data store** | In-memory atoms | Datalevin database |
| **Sync atom key** | `:code-browser-state` | `:code-browser-v2` |
| **Config** | `bb-code-browser-dev-system.edn` | `system-cb-v2-test.edn` |
| **How to load UI** | Click "Load Code Browser" button | Load via nREPL (see steps below) |

### Visual Differences (How to Tell Them Apart)

| Feature | v1 | v2 |
|---------|----|----|
| Project selector | Dropdown with git controls (🌿 branch, ↑ ahead) | Simple list panel with filter |
| "Add project" input | Text input + 📁 browse button | Not implemented |
| Git branch display | Shows branch name + dirty indicator | Not implemented (R3.5) |
| Clone git URL input | Present with 📥 button | Not implemented |
| Panels heading style | Different styling | "Projects", "Select Project", "Select Namespace", "Source" headers |

**If you see git controls and "Add project path..." input → You're looking at v1!**

### Testing Code Browser v2 (Complete Error-Free Setup)

> **IMPORTANT:** Follow these steps IN EXACT ORDER. Each step depends on the previous one.

#### Step 1: Cleanup and Start Server

```bash
cd /Users/franksiebenlist/Development/bb-mcp-server

# Check/cleanup any existing Datalevin pods
bb datalevin:status     # Show running pods
bb datalevin:stop       # Stop all pods (if any)

# Stop any existing v2 server
bb server:stop cb-v2-test 2>/dev/null || true

# Start fresh v2 server (waits for health automatically)
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn
```

#### Step 2: Open Browser and Wait for Connection

> **NOTE:** v2 now supports flexible initialization order. You can open the browser before or after
> running `init!`. The `enable!` function is defensive and won't fail without a database.
> The `init!` function clears any previous error state and auto-enables browser sync.

```bash
# Open browser
open http://localhost:8091

# Wait 3-5 seconds for WebSocket connection, then find browser nickname
bb nrepl list --mcp cb-v2-test
```

Note the browser connection nickname (e.g., `browser-1`, `browser-4`).

#### Step 3: Initialize v2 Backend

```bash
# Initialize v2 database and sources
# This now auto-enables browser sync and clears any previous error state
cat > /tmp/init-v2.json << 'EOF'
{"code": "(require '[code-browser.core :as cb-v2]) (cb-v2/init! {:db-path \"/tmp/cb-v2-test\" :sources [{:type :dir :path \".\"}]})"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/init-v2.json --mcp cb-v2-test
```

**Expected output:** A database object is returned (success). The `init!` function now:
1. Clears any previous error state
2. Creates the Datalevin database
3. Scans sources and populates data
4. Auto-enables browser sync (`:auto-enable? true` by default)
5. Loads projects automatically

#### Step 4: Load v2 Browser Code (NOT "Load Code Browser" button!)

> ⚠️ **DO NOT click "Load Code Browser"** - that loads v1!

```bash
# Replace browser-N with your actual nickname from Step 2
BROWSER_NICK="browser-4"  # Change this!

# Load v2 browser code
bb nrepl load-file modules/sente-browser/src/browser/code_browser_v2.cljs \
  --connection $BROWSER_NICK --mcp cb-v2-test
```

**Expected output:** Contains `"value": "#'code-browser-v2/unmount!"`

#### Step 5: Mount v2 UI

```bash
# Mount v2 (use args-file due to ! in function name)
cat > /tmp/mount-v2.json << 'EOF'
{"code":"(code-browser-v2/mount!)","connection":"browser-4","timeout":30000}
EOF
# Replace browser-4 with your actual nickname in the JSON above!
bb mcp call nrepl.nrepl-eval --args-file /tmp/mount-v2.json --mcp cb-v2-test
```

**Expected output:** `"value": "nil"` (success)

#### Step 6: Verify and Use v2

The browser should now show the v2 interface:
- **Projects panel** with filter box and "Refresh" button (projects already loaded!)
- **Namespace panel** (empty until you select a project)
- **Symbols panel** with sort mode indicator
- **Source panel** with tabs

Since `init!` now auto-loads projects, you should see your project listed immediately.
Click on a project to navigate to its namespaces!

### Quick v2 Setup Script

Save time with this complete script:

```bash
#!/bin/bash
# v2-setup.sh - Complete v2 Code Browser setup
cd /Users/franksiebenlist/Development/bb-mcp-server

echo "=== Stopping existing servers ==="
bb datalevin:stop 2>/dev/null
bb server:stop cb-v2-test 2>/dev/null || true

echo "=== Clearing stale database (IMPORTANT - prevents source lookup failures) ==="
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock

echo "=== Starting v2 server ==="
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn

echo "=== Initializing v2 backend (scans current commit, populates fresh database) ==="
cat > /tmp/init-v2.json << 'EOF'
{"code": "(require '[code-browser.core :as cb-v2]) (cb-v2/init! {:db-path \"/tmp/cb-v2-test\" :sources [{:type :dir :path \".\"}]})"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/init-v2.json --mcp cb-v2-test

echo "=== Opening browser ==="
open http://localhost:8091
sleep 3  # Wait for browser connection

echo ""
echo "=== NEXT STEPS ==="
echo "1. Run: bb nrepl list --mcp cb-v2-test"
echo "2. Note browser nickname (e.g., browser-1)"
echo "3. Run: bb nrepl load-file modules/sente-browser/src/browser/code_browser_v2.cljs --connection browser-N --mcp cb-v2-test"
echo "4. Create mount file: cat > /tmp/mount-v2.json << 'EOF'"
echo '   {"code":"(code-browser-v2/mount!)","connection":"browser-N","timeout":30000}'
echo "   EOF"
echo "5. Run: bb mcp call nrepl.nrepl-eval --args-file /tmp/mount-v2.json --mcp cb-v2-test"
echo "6. Click on the project '.' to load namespaces, then click namespace/symbol to view source!"
```

### Datalevin Pod Management

Code Browser v2 uses Datalevin for metadata storage. Datalevin runs as a Babashka pod.

```bash
bb datalevin:status     # Show running Datalevin pod processes
bb datalevin:stop       # Stop all Datalevin pods gracefully
bb datalevin:cleanup    # Stop pods + remove lock files (use if DB is stuck)
```

**Common Datalevin issues:**
| Issue | Symptom | Fix |
|-------|---------|-----|
| Multiple pods | Resource contention, slow queries | `bb datalevin:stop`, restart server |
| Lock file stuck | "Database is locked" error | `bb datalevin:cleanup` |
| Pod not starting | v2 init fails | Check `bb datalevin:status`, restart server |

**Database location:** `/tmp/cb-v2-test` (configurable in `system-cb-v2-test.edn`)

### Verify You're Testing v2

**Check the sync state keys:**
```bash
cat > /tmp/check-v2.json << 'EOF'
{"code": "(keys @code-browser.sync/!state)"}
EOF
bb mcp call local-eval.local-eval --args-file /tmp/check-v2.json --mcp cb-v2-test
```

**v2 state keys:** `(:projects :selected-project :namespaces :selected-ns :symbols :aliases :refers :selected-symbol :source :sort-mode :loading? :error)`

**v1 state keys:** Different structure - check `sente-browser.code-browser/!code-browser-state`

### Common v2 Testing Mistakes

| Mistake | Symptom | Prevention |
|---------|---------|------------|
| Testing v1 thinking it's v2 | See git controls, "Add project" input | Look for v1-only UI elements |
| Forgetting to init v2 | v2 panels empty, only v1 works | Run init command after server start |
| Using wrong config | Mixed v1/v2 behavior | Use `system-cb-v2-test.edn` for v2 |
| Using wrong server nickname | Commands fail or hit wrong server | Use `cb-v2-test` for v2, `code-browser-dev` for v1 |

### v2 Files for Reference

| File | Purpose |
|------|---------|
| `modules/code-browser-v2/src/code_browser/core.clj` | v2 public API, `init!` |
| `modules/code-browser-v2/src/code_browser/sync.clj` | v2 sync state atom |
| `modules/code-browser-v2/src/code_browser/handlers.clj` | v2 event handlers |
| `modules/sente-browser/src/browser/code_browser_v2.cljs` | v2 browser UI |
| `system-cb-v2-test.edn` | v2 test server config |

### Running v2 Unit Tests

```bash
bb test:module code-browser-v2   # 30 tests, 459 assertions
```

These tests verify the server-side v2 logic (Datalevin, handlers, etc.) without browser.

### Known Issues (v2)

> **Status as of 2026-01-19:** v2 is fully functional! Source loading, namespace/symbol navigation all working. See "Stale Database Data" section for important setup requirement.

#### Recently Fixed

| Issue | Description | Fix Applied |
|-------|-------------|-------------|
| **Error state not cleared** | Initial "No database configured" error persisted after init | ✅ `init!` now clears previous error state automatically |
| **Initialization order** | Browser connecting before `init!` caused errors | ✅ `enable!` now defensive - skips project load if no database |
| **Auto-enable issues** | Had to manually call `enable!` after `init!` | ✅ `init!` now auto-enables via `:auto-enable? true` (default) |
| **Source not loading** | Clicking symbols showed "Select a symbol to view source" | ✅ Stale data bug - see "Stale Database Data" below |
| **Slow namespace queries** | Selecting project with 200+ namespaces times out | ✅ Fixed in deadlock resolution (de3e1ef) |

#### Remaining Issues

| Issue | Description | Impact | Workaround |
|-------|-------------|--------|------------|
| **Stale database data** | Old Datalevin data with outdated commit hashes | Source lookup fails | Delete database directory and rescan (see below) |
| **Project names show as "."** | URI-based names not displaying properly | UI shows "." instead of project name | Known - cosmetic issue |

### Stale Database Data (IMPORTANT)

**Symptom:** Clicking symbols doesn't load source, shows "Select a symbol to view source"

**Cause:** Datalevin stores URIs with commit hashes (e.g., `dir://.@ceb04b4/ns/symbol`). When you rescan with a newer commit, the source adapter registers symbols with the NEW commit hash (`dir://.@de3e1ef/ns/symbol`), but the database still has OLD URIs. The lookup fails because URIs don't match.

**Fix:** Delete the database directory before reinitializing:

```bash
# Stop server
bb server:stop cb-v2-test

# Delete stale database
rm -rf /tmp/cb-v2-test /tmp/cb-v2-test.lock

# Restart with fresh database
bb server:start-wait --nickname cb-v2-test --config system-cb-v2-test.edn
# Then run init! to create fresh data
```

**Prevention:** The setup script should always delete the database directory when starting fresh. This is now included in the quick setup script below.

**Recommendation:** v2 browser testing is now functional! The full flow works:
- 201 namespaces load correctly
- Symbols display for selected namespace
- Source code displays when clicking symbols
- File path and line numbers shown

**Known limitations:**
- Project names display as "." (cosmetic)
- Need to delete database when code changes commit
