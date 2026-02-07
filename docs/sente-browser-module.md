# Sente-Browser Module: nREPL for Browser Scittle Runtimes

**Status**: Proposal / Design Document
**Created**: 2025-12-22
**Updated**: 2025-12-23
**Author**: Claude Code session

## Executive Summary

This document proposes creating a new `sente-browser` module for bb-mcp-server that embeds a sente-lite WebSocket server, enabling Claude to directly interact with browser-based Scittle runtimes. The integration eliminates the need for a separate bencode proxy, providing a cleaner architecture.

**Goal:** Claude can eval code in browsers using the same `nrepl-eval` tool it uses for JVM nREPL servers.

## Why bb-mcp-server?

bb-mcp-server was chosen over mcp-nrepl-joyride because:

| Factor | bb-mcp-server | mcp-nrepl-joyride |
|--------|---------------|-------------------|
| Module isolation | Clean new module | Inline modification |
| nrepl tools | Already present | Already present |
| Lifecycle management | start/stop/status | Must add |
| Test structure | Per-module tests | Mixed |
| External loading | env var support | None |

The modular architecture allows clean separation without polluting existing nrepl code.

## Current Architecture (What We Have)

```
                                   ┌─────────────────────────────────────┐
                                   │  bb-mcp-server                       │
┌─────────┐     MCP/JSON-RPC      │  ┌─────────────────────────────────┐ │
│ Claude  │ ──────────────────>    │  │ nrepl module                    │ │
│         │ <──────────────────    │  │ - nrepl-connection              │ │
└─────────┘                        │  │ - nrepl-eval                    │ │
                                   │  │ - nrepl-load-file               │ │
                                   │  └───────────────┬─────────────────┘ │
                                   └──────────────────┼───────────────────┘
                                                      │ bencode/socket
                                                      ▼
                                               ┌─────────────┐
                                               │ nREPL Server│
                                               │ (JVM/BB)    │
                                               └─────────────┘
```

**Current limitation:** Can only connect to socket-based nREPL servers.

## Proposed Architecture (With sente-browser Module)

```
                                   ┌─────────────────────────────────────────────┐
                                   │  bb-mcp-server                               │
┌─────────┐     MCP/JSON-RPC      │  ┌─────────────────────────────────────────┐ │
│ Claude  │ ──────────────────>    │  │ nrepl module (existing)                 │ │
│         │ <──────────────────    │  │ - connection.clj (extended w/ :type)    │ │
└─────────┘                        │  │ - messages.clj (extended w/ adapter)    │ │
                                   │  └───────────────┬─────────────────────────┘ │
                                   │                  │                           │
                                   │  ┌───────────────┴─────────────────────────┐ │
                                   │  │ sente-browser module (NEW)              │ │
                                   │  │ - Embeds sente-lite WebSocket server    │ │
                                   │  │ - Serves bootstrap HTML                 │ │
                                   │  │ - Registers browser connections         │ │
                                   │  └───────────────┬─────────────────────────┘ │
                                   └──────────────────┼───────────────────────────┘
                                                      │
                            ┌─────────────────────────┼─────────────────────────┐
                            │                         │                         │
                   bencode/socket              EDN/WebSocket              HTTP/HTML
                            │                         │                         │
                            ▼                         ▼                         ▼
                     ┌─────────────┐           ┌─────────────┐           ┌─────────────┐
                     │ nREPL Server│           │ Browser     │           │ Browser     │
                     │ (JVM/BB)    │           │ (Scittle)   │ <─────────│ Bootstrap   │
                     └─────────────┘           └─────────────┘           └─────────────┘
```

**Benefits:**
1. Single process - no proxy
2. Direct EDN messaging (no bencode translation)
3. Same MCP tools work for both targets
4. Simpler deployment
5. Registry-based discovery (already in sente-lite)

## Module Structure

```
modules/sente-browser/
├── module.edn
├── README.md
├── src/
│   └── sente_browser/
│       ├── core.clj            # Module lifecycle (start/stop/status)
│       ├── server.clj          # sente-lite WebSocket server integration
│       ├── bootstrap.clj       # HTTP server for bootstrap HTML
│       └── adapter.clj         # Adapter for nrepl connection/messages
└── test/
    ├── run_tests.clj
    └── sente_browser/
        └── integration_test.clj
```

### module.edn

```clojure
{:name "sente-browser"
 :version "0.1.0"
 :description "Browser nREPL via sente-lite WebSocket - enables Claude to eval in browser Scittle"

 ;; Depends on nrepl module for connection/message state
 :requires ["nrepl"]

 ;; Entry point
 :entry "sente-browser.core/module"

 ;; Default configuration
 :defaults {:enabled false
            :ws-port 8090
            :bootstrap-port 8091
            :host "127.0.0.1"}  ; Security: localhost only by default

 ;; Test runner
 :test-runner "test/run_tests.clj"}
```

## Component Analysis

### Existing nrepl Module Components (to extend)

| Component | File | Purpose |
|-----------|------|---------|
| Connection State | `modules/nrepl/src/nrepl/state/connection.clj` | Multi-connection registry with nicknames |
| Message Queue | `modules/nrepl/src/nrepl/state/messages.clj` | Async message queues per connection |
| Socket Adapter | `adapt-connection-for-messaging` | Wraps socket for bencode I/O |
| Connection Tool | `modules/nrepl/src/nrepl/tools/nrepl_connection.clj` | connect/disconnect/status ops |
| Eval Tool | `modules/nrepl/src/nrepl/tools/nrepl_eval.clj` | Delegates to nrepl-send-message |

### sente-lite Components (to integrate)

| Component | File | Purpose |
|-----------|------|---------|
| Protocol | `sente_lite/modules/nrepl/src/nrepl_sente/protocol.cljc` | EDN message format definitions |
| Server | `sente_lite/modules/nrepl/src/nrepl_sente/server.cljc` | Evaluates code in BB/Scittle |
| sente Server | `sente_lite/src/sente_lite/server.cljc` | WebSocket server with HTTP handler |

## Implementation Details

### 1. Module Core (core.clj)

```clojure
(ns sente-browser.core
  "Sente-browser module - browser nREPL via WebSocket"
  (:require [sente-browser.server :as server]
            [sente-browser.bootstrap :as bootstrap]
            [sente-browser.adapter :as adapter]
            [taoensso.trove :as log]))

(defn start
  "Start the sente-browser module"
  [deps config]
  (when (:enabled config true)
    (log/log! {:level :info
               :id ::starting
               :msg "Starting sente-browser module"
               :data {:config config}})

    ;; Start WebSocket server
    (let [ws-server (server/start! config)
          http-server (bootstrap/start! config)]

      ;; Install adapter hooks into nrepl module
      (adapter/install-hooks!)

      {:ws-server ws-server
       :http-server http-server
       :config config})))

(defn stop
  "Stop the sente-browser module"
  [instance]
  (when instance
    (log/log! {:level :info
               :id ::stopping
               :msg "Stopping sente-browser module"})

    ;; Remove adapter hooks
    (adapter/remove-hooks!)

    ;; Stop servers
    (server/stop! (:ws-server instance))
    (bootstrap/stop! (:http-server instance)))
  nil)

(defn status
  "Get module status"
  [instance]
  (if instance
    {:status :ok
     :ws-port (get-in instance [:config :ws-port])
     :bootstrap-port (get-in instance [:config :bootstrap-port])
     :browser-count (server/browser-count)}
    {:status :stopped}))

(def module
  {:start start
   :stop stop
   :status status})
```

### 2. WebSocket Server Integration (server.clj)

```clojure
(ns sente-browser.server
  "Embed sente-lite WebSocket server for browser connections"
  (:require [sente-lite.server :as sente-server]
            [nrepl-sente.protocol :as protocol]
            [nrepl.state.connection :as conn-state]
            [nrepl.state.results :as results]
            [taoensso.trove :as log]))

(defonce !server (atom nil))
(defonce !browser-connections (atom {}))  ; sente-conn-id -> mcp-conn-id

(defn handle-browser-connect
  "Called when browser connects via WebSocket"
  [sente-conn-id user-agent]
  (let [mcp-conn-id (conn-state/register-browser-connection! sente-conn-id user-agent)]
    (swap! !browser-connections assoc sente-conn-id mcp-conn-id)
    (log/log! {:level :info
               :id ::browser-connected
               :msg "Browser connected"
               :data {:sente-conn-id sente-conn-id
                      :mcp-conn-id mcp-conn-id
                      :user-agent user-agent}})))

(defn handle-browser-disconnect
  "Called when browser disconnects"
  [sente-conn-id]
  (when-let [mcp-conn-id (get @!browser-connections sente-conn-id)]
    (conn-state/mark-connection-closed! mcp-conn-id :browser-disconnect "Browser closed")
    (swap! !browser-connections dissoc sente-conn-id)
    (log/log! {:level :info
               :id ::browser-disconnected
               :msg "Browser disconnected"
               :data {:sente-conn-id sente-conn-id :mcp-conn-id mcp-conn-id}})))

(defn handle-browser-message
  "Handle message from browser (nREPL response)"
  [sente-conn-id event-id data]
  (when (= event-id :nrepl/response)
    (when-let [mcp-conn-id (get @!browser-connections sente-conn-id)]
      (when-let [msg-id (:id data)]
        ;; Deliver result to waiting promise
        (results/deliver-result! mcp-conn-id msg-id data)))))

(defn send-to-browser!
  "Send message to browser via sente"
  [sente-conn-id message]
  (when-let [server @!server]
    (sente-server/send-event-to-connection!
      server
      sente-conn-id
      [:nrepl/request message])))

(defn start!
  "Start embedded sente-lite server"
  [config]
  (let [host (:host config "127.0.0.1")  ; Security: localhost by default
        port (:ws-port config 8090)
        server (sente-server/start-server!
                 {:host host
                  :port port
                  :on-connect handle-browser-connect
                  :on-disconnect handle-browser-disconnect
                  :on-message handle-browser-message})]
    (reset! !server server)
    (log/log! {:level :info
               :id ::server-started
               :msg "Sente WebSocket server started"
               :data {:host host :port port}})
    server))

(defn stop!
  "Stop embedded sente-lite server"
  [server]
  (when server
    (sente-server/stop-server! server)
    (reset! !server nil)
    (reset! !browser-connections {})))

(defn browser-count
  "Get count of connected browsers"
  []
  (count @!browser-connections))

(defn get-sente-conn-id
  "Get sente connection ID for an MCP connection ID"
  [mcp-conn-id]
  (->> @!browser-connections
       (filter (fn [[_ mcp]] (= mcp mcp-conn-id)))
       first
       first))
```

### 3. Connection State Extension

**Add to**: `modules/nrepl/src/nrepl/state/connection.clj`

```clojure
;; Add :type field to connection structure
;; Existing: :socket (default)
;; New: :browser

(defn register-browser-connection!
  "Register a browser connection from sente-lite.
   Returns the MCP connection ID."
  [sente-conn-id user-agent]
  (let [conn-id (str "browser-" (uuidv7/uuidv7))
        connection-data {:connection-id conn-id
                         :type :browser           ;; NEW field
                         :sente-conn-id sente-conn-id
                         :user-agent user-agent
                         :status :connected
                         :created-at (System/currentTimeMillis)
                         :closed-at nil
                         :error nil}]
    (swap! connection-state
           (fn [state]
             (-> state
                 (assoc-in [:connections conn-id] connection-data)
                 (update :connection-counter inc))))
    ;; Auto-generate nickname
    (let [nickname (str "browser-" (:connection-counter @connection-state))]
      (register-nickname! nickname conn-id))
    (log/log! {:level :info
               :id ::browser-connection-registered
               :msg "Registered browser connection"
               :data {:connection-id conn-id :sente-conn-id sente-conn-id}})
    conn-id))

(defn is-browser-connection?
  "Check if connection is a browser (sente) connection"
  [connection-id]
  (= :browser (get-in @connection-state [:connections connection-id :type])))

(defn get-browser-connections
  "Get all browser connections"
  []
  (->> (:connections @connection-state)
       (filter (fn [[_ conn]] (= :browser (:type conn))))
       (into {})))
```

### 4. Message Adapter Extension

**Add to**: `modules/nrepl/src/nrepl/state/messages.clj`

```clojure
(defn adapt-browser-connection-for-messaging
  "Convert browser connection to messaging format.
   Uses EDN over sente instead of bencode over socket."
  [connection]
  (when (and connection (= :browser (:type connection)))
    {:type :browser
     :sente-conn-id (:sente-conn-id connection)
     :id (:connection-id connection)}))

(defn adapt-connection-for-messaging
  "Convert connection to messaging format - handles both socket and browser."
  [connection]
  (case (:type connection :socket)  ; Default to :socket for backward compatibility
    :browser (adapt-browser-connection-for-messaging connection)
    :socket  (adapt-socket-connection-for-messaging connection)
    nil))
```

### 5. Message Watcher Extension

The watcher in `modules/nrepl/src/nrepl/state/watchers.clj` needs to route based on connection type:

```clojure
(defn send-message-to-connection!
  "Send message to connection - handles both socket and browser"
  [{:keys [type] :as formatted-conn} message]
  (case type
    :browser
    ;; Send via sente (requires sente-browser module)
    (when-let [send-fn (resolve 'sente-browser.server/send-to-browser!)]
      (send-fn (:sente-conn-id formatted-conn) message))

    :socket
    ;; Send via bencode (existing behavior)
    (bencode/write-bencode (:out formatted-conn) message)

    ;; Default: try socket
    (bencode/write-bencode (:out formatted-conn) message)))
```

### 6. Bootstrap HTTP Server (bootstrap.clj)

```clojure
(ns sente-browser.bootstrap
  "HTTP server to serve bootstrap HTML for browser nREPL"
  (:require [org.httpkit.server :as http]
            [taoensso.trove :as log]))

(defonce !server (atom nil))

(defn bootstrap-html
  "Generate bootstrap HTML that loads Scittle and connects to sente"
  [config]
  (str "<!DOCTYPE html>
<html>
<head>
  <title>nREPL Browser - bb-mcp-server</title>
  <script src='https://cdn.jsdelivr.net/npm/scittle@0.6.17/dist/scittle.js'></script>
  <script src='https://cdn.jsdelivr.net/gh/franks42/sente-lite@main/dist/sente-lite.js'></script>
</head>
<body>
  <h1>nREPL Browser Runtime</h1>
  <p>Connected to bb-mcp-server on port " (:ws-port config) "</p>
  <div id='status'>Connecting...</div>
  <script type='application/x-scittle'>
  (ns nrepl.browser
    (:require [sente-lite.client :as client]
              [nrepl-sente.server :as nrepl-server]))

  ;; Connect to embedded sente server
  (client/connect! {:port " (:ws-port config) "
                    :on-connect #(set! (.-textContent (js/document.getElementById \"status\"))
                                       \"Connected!\")
                    :on-message nrepl-server/handle-request})

  ;; Start nREPL server in browser
  (nrepl-server/start!)
  (js/console.log \"nREPL browser runtime ready\")
  </script>
</body>
</html>"))

(defn handler
  "HTTP request handler"
  [config request]
  (case (:uri request)
    "/nrepl" {:status 200
              :headers {"Content-Type" "text/html"}
              :body (bootstrap-html config)}
    "/health" {:status 200
               :headers {"Content-Type" "application/json"}
               :body "{\"status\":\"ok\"}"}
    {:status 404
     :body "Not found"}))

(defn start!
  "Start bootstrap HTTP server"
  [config]
  (let [host (:host config "127.0.0.1")  ; Security: localhost by default
        port (:bootstrap-port config 8091)
        server (http/run-server (partial handler config) {:ip host :port port})]
    (reset! !server server)
    (log/log! {:level :info
               :id ::bootstrap-started
               :msg "Bootstrap HTTP server started"
               :data {:host host :port port :url (str "http://" host ":" port "/nrepl")}})
    server))

(defn stop!
  "Stop bootstrap HTTP server"
  [server]
  (when server
    (server)  ; http-kit returns stop fn
    (reset! !server nil)))
```

## Usage Flow

### Key Insight: Different Connection Pattern!

**Socket connections (traditional):** Claude initiates -> `op=connect`
**Browser connections (sente):** Browsers self-connect -> Claude discovers & selects

```
+----------------------------------------------------------------------+
|  SOCKET PATTERN                 |  BROWSER PATTERN                   |
|  -----------------              |  ----------------                   |
|  Claude: "connect to :7888"     |  Browser: opens URL, auto-connects |
|  Server: accepts connection     |  Server: accepts connection        |
|  Claude: ready to eval          |  Claude: "who's connected?"        |
|                                 |  Claude: selects & evals           |
+----------------------------------------------------------------------+
```

### Step-by-Step Flow

1. **Start bb-mcp-server with sente-browser module enabled**:
   ```bash
   SENTE_BROWSER_ENABLED=true bb server --stdio
   ```
   Server now listening on:
   - Port 8090: WebSocket (sente)
   - Port 8091: HTTP bootstrap

2. **Browsers connect (user action, not Claude)**:
   ```
   http://localhost:8091/nrepl
   ```
   Browser loads Scittle + nREPL client, auto-connects via WebSocket.

3. **Claude discovers connected browsers**:
   ```json
   {"tool": "nrepl-connection", "op": "list"}
   ```
   Returns:
   ```json
   {
     "browsers": [
       {"nickname": "browser-1", "user-agent": "Chrome/120"},
       {"nickname": "browser-2", "user-agent": "Firefox/121"}
     ],
     "sockets": [...]
   }
   ```

4. **Claude evals in specific browser**:
   ```json
   {"tool": "nrepl-eval",
    "connection": "browser-1",
    "code": "(js/alert \"Hello from Claude!\")"}
   ```

### Tool Operation Semantics

| Operation | Socket | Browser |
|-----------|--------|---------|
| `list` | List all connections | **First step! Discover browsers** |
| `connect` | Claude initiates TCP | **Assign nickname** to browser |
| `status` | Show socket status | Show browser details |
| `disconnect` | Claude closes socket | Claude kicks browser |

## File Loading Clarification

| Tool | Browser Support |
|------|----------------|
| `nrepl-load-file` | NO - browser has no filesystem |
| `nrepl-eval-local-file` | YES - MCP reads file, sends content |
| `nrepl-eval` | YES - works normally |

## Configuration

### Environment Variables

```bash
SENTE_BROWSER_ENABLED=true      # Enable sente-browser module
SENTE_BROWSER_WS_PORT=8090      # WebSocket port
SENTE_BROWSER_HTTP_PORT=8091    # HTTP bootstrap port
SENTE_BROWSER_HOST=127.0.0.1    # Bind address (localhost by default for security)
```

### Module Config

```clojure
{:sente-browser {:enabled true
                 :ws-port 8090
                 :bootstrap-port 8091
                 :host "127.0.0.1"}}  ; Use "0.0.0.0" for network access
```

## Implementation Phases

### Phase 1: Minimal Integration (MVP)
- [ ] Create `modules/sente-browser/` structure
- [ ] Add sente-lite as dependency
- [ ] Create module.edn with lifecycle
- [ ] Implement server.clj with basic WebSocket
- [ ] Add `:type` field to connection.clj
- [ ] Add browser adapter to messages.clj
- [ ] Test: Claude can list browser connections

### Phase 2: Full nREPL Support
- [ ] Implement response routing from browser
- [ ] Add bootstrap HTTP server
- [ ] Test: Claude can eval code in browser
- [ ] Test: stdout/stderr streaming works

### Phase 3: Developer Experience
- [ ] Auto-nickname generation
- [ ] Connection health monitoring
- [ ] Broadcast to all browsers
- [ ] Documentation and examples

## Multi-Browser Support

Yes, multiple browsers can connect concurrently:

```
+----------+                    +------------------------------------------+
| Claude   | ---MCP tools--->   |  bb-mcp-server                           |
+----------+                    |                                          |
                               |  Connection Registry:                    |
                               |  +------------------------------------+  |
                               |  | browser-1  -> sente conn-123       |  |
                               |  | browser-2  -> sente conn-456       |  |
                               |  | browser-3  -> sente conn-789       |  |
                               |  | jvm-nrepl  -> socket :7888         |  |
                               |  +------------------------------------+  |
                               +------------------------------------------+
                                      |         |         |
                    WebSocket --------+         |         +-------- WebSocket
                                                |
                               +----------------+----------------+
                               |                                 |
                        +------+------+  +------+------+  +------+------+
                        | Browser 1   |  | Browser 2   |  | Browser 3   |
                        | (Chrome)    |  | (Firefox)   |  | (Safari)    |
                        +-------------+  +-------------+  +-------------+
```

## Dependency

Add sente-lite to bb-mcp-server:

```clojure
;; In bb.edn - development
io.github.franks42/sente-lite {:local/root "/Users/franksiebenlist/Development/sente_lite"}

;; In bb.edn - deployment
io.github.franks42/sente-lite {:git/url "https://github.com/franks42/sente-lite"
                                :git/sha "..."}
```

## Future Refinements (Post-MVP)

After the MVP works, consider these architectural improvements:

### Adapter Registry Pattern

**Problem:** The MVP adds hardcoded `:browser` type handling in the nrepl module, creating circular conceptual dependency (sente-browser depends on nrepl, but nrepl has logic for sente-browser).

**Solution:** Refactor nrepl to support pluggable connection adapters:

```clojure
;; In nrepl module - expose registration API:
(ns nrepl.state.adapters)

(defonce !adapters (atom {:socket default-socket-adapter}))

(defn register-adapter!
  "Register a connection adapter for a type"
  [type adapter-fns]
  (swap! !adapters assoc type adapter-fns))

(defn unregister-adapter!
  "Unregister a connection adapter"
  [type]
  (swap! !adapters dissoc type))

(defn get-adapter
  "Get adapter for connection type"
  [type]
  (get @!adapters type))
```

```clojure
;; In sente-browser module - register on startup:
(defn install-hooks! []
  (adapters/register-adapter! :browser
    {:adapt-connection adapt-browser-connection-for-messaging
     :send-message send-to-browser!}))

(defn remove-hooks! []
  (adapters/unregister-adapter! :browser))
```

**Benefits:**
- nrepl module stays clean, unaware of browser specifics
- Easy to add more connection types (remote proxies, heavy clients)
- Clean unload when sente-browser module stops
- Follows bb-mcp-server's modular philosophy

### Other Refinements

- Connection health monitoring with heartbeat
- Automatic reconnection handling
- Browser capability detection (what APIs available)
- Rate limiting for eval requests

## Conclusion

The modular approach in bb-mcp-server makes this integration clean:
1. New module with clear boundaries
2. Minimal changes to existing nrepl module (add :type field, browser adapter)
3. Module lifecycle handles server start/stop
4. Same MCP tools work for both socket and browser connections
5. Easy to enable/disable via configuration

Estimated effort: 2-3 focused sessions to reach MVP.
