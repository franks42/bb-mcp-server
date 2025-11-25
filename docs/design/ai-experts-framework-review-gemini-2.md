# AI Experts Framework Review (Iteration 2)

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** 2025-11-24
**Scope:** `port-management-architecture.md`, `ai-orchestrator-architecture.md`, `ai-experts-framework.md`, `IMPLEMENTATION_PLAN.md`

## 1. Executive Summary

The updated design documents present a cohesive and well-structured architecture for a sophisticated Multi-Agent System (MAS). The separation of concerns is excellent:
*   **Infrastructure Layer**: `port-registry` handles resource allocation.
*   **Orchestration Layer**: `ai-orchestrator` abstracts the AI providers (brains).
*   **Application Layer**: `ai-experts-framework` defines the agents (personas + tools + knowledge).

The transition from a simple `claude-manager` to a generic `ai-orchestrator` is a necessary evolution to support future providers (OpenAI, Ollama) and aligns perfectly with the "Expert" concept.

## 2. Component Analysis

### A. Port Management (`port-management-architecture.md`)
*   **Verdict**: **Approved**.
*   **Strengths**: The file-based persistence (`ports.edn`) is simple and effective for a single-node system. The range-based allocation strategy (19880-19999 for experts) prevents conflicts with standard services.
*   **Recommendation**: Ensure the `release-port!` function is robust. If a process crashes without releasing the port, the registry might think it's in use. Consider a "GC" mechanism that checks if the PID associated with a port is still alive (using `pid_util.clj`) when loading the registry.

### B. AI Orchestrator (`ai-orchestrator-architecture.md`)
*   **Verdict**: **Approved**.
*   **Strengths**: The plugin architecture using multimethods (`defmulti start-instance!`) is the "Clojure way" and allows for easy extensibility.
*   **Refinement**: The document mentions `claude-manager` will be refactored. Ensure that the existing "Dedicated Reader Loop" pattern (which is robust) is preserved in the `claude-subprocess` provider implementation. Don't lose the stability gains from Phase 13A during the refactor.

### C. AI Experts Framework (`ai-experts-framework.md`)
*   **Verdict**: **Approved with Comments**.
*   **Integration**: This component effectively ties the others together. An "Expert" is essentially:
    *   1 x AI Instance (via `ai-orchestrator`)
    *   1 x Dedicated MCP Server (via `bb-mcp-server.main` + `port-registry`)
    *   1 x Driver (Clojure logic)
*   **Observation**: The "Expert Driver" is the critical glue code. It needs to manage the lifecycle of *both* the AI process and the MCP server process.

## 3. Implementation Plan Review

The `IMPLEMENTATION_PLAN.md` has been updated to reflect these designs. The sequence is logical:

1.  **Phase 13B: Multi-Provider Refactor** (Refactor `claude-manager` -> `ai-orchestrator`)
2.  **Phase 13C: OpenAI Provider** (Validation of generic interface)
3.  **Phase 13E: Expert Registry MVP** (The actual framework)

**Critical Path Adjustment**:
You need the **Port Registry** before you can implement the **Dedicated MCP Servers** for the Experts in Phase 13E.
*   **Action**: Add a "Phase 13-Port" or include it in Phase 13E.1. I recommend making it a small standalone phase or the first step of 13E.

## 4. Technical Recommendations

### 1. The "Zombie Port" Problem
In `port-registry`, implement a `validate-registry!` function that runs on startup:
```clojure
(defn validate-registry! []
  (let [registry (load-registry)]
    (doseq [[port {:keys [pid]}] (:allocations registry)]
      (when-not (pid-util/process-alive? pid)
        (log/warn "Releasing zombie port" port "for dead PID" pid)
        (release-port! port)))))
```
This prevents the registry from filling up with "used" ports from crashed sessions.

### 2. Expert Lifecycle Coordination
When `start-expert!` is called:
1.  Allocate Port (via `port-registry`).
2.  Start MCP Server (subprocess on Port).
3.  Wait for MCP Server Health Check (crucial!).
4.  Start AI Instance (via `ai-orchestrator`), passing the MCP Server URL in the system prompt or tool config.
5.  Return Expert Record.

If step 3 or 4 fails, ensure step 2 (MCP Server) is killed and step 1 (Port) is released. Use a `try/finally` or component management pattern.

### 3. Testing Strategy
*   **Port Registry**: Unit tests with mock PIDs.
*   **Orchestrator**: Integration tests with the `mock-claude` provider (already exists, just move it).
*   **Experts**: End-to-end test where an expert "echoes" a tool call.

## 5. Conclusion

The architecture is sound. The addition of `port-registry` fills a critical gap for the "Dedicated MCP Server" pattern. The plan is ready for execution, subject to the minor adjustment of scheduling the Port Registry implementation.

**Green light to proceed.**
