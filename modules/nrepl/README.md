# nrepl

nREPL client tools for remote Clojure REPL interaction.

## Overview

Connect, evaluate code, and manage remote Clojure REPLs. Supports multiple simultaneous connections with nicknames for easy switching.

## Tools

| Tool | Description |
|------|-------------|
| `nrepl-connection` | Manage nREPL connections (connect, disconnect, status, list) |
| `nrepl-eval` | Execute Clojure code in connected nREPL server |
| `nrepl-load-file` | Load Clojure files into nREPL server |
| `nrepl-eval-local-file` | Read local file and evaluate via nREPL |
| `nrepl-send-message` | Low-level nREPL protocol access |
| `nrepl-send-message-async` | Async message sending (internal) |
| `nrepl-get-result-async` | Async result retrieval (internal) |
| `local-nrepl-server` | Manage built-in Babashka nREPL server |
| `must-read-mcp-nrepl-context` | AI agent onboarding guide |

## Quick Start

```clojure
;; 1. Connect to nREPL server
{:op "connect" :connection "localhost:7888" :nickname "my-repl"}

;; 2. Evaluate code
{:code "(+ 1 2 3)"}
;; => {:value "6"}

;; 3. Load a file
{:file-path "/path/to/code.clj"}
```

### Connection Management

```clojure
;; Connect
{:op "connect" :connection "7888"}  ; localhost:7888
{:op "connect" :connection "host:port" :nickname "prod"}

;; Check status
{:op "status"}

;; List all connections
{:op "list"}

;; Disconnect
{:op "disconnect" :connection "prod"}
{:op "disconnect-all"}
```

### Code Evaluation

```clojure
;; Basic eval
{:code "(map inc [1 2 3])"}

;; With timeout
{:code "(long-running-fn)" :timeout 60000}

;; Base64 for complex code
{:code "base64-encoded-code" :input-base64 true}
```

## Module Structure

```
modules/nrepl/
├── module.edn
├── README.md
├── src/nrepl/
│   ├── core.clj           # Module entry
│   ├── client.clj         # nREPL client
│   ├── connection.clj     # Connection management
│   └── tools/             # Tool implementations
│       ├── nrepl-connection.clj
│       ├── nrepl-eval.clj
│       └── ...
└── test/
    └── run_tests.clj
```

## License

Same as bb-mcp-server project.
