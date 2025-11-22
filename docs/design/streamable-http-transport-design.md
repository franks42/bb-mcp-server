# Streamable HTTP Transport Design

## Overview

This document describes the design for implementing MCP's **Streamable HTTP transport** (spec version 2025-03-26) in bb-mcp-server using Babashka's http-kit.

**Status:** Design
**Date:** 2025-11-22
**MCP Spec:** https://modelcontextprotocol.io/specification/2025-03-26/basic/transports

---

## Background

### Why Streamable HTTP?

The MCP specification 2025-03-26 introduced **Streamable HTTP** to replace the older HTTP+SSE transport:

| Old (2024-11-05) | New (2025-03-26) |
|------------------|------------------|
| Separate endpoints for requests/responses | Single `/mcp` endpoint |
| SSE only for server→client | SSE optional, used when needed |
| Complex dual-connection management | Simpler single-connection model |
| No session management | Built-in session ID support |

### Key Benefits

1. **Single endpoint** - All communication through `/mcp`
2. **Flexible response modes** - JSON for simple, SSE for streaming
3. **Bidirectional** - Server can send notifications/requests to client
4. **Session support** - State management via `Mcp-Session-Id` header
5. **Backward compatible** - Can detect and serve old SSE clients

---

## Specification Summary

### Endpoint Structure

```
POST /mcp  - Send JSON-RPC messages
GET  /mcp  - Open SSE stream for notifications
```

### POST Request Flow

```
Client                          Server
  │                               │
  │  POST /mcp                    │
  │  Accept: application/json,    │
  │          text/event-stream    │
  │  Content-Type: application/json
  │  Body: {"jsonrpc":"2.0",...}  │
  │ ─────────────────────────────►│
  │                               │
  │  Option A: JSON Response      │
  │  Content-Type: application/json
  │  {"jsonrpc":"2.0",...}        │
  │ ◄─────────────────────────────│
  │                               │
  │  Option B: SSE Stream         │
  │  Content-Type: text/event-stream
  │  data: {"jsonrpc":"2.0",...}  │
  │  data: {"jsonrpc":"2.0",...}  │
  │ ◄─────────────────────────────│
```

### GET Request Flow (Notifications)

```
Client                          Server
  │                               │
  │  GET /mcp                     │
  │  Accept: text/event-stream    │
  │  Mcp-Session-Id: <id>         │
  │ ─────────────────────────────►│
  │                               │
  │  SSE Stream (long-lived)      │
  │  Content-Type: text/event-stream
  │  data: {"method":"notification",...}
  │  ...                          │
  │ ◄─────────────────────────────│
```

### Session Management

1. Server generates session ID during `initialize` response
2. Client includes `Mcp-Session-Id` header on subsequent requests
3. Server validates session and returns 400 if missing/invalid
4. Session terminates with 404 response

### Headers

| Header | Direction | Purpose |
|--------|-----------|---------|
| `Accept` | Request | `application/json, text/event-stream` |
| `Content-Type` | Request | `application/json` |
| `Content-Type` | Response | `application/json` or `text/event-stream` |
| `Mcp-Session-Id` | Both | Session identifier |
| `Last-Event-ID` | Request | SSE resumability |

---

## Implementation Design

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    transport/streamable_http.clj                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │   Router    │───►│  POST /mcp  │───►│   JSON-RPC  │         │
│  │             │    │   Handler   │    │   Processor │         │
│  │             │    └─────────────┘    └─────────────┘         │
│  │             │                                                │
│  │             │    ┌─────────────┐    ┌─────────────┐         │
│  │             │───►│  GET /mcp   │───►│ SSE Stream  │         │
│  │             │    │   Handler   │    │   Manager   │         │
│  └─────────────┘    └─────────────┘    └─────────────┘         │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                   Session Manager                         │  │
│  │  - Session ID generation (UUID)                          │  │
│  │  - Session state storage (atom)                          │  │
│  │  - Session validation                                    │  │
│  │  - Active SSE channels per session                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     Existing Infrastructure    │
              │  - test-harness/process-json-rpc
              │  - registry/tools             │
              │  - module system              │
              └───────────────────────────────┘
```

### File Structure

```
src/bb_mcp_server/
└── transport/
    ├── http.clj              # Current basic HTTP (keep for now)
    ├── streamable_http.clj   # NEW: MCP 2025-03-26 transport
    ├── sse.clj               # NEW: SSE utilities
    └── session.clj           # NEW: Session management
```

### Core Components

#### 1. Session Manager (`session.clj`)

```clojure
(ns bb-mcp-server.transport.session
  "MCP session management for Streamable HTTP transport.")

;; Session state atom
(defonce sessions (atom {}))

;; Session structure
{:session-id "uuid-string"
 :created-at <instant>
 :last-activity <instant>
 :client-info {...}          ; From initialize request
 :sse-channels #{...}        ; Active SSE connections
 :state {...}}               ; Application state

;; API
(defn create-session! [client-info] ...)
(defn get-session [session-id] ...)
(defn update-session! [session-id f] ...)
(defn destroy-session! [session-id] ...)
(defn valid-session? [session-id] ...)
(defn add-sse-channel! [session-id channel] ...)
(defn remove-sse-channel! [session-id channel] ...)
```

#### 2. SSE Utilities (`sse.clj`)

```clojure
(ns bb-mcp-server.transport.sse
  "Server-Sent Events utilities for http-kit."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]))

;; SSE event format
;; id: <event-id>
;; event: <event-type>
;; data: <json-payload>
;;
;; (blank line)

(defn format-sse-event
  "Format data as SSE event string."
  [{:keys [id event data]}]
  (str (when id (str "id: " id "\n"))
       (when event (str "event: " event "\n"))
       "data: " (if (string? data) data (json/generate-string data))
       "\n\n"))

(defn send-sse-event!
  "Send SSE event to channel."
  [channel event-data]
  (http/send! channel (format-sse-event event-data) false))

(defn open-sse-stream
  "Open SSE stream on http-kit channel."
  [channel on-close-fn]
  (http/send! channel
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"
               "X-Accel-Buffering" "no"}}  ; Disable nginx buffering
    false)
  (http/on-close channel on-close-fn)
  channel)

(defn close-sse-stream!
  "Close SSE stream."
  [channel]
  (http/close channel))
```

#### 3. Streamable HTTP Transport (`streamable_http.clj`)

```clojure
(ns bb-mcp-server.transport.streamable-http
  "MCP Streamable HTTP transport (spec 2025-03-26)."
  (:require [org.httpkit.server :as http]
            [bb-mcp-server.test-harness :as harness]
            [bb-mcp-server.transport.sse :as sse]
            [bb-mcp-server.transport.session :as session]
            [cheshire.core :as json]))

;; -----------------------------------------------------------------------------
;; Request Handlers
;; -----------------------------------------------------------------------------

(defn handle-post
  "Handle POST /mcp - JSON-RPC messages."
  [request]
  (let [session-id (get-in request [:headers "mcp-session-id"])
        accept (get-in request [:headers "accept"] "")
        body (slurp (:body request))
        parsed (json/parse-string body true)]

    (cond
      ;; Initialize request - create session
      (= "initialize" (:method parsed))
      (let [response (harness/process-json-rpc body)
            new-session (session/create-session! (:params parsed))
            response-with-session (add-session-to-response response new-session)]
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Mcp-Session-Id" (:session-id new-session)}
         :body response-with-session})

      ;; Other requests - validate session
      (not (session/valid-session? session-id))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
               {:jsonrpc "2.0"
                :error {:code -32003 :message "Invalid or missing session"}
                :id (:id parsed)})}

      ;; Normal request - check if client wants SSE
      (and (str/includes? accept "text/event-stream")
           (should-stream? parsed))
      (handle-streaming-response request parsed session-id)

      ;; Normal JSON response
      :else
      (let [response (harness/process-json-rpc body)]
        (session/touch-session! session-id)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body response}))))

(defn handle-get
  "Handle GET /mcp - Open SSE notification stream."
  [request]
  (let [session-id (get-in request [:headers "mcp-session-id"])
        accept (get-in request [:headers "accept"] "")]

    (cond
      ;; Must accept SSE
      (not (str/includes? accept "text/event-stream"))
      {:status 406
       :body "Must accept text/event-stream"}

      ;; Must have valid session
      (not (session/valid-session? session-id))
      {:status 400
       :body "Invalid or missing session"}

      ;; Open SSE stream
      :else
      (http/with-channel request channel
        (sse/open-sse-stream channel
          (fn [status]
            (session/remove-sse-channel! session-id channel)))
        (session/add-sse-channel! session-id channel)))))

(defn handle-delete
  "Handle DELETE /mcp - Terminate session."
  [request]
  (let [session-id (get-in request [:headers "mcp-session-id"])]
    (when (session/valid-session? session-id)
      (session/destroy-session! session-id))
    {:status 204}))

;; -----------------------------------------------------------------------------
;; Router
;; -----------------------------------------------------------------------------

(defn mcp-handler
  "Main MCP endpoint handler."
  [request]
  (case (:request-method request)
    :post (handle-post request)
    :get (handle-get request)
    :delete (handle-delete request)
    :options {:status 204 :headers cors-headers}
    {:status 405 :body "Method not allowed"}))

(defn routes
  "Route requests to appropriate handler."
  [request]
  (case (:uri request)
    "/mcp" (mcp-handler request)
    "/health" (health-handler request)
    {:status 404 :body "Not found"}))

;; -----------------------------------------------------------------------------
;; Server Lifecycle
;; -----------------------------------------------------------------------------

(defn start!
  "Start Streamable HTTP server."
  [{:keys [port host] :or {port 3000 host "0.0.0.0"}}]
  (harness/setup!)
  (let [server (http/run-server #'routes {:port port :host host})]
    (reset! server-atom server)
    server))

(defn stop!
  "Stop server and cleanup sessions."
  []
  (session/destroy-all-sessions!)
  (when-let [stop-fn @server-atom]
    (stop-fn))
  (reset! server-atom nil))
```

---

## Security Considerations

### Origin Validation

Per spec: "Servers MUST validate the Origin header on all incoming connections to prevent DNS rebinding attacks."

```clojure
(defn validate-origin
  "Validate Origin header against allowed origins."
  [request allowed-origins]
  (let [origin (get-in request [:headers "origin"])]
    (or (nil? origin)  ; Same-origin requests have no Origin
        (contains? allowed-origins origin)
        (and (= "localhost" (:server-name request))
             (str/starts-with? origin "http://localhost")))))
```

### Session Security

1. **UUID generation** - Use `java.util.UUID/randomUUID` (secure)
2. **Session timeout** - Expire inactive sessions after configurable period
3. **Session binding** - Optional: bind to client IP or other identifier
4. **HTTPS only** - Recommend HTTPS for production

### Rate Limiting

```clojure
(defn rate-limit-middleware
  "Rate limit requests per session."
  [handler {:keys [requests-per-minute]}]
  (fn [request]
    (let [session-id (get-in request [:headers "mcp-session-id"])]
      (if (rate-limited? session-id requests-per-minute)
        {:status 429 :body "Too many requests"}
        (handler request)))))
```

---

## Backward Compatibility

### Detecting Old SSE Clients

Per spec, old clients (2024-11-05) will:
1. Send GET to server URL expecting SSE endpoint event
2. Not include `Mcp-Session-Id` header initially

Detection strategy:

```clojure
(defn detect-old-client
  "Detect if client is using old HTTP+SSE transport."
  [request]
  (and (= :get (:request-method request))
       (nil? (get-in request [:headers "mcp-session-id"]))
       (str/includes? (get-in request [:headers "accept"] "") "text/event-stream")))
```

For now, we'll return 405 for old clients with a helpful error message. Full backward compatibility is optional.

---

## Testing Strategy

### Unit Tests

```clojure
;; test/bb_mcp_server/transport/streamable_http_test.clj

(deftest test-session-lifecycle
  (let [session (session/create-session! {:clientInfo {:name "test"}})]
    (is (session/valid-session? (:session-id session)))
    (session/destroy-session! (:session-id session))
    (is (not (session/valid-session? (:session-id session))))))

(deftest test-sse-event-format
  (is (= "data: {\"test\":true}\n\n"
         (sse/format-sse-event {:data {:test true}})))
  (is (= "id: 1\nevent: message\ndata: hello\n\n"
         (sse/format-sse-event {:id "1" :event "message" :data "hello"}))))
```

### Integration Tests

```bash
# Test POST with JSON response
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}'

# Test GET for SSE stream
curl -N http://localhost:3000/mcp \
  -H "Accept: text/event-stream" \
  -H "Mcp-Session-Id: <session-id>"

# Test session termination
curl -X DELETE http://localhost:3000/mcp \
  -H "Mcp-Session-Id: <session-id>"
```

### Compliance Testing

Test against MCP SDK clients:
- Python SDK with `transport="streamable-http"`
- TypeScript SDK with StreamableHTTP transport

---

## Implementation Phases

### Phase 1: Core Infrastructure
- [ ] `session.clj` - Session management
- [ ] `sse.clj` - SSE utilities
- [ ] Basic POST handler (JSON response only)
- [ ] Unit tests

### Phase 2: Full POST Support
- [ ] Session creation on initialize
- [ ] Session validation middleware
- [ ] Accept header negotiation
- [ ] SSE streaming response option

### Phase 3: GET Endpoint
- [ ] SSE notification stream
- [ ] Channel management per session
- [ ] Server-initiated messages

### Phase 4: Production Ready
- [ ] Origin validation
- [ ] Rate limiting
- [ ] Session timeout/cleanup
- [ ] Error handling improvements
- [ ] Integration with module system

### Phase 5: Optional Enhancements
- [ ] Backward compatibility for old SSE clients
- [ ] Event ID for resumability
- [ ] Metrics/telemetry

---

## Configuration

```clojure
;; system.edn addition
{:transport
 {:type :streamable-http     ; or :stdio, :http (legacy)
  :port 3000
  :host "0.0.0.0"
  :allowed-origins #{"http://localhost:*"}
  :session-timeout-ms 3600000  ; 1 hour
  :rate-limit {:requests-per-minute 60}}}
```

---

## Open Questions

1. **Streaming tool responses** - Should we stream long tool outputs via SSE?
2. **Multi-session** - Support multiple sessions per client?
3. **Batching** - MCP 2025-06-18 removed JSON-RPC batching - do we support it anyway?
4. **WebSocket** - Future transport option?

---

## References

- [MCP Spec - Transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [Why MCP Deprecated SSE](https://blog.fka.dev/blog/2025-06-06-why-mcp-deprecated-sse-and-go-with-streamable-http/)
- [Latacora Clojure MCP SDK](https://github.com/latacora/mcp-sdk) (JVM reference)
- [http-kit Server](http://http-kit.github.io/server.html)
- [Cloudflare MCP Streamable HTTP](https://blog.cloudflare.com/streamable-http-mcp-servers-python/)

---

*Status: Design complete, ready for implementation review*
