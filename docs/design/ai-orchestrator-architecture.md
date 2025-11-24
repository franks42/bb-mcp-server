# AI Orchestrator - Multi-Provider Architecture & Design

**Status:** Architecture Design Phase
**Phase:** 13A Complete (Claude-only prototype), 13B In Progress (Multi-provider refactor)
**Date:** 2025-11-24
**Reviewed by:** User (architectural vision), Gemini (subprocess patterns)

---

## Overview

This document describes the architecture for a **multi-provider AI orchestration system** built on bb-mcp-server. The system manages AI instances from multiple providers (Claude subprocess, OpenAI HTTP, Anthropic HTTP, local Ollama, etc.) through a unified interface.

### Goals

1. **Provider-agnostic orchestration** - Manage any AI regardless of access method
2. **Unified API** - `start-instance!`, `ask`, `stop-instance!` work with all providers
3. **Protocol abstraction** - Support subprocess stdio, HTTP REST, WebSocket, etc.
4. **Multi-agent coordination** - Route tasks to appropriate AI based on capabilities
5. **Extensibility** - Add new providers without modifying orchestrator core

---

## Architecture Vision

### Layered Module Design

```
┌─────────────────────────────────────────────────────────┐
│                    MCP Tools Layer                       │
│  (claude_spawn, ai_ask, ai_list, ai_stop, etc.)        │
└─────────────────────────────────────────────────────────┘
                           ▲
                           │
┌─────────────────────────────────────────────────────────┐
│              ai-orchestrator (Core Module)               │
│  ┌────────────┐  ┌──────────┐  ┌────────────────┐     │
│  │  Registry  │  │ Protocol │  │ Message Router │     │
│  │ (metadata) │  │(multimethod)│ │   (correlation)│     │
│  └────────────┘  └──────────┘  └────────────────┘     │
└─────────────────────────────────────────────────────────┘
                           ▲
        ┌──────────────────┼──────────────────┐
        │                  │                   │
┌───────┴────────┐  ┌──────┴──────┐  ┌────────┴────────┐
│claude-subprocess│  │openai-http  │  │anthropic-http   │
│   (provider)    │  │  (provider) │  │   (provider)    │
│                 │  │             │  │                 │
│ • spawn process │  │ • HTTP REST │  │ • HTTP REST     │
│ • JSONL stdio   │  │ • streaming │  │ • streaming     │
│ • reader loop   │  │ • API keys  │  │ • API keys      │
└─────────────────┘  └─────────────┘  └─────────────────┘
```

### Separation of Concerns

**ai-orchestrator (infrastructure):**
- Provider-agnostic instance registry
- Request/response correlation
- Message routing
- Lifecycle management (start, stop)
- Provider protocol definition (multimethods)

**Provider modules (implementations):**
- Provider-specific connection/transport
- Protocol handling (JSONL, HTTP, WebSocket)
- Authentication (API keys, tokens)
- Model selection
- Cost tracking

---

## Module Structure

### Module: ai-orchestrator

**Location:** `modules/ai-orchestrator/`

**Responsibilities:**
- Maintain provider-agnostic registry of AI instances
- Define provider protocol (multimethods for extensibility)
- Route messages to/from instances
- Correlate async responses to requests
- Expose public API: `start-instance!`, `ask`, `stop-instance!`, `list-instances`

**Files:**
```
modules/ai-orchestrator/
├── module.edn
├── src/ai_orchestrator/
│   ├── core.clj         # Public API
│   ├── registry.clj     # Instance registry (provider-agnostic)
│   ├── protocol.clj     # Provider protocol (multimethods)
│   └── router.clj       # Message routing & correlation
└── test/
    └── ai_orchestrator/
        └── core_test.clj
```

**Registry Schema:**
```clojure
;; Instance structure (stored in registry)
{:name "my-claude"
 :provider-type :claude-subprocess    ; or :openai-http, :anthropic-http
 :protocol :jsonl-stream              ; or :http-rest, :http-sse
 :model "claude-3-5-haiku-20241022"
 :capabilities #{:chat :tools :streaming}
 :transport {...}                      ; Provider-specific state
 :session-id (atom nil)               ; Conversation context
 :pending-requests (atom {})          ; Request correlation
 :created-at 1700000000000
 :metadata {...}}                      ; Provider-specific metadata
```

### Module: claude-subprocess-provider

**Location:** `modules/claude-subprocess-provider/`

**Responsibilities:**
- Spawn Claude CLI subprocess
- Manage stdio streams (dedicated reader loop)
- Parse/format JSONL messages
- Implement ai-orchestrator protocol

**Files:**
```
modules/claude-subprocess-provider/
├── module.edn           # Depends on: ai-orchestrator
├── src/claude_subprocess/
│   ├── core.clj         # Implements protocol multimethods
│   ├── process.clj      # Process spawning & reader loop
│   └── jsonl.clj        # JSONL protocol handling
└── test/
    ├── mock_claude.clj  # JSONL echo mock
    └── claude_subprocess/
        └── core_test.clj
```

**Protocol Implementation:**
```clojure
(ns claude-subprocess.core
  (:require [ai-orchestrator.protocol :as proto]))

(defmethod proto/create-instance :claude-subprocess
  [{:keys [name cmd model] :as opts}]
  ;; Spawn process, start reader loop
  ;; Return transport map
  ...)

(defmethod proto/send-message :claude-subprocess
  [instance message]
  ;; Write JSONL to stdin
  ...)

(defmethod proto/stop-instance :claude-subprocess
  [instance]
  ;; Kill process, cleanup
  ...)
```

### Module: openai-http-provider (Future)

**Location:** `modules/openai-http-provider/`

**Responsibilities:**
- Connect to OpenAI-compatible HTTP APIs
- Handle HTTP REST requests
- Support streaming responses (SSE)
- API key management

**Protocol Implementation:**
```clojure
(defmethod proto/create-instance :openai-http
  [{:keys [name base-url api-key model] :as opts}]
  ;; Create HTTP client, validate connection
  {:provider-type :openai-http
   :protocol :http-rest
   :transport {:base-url base-url
               :api-key api-key
               :http-client ...}
   ...})

(defmethod proto/send-message :openai-http
  [instance message]
  ;; POST /v1/chat/completions
  ...)
```

**Testing Strategy:**
Use openai-http-provider to connect to another Claude instance via HTTP to validate the infrastructure with the same AI model.

---

## Provider Protocol Definition

### Multimethods (ai-orchestrator/protocol.clj)

```clojure
(ns ai-orchestrator.protocol
  "Provider protocol using Clojure multimethods for extensibility.")

;; ============================================================================
;; Protocol Multimethods
;; ============================================================================

(defmulti create-instance
  "Create a new AI instance.

   Dispatches on :provider-type in opts map.

   Arguments:
     opts - Map with:
            :name           - Unique instance name (required)
            :provider-type  - Provider type keyword (required)
            :model          - Model identifier (optional)
            ... provider-specific options

   Returns:
     Instance map suitable for registry, or {:error ...}"
  :provider-type)

(defmulti send-message
  "Send a message to an AI instance.

   Dispatches on :provider-type in instance.

   Arguments:
     instance - Instance map from registry
     message  - Message string

   Returns:
     true on success, false on failure"
  (fn [instance _message] (:provider-type instance)))

(defmulti stop-instance
  "Stop an AI instance and cleanup resources.

   Dispatches on :provider-type in instance.

   Arguments:
     instance - Instance map from registry

   Returns:
     true on success, false if already stopped"
  :provider-type)

(defmulti get-capabilities
  "Get capabilities of this provider type.

   Returns:
     Set of capability keywords, e.g. #{:chat :tools :streaming :vision}"
  :provider-type)

;; ============================================================================
;; Default Implementations (errors for missing providers)
;; ============================================================================

(defmethod create-instance :default
  [{:keys [provider-type] :as opts}]
  {:error (str "Unknown provider type: " provider-type)
   :available-providers (keys (methods create-instance))})

(defmethod send-message :default
  [instance _message]
  (throw (ex-info "send-message not implemented for provider"
                  {:provider-type (:provider-type instance)})))

(defmethod stop-instance :default
  [instance]
  (throw (ex-info "stop-instance not implemented for provider"
                  {:provider-type (:provider-type instance)})))
```

### Alternative: Clojure Protocols (vs Multimethods)

**Option A: Multimethods (recommended for Phase 13B)**
- ✅ Simple dispatch on `:provider-type` keyword
- ✅ Easy to add providers (no recompile needed)
- ✅ Works well with data-oriented design
- ❌ No compile-time type checking
- ❌ Single dispatch only (not a concern here)

**Option B: Clojure Protocols**
- ✅ Faster dispatch
- ✅ Multiple dispatch possible
- ❌ Requires provider record types (more ceremony)
- ❌ Less flexible for dynamic providers

**Decision:** Use multimethods for simplicity and extensibility. Protocols can be added later if performance becomes an issue.

---

## Public API Design

### ai-orchestrator/core.clj

```clojure
(ns ai-orchestrator.core
  "Multi-provider AI orchestration - Public API."
  (:require [ai-orchestrator.protocol :as proto]
            [ai-orchestrator.registry :as registry]
            [ai-orchestrator.router :as router]))

(defn start-instance!
  "Start a new AI instance.

   Arguments:
     name - Unique instance name
     opts - Provider options:
            :provider-type - Required (:claude-subprocess, :openai-http, etc.)
            :model         - Model identifier (provider-specific)
            ... additional provider-specific options

   Examples:
     ;; Claude subprocess
     (start-instance! \"researcher\"
       {:provider-type :claude-subprocess
        :cmd [\"claude\" \"--model\" \"claude-3-5-haiku-20241022\"]})

     ;; OpenAI HTTP
     (start-instance! \"gpt-helper\"
       {:provider-type :openai-http
        :base-url \"https://api.openai.com/v1\"
        :api-key (System/getenv \"OPENAI_API_KEY\")
        :model \"gpt-4-turbo\"})

   Returns:
     Instance map on success, {:error ...} on failure."
  [name opts]
  (let [opts-with-name (assoc opts :name name)
        instance (proto/create-instance opts-with-name)]
    (if (:error instance)
      instance
      (registry/register-instance! instance))))

(defn ask
  "Send message to AI instance and wait for response.

   Works with any provider - protocol abstraction handled internally.

   Arguments:
     name    - Instance name
     message - Message string

   Returns:
     Response map: {:content \"...\" :cost_usd ... :duration_ms ...}
     Or {:error true :message \"...\"} on failure."
  [name message]
  (if-let [instance (registry/get-instance name)]
    (router/send-and-await instance message)
    {:error true :message (str "Instance not found: " name)}))

(defn stop-instance!
  "Stop an AI instance.

   Arguments:
     name - Instance name

   Returns:
     true on success, false if not found."
  [name]
  (if-let [instance (registry/get-instance name)]
    (do
      (proto/stop-instance instance)
      (registry/unregister-instance! name)
      true)
    false))

(defn list-instances
  "List all active AI instances.

   Returns:
     Sequence of maps: {:name :provider-type :model :created-at :alive?}"
  []
  (registry/list-instances))

(defn get-instance-info
  "Get detailed info about an instance.

   Arguments:
     name - Instance name

   Returns:
     Instance metadata map or nil."
  [name]
  (registry/get-instance-info name))
```

---

## Implementation Phases

### Phase 13A: Claude-Only Prototype ✅ COMPLETE

**What was implemented:**
- Created `modules/claude-manager/` (monolithic, Claude-only)
- Dedicated Reader Loop pattern for subprocess stdio
- Promise-based async with request correlation
- JSONL protocol handling
- Tests: 12 tests, 23 assertions passing

**Current Status:** Works but not extensible to other providers.

### Phase 13B: Multi-Provider Refactor (IN PROGRESS)

**Goal:** Extract orchestration infrastructure and make claude-manager a provider plugin.

**Tasks:**

1. **Create ai-orchestrator module**
   - [ ] Extract registry from claude-manager (make provider-agnostic)
   - [ ] Define protocol multimethods (create-instance, send-message, stop-instance)
   - [ ] Extract router/correlation logic (provider-agnostic)
   - [ ] Public API with provider dispatch

2. **Refactor claude-manager → claude-subprocess-provider**
   - [ ] Implement protocol multimethods for :claude-subprocess
   - [ ] Keep process spawning and JSONL logic
   - [ ] Add dependency on ai-orchestrator module
   - [ ] Update tests to work through orchestrator API

3. **Rename functions for consistency**
   - [ ] `spawn!` → `start-instance!`
   - [ ] `kill!` → `stop-instance!`
   - [ ] Update all references

4. **Integration testing**
   - [ ] Test claude-subprocess-provider through orchestrator
   - [ ] Verify registry works with provider metadata
   - [ ] Ensure backward compatibility with existing tests

### Phase 13C: OpenAI HTTP Provider (Validation)

**Goal:** Prove multi-provider design by adding second provider.

**Tasks:**
- [ ] Create `modules/openai-http-provider/`
- [ ] Implement protocol multimethods for :openai-http
- [ ] HTTP client with /v1/chat/completions endpoint
- [ ] API key management
- [ ] **Test with Claude via HTTP** - Use OpenAI-compatible endpoint to talk to Claude instance
- [ ] Verify orchestrator handles both providers seamlessly

**Why test with Claude via HTTP?**
Using the same AI model (Claude) accessed via different transports (subprocess vs HTTP) validates the infrastructure abstraction without introducing LLM behavior variability.

### Phase 13D: MCP Tool Exposure

- [ ] Register MCP tools in ai-orchestrator module.edn
- [ ] `ai_start` - Start instance (any provider)
- [ ] `ai_ask` - Send message (provider-agnostic)
- [ ] `ai_list` - List instances with provider info
- [ ] `ai_stop` - Stop instance
- [ ] Provider selection in tool arguments

### Phase 13E: Advanced Features

- [ ] Provider aliases (e.g., "cheap" → haiku, "smart" → opus)
- [ ] Cost tracking across providers
- [ ] Automatic failover/retry
- [ ] Load balancing
- [ ] Session forking (Claude `--resume`)

---

## Configuration Design

### Module Configuration (system.edn)

```clojure
{:modules
 {:ai-orchestrator
  {:max-instances 20
   :default-timeout-ms 120000}

  :claude-subprocess-provider
  {:claude-path "~/.claude/local/claude"
   :default-model "claude-3-5-haiku-20241022"
   :default-args ["-p" "--verbose"
                  "--input-format" "stream-json"
                  "--output-format" "stream-json"
                  "--permission-mode" "bypassPermissions"]}

  :openai-http-provider
  {:base-url "https://api.openai.com/v1"
   :api-key-env "OPENAI_API_KEY"  ; Read from environment
   :default-model "gpt-4-turbo"
   :timeout-ms 30000}}}
```

### Provider Aliases (Future)

```clojure
;; User-defined aliases for convenience
{:provider-aliases
 {:cheap {:provider-type :claude-subprocess
          :model "claude-3-5-haiku-20241022"}
  :smart {:provider-type :openai-http
          :model "gpt-4-turbo"}
  :private {:provider-type :ollama-http
            :model "llama-3-70b"}}}

;; Usage:
(start-instance! "helper" {:alias :cheap})
```

---

## Design Decisions

### 1. Why Multimethods?

**Decision:** Use multimethods for provider dispatch.

**Alternatives considered:**
- Protocols (more ceremony, not needed)
- Conditional dispatch (not extensible)
- Component library (overkill for this use case)

**Rationale:** Multimethods provide clean extensibility without requiring provider modules to depend on each other. New providers just `defmethod` the protocol functions.

### 2. Registry Schema - Provider Metadata

**Decision:** Store `:provider-type`, `:protocol`, `:capabilities` in registry.

**Rationale:**
- Enables routing decisions ("which instance supports vision?")
- Allows provider-agnostic queries
- Facilitates debugging ("what providers are running?")

### 3. Transport Abstraction

**Decision:** Store provider-specific state in `:transport` map.

**Rationale:**
- Orchestrator doesn't need to understand subprocess vs HTTP
- Providers manage their own connection state
- Clean separation of concerns

### 4. API Consistency - start-instance! vs spawn!

**Decision:** Rename to `start-instance!` for consistency with module lifecycle.

**Rationale:**
- Matches standard `start`/`stop` pattern
- "instance" clarifies we're managing AI instances
- Reduces cognitive load for users

---

## Testing Strategy

### Unit Tests (per module)

**ai-orchestrator:**
- Registry operations (register, unregister, list)
- Router correlation (request-id matching)
- Protocol dispatch (mock providers)

**claude-subprocess-provider:**
- Process spawning
- JSONL parsing/formatting
- Reader loop message handling
- Mock subprocess testing

**openai-http-provider:**
- HTTP client creation
- API key handling
- Request/response formatting
- Mock HTTP server testing

### Integration Tests

**Cross-provider validation:**
1. Start Claude subprocess instance
2. Start OpenAI HTTP instance (pointing to Claude)
3. Send same question to both
4. Verify both respond (infrastructure works)
5. Stop both instances

**Multi-instance orchestration:**
1. Start 3 instances (2 Claude subprocess, 1 HTTP)
2. Send messages to each
3. Verify correlation (responses match requests)
4. List instances (shows all 3)
5. Stop all

---

## Migration Path from Phase 13A

### Current State (claude-manager)

```
modules/claude-manager/
├── src/claude_manager/
│   ├── core.clj         # spawn!, ask, kill!
│   ├── process.clj      # subprocess + reader loop
│   └── registry.clj     # claude-specific registry
```

### Target State (Phase 13B+)

```
modules/ai-orchestrator/
├── src/ai_orchestrator/
│   ├── core.clj         # start-instance!, ask, stop-instance!
│   ├── registry.clj     # provider-agnostic registry
│   ├── protocol.clj     # multimethods
│   └── router.clj       # correlation

modules/claude-subprocess-provider/
├── src/claude_subprocess/
│   ├── core.clj         # implements protocol
│   ├── process.clj      # subprocess + reader loop
│   └── jsonl.clj        # JSONL handling
```

### Migration Steps

1. **Create ai-orchestrator module**
   - Copy registry.clj, make provider-agnostic
   - Create protocol.clj with multimethods
   - Create router.clj with correlation logic

2. **Rename claude-manager → claude-subprocess-provider**
   - Update module.edn to depend on ai-orchestrator
   - Implement protocol multimethods
   - Remove registry (use orchestrator's)

3. **Update tests**
   - Test claude-subprocess-provider through orchestrator API
   - Keep mock_claude.clj for subprocess testing

4. **Backward compatibility**
   - Deprecate old function names (spawn!, kill!)
   - Add aliases for transition period
   - Update documentation

---

## Related Documents

- [IMPLEMENTATION_PLAN.md](../../IMPLEMENTATION_PLAN.md) - Overall project phases
- [clay-noj-ai/context.md](../../../clay-noj-ai/context.md) - Claude subprocess prototype
- [claude-subprocess-spawning-architecture.md](./claude-subprocess-spawning-architecture.md) - Original Claude-only design
- [MCP Specification](https://spec.modelcontextprotocol.io/) - Protocol spec
- [gemini-claude-subprocess-spawning-review.md](../../gemini-claude-subprocess-spawning-review.md) - Gemini's review

---

## Open Questions

1. **Should we support dynamic provider registration at runtime?**
   - Allows loading provider modules on demand
   - More complex discovery mechanism
   - Defer to Phase 14+

2. **How to handle provider-specific errors uniformly?**
   - Define error code taxonomy (:timeout, :auth-failed, :rate-limited)
   - Map provider errors to common codes
   - Include provider-specific details in :data

3. **Should orchestrator track cost/usage?**
   - Some providers report cost (Claude result messages)
   - Others don't (local Ollama)
   - Track when available, make optional

4. **Multi-turn conversations - who manages context?**
   - Subprocess: Claude CLI manages via session-id
   - HTTP: Need to track message history ourselves
   - Orchestrator provides optional context management

---

*This is a living document. Update as design evolves.*
