# AI Experts Framework Review

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** 2025-02-25
**Target Document:** `docs/design/ai-experts-framework.md`

## 1. Executive Summary

The "AI Experts Framework" design is a robust and forward-thinking architecture that effectively leverages the "Curriculum" concept to create specialized, reusable AI agents. The proposed phased implementation (File-based MVP → Message Bus → Dynamic Orchestration) is pragmatic and aligns well with the current capabilities of the `bb-mcp-server`.

The decision to use **Dedicated MCP Servers** (Option B) is particularly strong, as it capitalizes on the recent "Triple Interface" refactoring (specifically the HTTP transport) to provide isolation, flexibility, and scalability.

## 2. Architectural Validation

### Strengths
*   **Curriculum-as-Code**: Defining experts via `manifest.edn` and associated markdown files is an excellent "Infrastructure as Code" pattern for AI. It makes experts versionable, shareable, and easy to modify.
*   **Hybrid Curriculum Loading**: The strategy of putting "Essential" knowledge in the system prompt and "Reference" knowledge behind an MCP tool is a highly effective optimization for context window management and cost.
*   **Dedicated MCP Servers**: This architecture solves the "Context Pollution" problem elegantly. By giving each expert a curated set of tools via its own HTTP endpoint, we reduce token usage and the risk of tool confusion.
*   **Phased Database Adoption**: The recommendation to start with files (Phase 13E) and introduce Datalevin (Phase 13F/14) is the correct engineering trade-off. It allows for rapid prototyping without getting bogged down in schema design too early.

### Alignment with Existing Work
*   **Transport Modularity**: The recent work on `bb-mcp-server.main` to support `--http` and `--stdio` seamlessly supports the "Dedicated MCP Server" pattern. The server is ready to be spawned as a subprocess or run as a standalone service for this purpose.
*   **Claude Subprocesses**: The design builds logically on the `claude-subprocess-spawning-architecture.md` review, moving from "how to spawn" to "what to spawn".

## 3. Critical Questions & Recommendations

### A. Inter-Agent Communication & The "Driver" Pattern
The design mentions a `core.async` message bus:
```clojure
;; Consumer
(async/go-loop []
  (when-let [msg (async/<! message-bus)]
    (route-message msg)
    (recur)))
```
**Critique**: Claude instances are fundamentally *reactive*. They wait for a user message, process it, and return a response. They do not inherently "listen" to a bus.
**Recommendation**: Explicitly define the **"Expert Driver"** component. This is the Clojure code running in the main process that wraps the Claude subprocess.
*   The *Driver* subscribes to the `core.async` bus.
*   When a message arrives, the *Driver* formats it as a "User" message (or a "System" event) and sends it to the Claude subprocess via stdin (or the API).
*   The *Driver* handles the Claude response and publishes it back to the bus.

### B. State Management & History
**Question**: Where does the conversation history live?
*   **Option A**: Inside the Claude process (implicit).
*   **Option B**: Managed by the BB Server (explicit).
**Recommendation**: **Option B**. The BB Server should maintain the conversation history (list of messages).
*   **Why**: This allows you to "pause" an expert (kill the process) and "resume" it later by replaying the history (or caching the context). It also enables the "Orchestrator" to inspect the expert's reasoning chain without asking the expert.

### C. Security of HTTP Servers
**Observation**: The "Dedicated MCP Servers" using HTTP are great, but introduce network sockets.
**Recommendation**:
*   Ensure these servers bind strictly to `127.0.0.1` (localhost) by default to prevent network exposure.
*   Use the `pid_util.clj` (or similar) to rigorously track and kill these child processes. Orphaned MCP servers on random ports will be a nuisance.

### D. The "Curriculum" vs. "Persona"
**Refinement**: The term "Curriculum" implies *learning material*. The document uses it to mean *definition + knowledge*.
*   Consider distinguishing between:
    *   **Profile/Manifest**: The static definition (ID, model, tools).
    *   **Context/Knowledge**: The docs/markdown files.
    *   **Persona**: The system prompt instructions.
    *   **Curriculum**: The *combination* of these that creates the expert.
    *   *Note*: The current terminology is fine, but be consistent.

## 4. Implementation Suggestions

### 1. Refine `start-dedicated-mcp-server!`
Ensure this function leverages the new `bb-mcp-server.main` entry point.
```clojure
(defn start-dedicated-mcp-server! [domain port]
  (let [cmd ["bb" "-m" "bb-mcp-server.main" "--http" (str port) "--config" (config-path domain)]]
    ;; ... spawn and wait for health check ...
    ))
```

### 2. The "Expert" Record
Formalize the "Expert" entity in Clojure code early.
```clojure
(defrecord Expert [id process port bus-channel history])
```
This will make managing the lifecycle (start/stop/restart) much easier than passing around loose maps.

### 3. Tool Registry Integration
When an expert starts, it might need to "register" its own capabilities with the Orchestrator.
*   **Idea**: The Orchestrator could have a dynamic tool `delegate_to_expert` that lists currently active experts.

## 5. Conclusion
The **AI Experts Framework** is approved for implementation. It is a logical next step that transforms the `bb-mcp-server` from a simple tool provider into a sophisticated Multi-Agent System (MAS) platform.

**Next Step**: Proceed with **Phase 13E (File-Based Expert Registry)**.
