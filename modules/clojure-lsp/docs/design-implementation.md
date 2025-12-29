# clojure-lsp Module: Design & Implementation

## Purpose

Provide Clojure code intelligence (go-to-definition, find references, hover docs, etc.) by wrapping the `clojure-lsp` binary as a persistent subprocess.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Interfaces                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────┐  │
│  │ bb clojure- │  │ local-eval  │  │ MCP     │  │
│  │ lsp CLI     │  │ (REPL)      │  │ Tools   │  │
│  └──────┬──────┘  └──────┬──────┘  └────┬────┘  │
│         └────────────────┴──────────────┘       │
│                          │                      │
│                          ▼                      │
│  ┌───────────────────────────────────────────┐  │
│  │              tools.clj                    │  │
│  │  High-level Clojure API                   │  │
│  │  definition(), hover(), references()...   │  │
│  └─────────────────────┬─────────────────────┘  │
│                        ▼                        │
│  ┌───────────────────────────────────────────┐  │
│  │              client.clj                   │  │
│  │  Async LSP client                         │  │
│  │  request!(), notify!(), did-open!()       │  │
│  └─────────────────────┬─────────────────────┘  │
│                        ▼                        │
│  ┌───────────────────────────────────────────┐  │
│  │              jsonrpc.clj                  │  │
│  │  Wire protocol (Content-Length framing)   │  │
│  │  write-message!(), read-message!()        │  │
│  └─────────────────────┬─────────────────────┘  │
│                        ▼                        │
│               ┌─────────────────┐               │
│               │  clojure-lsp    │               │
│               │  (subprocess)   │               │
│               └─────────────────┘               │
└─────────────────────────────────────────────────┘
```

---

## Layer Descriptions

### jsonrpc.clj - Wire Protocol

Handles LSP's Content-Length framed JSON-RPC:

```
Content-Length: 42\r\n
\r\n
{"jsonrpc":"2.0","method":"initialize"...}
```

| Function | Purpose |
|----------|---------|
| `write-message!` | Serialize JSON, add header, write to stdout |
| `read-message!` | Parse header, read exact bytes, parse JSON |

### client.clj - Async LSP Client

Manages subprocess and request/response matching.

**State** (single atom):
```clojure
{:process      <subprocess>
 :in           <reader>
 :out          <writer>
 :request-id   42              ; incrementing counter
 :pending      {42 <promise>}  ; id -> promise for response
 :initialized? true
 :diagnostics  {"file://..." [...]}}  ; cached from notifications
```

| Function | Purpose |
|----------|---------|
| `start!` | Spawn subprocess, send `initialize` handshake |
| `stop!` | Send `shutdown`/`exit`, destroy process |
| `request!` | Send request, block until response (promise-based) |
| `notify!` | Send notification (no response expected) |
| `did-open!` | Tell LSP about file content |
| `did-close!` | Tell LSP to drop file |

**Background reader loop:** A future continuously reads messages. Responses (have `id`) deliver to pending promises. Notifications (no `id`) update state (e.g., diagnostics).

### tools.clj - High-Level API

Clean Clojure API using `with-file` pattern:

```clojure
(with-file path
  (fn []
    (client/request! "textDocument/hover" {...})))
```

This ensures:
1. `did-open` sends current file content to LSP
2. Operation runs
3. `did-close` tells LSP to drop the file

| Function | Purpose |
|----------|---------|
| `definition` | Go to definition |
| `references` | Find all references |
| `hover` | Get docs/type info |
| `completions` | Get completion suggestions |
| `code-actions` | Get available refactorings |
| `rename` | Rename symbol project-wide |
| `diagnostics` | Get warnings/errors |
| `document-symbols` | List symbols in file |
| `call-hierarchy` | Incoming/outgoing calls |

### core.clj - Module Entry Point

Implements bb-mcp-server module lifecycle.

| Function | Purpose |
|----------|---------|
| `start` | Register tools, log startup |
| `stop` | Stop LSP subprocess, unregister tools |
| `status` | Return running/idle state |

---

## Sync API over Async I/O

The LSP protocol is async (requests go out, responses come back later with matching IDs), but the API functions appear synchronous. This is achieved with the **promise bridge pattern**:

```
┌─────────────────────────────────────────────────────────────┐
│  Calling Thread                 Background Reader Thread    │
│                                                             │
│  (request! "hover" {...})                                   │
│       │                                                     │
│       ├──► 1. Generate id=42                                │
│       │                                                     │
│       ├──► 2. Create promise                                │
│       │       {:pending {42 <promise>}}                     │
│       │                                                     │
│       ├──► 3. Write to subprocess stdin                     │
│       │                                                     │
│       ├──► 4. (deref promise 30000 ::timeout)               │
│       │       │                                             │
│       │       │  ┌─────────── BLOCKS HERE ───────────┐      │
│       │       │  │                                   │      │
│       │       ▼  │                                   │      │
│                  │     5. Reader gets response       │      │
│                  │        {"id":42,"result":{...}}   │      │
│                  │              │                    │      │
│                  │     6. Lookup pending[42]         │      │
│                  │              │                    │      │
│                  │     7. (deliver promise result)   │      │
│                  │              │                    │      │
│                  └──────────────┼────────────────────┘      │
│       │                         │                           │
│       ◄─────────────────────────┘                           │
│       │                                                     │
│       └──► 8. Return result                                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Key insight:** The `pending` map `{id -> promise}` is the rendezvous point:
- Caller creates promise, stores it, blocks on `deref`
- Reader finds matching promise by ID, calls `deliver`
- `deliver` unblocks the `deref`, caller continues

This pattern is common in: nREPL clients, HTTP clients, any request-response protocol over streams.

---

## Timeout Handling

When `deref` times out (default 30s):

```
Timeline:
───────────────────────────────────────────────────────────►

0ms        Request sent, promise created, pending[42] = promise
           Caller blocks on (deref prom 30000 ::timeout)

30000ms    Timeout! deref returns ::timeout
           pending[42] removed
           Caller gets {:error {:message "Request timeout"}}

35000ms    Late response arrives: {"id":42,"result":{...}}
           Reader calls handle-response!
           pending[42] is nil (already removed)
           Response silently dropped
```

| Scenario | Handling | Status |
|----------|----------|--------|
| Orphaned promise | Cleaned up | OK |
| Late response | Silently dropped | OK |
| ID reuse collision | IDs increment forever | Low risk |
| Subprocess hung | Not detected | Phase 5 work |

---

## CLI

`scripts/clojure_lsp_cli.clj` provides command-line access:

```bash
bb clojure-lsp start <project-root>
bb clojure-lsp hover <file> <line> <col>
bb clojure-lsp definition <file> <line> <col>
# ... 12 commands total
```

**How it works:**
1. Parse args
2. Build Clojure code string: `(tools/hover {:file "..." :line 10 :column 5})`
3. Send to MCP server via `local-eval.local-eval` tool
4. Print result as JSON

---

## Test Coverage

| File | Type | Description |
|------|------|-------------|
| `jsonrpc_test.clj` | Unit | Wire protocol (write/read/roundtrip/EOF) |
| `client_test.clj` | Integration | Start/stop with real clojure-lsp subprocess |
| `server_test.clj` | Integration | Server wrapper lifecycle |

**Test strategy:** Integration tests spawn real `clojure-lsp` subprocess and use the module's own source files as test data. This provides realistic testing - the LSP analyzes actual Clojure code.

```clojure
;; Tests use the module's own code as test corpus
(let [root (System/getProperty "user.dir")]  ; bb-mcp-server root
  (client/start! {:project-root root})
  (tools/definition {:file "modules/clojure-lsp/src/.../client.clj"
                     :line 10 :column 5}))
```

**Missing coverage:**
- `tools.clj` functions - tested manually via CLI, should add automated tests
- Promise/pending logic - no mock-based unit tests

---

## Implementation Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Foundation (jsonrpc, client, server) | Complete |
| 2 | Clojure API (tools.clj) | Complete |
| 3 | CLI (bb clojure-lsp) | Complete |
| 4 | MCP Tools | Pending |
| 5 | Polish & Docs | Pending |

**Phase 4** will register proper MCP tools (`clj-definition`, `clj-hover`, etc.) as thin wrappers around `tools.clj`.

**Phase 5** will add error handling (timeouts, auto-restart), optional multi-project support, and documentation.

---

*Last Updated: 2025-12-29*
