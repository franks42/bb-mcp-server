# Streamable HTTP Transport - Implementation Plan

## Overview

This document outlines the implementation plan for **streamable-http** - a **generic HTTP streaming library for Babashka** that provides:

1. **Session management** - UUID-based sessions with configurable headers
2. **Server-Sent Events (SSE)** - Server-to-client push notifications
3. **Structured lifecycle** - Start/stop with cleanup
4. **Ring middleware** - CORS, auth, rate-limiting, logging

### Broader Vision

This is an **upgrade to bb's HTTP server stack**, providing an alternative to WebSocket-based solutions:

| | WebSocket | streamable-http (SSE) |
|--|-----------|----------------------|
| Direction | Bidirectional | Request/Response + Server Push |
| Complexity | More complex | Simpler, standard HTTP |
| Firewalls | Sometimes blocked | Always works |
| Reconnection | Manual | Built into EventSource |

### Use Cases

1. **MCP Transport** - Primary use case, implements MCP spec 2025-03-26
2. **REST APIs** - Same tools exposed via REST with real-time features
3. **Any bb project** - Reusable session/SSE infrastructure

**Status:** Phase 7 Complete - REST API Transport with Documentation
**Date:** 2025-11-22
**Last Updated:** 2025-11-23
**Design Doc:** `streamable-http-transport-design.md`
**Review:** `streamable-http-transport-review.md` (Approved)

**Recent additions (Phase 7.5):**
- `/api/server` endpoint for server introspection
- `/api/openapi.json` OpenAPI 3.0 spec generation
- `/api/docs` HTML documentation page
- Module-based REST routing (`/api/modules/:module/tools/:name`)
- `moduleToolSeparator` exposed in MCP initialize and REST `/api/server`

---

## Modularity Goals

### Design Principles

1. **Zero coupling** to bb-mcp-server internals
2. **Protocol-only dependency** - depends on a handler function, not specific implementations
3. **Self-contained** - all code in one directory
4. **Minimal dependencies** - only http-kit, cheshire (both bb-compatible)
5. **Clear API boundary** - single entry point with documented contract
6. **Ring middleware compatible** - standard `(fn [handler] (fn [req] ...))` pattern

### Future Extraction Path

```
Current location:
  bb-mcp-server/modules/streamable-http/

Future standalone repo:
  bb-streamable-http/              # Generic library (NOT MCP-specific)
  ├── src/
  │   └── streamable_http/
  │       ├── core.clj             # Generic API
  │       ├── session.clj          # Session management
  │       ├── sse.clj              # SSE utilities
  │       └── middleware.clj       # Ring middleware
  ├── test/
  ├── bb.edn
  └── README.md
```

---

## Dual-Interface Architecture

The library supports multiple protocol adapters on a shared transport:

```
┌─────────────────────────────────────────────────────┐
│              streamable-http (generic)              │
│     Sessions │ SSE │ Middleware │ Lifecycle         │
├─────────────────┬───────────────────────────────────┤
│   /mcp          │          /api                     │
│   JSON-RPC 2.0  │          REST                     │
│   MCP Protocol  │          REST conventions         │
├─────────────────┴───────────────────────────────────┤
│                 Tool Registry                       │
│           (same tools, two interfaces)              │
└─────────────────────────────────────────────────────┘
```

### Configuration for Different Use Cases

```clojure
;; MCP Transport (current default)
{:session-header "Mcp-Session-Id"
 :path "/mcp"
 :protocol :json-rpc}

;; REST API
{:session-header "X-Session-Id"
 :path "/api"
 :protocol :rest}

;; Generic (custom)
{:session-header "X-My-Session"
 :path "/stream"
 :protocol :custom}
```

### Shared Features Across Protocols

| Feature | Available to All |
|---------|------------------|
| Session management | ✅ |
| SSE notifications | ✅ |
| CORS middleware | ✅ |
| Rate limiting | ✅ |
| Basic auth | ✅ |
| Request logging | ✅ |

---

## Module Structure

```
modules/streamable-http/
├── module.edn                    # bb-mcp-server module manifest
├── README.md                     # Standalone documentation
├── src/
│   └── streamable_http/
│       ├── core.clj              # Public API - single entry point
│       ├── server.clj            # HTTP server lifecycle
│       ├── router.clj            # Request routing
│       ├── middleware.clj        # Ring middleware (CORS, auth, rate-limit)
│       ├── handlers/
│       │   ├── post.clj          # POST /mcp handler
│       │   ├── get.clj           # GET /mcp handler (SSE stream)
│       │   └── delete.clj        # DELETE /mcp handler
│       ├── session.clj           # Session management
│       ├── sse.clj               # SSE utilities
│       └── util.clj              # Shared utilities
└── test/
    ├── run_tests.clj             # Test runner
    ├── core_test.clj
    ├── middleware_test.clj       # Middleware tests
    ├── session_test.clj
    ├── sse_test.clj
    └── integration_test.clj
```

---

## Public API Contract

### Core Namespace (`streamable-http.core`)

```clojure
(ns streamable-http.core
  "MCP Streamable HTTP Transport - Public API

   This module implements MCP spec 2025-03-26 Streamable HTTP transport.
   It is designed to be handler-agnostic: you provide a function that
   processes JSON-RPC messages, and this module handles all HTTP/SSE concerns.")

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-config
  {:port 3000
   :host "0.0.0.0"
   :path "/mcp"                      ; MCP endpoint path
   :health-path "/health"            ; Health check path
   :session-timeout-ms 3600000       ; 1 hour
   :session-cleanup-interval-ms 60000 ; Check every minute

   ;; Middleware configuration
   :middleware []                    ; Vector of Ring middleware functions
   :cors {:enabled true              ; Use built-in CORS middleware
          :allowed-origins #{}}      ; Empty = allow all (dev only!)
   :rate-limit nil                   ; {:requests-per-minute 60}
   :basic-auth nil})                 ; {:credentials {"user" "pass"}}

;; =============================================================================
;; Handler Protocol
;; =============================================================================

;; The transport requires a handler function with this signature:
;;
;;   (fn [json-rpc-request] -> json-rpc-response)
;;
;; Where:
;;   json-rpc-request  = {:jsonrpc "2.0" :method "..." :params {...} :id ...}
;;   json-rpc-response = {:jsonrpc "2.0" :result {...} :id ...}
;;                    or {:jsonrpc "2.0" :error {...} :id ...}
;;
;; The handler is responsible for:
;;   - Processing initialize, tools/list, tools/call, etc.
;;   - Returning valid JSON-RPC 2.0 responses
;;
;; The transport handles:
;;   - HTTP/SSE protocol
;;   - Session management
;;   - CORS, security headers
;;   - Error wrapping for transport-level errors

;; =============================================================================
;; Public Functions
;; =============================================================================

(defn create-server
  "Create a Streamable HTTP server instance (not started).

   Arguments:
     handler - Function (fn [json-rpc-map] -> json-rpc-response-map)
     config  - Optional config map (merged with default-config)

   Returns:
     Server instance map with :start!, :stop!, :status functions

   Example:
     (def server (create-server my-handler {:port 8080}))
     ((:start! server))
     ;; ... use server ...
     ((:stop! server))"
  [handler & [config]]
  ...)

(defn start-server!
  "Convenience function to create and start a server in one call.

   Returns the server instance (can call :stop! on it later)."
  [handler & [config]]
  ...)

(defn stop-server!
  "Stop a running server instance."
  [server]
  ...)

;; =============================================================================
;; Session API (for advanced use)
;; =============================================================================

(defn get-session
  "Get session data by ID. Returns nil if not found."
  [server session-id]
  ...)

(defn send-notification!
  "Send a notification to all SSE channels for a session.

   Arguments:
     server     - Server instance
     session-id - Target session
     method     - Notification method (e.g., \"notifications/message\")
     params     - Notification params map

   Returns:
     Number of channels notified"
  [server session-id method params]
  ...)

(defn broadcast-notification!
  "Send a notification to ALL active sessions.

   Use sparingly - for server-wide announcements only."
  [server method params]
  ...)
```

---

## Ring Middleware Architecture

### Middleware Pattern

The module uses standard Ring middleware pattern for extensibility:

```clojure
;; Ring middleware signature
(defn wrap-something
  "Middleware that does something."
  [handler]
  (fn [request]
    ;; Pre-processing (modify request, check auth, etc.)
    (let [response (handler modified-request)]
      ;; Post-processing (add headers, transform response, etc.)
      response)))

;; With configuration
(defn wrap-something
  [handler {:keys [option1 option2]}]
  (fn [request]
    ...))
```

### Middleware Application Order

Middleware is applied **inside-out** (last in config = closest to handler):

```clojure
;; Config
{:middleware [wrap-logging wrap-cors wrap-auth]}

;; Results in:
;; request -> wrap-logging -> wrap-cors -> wrap-auth -> handler
;; response <- wrap-logging <- wrap-cors <- wrap-auth <-
```

### Built-in Middleware (`streamable-http.middleware`)

All pure Clojure, zero external dependencies, Babashka-compatible:

```clojure
(ns streamable-http.middleware
  "Ring middleware for MCP Streamable HTTP transport.
   All middleware follows standard Ring pattern and is bb-compatible.")

;; =============================================================================
;; CORS Middleware
;; =============================================================================

(defn wrap-cors
  "Add CORS headers to responses.

   Options:
     :allowed-origins - Set of allowed origins (empty = allow all)
     :allowed-methods - HTTP methods (default: POST, GET, DELETE, OPTIONS)
     :allowed-headers - Request headers (default: Content-Type, Authorization, Mcp-Session-Id)
     :max-age         - Preflight cache time in seconds (default: 86400)

   Example:
     (wrap-cors handler {:allowed-origins #{\"https://example.com\"}})"
  [handler & [{:keys [allowed-origins allowed-methods allowed-headers max-age]
               :or {allowed-methods #{:post :get :delete :options}
                    allowed-headers #{\"content-type\" \"authorization\" \"mcp-session-id\"}
                    max-age 86400}}]]
  (fn [request]
    (let [origin (get-in request [:headers \"origin\"])]
      (if (= (:request-method request) :options)
        ;; Preflight response
        {:status 204
         :headers (cors-headers origin allowed-origins allowed-methods allowed-headers max-age)}
        ;; Regular response with CORS headers
        (when-let [response (handler request)]
          (update response :headers merge
                  (cors-headers origin allowed-origins allowed-methods allowed-headers max-age)))))))

;; =============================================================================
;; Rate Limiting Middleware
;; =============================================================================

(defn wrap-rate-limit
  "Limit requests per client IP using token bucket algorithm.

   Options:
     :requests-per-minute - Max requests per minute per IP
     :burst               - Allow burst up to N requests (default: same as rpm)
     :key-fn              - Function to extract rate limit key (default: :remote-addr)

   Example:
     (wrap-rate-limit handler {:requests-per-minute 60})"
  [handler {:keys [requests-per-minute burst key-fn]
            :or {key-fn :remote-addr}}]
  (let [buckets (atom {})
        burst (or burst requests-per-minute)]
    (fn [request]
      (let [key (key-fn request)]
        (if (allow-request? buckets key requests-per-minute burst)
          (handler request)
          {:status 429
           :headers {\"Content-Type\" \"application/json\"
                     \"Retry-After\" \"60\"}
           :body \"{\\\"error\\\":\\\"Rate limit exceeded\\\"}\"})))))

;; =============================================================================
;; Basic Auth Middleware
;; =============================================================================

(defn wrap-basic-auth
  "HTTP Basic Authentication.

   Options:
     :credentials - Map of username -> password
     :realm       - Auth realm (default: \"MCP Server\")

   Example:
     (wrap-basic-auth handler {:credentials {\"admin\" \"secret\"}})"
  [handler {:keys [credentials realm] :or {realm \"MCP Server\"}}]
  (fn [request]
    (if-let [auth-header (get-in request [:headers \"authorization\"])]
      (if (valid-basic-auth? auth-header credentials)
        (handler request)
        {:status 401
         :headers {\"WWW-Authenticate\" (str \"Basic realm=\\\"\" realm \"\\\"\")}
         :body \"{\\\"error\\\":\\\"Invalid credentials\\\"}\"})
      {:status 401
       :headers {\"WWW-Authenticate\" (str \"Basic realm=\\\"\" realm \"\\\"\")}
       :body \"{\\\"error\\\":\\\"Authentication required\\\"}\"})))

;; =============================================================================
;; Request Logging Middleware
;; =============================================================================

(defn wrap-request-logging
  "Log incoming requests and outgoing responses.

   Options:
     :log-fn   - Logging function (default: println)
     :level    - Log level keyword (default: :info)
     :include  - Set of fields to include (:headers, :body, :timing)

   Example:
     (wrap-request-logging handler {:log-fn my-logger :include #{:timing}})"
  [handler & [{:keys [log-fn level include]
               :or {log-fn println level :info include #{:timing}}}]]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          elapsed (- (System/currentTimeMillis) start)]
      (log-fn {:level level
               :method (:request-method request)
               :uri (:uri request)
               :status (:status response)
               :elapsed-ms elapsed})
      response)))

;; =============================================================================
;; Origin Validation Middleware (DNS Rebinding Protection)
;; =============================================================================

(defn wrap-origin-validation
  "Validate Origin/Host headers to prevent DNS rebinding attacks.

   Options:
     :allowed-hosts - Set of allowed Host header values

   Example:
     (wrap-origin-validation handler {:allowed-hosts #{\"localhost:3000\"}})"
  [handler {:keys [allowed-hosts]}]
  (fn [request]
    (let [host (get-in request [:headers \"host\"])]
      (if (or (empty? allowed-hosts) (contains? allowed-hosts host))
        (handler request)
        {:status 403
         :body \"{\\\"error\\\":\\\"Invalid host\\\"}\"}))))
```

### Using External Ring Middleware

Any Ring-compatible middleware works with the `:middleware` config:

```clojure
;; Example with hypothetical external middleware
(require '[ring.middleware.json :refer [wrap-json-body]]
         '[some-lib.auth :refer [wrap-jwt-auth]])

(start-server! handler
  {:port 8080
   :middleware [wrap-json-body
                (fn [h] (wrap-jwt-auth h {:secret "..."}))
                wrap-request-logging]})
```

### Middleware Stack Assembly

The server assembles the middleware stack at startup:

```clojure
(defn- build-handler
  "Build final handler with middleware stack."
  [base-handler config]
  (let [{:keys [middleware cors rate-limit basic-auth]} config]
    (cond-> base-handler
      ;; Apply user middleware (in reverse for correct order)
      (seq middleware) (apply-middleware (reverse middleware))
      ;; Apply built-in middleware based on config
      basic-auth      (wrap-basic-auth basic-auth)
      rate-limit      (wrap-rate-limit rate-limit)
      (:enabled cors) (wrap-cors cors))))
```

---

## Implementation Phases

### Phase 1: Foundation (Core Infrastructure)

**Goal:** Establish module structure and core utilities

**Tasks:**
- [x] Create module directory structure
- [x] Create `module.edn` manifest
- [x] Implement `util.clj` (JSON helpers, logging)
- [x] Implement `sse.clj` (SSE event formatting, channel helpers)
- [x] Implement `session.clj` (session CRUD, cleanup task)
- [x] Unit tests for session and SSE (26 tests, 54 assertions)

**Files:**
```
modules/streamable-http/
├── module.edn
├── src/streamable_http/
│   ├── util.clj
│   ├── sse.clj
│   └── session.clj
└── test/
    ├── run_tests.clj
    └── streamable_http/
        ├── session_test.clj
        └── sse_test.clj
```

**Deliverable:** Session management and SSE utilities working in isolation ✅

---

### Phase 2: HTTP Handlers

**Goal:** Implement the three HTTP handlers

**Tasks:**
- [x] Implement `handlers/post.clj` (JSON-RPC via POST)
- [x] Implement `handlers/get.clj` (SSE stream opening)
- [x] Implement `handlers/delete.clj` (session termination)
- [x] Implement `router.clj` (dispatch to handlers)
- [x] Unit tests for each handler (21 new tests, 37 assertions)

**Files:**
```
src/streamable_http/
├── router.clj
└── handlers/
    ├── post.clj
    ├── get.clj
    └── delete.clj

test/streamable_http/
├── handlers_test.clj
└── router_test.clj
```

**Deliverable:** All handlers working with mock JSON-RPC processor ✅

---

### Phase 2.5: Ring Middleware Layer

**Goal:** Implement built-in middleware with Ring-compatible pattern

**Tasks:**
- [x] Implement `middleware.clj` with Ring-compatible wrappers
- [x] Implement `wrap-cors` with configurable origins
- [x] Implement `wrap-rate-limit` with token bucket algorithm
- [x] Implement `wrap-basic-auth` for simple auth
- [x] Implement `wrap-request-logging` for debugging
- [x] Implement `wrap-origin-validation` for DNS rebinding protection
- [x] Implement `wrap-api-key` for API key auth (Anthropic + OpenAI styles)
- [x] Implement `apply-middleware` helper for composition
- [x] Unit tests for each middleware (33 new tests, 58 assertions)

**Files:**
```
src/streamable_http/
└── middleware.clj

test/streamable_http/
└── middleware_test.clj
```

**Middleware API:**
```clojure
;; All middleware follows Ring pattern: (fn [handler & [opts]] (fn [req] ...))

;; Built-in middleware
(wrap-cors handler {:allowed-origins #{"https://example.com"}})
(wrap-rate-limit handler {:requests-per-minute 60})
(wrap-basic-auth handler {:credentials {"user" "pass"}})
(wrap-api-key handler {:validate-fn my-key-validator})  ; Anthropic x-api-key + OpenAI Bearer
(wrap-request-logging handler {:log-fn my-logger})
(wrap-origin-validation handler {:allowed-hosts #{"localhost:3000"}})

;; User can provide any Ring middleware via :middleware config
```

**Deliverable:** Pluggable middleware system compatible with Ring ecosystem ✅

---

### Phase 3: Server Lifecycle

**Goal:** Complete server with lifecycle management

**Tasks:**
- [x] Implement `server.clj` (http-kit server wrapper)
- [x] Implement `core.clj` (public API)
- [x] Wire everything together
- [x] Integration tests with real HTTP requests (10 tests, 26 assertions)

**Files:**
```
src/streamable_http/
├── core.clj
└── server.clj
test/streamable_http/
└── integration_test.clj
```

**Deliverable:** Fully functional standalone module ✅

---

### Phase 4: bb-mcp-server Integration

**Goal:** Integrate with bb-mcp-server module system

**Tasks:**
- [x] Add module.edn with proper manifest
- [x] Create adapter using router/route-request (no adapter needed - maps work directly!)
- [x] Update bb.edn paths for streamable-http module
- [x] Add bb tasks: `server:streamable`, `test:streamable`
- [x] Test with existing tools (hello, add, calculate) - all 16 tools work
- [x] Test DELETE endpoint (session termination)
- [x] Test SSE stream (GET with proper headers)
- [x] Test error handling (invalid session, method not allowed)

**Files:**
```
bb.edn                         # Added paths and tasks
scripts/
└── streamable_http_server.clj # Startup script (NEW)
```

**Deliverable:** Working as bb-mcp-server module ✅

---

### Phase 5: Production Hardening

**Goal:** Security and reliability for production use

**Tasks:**
- [x] Origin validation (DNS rebinding protection) - `wrap-origin-validation`
- [x] Rate limiting middleware - `wrap-rate-limit` (token bucket)
- [x] Request logging/telemetry - `wrap-request-logging`
- [x] Error handling improvements - `safe-call-handler` with JSON-RPC errors
- [x] Session timeout enforcement - cleanup task verified working
- [x] Graceful shutdown (drain connections) - `notify-sse-clients-shutdown!`
- [x] API key authentication - `wrap-api-key` (Anthropic + OpenAI styles)

**Files Updated:**
```
src/streamable_http/
├── middleware.clj  - All security middleware
├── server.clj      - Graceful shutdown with SSE notification
└── handlers/post.clj - Exception handling with JSON-RPC errors
```

**Deliverable:** Production-ready module ✅

---

### Phase 5.5: Server Operations & MCP Notifications

**Goal:** Graceful server management and MCP-compliant notifications

**Tasks:**
- [x] Implement PID file management (`scripts/pid_util.clj`)
- [x] Add `bb server:stop <port>` task for graceful shutdown
- [x] Implement `listChanged` capability in initialize handler
- [x] Add callback mechanism in registry for tool list changes
- [x] Wire `broadcast-notification!` to registry changes
- [x] Server broadcasts `notifications/tools/list_changed` when tools added/removed

**Files:**
```
scripts/
└── pid_util.clj                    # PID file utilities

src/bb_mcp_server/
├── handlers/initialize.clj         # Added {:tools {:listChanged true}}
└── registry.clj                    # Added list-changed-callback mechanism

scripts/
└── streamable_http_server.clj      # Wires callback to broadcast
```

**Key Implementation Details:**

1. **PID File Management:**
   - `pid-util/write-pid-file!` creates `.pid/<port>.pid` on startup
   - `pid-util/delete-pid-file!` removes on shutdown
   - `bb server:stop <port>` reads PID and sends SIGTERM

2. **listChanged Capability:**
   - Initialize response declares `{:tools {:listChanged true}}`
   - `registry/set-list-changed-callback!` sets notification callback
   - `register!` and `unregister!` call callback when tool list changes
   - Callback triggers `broadcast-notification!` to all SSE clients

**Usage:**
```bash
# Start server (writes PID file)
bb server:streamable 19878

# Stop server gracefully (reads PID, sends SIGTERM)
bb server:stop 19878
```

**Hot-reload workflow:**
```clojure
;; Dynamic tool registration triggers notification
(registry/register! {:name "new-tool" ...})
;; → All SSE clients receive notifications/tools/list_changed
;; → Clients can call tools/list to get updated tool list
```

**Deliverable:** Graceful server management + MCP-compliant list change notifications ✅

---

### Phase 6: Documentation (Deferred)

**Goal:** Document the module for users

**Note:** Extraction to standalone repo is **deferred** - keeping all code together during active development.

**Tasks:**
- [ ] Comprehensive README.md for `modules/streamable-http/`
- [ ] API documentation (public functions in `core.clj`)
- [ ] Usage examples
- [ ] Changelog

**Deferred (extraction prep):**
- Standalone bb.edn (no bb-mcp-server deps)
- GitHub Actions CI
- `git subtree split`

**Deliverable:** Well-documented module within bb-mcp-server

---

### Phase 7: REST API Transport ✅

**Goal:** RESTful API alongside MCP JSON-RPC, with transport-aware tool routing

**Motivation:**
- Dashboards and web UIs prefer REST over JSON-RPC
- OpenAPI/Swagger tooling for API documentation
- Easier integration for non-MCP clients
- Security: some tools should only be exposed on certain transports

**Transport Routing Table:**

Each tool declares which transports it supports:

```clojure
;; In tool registration
{:name "calculate"
 :description "Math calculations"
 :inputSchema {...}
 :handler calculate-fn
 :transports #{:rest :mcp-http :mcp-stdio}}  ; NEW: transport whitelist

;; Or in module.edn
{:tools [{:name "nrepl-eval"
          :transports #{:mcp-stdio}}  ; Dangerous - local only!
         {:name "echo"
          :transports #{:rest :mcp-http :mcp-stdio}}]}  ; Safe everywhere
```

**Default behavior:**
- If `:transports` not specified → all transports (backward compatible)
- Tools filter by transport at request time

**Transport types:**
| Transport | Description | Use Case |
|-----------|-------------|----------|
| `:mcp-stdio` | Claude Code subprocess | Local dev, full trust |
| `:mcp-http` | MCP JSON-RPC over HTTP | Cloud MCP clients |
| `:rest` | RESTful API | Dashboards, web UIs |

**Tasks:**
- [x] Extend registry schema to include `:transports` field
- [x] Add `list-tools-for-transport` function to registry
- [x] Design REST API routes with module-based routing
  - `GET /api/server` → server info (name, version, moduleToolSeparator)
  - `GET /api/modules` → list all modules
  - `GET /api/modules/:module/tools` → list tools in module
  - `GET /api/modules/:module/tools/:name` → tool metadata
  - `POST /api/modules/:module/tools/:name` → invoke tool
- [x] Implement REST router in `streamable-http/handlers/rest.clj`
- [x] Implement REST handlers (direct tool invocation, no MCP adapter needed)
- [x] Generate OpenAPI 3.0 spec (`/api/openapi.json`)
- [x] Generate HTML documentation (`/api/docs`)
- [x] Integration tests for REST endpoints (99 tests, 231 assertions)
- [ ] Document REST API in module README (deferred to Phase 6)

**Architecture:**
```
┌─────────────────────────────────────────────────────┐
│              streamable-http (generic)              │
│     Sessions │ SSE │ Middleware │ Lifecycle         │
├─────────────────┬───────────────────────────────────┤
│   /mcp          │          /api                     │
│   JSON-RPC 2.0  │          REST                     │
│   MCP Protocol  │          REST conventions         │
├─────────────────┴───────────────────────────────────┤
│                 Tool Registry                       │
│           (same tools, two interfaces)              │
└─────────────────────────────────────────────────────┘
```

**Files:**
```
src/streamable_http/
├── handlers/
│   └── rest.clj          # REST endpoint handlers (module-based routes)
├── openapi.clj           # OpenAPI 3.0 spec generation
└── docs.clj              # HTML documentation generation

test/streamable_http/
└── rest_test.clj         # REST integration tests

src/bb_mcp_server/
└── registry.clj          # Added module-tool-separator, module listing fns
```

**Deliverable:** RESTful API works alongside MCP JSON-RPC ✅

---

## Dependency Isolation

### Module Dependencies

```clojure
;; modules/streamable-http/module.edn
{:name "streamable-http"
 :version "0.1.0"
 :description "MCP Streamable HTTP Transport (spec 2025-03-26)"
 :requires []  ; NO dependencies on other modules!
 :entry "streamable-http.core/module"

 ;; External deps (must be in bb.edn)
 :bb-deps {http-kit/http-kit {:mvn/version "2.8.1"}
           cheshire/cheshire {:mvn/version "6.1.0"}}

 :defaults {:port 3000
            :host "0.0.0.0"}}
```

### Handler Injection Pattern

The module does NOT depend on bb-mcp-server's test-harness. Instead, it receives a handler function:

```clojure
;; In bb-mcp-server integration code (NOT in the module)
(ns bb-mcp-server.adapters.streamable-http
  (:require [streamable-http.core :as shttp]
            [bb-mcp-server.test-harness :as harness]))

(defn create-handler
  "Adapter: wraps test-harness for streamable-http"
  []
  (fn [json-rpc-request]
    ;; Convert map -> JSON string -> process -> parse result
    (let [request-str (json/generate-string json-rpc-request)
          response-str (harness/process-json-rpc request-str)]
      (json/parse-string response-str true))))

(defn start-streamable-server! [config]
  (shttp/start-server! (create-handler) config))
```

This keeps the module completely independent.

---

## Testing Strategy

### Unit Tests (Per Component)

| Component | Test File | Coverage |
|-----------|-----------|----------|
| `session.clj` | `session_test.clj` | CRUD, timeout, cleanup |
| `sse.clj` | `sse_test.clj` | Event formatting, channel ops |
| `handlers/*.clj` | `handlers_test.clj` | Request/response per method |
| `router.clj` | `router_test.clj` | Routing logic |

### Integration Tests

```clojure
;; test/integration_test.clj
(ns streamable-http.integration-test
  (:require [clojure.test :refer :all]
            [streamable-http.core :as shttp]
            [babashka.http-client :as http]))

(def test-handler
  "Echo handler for testing"
  (fn [{:keys [method params id]}]
    {:jsonrpc "2.0"
     :result {:echo {:method method :params params}}
     :id id}))

(deftest test-full-lifecycle
  (let [server (shttp/start-server! test-handler {:port 9999})]
    (try
      ;; Test initialize
      (let [resp (http/post "http://localhost:9999/mcp"
                   {:body (json/generate-string
                            {:jsonrpc "2.0"
                             :method "initialize"
                             :params {}
                             :id 1})
                    :headers {"Content-Type" "application/json"
                              "Accept" "application/json"}})]
        (is (= 200 (:status resp)))
        (is (contains? (:headers resp) "mcp-session-id")))
      (finally
        ((:stop! server))))))
```

### Compliance Tests

Test against official MCP SDK clients:
- Python SDK with `transport="streamable-http"`
- TypeScript SDK with StreamableHTTP transport

---

## File-by-File Implementation Order

```
Day 1: Foundation
  1. module.edn
  2. util.clj
  3. sse.clj + sse_test.clj
  4. session.clj + session_test.clj

Day 2: Handlers
  5. handlers/post.clj
  6. handlers/get.clj
  7. handlers/delete.clj
  8. router.clj

Day 3: Middleware ✅
  9. middleware.clj (wrap-cors, wrap-rate-limit, wrap-basic-auth, wrap-request-logging, wrap-origin-validation)
  10. middleware_test.clj (23 tests)
  11. apply-middleware composition helper

Day 4: Server & API
  12. server.clj
  13. core.clj + core_test.clj
  14. integration_test.clj

Day 5: Integration
  15. bb-mcp-server adapter
  16. Update bb.edn paths
  17. End-to-end testing

Day 6: Hardening & Docs
  18. wrap-origin-validation, wrap-request-logging
  19. External middleware compatibility testing
  20. Documentation
```

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| http-kit SSE limitations in bb | Verified: all primitives work (see test-sse.clj) |
| Session memory leaks | Periodic cleanup task with configurable interval |
| Race conditions on reconnect | Atomic operations via `swap!` with proper locking |
| Breaking existing HTTP transport | New module, existing `transport/http.clj` unchanged |
| Scope creep | Strict phase boundaries, no features outside plan |
| External middleware compatibility | Ring pattern is well-defined; test with ring-cors, buddy-auth |
| Rate limit state in multi-instance | Document limitation; recommend Redis-backed limiter for prod |

---

## Success Criteria

### Phase 1 Complete When:
- [x] `bb modules/streamable-http/test/run_tests.clj` passes
- [x] Session create/get/destroy works
- [x] SSE events format correctly

### Phase 2 Complete When:
- [x] POST handler processes JSON-RPC (initialize, requests, batches)
- [x] GET handler opens SSE streams with session validation
- [x] DELETE handler terminates sessions
- [x] Router dispatches to correct handlers
- [x] 47 tests, 91 assertions passing

### Phase 3 Complete When:
- [x] curl can complete initialize handshake
- [x] Session ID returned in header
- [x] Full session lifecycle works (init → request → delete)
- [x] 57 tests, 117 assertions passing

### Phase 2.5 Complete When:
- [x] All built-in middleware (CORS, rate-limit, basic-auth, api-key) working
- [x] API key middleware supports Anthropic (x-api-key) and OpenAI (Bearer) styles
- [x] Middleware stack assembly correctly orders wrappers (apply-middleware)
- [x] External Ring middleware can be injected via `:middleware` config
- [x] Tests pass for each middleware in isolation
- [x] 90 tests, 175 assertions passing

### Phase 4 Complete When:
- [x] bb-mcp-server tools work over Streamable HTTP (all 16 tools verified)
- [x] Equivalent functionality to current HTTP transport
- [x] Session lifecycle works (initialize → request → delete)
- [x] SSE stream endpoint returns proper headers (text/event-stream, keep-alive)

### Phase 5 Complete When:
- [x] Security middleware in place (CORS, rate-limit, auth, origin validation)
- [x] Error handling returns proper JSON-RPC errors for exceptions
- [x] Session timeout cleanup verified working
- [x] Graceful shutdown notifies SSE clients before closing
- [x] 90 tests, 175 assertions passing

### Phase 5.5 Complete When:
- [x] `bb server:stop <port>` gracefully stops server via PID file
- [x] PID file created on startup, deleted on shutdown
- [x] Initialize response includes `{:tools {:listChanged true}}`
- [x] `registry/register!` triggers `notifications/tools/list_changed` broadcast
- [x] `registry/unregister!` triggers `notifications/tools/list_changed` broadcast
- [x] All 133 tests, 345 assertions passing (including module tests)

### Phase 6 Complete When:
- [ ] README.md documents public API
- [ ] Usage examples included
- [ ] (Deferred: standalone bb.edn, extraction)

### Phase 7 Complete When:
- [x] Registry supports `:transports` field on tools
- [x] `list-tools-for-transport` filters tools by transport
- [x] `GET /api/modules` returns list of modules
- [x] `GET /api/modules/:module/tools` returns tools in module
- [x] `GET /api/modules/:module/tools/:name` returns tool metadata
- [x] `POST /api/modules/:module/tools/:name` invokes tool
- [x] `GET /api/server` returns server info with `moduleToolSeparator`
- [x] `GET /api/openapi.json` returns OpenAPI 3.0 spec
- [x] `GET /api/docs` returns HTML documentation
- [x] Same tools accessible via MCP and REST (when whitelisted)
- [x] REST integration tests passing (99 tests, 231 assertions)

---

## Open Decisions

1. **Logging**: Use `taoensso.timbre` (current bb-mcp-server) or keep module dependency-free with `println`?
   - **Recommendation:** Accept logger as optional config parameter

2. **Metrics**: Include metrics collection or defer?
   - **Recommendation:** Defer to Phase 5, make it pluggable

3. **Protocol version**: Support only 2025-03-26 or also 2024-11-05?
   - **Recommendation:** Start with 2025-03-26 only, add compat later if needed

4. **JWT/OAuth**: Include JWT validation in built-in middleware or require external?
   - **Recommendation:** External only - JWT crypto may need pods/JVM; provide plugin slot
   - **Alternative:** If demand exists, create separate `streamable-http-auth` module

5. **Middleware order**: User middleware before or after built-in?
   - **Decision:** User middleware runs BEFORE built-in (outermost layer)
   - **Rationale:** Allows user to intercept/transform before any processing

6. **Rate limit algorithm**: Token bucket vs sliding window?
   - **Decision:** Token bucket - simpler, works for single-instance
   - **Note:** Document that distributed rate limiting needs external solution

---

## Operational Notes

### Hot-Reloading Tools

There are two approaches to add/remove tools:

#### Approach 1: Dynamic Registration (No Server Restart)

Add tools at runtime without stopping the server:

1. **Load the module** using `local-load-file` tool:
   ```
   local-load-file with path: "modules/hello/src/hello/core.clj"
   ```

2. **Start the module** using `mcp-local-eval` tool:
   ```clojure
   (hello.core/start {} {:greeting "Hi"})
   ```
   This registers the tool with the server's registry.

3. **Reconnect MCP client** - In Claude Code, run `/mcp` to refresh tool list

**Why this works:**
- `local-load-file` loads the module namespace into the running server
- The module's `start` function calls `registry/register!`
- Server immediately has the new tool available
- Client reconnect refreshes the cached tool list (no new session needed if same server)

**Example workflow:**
```
# In Claude Code, use the MCP tools:

1. local-load-file: modules/hello/src/hello/core.clj
   → Loads hello.core namespace

2. local-eval: (hello.core/start {} {:greeting "Hi"})
   → Registers hello tool, returns {:registered-tools ["hello"]}

3. /mcp (Claude Code command)
   → Reconnects, now sees hello tool

4. hello: {name: "Frank"}
   → "Hi, Frank!"
```

#### Approach 2: Server Restart (Config-Based)

When modifying `system.edn` for permanent changes:

1. **Stop the server** using `bb server:stop <port>`
2. **Edit `system.edn`** to add/remove modules
3. **Restart the server** on the **same port** using `bb server:streamable <port>`
4. **Force MCP client reconnect** - In Claude Code, run `/mcp` to reconnect

**Example workflow:**
```bash
# Stop server
bb server:stop 19878

# Edit system.edn to add "math" module
# :modules ["hello" "echo" "strings" "calculate" "mcp-local-eval" "nrepl" "math"]

# Restart on same port
bb server:streamable 19878

# In Claude Code: /mcp (to reconnect)
# Now both old and new tools are available
```

#### When to Use Each Approach

| Scenario | Approach |
|----------|----------|
| Testing new tool during development | Dynamic (no restart) |
| Permanent addition to server config | Server restart |
| Quick iteration on tool handler | Dynamic (no restart) |
| Production deployment | Server restart |

Both approaches require a client `/mcp` reconnect to see new tools, because MCP clients cache the tool list from `tools/list` at initialization.

---

## References

- [Design Doc](streamable-http-transport-design.md)
- [Gemini Review](streamable-http-transport-review.md)
- [MCP Spec - Transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [bb-mcp-server Module System](../dynamic-module-loading.md)

---

*Status: Planning complete, ready for Phase 1 implementation*
