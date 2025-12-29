# Clojure-LSP Integration for bb-mcp-server

## Executive Summary

This document outlines the recommended approach for integrating clojure-lsp capabilities into [bb-mcp-server](https://github.com/franks42/bb-mcp-server) as an MCP module. The recommended approach is to spawn clojure-lsp as a persistent subprocess and communicate via JSON-RPC over stdio.

**Key Recommendation**: Use stdio JSON-RPC (not the Babashka pod) to get full LSP capabilities.

---

## Development Strategy: API-First via local-eval

**Key Insight:** Build the Clojure API first, test via local-eval, then wrap with CLI and MCP tools.

### Why This Approach?

| Benefit | Explanation |
|---------|-------------|
| **Fast iteration** | No MCP protocol overhead - call functions directly |
| **Live reload** | Edit code, `(require ... :reload)`, test immediately |
| **Full introspection** | Inspect `@state`, pending promises, diagnostics |
| **API validation** | Validate design before committing to MCP tool shapes |

### Development Layers

```
┌─────────────────────────────────────────────────────────┐
│  Layer 4: MCP Tools (clj-definition, clj-hover, ...)   │
│           → For Claude/AI agents                        │
├─────────────────────────────────────────────────────────┤
│  Layer 3: CLI (bb clojure-lsp definition ...)          │
│           → Uses local-eval to call Layer 2             │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Clojure API (tools.clj)                      │
│           → Pure functions, tested via local-eval       │
├─────────────────────────────────────────────────────────┤
│  Layer 1: LSP Client (client.clj, jsonrpc.clj)         │
│           → Async subprocess communication              │
└─────────────────────────────────────────────────────────┘
```

### Workflow

1. **Start server:** `bb server --http`
2. **Edit code:** Modify `tools.clj` in your editor
3. **Reload:** `(require '[...tools :as t] :reload)` via local-eval
4. **Test:** `(t/definition {:file "..." :line 42 :column 10})`
5. **Iterate:** Back to step 2

### CLI via local-eval

The `bb clojure-lsp` CLI uses local-eval to call the Clojure API on a running server:

```clojure
;; CLI implementation pattern
(defn cmd-definition [{:keys [file line column server]}]
  (mcp-client/call-tool
    server
    "local-eval"
    {:code (pr-str
             `(do
                (require '[bb-mcp-server.modules.clojure-lsp.tools :as t])
                (t/definition {:file ~file :line ~line :column ~column})))}))
```

This means:
- Single clojure-lsp process (managed by server)
- CLI is thin - just sends code to eval
- State is shared across all interfaces

---

## Background

### The Problem

Claude Code's native LSP plugin system is currently broken (GitHub issues #15148, #14803, #15202). Even when fixed, native plugins are Claude Code-specific and not portable.

### The Opportunity

bb-mcp-server's modular architecture is ideal for adding clojure-lsp as a module that:
- Works with any MCP client (Claude Code, Claude Desktop, etc.)
- Provides full LSP capabilities
- Stays in the Clojure ecosystem
- Reuses existing async patterns from the nREPL module

---

## Approach Comparison

### Option 1: Babashka Pod (NOT Recommended)

clojure-lsp is available as a Babashka pod:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod 'com.github.clojure-lsp/clojure-lsp "2024.04.22-11.50.26")
(require '[clojure-lsp.api :as lsp-api])
```

#### Pod API Functions (Complete List)

| Function | Description |
|----------|-------------|
| `analyze-project-and-deps!` | Analyze project + dependencies |
| `analyze-project-only!` | Analyze project without deps |
| `clean-ns!` | Organize ns forms |
| `diagnostics` | Get warnings/errors |
| `format!` | Format code via cljfmt |
| `rename!` | Rename by fully-qualified name |
| `dump` | Export analysis data |

#### Pod Limitations

The pod API operates on **whole namespaces/files**, not cursor positions:

```clojure
;; Pod - rename by FQN only
(api/rename! {:from 'my.ns/old-name :to 'my.ns/new-name})

;; Cannot do: "rename symbol at line 42, column 15"
```

**Missing from Pod API:**
- Go to definition (at cursor)
- Find references (at cursor)
- Hover/documentation
- Code completion
- Code actions (30+ refactorings)
- Call hierarchy
- Document symbols
- Semantic tokens
- Incremental document sync

### Option 2: Stdio JSON-RPC (RECOMMENDED)

Spawn clojure-lsp as a subprocess and speak LSP protocol over stdio.

#### Full LSP Capabilities

| Category | Features |
|----------|----------|
| **Navigation** | definition, references, implementation, type definition |
| **Information** | hover, signature help, document highlight |
| **Completion** | completion, completion resolve |
| **Symbols** | document symbols, workspace symbols |
| **Refactoring** | 30+ code actions (extract function, thread-first/last, move to let, inline, etc.) |
| **Hierarchy** | call hierarchy (incoming/outgoing) |
| **Diagnostics** | Real-time via notifications |
| **Rename** | At cursor position, project-wide |
| **Semantic** | Semantic tokens for syntax highlighting |

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    bb-mcp-server                        │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │              clojure-lsp module                   │ │
│  │                                                   │ │
│  │  ┌─────────────┐    ┌─────────────────────────┐  │ │
│  │  │ MCP Tools   │    │    LSP Client           │  │ │
│  │  │             │    │                         │  │ │
│  │  │ clj-init    │───▶│ - JSON-RPC framing      │  │ │
│  │  │ clj-definition│  │ - Request/Response      │  │ │
│  │  │ clj-references│◀─│ - Notification handling │  │ │
│  │  │ clj-hover   │    │ - Document sync         │  │ │
│  │  │ clj-completions│ │ - Async pending map     │  │ │
│  │  │ clj-rename  │    └───────────┬─────────────┘  │ │
│  │  │ clj-code-actions│            │ stdio          │ │
│  │  └─────────────┘                ▼                │ │
│  │                     ┌─────────────────────────┐  │ │
│  │                     │     clojure-lsp         │  │ │
│  │                     │     (subprocess)        │  │ │
│  │                     │     - persistent        │  │ │
│  │                     │     - warm cache        │  │ │
│  │                     └─────────────────────────┘  │ │
│  └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## Implementation

### JSON-RPC Framing

LSP uses Content-Length headers for message framing:

```clojure
(ns clojure-lsp.jsonrpc
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn write-message! [^java.io.BufferedWriter out msg]
  (let [body (json/generate-string msg)
        len (count (.getBytes body "UTF-8"))]
    (.write out (str "Content-Length: " len "\r\n\r\n" body))
    (.flush out)))

(defn read-message! [^java.io.BufferedReader in]
  (loop [headers {}]
    (let [line (.readLine in)]
      (cond
        (nil? line) nil
        (str/blank? line)
        (let [len (parse-long (get headers "Content-Length"))
              buf (char-array len)]
          (.read in buf 0 len)
          (json/parse-string (String. buf) true))
        :else
        (let [[k v] (str/split line #": " 2)]
          (recur (assoc headers k v)))))))
```

### LSP Client Core

```clojure
(ns clojure-lsp.client
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure-lsp.jsonrpc :as rpc])
  (:import [java.io BufferedReader BufferedWriter 
            InputStreamReader OutputStreamWriter]))

(defonce ^:private state 
  (atom {:process nil
         :in nil
         :out nil
         :request-id 0
         :pending {}
         :initialized? false
         :docs #{}
         :diagnostics {}}))

;; --- Reader Loop (runs in separate thread) ---

(defn- handle-response! [{:keys [id result error]}]
  (when-let [p (get-in @state [:pending id])]
    (deliver p (or result {:error error}))
    (swap! state update :pending dissoc id)))

(defn- handle-notification! [{:keys [method params]}]
  (case method
    "textDocument/publishDiagnostics"
    (swap! state assoc-in [:diagnostics (:uri params)] (:diagnostics params))
    nil))

(defn- read-loop! [^BufferedReader in]
  (try
    (loop []
      (when-let [msg (rpc/read-message! in)]
        (if (:id msg)
          (handle-response! msg)
          (handle-notification! msg))
        (recur)))
    (catch Exception e
      (println "LSP read loop ended:" (.getMessage e)))))

;; --- Public API ---

(defn request! 
  "Send request and wait for response. Returns result or {:error ...}"
  [method params & {:keys [timeout] :or {timeout 30000}}]
  (let [id (-> (swap! state update :request-id inc) :request-id)
        p (promise)]
    (swap! state assoc-in [:pending id] p)
    (rpc/write-message! (:out @state) 
                        {:jsonrpc "2.0" :id id :method method :params params})
    (let [result (deref p timeout ::timeout)]
      (if (= result ::timeout)
        {:error {:message "Request timeout"}}
        result))))

(defn notify! 
  "Send notification (no response expected)"
  [method params]
  (rpc/write-message! (:out @state) 
                      {:jsonrpc "2.0" :method method :params params}))

(defn did-open! [path]
  (let [uri (str "file://" path)
        text (slurp path)]
    (notify! "textDocument/didOpen"
             {:textDocument {:uri uri
                             :languageId "clojure"
                             :version 1
                             :text text}})))

(defn did-close! [path]
  (let [uri (str "file://" path)]
    (notify! "textDocument/didClose" {:textDocument {:uri uri}})))

(defn start! 
  "Start clojure-lsp subprocess and initialize"
  [{:keys [project-root executable-path]
    :or {executable-path "clojure-lsp"}}]
  (when-not (:process @state)
    (let [proc (p/process [executable-path]
                          {:dir project-root
                           :in :pipe
                           :out :pipe
                           :err :inherit})
          in (BufferedReader. (InputStreamReader. (:out proc)))
          out (BufferedWriter. (OutputStreamWriter. (:in proc)))]
      
      (swap! state assoc
             :process proc
             :in in
             :out out
             :project-root project-root)
      
      ;; Start reader thread
      (future (read-loop! in))
      
      ;; LSP Initialize handshake
      (let [result (request! "initialize"
                             {:processId (.pid (ProcessHandle/current))
                              :rootUri (str "file://" project-root)
                              :capabilities 
                              {:textDocument {:hover {:contentFormat ["markdown" "plaintext"]}
                                              :completion {:completionItem {:snippetSupport false}}
                                              :definition {:linkSupport false}
                                              :references {}
                                              :rename {:prepareSupport true}
                                              :codeAction {:codeActionLiteralSupport 
                                                           {:codeActionKind {:valueSet []}}}}}
                              :workspaceFolders [{:uri (str "file://" project-root)
                                                  :name "root"}]})]
        (notify! "initialized" {})
        (swap! state assoc :initialized? true)
        result))))

(defn stop! 
  "Shutdown clojure-lsp subprocess"
  []
  (when-let [proc (:process @state)]
    (request! "shutdown" nil)
    (notify! "exit" nil)
    (p/destroy proc)
    (reset! state {:process nil :request-id 0 :pending {} 
                   :initialized? false :docs #{} :diagnostics {}})))

(defn ready? [] (:initialized? @state))

(defn get-diagnostics 
  "Get cached diagnostics for file or all files"
  ([] (:diagnostics @state))
  ([path] (get-in @state [:diagnostics (str "file://" path)])))
```

### Convenience Wrappers

```clojure
(ns clojure-lsp.tools
  (:require [clojure-lsp.client :as lsp]))

(defn- uri [path] (str "file://" path))
(defn- pos [line col] {:line (dec line) :character (dec col)})

(defn- with-file [file f]
  (lsp/did-open! file)
  (try
    (f)
    (finally
      (lsp/did-close! file))))

(defn definition [{:keys [file line column]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/definition"
                    {:textDocument {:uri (uri file)}
                     :position (pos line column)}))))

(defn references [{:keys [file line column include-declaration]
                   :or {include-declaration true}}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/references"
                    {:textDocument {:uri (uri file)}
                     :position (pos line column)
                     :context {:includeDeclaration include-declaration}}))))

(defn hover [{:keys [file line column]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/hover"
                    {:textDocument {:uri (uri file)}
                     :position (pos line column)}))))

(defn completions [{:keys [file line column]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/completion"
                    {:textDocument {:uri (uri file)}
                     :position (pos line column)}))))

(defn code-actions [{:keys [file line column]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/codeAction"
                    {:textDocument {:uri (uri file)}
                     :range {:start (pos line column)
                             :end (pos line column)}
                     :context {:diagnostics []}}))))

(defn rename [{:keys [file line column new-name]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/rename"
                    {:textDocument {:uri (uri file)}
                     :position (pos line column)
                     :newName new-name}))))

(defn document-symbols [{:keys [file]}]
  (with-file file
    (fn []
      (lsp/request! "textDocument/documentSymbol"
                    {:textDocument {:uri (uri file)}}))))

(defn call-hierarchy-incoming [{:keys [file line column]}]
  (with-file file
    (fn []
      (let [items (lsp/request! "textDocument/prepareCallHierarchy"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)})]
        (when (seq items)
          (lsp/request! "callHierarchy/incomingCalls" {:item (first items)}))))))

(defn call-hierarchy-outgoing [{:keys [file line column]}]
  (with-file file
    (fn []
      (let [items (lsp/request! "textDocument/prepareCallHierarchy"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)})]
        (when (seq items)
          (lsp/request! "callHierarchy/outgoingCalls" {:item (first items)}))))))
```

### Module Definition

```clojure
;; modules/clojure-lsp/module.edn
{:name "clojure-lsp"
 :version "0.1.0"
 :description "Clojure LSP integration via persistent subprocess"
 
 :tools
 [{:name "clj-init"
   :description "Initialize clojure-lsp for a project. Call this first. Initial analysis may take 30s-2min for large projects, subsequent calls are fast."
   :input-schema {:type "object"
                  :properties {:project-root {:type "string" 
                                              :description "Absolute path to project root"}
                               :executable-path {:type "string"
                                                 :description "Optional. Absolute path to clojure-lsp executable. Defaults to 'clojure-lsp' on PATH."}}
                  :required ["project-root"]}}
  
  {:name "clj-definition"
   :description "Go to definition of symbol at position. Returns location(s) of definition."
   :input-schema {:type "object"
                  :properties {:file {:type "string" :description "Absolute file path"}
                               :line {:type "integer" :description "1-indexed line number"}
                               :column {:type "integer" :description "1-indexed column number"}}
                  :required ["file" "line" "column"]}}
  
  {:name "clj-references"
   :description "Find all references to symbol at position across the project."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}
                               :include-declaration {:type "boolean" :default true}}
                  :required ["file" "line" "column"]}}
  
  {:name "clj-hover"
   :description "Get documentation and type information for symbol at position."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}}
                  :required ["file" "line" "column"]}}
  
  {:name "clj-completions"
   :description "Get completion suggestions at position."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}}
                  :required ["file" "line" "column"]}}
  
  {:name "clj-code-actions"
   :description "Get available refactorings and quick fixes at position."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}}
                  :required ["file" "line" "column"]}}
  
  {:name "clj-rename"
   :description "Rename symbol at position across the entire project."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}
                               :new-name {:type "string"}}
                  :required ["file" "line" "column" "new-name"]}}
  
  {:name "clj-diagnostics"
   :description "Get current diagnostics (errors, warnings) for a file or entire project."
   :input-schema {:type "object"
                  :properties {:file {:type "string" :description "Optional - omit for all files"}}}}
  
  {:name "clj-document-symbols"
   :description "Get all symbols (functions, vars, etc.) defined in a file."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}}
                  :required ["file"]}}
  
  {:name "clj-call-hierarchy"
   :description "Get incoming or outgoing call hierarchy for function at position."
   :input-schema {:type "object"
                  :properties {:file {:type "string"}
                               :line {:type "integer"}
                               :column {:type "integer"}
                               :direction {:type "string" 
                                           :enum ["incoming" "outgoing"]
                                           :default "incoming"}}
                  :required ["file" "line" "column"]}}]}
```

---

## Concurrency Model

LSP JSON-RPC supports concurrent requests. Each request has a unique `id`, and responses can arrive in any order:

```
┌────────────────────────────────────────────────────┐
│                 bb-mcp-server                      │
│                                                    │
│  Request A (id=1) ──┐                              │
│  Request B (id=2) ──┼──▶ pending: {1: promise-A   │
│  Request C (id=3) ──┘              2: promise-B   │
│                                    3: promise-C}  │
│                                         ▲         │
│                                         │ deliver │
│                        ┌────────────────┴───────┐ │
│                        │    Reader Thread       │ │
│                        │    (matches by id)     │ │
│                        └────────────▲───────────┘ │
│                                     │ stdio       │
│                           ┌─────────┴─────────┐   │
│                           │   clojure-lsp     │   │
│                           └───────────────────┘   │
└────────────────────────────────────────────────────┘
```

Multiple MCP requests can be in flight simultaneously. The `request!` function blocks only the caller until its specific response arrives.

---

## Similarity to nREPL Module

The async machinery is nearly identical to the existing nREPL module:

| Aspect | nREPL | LSP |
|--------|-------|-----|
| Transport | Socket or stdio | Stdio |
| Framing | Bencode | Content-Length + JSON |
| Request ID | `:id` field | `id` field |
| Pending tracking | `{id → promise}` | `{id → promise}` |
| Reader loop | Separate thread | Separate thread |
| Notifications | Server → Client | Server → Client (diagnostics, etc.) |

### Potential Shared Abstraction

```clojure
(ns bb-mcp-server.async-client)

(defprotocol IAsyncClient
  (request! [this method params])
  (notify! [this method params])
  (close! [this]))

(defn create-client [{:keys [read-fn write-fn on-notification]}]
  (let [state (atom {:id 0 :pending {}})]
    ;; Reader loop
    (future
      (loop []
        (when-let [msg (read-fn)]
          (if-let [id (:id msg)]
            ;; Response
            (when-let [p (get-in @state [:pending id])]
              (deliver p msg)
              (swap! state update :pending dissoc id))
            ;; Notification
            (when on-notification
              (on-notification msg)))
          (recur))))
    
    (reify IAsyncClient
      (request! [_ method params]
        (let [id (-> (swap! state update :id inc) :id)
              p (promise)]
          (swap! state assoc-in [:pending id] p)
          (write-fn {:id id :method method :params params})
          (deref p 30000 {:error "timeout"})))
      (notify! [_ method params]
        (write-fn {:method method :params params}))
      (close! [_]
        (reset! state {:id 0 :pending {}})))))
```

---

## Usage Examples

### From REPL / Scripts

```clojure
(require '[clojure-lsp.client :as lsp]
         '[clojure-lsp.tools :as tools])

;; Initialize
(lsp/start! {:project-root "/path/to/my-project"})

;; Navigate
(tools/definition {:file "/path/to/my-project/src/core.clj" 
                   :line 42 
                   :column 10})

;; Find usages
(tools/references {:file "/path/to/my-project/src/core.clj"
                   :line 42
                   :column 10})

;; Get docs
(tools/hover {:file "/path/to/my-project/src/core.clj"
              :line 42
              :column 10})

;; Refactor
(tools/rename {:file "/path/to/my-project/src/core.clj"
               :line 42
               :column 10
               :new-name "better-name"})

;; Cleanup
(lsp/stop!)
```

### Composing Higher-Level Functions

```clojure
(defn find-unused-public-vars [project-root]
  (lsp/start! {:project-root project-root})
  (let [diagnostics (lsp/get-diagnostics)]
    (->> diagnostics
         vals
         (mapcat identity)
         (filter #(= (:code %) "clojure-lsp/unused-public-var"))
         (map #(select-keys % [:uri :range :message])))))

(defn who-calls-this? [file line column]
  (tools/call-hierarchy-incoming {:file file :line line :column column}))
```

---

## Considerations

### Startup Time

clojure-lsp's initial analysis can take 30s-2min for large projects. After initialization, responses are fast due to cached analysis.

**Mitigation**: 
- Explicit `clj-init` tool call
- Cache persists in `.lsp/.cache/`
- Subsequent startups are faster

### Project Root Detection

The LSP server needs to know the project root for proper analysis.

**Options**:
- Explicit `project-root` parameter
- Infer from file path (find nearest `deps.edn`, `project.clj`, `bb.edn`)

### Multiple Projects

For working on multiple projects simultaneously, consider:
- Multiple clojure-lsp instances keyed by project-root
- Or reinitialize when switching projects

### Error Recovery

Handle subprocess crashes and timeouts:
- Health check via `clojure/serverInfo/raw`
- Automatic restart on failure
- Automatic restart on failure
- Timeout protection on all requests

### File Synchronization (Critical)

Since the MCP server is not an editor with persistent dirty buffers, it must ensure `clojure-lsp` always uses the latest file content from disk.

**Strategy: Stateless Open/Close**
For every request targeting a file:
1. `textDocument/didOpen`: Send current disk content to LSP (creates an overlay).
2. **Perform Operation**: Run the specific LSP request (hover, define, etc.).
3. `textDocument/didClose`: Tell LSP to close the file (drops the overlay).

This "stateless" approach ensures:
- `clojure-lsp` always analyzes the current state of the file on disk.
- No stale buffers persist in the MCP server process.
- No need to implement complex file watching logic in MCP.

---

## Summary

| Approach | Pod | Stdio JSON-RPC |
|----------|-----|----------------|
| **Capabilities** | 6 batch functions | 40+ LSP features |
| **Cursor-aware** | ❌ | ✅ |
| **Definition/References** | ❌ | ✅ |
| **Completion** | ❌ | ✅ |
| **Code Actions** | ❌ | ✅ (30+ refactorings) |
| **Real-time diagnostics** | ❌ | ✅ |
| **Implementation** | Simple | ~150 lines |
| **Reuses nREPL patterns** | N/A | ✅ |

**Recommendation**: Implement the stdio JSON-RPC approach. It provides full LSP capabilities, fits naturally into bb-mcp-server's modular architecture, and can share async patterns with the existing nREPL module.

---

## HTTP/REST/OpenAPI Interface

### What You Get "For Free"

bb-mcp-server already supports streamable HTTP, so the same MCP tools are accessible via multiple transports:

```bash
# MCP over stdio (Claude Code)
bb server:stdio

# MCP over HTTP (any HTTP client)
bb server:streamable 3000
```

Same tools, same handlers, two transports. No additional code required.

### Advantages of HTTP/REST Access

| Use Case | Benefit |
|----------|---------|
| **Editor plugins** | VSCode/Emacs/Vim extensions can call via HTTP without MCP SDK |
| **CI/CD pipelines** | `curl` calls for linting, formatting checks |
| **Web dashboards** | Browser-based code analysis tools |
| **Other languages** | Python/Ruby/JS scripts can use LSP features without MCP client |
| **Testing** | Easy to test with curl, Postman, httpie |
| **Debugging** | Inspect requests/responses in browser dev tools |
| **Load balancing** | Multiple clients can share one LSP server |
| **Remote access** | Access LSP over network, not just local |

### Example HTTP Usage

```bash
# Initialize
curl -X POST http://localhost:3000/tools/clj-init \
  -H "Content-Type: application/json" \
  -d '{"project-root": "/path/to/project"}'

# Go to definition
curl -X POST http://localhost:3000/tools/clj-definition \
  -H "Content-Type: application/json" \
  -d '{"file": "/path/to/src/core.clj", "line": 42, "column": 10}'

# Find references
curl -X POST http://localhost:3000/tools/clj-references \
  -H "Content-Type: application/json" \
  -d '{"file": "/path/to/src/core.clj", "line": 42, "column": 10}'
```

### Concrete Use Cases

#### 1. CI Lint Check

```bash
#!/bin/bash
# In CI pipeline
DIAG=$(curl -s http://lsp-server:3000/tools/clj-diagnostics)
ERRORS=$(echo "$DIAG" | jq '[.[] | select(.severity == 1)] | length')
if [ "$ERRORS" -gt 0 ]; then
  echo "Found $ERRORS errors"
  exit 1
fi
```

#### 2. Git Pre-Commit Hook

```bash
#!/bin/bash
# .git/hooks/pre-commit
for file in $(git diff --cached --name-only -- '*.clj'); do
  curl -s http://localhost:3000/tools/clj-diagnostics \
    -d "{\"file\": \"$file\"}" | jq -e '.errors == []' || exit 1
done
```

#### 3. Web-Based Code Explorer

```javascript
// Browser JS
async function goToDefinition(file, line, col) {
  const resp = await fetch('http://localhost:3000/tools/clj-definition', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({file, line, column: col})
  });
  const location = await resp.json();
  navigateToFile(location.uri, location.range.start);
}
```

#### 4. Emacs/Vim Without Full LSP Client

```elisp
;; Emacs - simple HTTP call instead of full LSP client setup
(defun my-clj-definition ()
  (interactive)
  (let* ((resp (request "http://localhost:3000/tools/clj-definition"
                 :type "POST"
                 :data (json-encode `(:file ,(buffer-file-name)
                                     :line ,(line-number-at-pos)
                                     :column ,(current-column)))
                 :sync t)))
    (find-file (plist-get resp :uri))
    (goto-line (plist-get (plist-get resp :range) :start))))
```

#### 5. Multi-Project Dashboard

```clojure
;; Analyze multiple projects, aggregate results
(defn analyze-all-projects [project-roots]
  (pmap (fn [root]
          {:project root
           :diagnostics (http/post "http://localhost:3000/tools/clj-diagnostics"
                                   {:body (json/encode {:project-root root})})})
        project-roots))
```

### OpenAPI Spec Generation

Auto-generate OpenAPI from MCP tool schemas:

```clojure
(defn mcp-tool->openapi-path [{:keys [name description input-schema]}]
  {(str "/tools/" name)
   {:post {:summary description
           :requestBody {:content {"application/json" {:schema input-schema}}}
           :responses {200 {:description "Success"}}}}})

;; Generate full OpenAPI spec from all registered tools
(defn generate-openapi-spec [tools]
  {:openapi "3.0.0"
   :info {:title "bb-mcp-server Clojure LSP API" :version "0.1.0"}
   :paths (into {} (map mcp-tool->openapi-path tools))})
```

This enables Swagger UI, automatic client code generation, and standardized API documentation.

### Access Methods Summary

| Access Method | Use Case |
|---------------|----------|
| **MCP stdio** | Claude Code, AI agents |
| **MCP HTTP** | AI agents over network |
| **REST/curl** | CI, scripts, quick testing |
| **OpenAPI** | Documentation, client codegen, Swagger UI |
| **Direct Clojure** | REPL, tests, composition |

All access methods use the same underlying tool handlers. The HTTP interface democratizes access — anything that can make HTTP calls can now use clojure-lsp features without needing an MCP SDK or LSP client implementation.

---

## References

- [clojure-lsp documentation](https://clojure-lsp.io/)
- [clojure-lsp.api (pod API)](https://cljdoc.org/d/com.github.clojure-lsp/clojure-lsp/CURRENT/api/clojure-lsp.api)
- [LSP Specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- [lsp4clj (clojure-lsp's LSP library)](https://github.com/clojure-lsp/lsp4clj)
- [bb-mcp-server](https://github.com/franks42/bb-mcp-server)
