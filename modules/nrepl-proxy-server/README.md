# nrepl-proxy-server

Shadow-cljs style nREPL proxy - connect Calva/terminal to bb or browser REPLs.

## Overview

This module provides an nREPL server that proxies between standard nREPL clients (Calva, CIDER, terminal) and browser-based ClojureScript REPLs connected via `sente-browser`.

## Quick Start

```clojure
;; Start the proxy server
(require '[nrepl-proxy-server.core :as proxy])
(proxy/start! {:port 1667})

;; Connect with: lein repl :connect 1667
;; Or in Calva: connect to localhost:1667
```

## Shadow-cljs Style API

Once connected, use the browser API in your REPL:

```clojure
;; List available browsers
(browser/list)
;; => [{:id :browser-1 :nickname "browser-1" :status :connected ...}
;;     {:id :browser-2 :nickname "browser-2" :status :connected ...}]

;; Switch to a browser REPL
(browser/repl :browser-1)
;; => "Switched to browser-1. To quit, type: :cljs/quit"

;; Now you're in the browser! Evaluate ClojureScript:
js/navigator.userAgent
;; => "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)..."

js/document.title
;; => "bb-mcp-server Browser nREPL"

(js/alert "Hello from bb!")

;; Return to bb
:cljs/quit
;; => nil

;; Back in bb - verify with:
(System/getProperty "java.vm.name")
;; => "Substrate VM"
```

## Configuration

```clojure
{:port 1667              ; TCP port (0 = ephemeral)
 :host "127.0.0.1"       ; Bind address
 :port-file ".nrepl-proxy-port"  ; Where to write port
 :write-port-file? true  ; Whether to write port file
 :enabled true}          ; Auto-start on module load
```

In `system.edn`:
```clojure
{:modules [..., "sente-browser", "nrepl-proxy-server"]
 :config {"sente-browser" {:enabled true :ws-port 8090 :bootstrap-port 8091}
          "nrepl-proxy-server" {:enabled true :port 1667}}}
```

## Module API

```clojure
(require '[nrepl-proxy-server.core :as proxy])

;; Lifecycle
(proxy/start! {:port 1667})  ; Start server
(proxy/stop!)                 ; Stop server
(proxy/status)               ; Get server status

;; Introspection
(proxy/list-browsers)        ; List connected browsers
(proxy/browser-connected? :browser-1)  ; Check if specific browser is connected
(proxy/list-sessions)        ; List nREPL sessions with their targets
(proxy/get-url)              ; Get nREPL URL (e.g., "nrepl://127.0.0.1:1667")
```

## Architecture

```
┌─────────────────┐
│  Calva/CIDER    │  nREPL client
│  or terminal    │
└────────┬────────┘
         │ TCP (bencode)
         ▼
┌─────────────────┐
│ nrepl-proxy-    │  This module
│ server          │  Routes by session target
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌───────┐ ┌─────────────┐
│  bb   │ │sente-browser│
│ REPL  │ │ (WebSocket) │
└───────┘ └──────┬──────┘
                 │
         ┌───────┼───────┐
         ▼       ▼       ▼
      browser browser browser
        -1      -2      -3
```

## Dependencies

- **sente-browser** - WebSocket connection to browser REPLs

## Files

```
modules/nrepl-proxy-server/
├── module.edn                           # Module manifest
├── src/nrepl_proxy_server/
│   ├── core.clj                         # Public API, module entry
│   ├── server.clj                       # TCP nREPL server, bencode
│   ├── session.clj                      # Session → target routing
│   └── api.clj                          # browser/list, browser/repl, :cljs/quit
└── test/
    ├── nrepl_proxy_server/
    │   ├── session_test.clj
    │   └── api_test.clj
    └── run_tests.clj
```

## Tests

```bash
bb modules/nrepl-proxy-server/test/run_tests.clj
# => 15 tests, 44 assertions, 0 failures
```
