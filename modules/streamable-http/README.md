# streamable-http

MCP Streamable HTTP Transport for Babashka (spec 2025-03-26).

## Overview

A self-contained HTTP streaming library that provides:
- **Session management** - UUID-based sessions with configurable timeout
- **Server-Sent Events (SSE)** - Server-to-client push notifications
- **Ring middleware** - CORS, rate limiting, authentication
- **Handler-agnostic** - Works with any JSON-RPC handler function

## Quick Start

```clojure
(require '[streamable-http.core :as shttp])

;; Define your JSON-RPC handler
(defn my-handler [msg]
  {:jsonrpc "2.0"
   :result {:echo (:params msg)}
   :id (:id msg)})

;; Start server
(def server (shttp/start-server! my-handler {:port 3000}))

;; Server is now running at http://localhost:3000
;; POST /mcp    - JSON-RPC requests
;; GET  /mcp    - SSE stream (with Mcp-Session-Id header)
;; DELETE /mcp  - Terminate session
;; GET /health  - Health check

;; Stop server
(shttp/stop-server! server)
```

## Configuration

```clojure
{:port 3000                          ; HTTP port
 :host "0.0.0.0"                     ; Bind address
 :path "/mcp"                        ; MCP endpoint path
 :health-path "/health"              ; Health check path
 :session-timeout-ms 3600000         ; 1 hour session timeout
 :session-cleanup-interval-ms 60000  ; Cleanup check interval
 :cors {:enabled true
        :allowed-origins #{}}        ; Empty = allow all (dev only!)
 :rate-limit nil                     ; {:requests-per-minute 60}
 :basic-auth nil}                    ; {:credentials {"user" "pass"}}
```

## API Reference

### Server Lifecycle

```clojure
;; Start server with handler and optional config
(start-server! handler config) -> server

;; Stop server
(stop-server! server)

;; Get server status
(server-status) -> {:running true :port 3000 ...}
```

### Session Management

```clojure
;; Get session by ID
(get-session session-id) -> {:id "..." :created-at ... :sse-channels [...]}

;; List all active sessions
(list-sessions) -> ["session-1" "session-2" ...]
```

### Notifications (SSE)

```clojure
;; Send notification to specific session
(send-notification! session-id "notifications/message" {:text "Hello"})

;; Broadcast to all sessions
(broadcast-notification! "notifications/tools/list_changed" {})
```

## HTTP Endpoints

### POST /mcp

JSON-RPC requests. First request must be `initialize`.

```bash
# Initialize session
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}'

# Response includes Mcp-Session-Id header
```

### GET /mcp

SSE stream for server notifications. Requires valid session.

```bash
curl http://localhost:3000/mcp \
  -H "Mcp-Session-Id: <session-id>" \
  -H "Accept: text/event-stream"
```

### DELETE /mcp

Terminate session.

```bash
curl -X DELETE http://localhost:3000/mcp \
  -H "Mcp-Session-Id: <session-id>"
```

## Middleware

Built-in Ring middleware available in `streamable-http.middleware`:

| Middleware | Purpose |
|------------|---------|
| `wrap-cors` | CORS headers with origin validation |
| `wrap-rate-limit` | Token bucket rate limiting |
| `wrap-basic-auth` | HTTP Basic authentication |
| `wrap-api-key` | API key auth (Anthropic + OpenAI styles) |
| `wrap-request-logging` | Request/response logging |
| `wrap-origin-validation` | DNS rebinding protection |

## Module Structure

```
modules/streamable-http/
├── module.edn              # Module manifest
├── README.md               # This file
├── src/streamable_http/
│   ├── core.clj            # Public API
│   ├── server.clj          # HTTP server lifecycle
│   ├── router.clj          # Request routing
│   ├── middleware.clj      # Ring middleware
│   ├── session.clj         # Session management
│   ├── sse.clj             # SSE utilities
│   ├── util.clj            # Shared utilities
│   └── handlers/
│       ├── post.clj        # POST handler
│       ├── get.clj         # GET/SSE handler
│       └── delete.clj      # DELETE handler
└── test/
    └── ...                 # Test files
```

## Dependencies

- `http-kit/http-kit` - HTTP server
- `cheshire/cheshire` - JSON encoding

## License

Same as bb-mcp-server project.
