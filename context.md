# Session Context for bb-mcp-server

**Last Updated:** 2025-12-25 (Platform Info Exchange - COMPLETE)
**Current Version:** v1.3.0

---

## Recent Completed Work

### Platform Info Exchange (2025-12-25) ✅ VERIFIED WORKING

**Goal achieved:** Exchange runtime metadata between sente peers.

**What works:**
- Browser collects: user-agent, platform, screen size, runtime (:scittle), URL
- Data sent via `:platform-info/update` sente event on connect
- Server stores in `!browser-connections` map under `:platform-info` key
- Watcher syncs changes automatically

**Verified output:**
```clojure
;; Browser sends:
{:runtime :scittle
 :user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)..."
 :platform "MacIntel"
 :language "en-US"
 :screen-width 1280
 :screen-height 720
 :url "http://localhost:8091/"}

;; Server logs:
::platform-info-received Browser platform info updated
  :runtime :scittle
  :keys (:runtime :user-agent :platform :language :screen-width :screen-height :url)
```

**Files involved (all working):**
- `sente_lite/src/sente_lite/registry.cljc` - Platform info atom & sync
- `sente_lite/modules/nrepl/src/nrepl_sente/browser_adapter.cljs` - Collects & sends
- `bb-mcp-server/modules/sente-browser/src/sente_browser/server.clj` - Receives & stores

**Note:** The earlier "namespace not in all-ns" concern was a red herring - the code works correctly when tested properly via Playwright on the correct port (8091).

### Bundle Dependency Validation (2025-12-25) ✅ IMPLEMENTED

Added automatic dependency order validation to `sente_lite/dist/build-bundle.bb`:

**Features:**
- Parses ns forms to extract `:require` clauses
- Validates each file's dependencies appear earlier in the list
- Fails fast with clear error message if order is wrong
- External deps (clojure.core, taoensso.trove, etc.) are whitelisted

**Example error output:**
```
❌ DEPENDENCY ORDER ERROR!

File: ../modules/nrepl/src/nrepl_sente/browser_adapter.cljs
  Namespace: nrepl-sente.browser-adapter
  Missing: sente-lite.client-scittle, sente-lite.registry
  These namespaces must appear EARLIER in source-files list
```

**To add new files:** Just add to `source-files` list - build will fail if order is wrong.

---

## Previous Completed Work

### nrepl-proxy-server Module (v1.3.0)

Shadow-cljs style browser REPL proxy - connect Calva/terminal to browser REPLs:

**API:**
```clojure
(browser/list)           ; List available browsers
(browser/repl :browser-127)  ; Switch to browser context
:cljs/quit               ; Return to bb context
```

**Features:**
- Multi-browser support (tested with 3 concurrent Playwright tabs)
- Seamless switching between browsers
- ClojureScript eval in browser (`js/navigator`, `js/document`, etc.)
- Automatic session cleanup on disconnect
- Module dependencies: `sente-browser` for WebSocket browser connections

**Key files:**
- `modules/nrepl-proxy-server/src/nrepl_proxy_server/*.clj`
- `system.edn` - config with `:enabled true :port 1667`

**Tests:** 15 tests, 44 assertions - all passing

---

## Key Commands

```bash
# Run server
bb server --http               # HTTP on port 3000
bb server --http 19878         # HTTP on custom port (for sente-browser testing)
bb server --stdio              # stdio transport (Claude Desktop)

# Testing
bb test:modules                # All module tests

# Verification (REQUIRED before commit - ZERO warnings)
clj-kondo --lint <files>       # 0 errors, 0 warnings
cljfmt check <files>           # All files formatted
```

---

## Key Constraints

1. **Babashka compatible** - All code must run in bb
2. **Telemetry required** - taoensso.trove for all I/O
3. **Zero lint warnings** - Not just errors
4. **Test before claiming** - "If we didn't test it doesn't exist"
5. **Never commit API keys** - Use .cak.sh (gitignored)

---

*Context updated 2025-12-25 - Platform info exchange incomplete, needs fresh investigation*
