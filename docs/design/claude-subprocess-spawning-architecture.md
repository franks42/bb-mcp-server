# Claude Subprocess Spawning - Architecture & Design

**Status:** Draft / Planning
**Phase:** 13 (proposed)
**Date:** 2025-11-23
**Reviewed by:** Gemini (see gemini-claude-subprocess-spawning-review.md)

---

## Overview

This document captures architectural ideas and design decisions for spawning Claude CLI instances as subprocesses with stdio connected to bb-mcp-server. The goal is to enable:

1. **Claude-as-a-tool** - Invoke Claude instances from MCP tools
2. **Multi-agent orchestration** - Multiple Claude instances communicating
3. **Session management** - Persistent processes with conversation state
4. **Request/Response correlation** - Match async replies to original requests

---

## Reference Implementation: clay-noj-ai

The `clay-noj-ai` project demonstrates the core patterns:

### Process Spawning

```clojure
(def claude-base-args
  ["-p" "--verbose"
   "--input-format" "stream-json"
   "--output-format" "stream-json"
   "--permission-mode" "bypassPermissions"])

(defn spawn! [name & {:keys [model]}]
  (let [proc (p/process (into [claude-path] args)
                        {:shutdown p/destroy-tree})
        writer (io/writer (:in proc))
        reader (io/reader (:out proc))]
    {:name name :process proc :writer writer :reader reader}))
```

### JSONL Protocol

**Input format:**
```json
{"type": "user", "message": {"role": "user", "content": "Hello"}}
```

**Output format (streamed lines):**
```json
{"type": "system", "subtype": "init", "session_id": "abc123", ...}
{"type": "assistant", "message": {"content": "Hello! How can I help?"}}
{"type": "result", "subtype": "success", "cost_usd": 0.001, ...}
```

### Session Forking

```clojure
(defn fork! [parent-name new-name & opts]
  (let [parent (get @registry parent-name)
        session-id (:session-id parent)]
    (spawn! new-name :resume session-id opts)))
```

Uses `--resume session-id` to inherit conversation context.

### Request ID Generation

```clojure
(defn next-request-id [claude-name]
  (let [n (swap! message-counter inc)]
    (format "%s-%06d-%s" claude-name n (subs (str (random-uuid)) 0 8))))
```

### Prototype Strengths (from Gemini review)

- **Process Management**: Uses `babashka.process` effectively for lifecycle
- **Session Management**: Successfully implements `fork!` and `spawn-from-session!` using `--resume`
- **JSONL Handling**: Correctly parses the stream-json format

### Prototype Weaknesses / Risks (from Gemini review)

- **Concurrency Model**: `ask` blocks reading from stdout - can desync with multiple threads or unsolicited messages
- **Thread-per-Request**: `ask-async` uses `future` - fine for low volume, but dedicated I/O loop is safer
- **Error Handling**: If Claude process dies, reader thread may hang or throw obscure errors

---

## Proposed Architecture

### Two-Module Approach

Consider separating concerns into two modules:

#### Module 1: `process-spawner` (generic)

Generic subprocess management with stdio connection:

- Spawn any process with stdin/stdout connected to bb
- Input/output queues for traffic management
- Request-ID correlation for async matching
- Process lifecycle (start, stop, restart)
- Health monitoring

#### Module 2: `claude-spawner` (Claude-specific)

Claude CLI specifics built on process-spawner:

- Claude CLI argument construction
- JSONL message formatting/parsing
- Session management (create, fork, resume)
- Model selection
- Cost tracking from result messages
- MCP tool exposure

### Decision Point

**Option A:** Two modules as described above
**Option B:** Single `claude-spawner` module with potential extraction later

*Recommendation:* Start with Option B (single module) to avoid premature abstraction. Extract `process-spawner` when/if we need to spawn non-Claude processes.

### Module Structure (from Gemini)

```
modules/claude-manager/
├── src/claude_manager/
│   ├── core.clj      # Public API (spawn, ask, list)
│   ├── process.clj   # Low-level process & I/O handling
│   └── registry.clj  # State management (atoms)
├── test/
└── module.edn
```

---

## Dedicated Reader Loop Pattern (Critical Fix)

**Key insight from Gemini:** Instead of `ask` reading directly from stdout, implement a **dedicated reader loop** per instance. This solves the concurrency issues in the prototype.

### Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                     Claude Instance                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌──────────────┐                    ┌──────────────┐      │
│   │ stdin writer │◄───── write ──────│   ask()      │      │
│   └──────────────┘                    │              │      │
│          │                            │  1. gen ID   │      │
│          ▼                            │  2. promise  │      │
│   ┌──────────────┐                    │  3. register │      │
│   │    Claude    │                    │  4. write    │      │
│   │   Process    │                    │  5. return   │      │
│   └──────────────┘                    └──────────────┘      │
│          │                                   ▲              │
│          ▼                                   │              │
│   ┌──────────────┐     dispatch      ┌──────┴───────┐      │
│   │ stdout reader│────────────────►  │ pending-reqs │      │
│   │   (future)   │                   │  {id→promise}│      │
│   │              │                   └──────────────┘      │
│   │ - reads lines│                                         │
│   │ - parses JSON│                                         │
│   │ - dispatches │                                         │
│   │   by type    │                                         │
│   └──────────────┘                                         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Reader Loop Implementation

```clojure
(defn start-reader-loop!
  "Start background reader for a Claude instance.
   Only ONE thing reads from stdout - ensures messages never lost/interleaved."
  [instance]
  (future
    (try
      (doseq [line (line-seq (:reader instance))]
        (let [msg (json/parse-string line true)]
          (case (:type msg)
            ;; Result completes a pending request
            "result"
            (when-let [req-id (get-current-request-id instance)]
              (deliver-result! instance req-id msg))

            ;; Assistant content accumulates for current turn
            "assistant"
            (accumulate-content! instance msg)

            ;; System messages (init, etc.)
            "system"
            (handle-system-message! instance msg)

            ;; Error delivered to pending request
            "error"
            (deliver-error! instance msg)

            ;; Unknown - log it
            (log/log! {:level :warn :msg "Unknown message type" :data msg}))))

      (catch Exception e
        (log/log! {:level :error :msg "Reader loop error" :error e})
        (mark-instance-dead! instance)))))

(defn ask
  "Send message and wait for response.
   Thread-safe: multiple callers can ask same instance."
  [instance message]
  (let [request-id (next-request-id (:name instance))
        p (promise)]
    ;; Register pending request
    (swap! (:pending-requests instance) assoc request-id p)
    ;; Write to stdin
    (write-message! instance {:type "user"
                              :message {:role "user" :content message}})
    ;; Block waiting for result (or timeout)
    (let [result (deref p 120000 {:error :timeout})]
      (swap! (:pending-requests instance) dissoc request-id)
      result)))
```

### Benefits

1. **Thread-safe**: Only one reader, multiple writers safe
2. **No desync**: Unsolicited messages handled correctly
3. **Clear error handling**: Reader loop catches process death
4. **Accumulation**: Multi-line responses collected properly

---

## Queue Design

### Why Queues?

1. **Decouple producers/consumers** - Multiple sources can send requests
2. **Flow control** - Don't overwhelm the Claude process
3. **Routing** - Direct responses to appropriate handlers
4. **Buffering** - Handle async nature of Claude responses

### Proposed Queue Structure

```
                    ┌─────────────────────┐
                    │   Claude Process    │
                    │  (persistent subprocess)
                    └─────────────────────┘
                           ▲     │
                           │     │ stdout
                    stdin  │     ▼
                    ┌──────┴─────────────┐
                    │   Request Queue    │ ←── MCP tools, other Claudes
                    │      (FIFO)        │
                    └────────────────────┘

                    ┌────────────────────┐
                    │  Response Router   │ ──► callbacks by request-id
                    │  (by request-id)   │
                    └────────────────────┘
```

### Queue Implementation (Phase 13)

Simple FIFO using Clojure's `clojure.core.async` or atom-based queue:

```clojure
;; Simple atom-based approach
(def request-queue (atom clojure.lang.PersistentQueue/EMPTY))
(def response-handlers (atom {})) ; request-id -> callback/promise

(defn enqueue-request! [request callback]
  (let [request-id (generate-request-id)]
    (swap! response-handlers assoc request-id callback)
    (swap! request-queue conj (assoc request :request-id request-id))
    request-id))
```

### Future: Message Bus (Phase 14+)

More sophisticated routing:

- Topic-based pub/sub
- Multiple subscribers per message
- Message persistence/replay
- Cross-Claude communication

---

## Request-Response Correlation

### Challenge

Claude's stream-json output is:
1. **Multi-line** - Each response spans multiple JSONL lines
2. **Async** - Responses may interleave (with multiple requests)
3. **Stateful** - Need to group lines by conversation turn

### Correlation Strategy

1. **Request ID in metadata** - Include in system prompt or user message
2. **Response grouping** - Collect lines from `system/init` to `result/success`
3. **Callback resolution** - Match completed response to pending callback

```clojure
;; Request structure
{:request-id "claude-main-000001-a3b4c5d6"
 :type "user"
 :message {:role "user" :content "..."}}

;; Track pending requests
(def pending-requests (atom {})) ; request-id -> {:callback ... :started-at ...}

;; Response accumulator
(def response-buffer (atom {})) ; session-id -> current response lines
```

### Timeout Handling

```clojure
(def request-timeout-ms 120000) ; 2 minutes

(defn check-timeouts! []
  (let [now (System/currentTimeMillis)
        timed-out (filter #(> (- now (:started-at %)) request-timeout-ms)
                          (vals @pending-requests))]
    (doseq [req timed-out]
      ((:callback req) {:error :timeout :request-id (:request-id req)})
      (swap! pending-requests dissoc (:request-id req)))))
```

---

## MCP Tool Interface

### Proposed Tools

```clojure
;; Start a new Claude instance
{:name "claude_spawn"
 :description "Start a new Claude subprocess"
 :inputSchema {:type "object"
               :properties {:name {:type "string"}
                           :model {:type "string"}
                           :system-prompt {:type "string"}}
               :required [:name]}}

;; Send message to Claude instance
{:name "claude_message"
 :description "Send a message to a Claude instance and get response"
 :inputSchema {:type "object"
               :properties {:instance {:type "string"}
                           :message {:type "string"}}
               :required [:instance :message]}}

;; List running instances
{:name "claude_list"
 :description "List all running Claude instances"
 :inputSchema {:type "object" :properties {}}}

;; Stop instance
{:name "claude_stop"
 :description "Stop a Claude instance"
 :inputSchema {:type "object"
               :properties {:instance {:type "string"}}
               :required [:instance]}}

;; Fork from existing session
{:name "claude_fork"
 :description "Fork a new Claude from existing session"
 :inputSchema {:type "object"
               :properties {:parent {:type "string"}
                           :name {:type "string"}}
               :required [:parent :name]}}
```

---

## Implementation Phases

### Phase 13A: Core Spawning

- [ ] Create `modules/claude-spawner/` structure
- [ ] Implement process spawn with babashka.process
- [ ] JSONL message parsing/formatting
- [ ] Basic send/receive (blocking)
- [ ] Single instance registry

### Phase 13B: Async & Queues

- [ ] Request queue (FIFO)
- [ ] Request-ID generation
- [ ] Response routing by request-id
- [ ] Callback/promise resolution
- [ ] Timeout handling

### Phase 13C: MCP Integration

- [ ] claude_spawn tool
- [ ] claude_message tool
- [ ] claude_list tool
- [ ] claude_stop tool
- [ ] Integration tests

### Phase 13D: Session Management

- [ ] Session ID tracking from init message
- [ ] Fork support (`--resume`)
- [ ] Session persistence (optional)

### Future Phases

- **Phase 14:** Message bus for multi-agent routing
- **Phase 15:** Cost tracking and budgeting
- **Phase 16:** Load balancing multiple instances

---

## Configuration (from Gemini)

Make paths and defaults configurable:

```clojure
;; config.edn or system.edn
{:claude-manager
 {:claude-path "~/.claude/local/claude"  ; don't hardcode
  :default-model "claude-sonnet-4-5-20250929"
  :request-timeout-ms 120000
  :max-instances 10}}
```

---

## Open Questions

1. **Single vs multi-instance per module?**
   - Single: Simpler, each spawn is independent
   - Multi: Registry-based, can route between instances
   - **Decision:** Multi-instance with registry (needed for orchestration)

2. **Blocking vs async API?**
   - Blocking simpler for MCP tool model
   - Async needed for multi-agent scenarios
   - **Decision:** Blocking `ask` with promise-based internals (async under hood)

3. **Error recovery?**
   - Restart crashed processes automatically?
   - Preserve conversation state?
   - **Decision:** Manual restart via tool, session recovery via `--resume`

4. **Resource limits?**
   - Max concurrent instances?
   - Memory/CPU limits per instance?
   - **Decision:** Configurable max-instances, defer resource limits

5. **Security considerations?**
   - bypassPermissions safe for server context?
   - Sandboxing options?
   - **Decision:** Configurable permission mode, warn in docs about risks

---

## Related Documents

- [IMPLEMENTATION_PLAN.md](../../IMPLEMENTATION_PLAN.md) - Overall project phases
- [clay-noj-ai/context.md](../../../clay-noj-ai/context.md) - Reference implementation docs
- [MCP Specification](https://spec.modelcontextprotocol.io/) - Protocol spec
- [gemini-claude-subprocess-spawning-review.md](../../gemini-claude-subprocess-spawning-review.md) - Gemini's architecture review

---

## Alternative Considered: Claude-as-a-Server

Instead of managing raw processes, could wrap Claude in its own MCP server. However, Claude CLI is a text-stream tool, and the Subprocess Manager approach is more flexible for orchestration and session management.

**Decision:** Stick with subprocess approach.

---

*This is a living document. Update as design decisions are made.*
