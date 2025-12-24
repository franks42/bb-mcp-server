# sente-browser

Browser nREPL module - evaluate ClojureScript in browsers via WebSocket.

## Overview

Enables Claude to evaluate code in browser-based Scittle runtimes. Browsers connect to a WebSocket server and appear as nREPL connections that Claude can interact with using the standard `nrepl-eval` tool.

**Architecture:**
- WebSocket server (sente-lite) accepts browser connections
- Bootstrap HTTP server serves HTML page with Scittle nREPL client
- Browsers auto-register as nREPL connections (type `:browser`)
- Claude discovers browsers via `nrepl-connection {:op "list"}`, then evals code

## Usage

### 1. Start the module

The module starts automatically when loaded. Default ports:
- WebSocket: 8090
- Bootstrap HTTP: 8091

### 2. Open browser

Navigate to `http://localhost:8091` to load the bootstrap page. The page:
- Connects to WebSocket server via sente-lite
- Displays connection status and eval log
- Automatically responds to nREPL eval requests

### 3. Eval code from Claude

```clojure
;; List connections - browser appears as type :browser
{:op "list"}

;; Eval in browser (use browser's connection ID)
{:connection "browser-abc123" :code "(js/alert \"Hello from Claude!\")"}

;; Browser-specific APIs work
{:connection "browser-abc123" :code "(.-innerHTML (js/document.querySelector \"h1\"))"}
```

## Public API

### Server Functions (`sente-browser.server`)

| Function | Description |
|----------|-------------|
| `browser-count` | Number of connected browsers |
| `get-browser-connections` | All browser connections as map |
| `get-connection-health` | Health status for all browsers |
| `send-to-browser!` | Send event to specific browser |
| `broadcast-to-browsers!` | Send event to all browsers |

### Connection Health

Heartbeat monitoring ensures stale connections are detected:
- Ping sent every 10 seconds
- Connections without pong for 30s are auto-disconnected
- `get-connection-health` returns per-connection health status

```clojure
(get-connection-health)
;; => {"sente-conn-1" {:mcp-conn-id "browser-xxx"
;;                     :healthy? true
;;                     :last-seen-ms-ago 2345}}
```

## Configuration

```clojure
;; module.edn or config
{:enabled true           ; Enable/disable module
 :host "127.0.0.1"       ; Bind address (default: localhost)
 :ws-port 8090           ; WebSocket port
 :bootstrap-port 8091}   ; Bootstrap HTTP port
```

## Module Structure

```
modules/sente-browser/
├── module.edn           # Module manifest
├── README.md            # This file
└── src/sente_browser/
    ├── core.clj         # Module lifecycle (start/stop/status)
    ├── server.clj       # WebSocket server, heartbeat, broadcast
    └── bootstrap.clj    # HTTP server for bootstrap page
```

## Security

- Binds to `127.0.0.1` by default (localhost only)
- No authentication (suitable for local development)
- For production, consider adding authentication middleware

## Dependencies

- `sente-lite` 0.4.2-SNAPSHOT - Lightweight WebSocket library
- `nrepl` module - For connection state management

## License

Same as bb-mcp-server project.
