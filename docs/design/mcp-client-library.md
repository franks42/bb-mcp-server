# MCP Client Library Design

**Status:** Planning
**Created:** 2025-12-27
**Goal:** Simplify MCP tool testing with a unified client library for scripts and BB tasks

---

## Overview

A shared library that eliminates the error-prone process of manually constructing JSON-RPC requests for MCP tool testing. Provides both script and BB task interfaces with identical functionality.

---

## Problem Statement

Testing MCP tools currently requires:
1. Manual port discovery from `.ports/.nickname` files
2. Complex JSON-RPC construction
3. Error-prone code escaping for shell/JSON
4. Session management boilerplate
5. Base64 encoding when escaping fails

This leads to:
- Frequent syntax errors
- Time wasted on debugging request format
- Inconsistent testing approaches
- Barrier to rapid prototyping

---

## Solution Architecture

### Core Library (`src/bb_mcp_server/mcp_client.clj`)

```clojure
(ns bb-mcp-server.mcp-client
  "Shared MCP client functionality"
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [babashka.http-client :as http]))
```

#### Key Functions

1. **Port Discovery**
   ```clojure
   (find-port-by-nickname nickname)
   (find-any-ports) ; Returns map of nickname->port
   ```

2. **Session Management**
   ```clojure
   (create-session! port)
   (get-or-create-session! port)
   ```

3. **Request Building**
   ```clojure
   (build-eval-request code session-id)
   (build-load-file-request path session-id)
   (encode-content text) ; Base64 when needed
   ```

4. **Communication**
   ```clojure
   (send-request! port request)
   (extract-result response)
   ```

### Dual Interface Design

#### 1. Script Interface (`scripts/mcp_eval.clj`)
```bash
# Direct execution
bb scripts/mcp_eval.clj "(+ 2 3)" --nickname my-server

# Load file
bb scripts/mcp_eval.clj --file src/example.clj --nickname test

# With custom config
bb scripts/mcp_eval.clj "(require '[my.mod :as m]) (m/foo)" --config custom.edn
```

#### 2. BB Task Interface (`bb.edn`)
```clojure
:mcp-eval {:task (exec 'scripts/mcp-eval-task)}
```
```bash
# Same API as script
bb mcp-eval "(+ 2 3)" --nickname my-server
bb mcp-eval --file src/example.clj
```

---

## Features

| Feature | Implementation | Priority |
|---------|----------------|----------|
| Auto port discovery | Read `.ports/.nickname` files | P0 |
| Session caching | In-memory session ID map | P0 |
| Smart encoding | Auto base64 when needed | P0 |
| File loading | `--file` flag support | P0 |
| Error handling | Clear error messages | P0 |
| Pretty output | Formatted results | P1 |
| Raw JSON mode | `--raw` flag | P1 |
| Config support | `--config` flag | P1 |
| Multiple servers | `--all` flag to test all | P2 |

---

## Implementation Plan

### Phase 1: Core Library
1. Create `src/bb_mcp_server/mcp_client.clj`
2. Implement port discovery functions
3. Add session management
4. Create request builders with encoding logic
5. Add HTTP communication layer

### Phase 2: Script Refactor
1. Refactor existing `scripts/mcp_eval.clj`
2. Add command-line argument parsing
3. Integrate with shared library
4. Add file loading support

### Phase 3: BB Task
1. Create `scripts/mcp-eval-task.clj`
2. Add `mcp-eval` task to `bb.edn`
3. Ensure API compatibility with script

### Phase 4: Polish
1. Add comprehensive error handling
2. Implement pretty output formatting
3. Add tests for shared library
4. Update documentation

---

## Technical Details

### Base64 Encoding Strategy
```clojure
(defn encode-content [text]
  (if (needs-encoding? text)
    (str "base64:" (b64/encode text))
    text))
```

### Port File Format
```json
{
  "pid": 94539,
  "port": 3003,
  "nickname": "test-server",
  "config": "bb-bootstrap-system.edn",
  "timestamp": "2025-12-27T07:14:39.292Z"
}
```

### Session Management
- Cache session IDs in atom map
- Auto-create on first use
- Include session ID in each request
- Handle session expiration gracefully

---

## Usage Examples

### Simple Evaluation
```bash
# Auto-discover server
bb mcp-eval "(+ 2 3)"

# With nickname
bb mcp-eval "(map inc [1 2 3])" --nickname calc-server

# Complex code with quotes
bb mcp-eval "(println \"Hello, World!\")"
```

### File Operations
```bash
# Load and evaluate file
bb mcp-eval --file src/my_module.clj

# Load with namespace
bb mcp-eval --file test/fixtures/example.clj --nickname test
```

### Advanced Usage
```bash
# Raw JSON output
bb mcp-eval "(System/getProperties)" --raw

# Test all running servers
bb mcp-eval "(+ 1 1)" --all

# With custom config
bb mcp-eval "(require '[custom :as c]) (c/init)" --config dev.edn
```

---

## Testing Strategy

1. Unit tests for `mcp_client.clj` functions
2. Integration tests with mock server
3. Script interface tests
4. BB task interface tests
5. Error scenario testing

---

## Benefits

1. **Reduced Errors**: No more manual JSON construction
2. **Faster Testing**: Quick evaluation of code snippets
3. **Consistency**: Same API for script and task
4. **Accessibility**: Lower barrier for assistants to test
5. **Maintainability**: Single source of truth for MCP client logic

---

## Future Enhancements

1. Interactive REPL mode
2. Batch file processing
3. Result caching
4. Custom tool invocation (not just eval)
5. WebSocket transport support
6. Metrics and timing information
