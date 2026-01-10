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
```

**Note:** `server:start-wait` handles health checking automatically - no need to manually verify ports or add sleep commands.

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

### 1. Wrong Connection Nickname
**Symptom:** Timeout on eval
**Cause:** Using stale nickname (browser-3) when new connection is active (browser-4)
**Fix:** Run `nrepl.nrepl-connection op=list` BEFORE every debugging session

### 2. Browser Disconnected
**Symptom:** No browser connections in list
**Cause:** Browser tab closed, or Playwright process ended
**Fix:** Restart Step 2

### 3. Server Not Running
**Symptom:** Connection refused
**Cause:** Server crashed or wasn't started
**Fix:** Restart Step 1

### 4. reagent.dom Not Available
**Symptom:** "Unable to resolve symbol: rdom" when loading code_browser.cljs
**Cause:** Scittle nREPL eval doesn't load reagent.dom as separate namespace
**Fix:** Use `bootstrap/mount-root!` instead of `rdom/render`

### 5. Empty Namespaces in Code Browser
**Symptom:** Code browser shows 0 namespaces
**Cause:** clojure-lsp not initialized
**Fix:** Run `bb mcp call clojure-lsp.clj-init '{"project-root":"/Users/franksiebenlist/Development/bb-mcp-server"}' --mcp code-browser-dev`

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
