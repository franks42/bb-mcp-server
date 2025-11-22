# Transport Abstraction Design

## Overview

bb-mcp-server supports multiple transports for MCP communication:
- **stdio** - Standard input/output (default for Claude Code)
- **HTTP** - HTTP POST for web clients
- **SSE** - Server-Sent Events for streaming (future)

All transports share the same request processing pipeline.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                         │
├─────────────┬─────────────┬─────────────┬──────────────────┤
│   stdio     │    HTTP     │    SSE      │    (future)      │
│  transport  │  transport  │  transport  │                  │
└──────┬──────┴──────┬──────┴──────┬──────┴──────────────────┘
       │             │             │
       └─────────────┼─────────────┘
                     ▼
         ┌───────────────────────┐
         │   Request Processor   │  ← Shared by all transports
         │  (router, handlers)   │
         └───────────────────────┘
```

---

## Transport Protocol

```clojure
;; src/bb_mcp_server/transport/protocol.clj

(defprotocol ITransport
  "Protocol for MCP transport implementations."

  (start! [this config]
    "Start the transport. Returns transport instance.
    Config may include :port, :host, etc.")

  (stop! [this]
    "Stop the transport gracefully.")

  (running? [this]
    "Check if transport is running."))
```

### Design Notes

- Transports are **stateful** - they manage connections/servers
- All transports use the **same request processor** (test-harness/process-json-rpc)
- Transports handle I/O framing (line-delimited for stdio, HTTP request/response for HTTP)
- Error responses are transport-specific (HTTP status codes, etc.)

---

## Stdio Transport (Existing)

Already implemented in `transport/stdio.clj`:

```clojure
;; Current implementation
(defn run-stdio-server! []
  (setup!)
  (doseq [line (line-seq reader)]
    (when-let [response (process-json-rpc line)]
      (println response)
      (flush))))
```

### Refactored to Protocol

```clojure
(defrecord StdioTransport [state]
  ITransport
  (start! [this config]
    (reset! state {:running true})
    (future (run-loop!))
    this)

  (stop! [this]
    (reset! state {:running false})
    this)

  (running? [this]
    (:running @state)))
```

---

## HTTP Transport (New)

### Endpoint Design

| Method | Path | Description |
|--------|------|-------------|
| POST | `/mcp` | JSON-RPC request/response |
| GET | `/health` | Health check |
| GET | `/metrics` | Prometheus metrics (future) |

### Request Flow

```
Client                    HTTP Transport              Request Processor
  │                            │                            │
  │  POST /mcp                 │                            │
  │  {"jsonrpc":"2.0",...}     │                            │
  │ ─────────────────────────► │                            │
  │                            │  process-json-rpc          │
  │                            │ ─────────────────────────► │
  │                            │                            │
  │                            │  {"jsonrpc":"2.0",...}     │
  │                            │ ◄───────────────────────── │
  │  200 OK                    │                            │
  │  {"jsonrpc":"2.0",...}     │                            │
  │ ◄───────────────────────── │                            │
```

### HTTP Implementation

```clojure
(ns bb-mcp-server.transport.http
  (:require [org.httpkit.server :as http]
            [bb-mcp-server.test-harness :as harness]
            [cheshire.core :as json]))

(defn mcp-handler [request]
  (let [body (slurp (:body request))
        response (harness/process-json-rpc body)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body response}))

(defn health-handler [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:status "ok"})})

(defn routes [request]
  (case [(:request-method request) (:uri request)]
    [:post "/mcp"] (mcp-handler request)
    [:get "/health"] (health-handler request)
    {:status 404 :body "Not found"}))

(defrecord HttpTransport [server-atom]
  ITransport
  (start! [this {:keys [port host] :or {port 3000 host "0.0.0.0"}}]
    (harness/setup!)
    (reset! server-atom (http/run-server routes {:port port :host host}))
    this)

  (stop! [this]
    (when-let [stop-fn @server-atom]
      (stop-fn))
    (reset! server-atom nil)
    this)

  (running? [this]
    (some? @server-atom)))
```

---

## HTTP Error Mapping

| JSON-RPC Error | HTTP Status |
|----------------|-------------|
| -32700 Parse error | 400 Bad Request |
| -32600 Invalid Request | 400 Bad Request |
| -32601 Method not found | 404 Not Found |
| -32602 Invalid params | 400 Bad Request |
| -32603 Internal error | 500 Internal Server Error |
| -32000 Tool not found | 404 Not Found |
| -32001 Execution failed | 500 Internal Server Error |
| -32002 Invalid tool params | 400 Bad Request |

### Implementation

```clojure
(defn error-code->http-status [code]
  (cond
    (<= -32600 code -32600) 400  ; Invalid request
    (= code -32601) 404          ; Method not found
    (= code -32602) 400          ; Invalid params
    (= code -32603) 500          ; Internal error
    (= code -32700) 400          ; Parse error
    (= code -32000) 404          ; Tool not found
    (= code -32001) 500          ; Execution failed
    (= code -32002) 400          ; Invalid tool params
    :else 500))

(defn mcp-handler [request]
  (let [body (slurp (:body request))
        response-str (harness/process-json-rpc body)
        response (json/parse-string response-str true)
        status (if (:error response)
                 (error-code->http-status (get-in response [:error :code]))
                 200)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body response-str}))
```

---

## CORS Support

For browser clients:

```clojure
(def cors-headers
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "POST, GET, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Authorization"})

(defn wrap-cors [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 204 :headers cors-headers}
      (update (handler request) :headers merge cors-headers))))
```

---

## Configuration

```clojure
;; config.edn
{:transport {:type :http        ; or :stdio
             :port 3000
             :host "0.0.0.0"
             :cors true}}
```

### CLI Arguments

```bash
# Stdio (default)
bb -m bb-mcp-server.main

# HTTP
bb -m bb-mcp-server.main --transport http --port 3000

# Both (future)
bb -m bb-mcp-server.main --transport stdio,http --port 3000
```

---

## Dependencies

Add to `bb.edn`:

```clojure
{:deps {http-kit/http-kit {:mvn/version "2.8.0"}}}
```

http-kit is Babashka-compatible and provides:
- Async HTTP server
- WebSocket support (for future SSE)
- Lightweight (~90KB)

---

## File Structure

```
src/bb_mcp_server/
├── transport/
│   ├── protocol.clj      ; ITransport protocol
│   ├── stdio.clj         ; Stdio implementation (refactored)
│   └── http.clj          ; HTTP implementation (new)
└── main.clj              ; Entry point with transport selection
```

---

## Testing Strategy

1. **Unit tests** - Test each transport in isolation
2. **Integration tests** - Test full request/response cycle
3. **curl tests** - Manual HTTP testing

```bash
# Health check
curl http://localhost:3000/health

# MCP request
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","params":{},"id":1}'
```

---

*Status: Design complete, ready for implementation*
