# Context: Sente-Browser Module for bb-mcp-server

**Read this first before starting work on the module.**

## What This Is About

We're creating a new `sente-browser` module for bb-mcp-server that embeds a sente-lite WebSocket server, enabling Claude to directly interact with browser-based Scittle runtimes.

**Goal:** Claude can eval code in browsers using the same `nrepl-eval` tool it uses for JVM nREPL servers.

## Key Documents

1. **`docs/sente-browser-module.md`** (this directory)
   - Full design document with architecture diagrams
   - Detailed implementation plan
   - Code snippets for each component
   - **READ THIS THOROUGHLY BEFORE CODING**

## Related Repositories

### This repo: bb-mcp-server
- Modular MCP server framework in Babashka
- You'll be creating a new module here
- Has existing `nrepl` module you'll extend/integrate with

### sente-lite: `/Users/franksiebenlist/Development/sente_lite`
- Lightweight WebSocket library for BB/nbb/Scittle
- You'll need to read source files from here
- Key files:
  - `src/sente_lite/server.cljc` - WebSocket server with HTTP handler
  - `modules/nrepl/src/nrepl_sente/server.cljc` - nREPL server for BB/Scittle
  - `modules/nrepl/src/nrepl_sente/protocol.cljc` - EDN message format

## Key Files in This Repo

Read these to understand the current architecture:

| File | Purpose |
|------|---------|
| `bb.edn` | Build config, module loading |
| `src/bb_mcp_server/module/protocol.clj` | Module lifecycle (start/stop/status) |
| `src/bb_mcp_server/registry.clj` | Tool registration |
| `modules/nrepl/module.edn` | Example module config |
| `modules/nrepl/src/nrepl/core.clj` | Example module lifecycle |
| `modules/nrepl/src/nrepl/state/connection.clj` | Connection registry (extend this) |
| `modules/nrepl/src/nrepl/state/messages.clj` | Message queues (extend this) |

## Module Structure to Create

```
modules/sente-browser/
├── module.edn              # Module configuration
├── README.md               # Module documentation
├── src/
│   └── sente_browser/
│       ├── core.clj        # Module lifecycle (start/stop/status)
│       ├── server.clj      # sente-lite WebSocket server
│       ├── bootstrap.clj   # HTTP server for bootstrap HTML
│       └── adapter.clj     # Adapter for nrepl connection/messages
└── test/
    ├── run_tests.clj
    └── sente_browser/
        └── integration_test.clj
```

## Dependency Required

sente-lite needs to be added as a dependency:

1. **Local path** (for development):
   ```clojure
   ;; In bb.edn
   io.github.franks42/sente-lite {:local/root "/Users/franksiebenlist/Development/sente_lite"}
   ```

2. **Git coordinate** (for deployment):
   ```clojure
   io.github.franks42/sente-lite {:git/url "https://github.com/franks42/sente-lite"
                                   :git/sha "..."}
   ```

## Suggested Implementation Order (Phase 1 MVP)

1. **Add sente-lite dependency** to bb.edn
2. **Create `modules/sente-browser/` directory structure**
3. **Create `module.edn`** with:
   - `:requires ["nrepl"]` dependency
   - `:entry "sente-browser.core/module"`
   - Default config for ports
4. **Create `core.clj`** - module lifecycle
   - start: Launch sente server + bootstrap HTTP
   - stop: Shutdown servers
   - status: Report browser count
5. **Create `server.clj`** - sente-lite integration
   - Embed sente-lite server
   - Handle browser connect/disconnect
   - Route messages to/from browsers
6. **Extend `modules/nrepl/src/nrepl/state/connection.clj`**
   - Add `:type` field (`:socket` or `:browser`)
   - Add `register-browser-connection!` function
   - Add `is-browser-connection?` helper
7. **Extend `modules/nrepl/src/nrepl/state/messages.clj`**
   - Add browser adapter alongside socket adapter
   - Route sends based on connection type
8. **Create `bootstrap.clj`** - HTTP server for HTML
9. **Test with live MCP interaction**
   - Start server with sente-browser enabled
   - Open browser to bootstrap URL
   - Use `nrepl-connection op=list` to see browser
   - Use `nrepl-eval connection=browser-1` to eval

## Critical Design Decisions

### Different Connection Pattern!

| | Socket | Browser |
|--|--------|---------|
| Who initiates? | Claude: `op=connect` | Browser opens URL |
| Discovery | Claude knows (it connected) | `op=list` shows browsers |
| `connect` meaning | Initiate TCP connection | Assign nickname to existing |

### File Loading

| Tool | Browser Support |
|------|----------------|
| `nrepl-load-file` | NO - browser has no filesystem |
| `nrepl-eval-local-file` | YES - MCP reads file, sends content |
| `nrepl-eval` | YES - works normally |

## Module Lifecycle Pattern

Follow the pattern in `modules/nrepl/src/nrepl/core.clj`:

```clojure
(def module
  {:start (fn [deps config] ...)  ; Returns instance
   :stop  (fn [instance] ...)     ; Returns nil
   :status (fn [instance] ...)})  ; Returns {:status :ok|:error ...}
```

## Testing Approach

For this integration, live MCP interaction is the primary test method:

1. Start bb-mcp-server with sente-browser enabled
2. Open browser to bootstrap URL (http://localhost:8091/nrepl)
3. Use Claude (via MCP tools) to interact with browser
4. Verify eval results come back correctly

## Environment Variables

```bash
SENTE_BROWSER_ENABLED=true      # Enable sente-browser module
SENTE_BROWSER_WS_PORT=8090      # WebSocket port
SENTE_BROWSER_HTTP_PORT=8091    # HTTP port for bootstrap HTML
SENTE_BROWSER_HOST=127.0.0.1    # Bind address (localhost by default for security)
```

**Security Note:** Servers bind to `127.0.0.1` (localhost) by default. Set `SENTE_BROWSER_HOST=0.0.0.0` only if you need network access.

## Running the Server

```bash
# Start with sente-browser module
SENTE_BROWSER_ENABLED=true bb server --stdio

# Or with specific ports
SENTE_BROWSER_WS_PORT=9090 SENTE_BROWSER_HTTP_PORT=9091 bb server --stdio
```

## MVP vs Future Architecture

The design doc describes an MVP approach that adds `:browser` type handling directly in the nrepl module. This works but creates some coupling.

**After MVP works**, see the "Future Refinements" section in `sente-browser-module.md` for the **Adapter Registry Pattern** - a cleaner architecture where nrepl exposes `register-adapter!` / `unregister-adapter!` and sente-browser registers itself on startup.

**For now:** Get MVP working first, then refactor.

## Questions?

If unclear on anything:
1. Re-read `docs/sente-browser-module.md`
2. Read the existing nrepl module as a reference
3. Read sente-lite source files
4. Ask the user for clarification

Good luck!
