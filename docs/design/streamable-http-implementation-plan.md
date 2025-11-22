# Streamable HTTP Transport - Implementation Plan

## Overview

This document outlines the implementation plan for the MCP Streamable HTTP transport as a **modular, self-contained component** that can be:

1. Used within bb-mcp-server
2. Extracted to its own repository later
3. Reused in other Babashka MCP projects

**Status:** Planning
**Date:** 2025-11-22
**Design Doc:** `streamable-http-transport-design.md`
**Review:** `streamable-http-transport-review.md` (Approved)

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
  bb-mcp-streamable-http/
  ├── src/
  ├── test/
  ├── bb.edn
  ├── README.md
  └── module.edn  (for bb-mcp-server integration)
```

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
- [ ] Create module directory structure
- [ ] Create `module.edn` manifest
- [ ] Implement `util.clj` (JSON helpers, logging)
- [ ] Implement `sse.clj` (SSE event formatting, channel helpers)
- [ ] Implement `session.clj` (session CRUD, cleanup task)
- [ ] Unit tests for session and SSE

**Files:**
```
modules/streamable-http/
├── module.edn
├── src/streamable_http/
│   ├── util.clj
│   ├── sse.clj
│   └── session.clj
└── test/
    ├── session_test.clj
    └── sse_test.clj
```

**Deliverable:** Session management and SSE utilities working in isolation

---

### Phase 2: HTTP Handlers

**Goal:** Implement the three HTTP handlers

**Tasks:**
- [ ] Implement `handlers/post.clj` (JSON-RPC via POST)
- [ ] Implement `handlers/get.clj` (SSE stream opening)
- [ ] Implement `handlers/delete.clj` (session termination)
- [ ] Implement `router.clj` (dispatch to handlers)
- [ ] Unit tests for each handler

**Files:**
```
src/streamable_http/
├── router.clj
└── handlers/
    ├── post.clj
    ├── get.clj
    └── delete.clj
```

**Deliverable:** All handlers working with mock JSON-RPC processor

---

### Phase 2.5: Ring Middleware Layer

**Goal:** Implement built-in middleware with Ring-compatible pattern

**Tasks:**
- [ ] Implement `middleware.clj` with Ring-compatible wrappers
- [ ] Implement `wrap-cors` with configurable origins
- [ ] Implement `wrap-rate-limit` with token bucket algorithm
- [ ] Implement `wrap-basic-auth` for simple auth
- [ ] Implement `wrap-request-logging` for debugging
- [ ] Implement `wrap-origin-validation` for DNS rebinding protection
- [ ] Implement middleware stack assembly in server
- [ ] Unit tests for each middleware

**Files:**
```
src/streamable_http/
└── middleware.clj

test/
└── middleware_test.clj
```

**Middleware API:**
```clojure
;; All middleware follows Ring pattern: (fn [handler & [opts]] (fn [req] ...))

;; Built-in middleware
(wrap-cors handler {:allowed-origins #{"https://example.com"}})
(wrap-rate-limit handler {:requests-per-minute 60})
(wrap-basic-auth handler {:credentials {"user" "pass"}})
(wrap-request-logging handler {:log-fn my-logger})
(wrap-origin-validation handler {:allowed-hosts #{"localhost:3000"}})

;; User can provide any Ring middleware via :middleware config
```

**Deliverable:** Pluggable middleware system compatible with Ring ecosystem

---

### Phase 3: Server Lifecycle

**Goal:** Complete server with lifecycle management

**Tasks:**
- [ ] Implement `server.clj` (http-kit server wrapper)
- [ ] Implement `core.clj` (public API)
- [ ] Wire everything together
- [ ] Integration tests with real HTTP requests

**Files:**
```
src/streamable_http/
├── core.clj
└── server.clj
test/
├── core_test.clj
└── integration_test.clj
```

**Deliverable:** Fully functional standalone module

---

### Phase 4: bb-mcp-server Integration

**Goal:** Integrate with bb-mcp-server module system

**Tasks:**
- [ ] Add module.edn with proper manifest
- [ ] Create adapter to wrap `test-harness/process-json-rpc`
- [ ] Add to system.edn as loadable module
- [ ] Test with existing tools (hello, math, etc.)
- [ ] Update bb.edn paths if needed

**Files:**
```
modules/streamable-http/
└── module.edn

scripts/
└── streamable_http_server.clj  (startup script)
```

**Deliverable:** Working as bb-mcp-server module

---

### Phase 5: Production Hardening

**Goal:** Security and reliability for production use

**Tasks:**
- [ ] Origin validation (DNS rebinding protection)
- [ ] Rate limiting middleware
- [ ] Request logging/telemetry
- [ ] Error handling improvements
- [ ] Session timeout enforcement
- [ ] Graceful shutdown (drain connections)

**Deliverable:** Production-ready module

---

### Phase 6: Documentation & Extraction Prep

**Goal:** Prepare for standalone repository

**Tasks:**
- [ ] Comprehensive README.md
- [ ] API documentation
- [ ] Usage examples
- [ ] Standalone bb.edn (no bb-mcp-server deps)
- [ ] GitHub Actions CI (if extracted)
- [ ] Changelog

**Deliverable:** Ready for extraction to own repo

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

Day 3: Middleware
  9. middleware.clj (wrap-cors, wrap-rate-limit, wrap-basic-auth)
  10. middleware_test.clj
  11. Middleware stack assembly

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
- [ ] `bb modules/streamable-http/test/run_tests.clj` passes
- [ ] Session create/get/destroy works
- [ ] SSE events format correctly

### Phase 2.5 Complete When:
- [ ] All built-in middleware (CORS, rate-limit, basic-auth) working
- [ ] Middleware stack assembly correctly orders wrappers
- [ ] External Ring middleware can be injected via `:middleware` config
- [ ] Tests pass for each middleware in isolation

### Phase 3 Complete When:
- [ ] curl can complete initialize handshake
- [ ] Session ID returned in header
- [ ] SSE stream opens on GET

### Phase 4 Complete When:
- [ ] bb-mcp-server tools work over Streamable HTTP
- [ ] Equivalent functionality to current HTTP transport

### Phase 5 Complete When:
- [ ] Security review passes
- [ ] No memory leaks under load test
- [ ] Graceful shutdown works

### Phase 6 Complete When:
- [ ] Module runs standalone (own bb.edn)
- [ ] README sufficient for external use
- [ ] Ready to `git subtree split`

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

## References

- [Design Doc](streamable-http-transport-design.md)
- [Gemini Review](streamable-http-transport-review.md)
- [MCP Spec - Transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [bb-mcp-server Module System](../dynamic-module-loading.md)

---

*Status: Planning complete, ready for Phase 1 implementation*
