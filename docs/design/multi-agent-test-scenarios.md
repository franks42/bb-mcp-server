# Multi-Agent Test Scenarios

This document outlines advanced test scenarios to validate and push the capabilities of the AI Expert Framework.

## 1. "The Debate Team" (Team Broadcast & Aggregation)
**Goal:** Validate `message-bus.teams` namespace, broadcast capabilities, and fan-out/fan-in concurrency.

### Scenario
1.  **Setup**:
    *   Create a `team` named `:future-council`.
    *   Spawn 3 experts:
        *   `optimist` (Claude Haiku): Always sees the bright side.
        *   `pessimist` (Claude Haiku): Always sees the risks.
        *   `realist` (Claude Haiku): Balances both views.
    *   Add all three to the `:future-council` team.
2.  **Execution**:
    *   A "Moderator" (script or another agent) sends a `team-broadcast!` message: *"What is the impact of AI on software engineering?"*
    *   The message bus delivers this to all 3 experts simultaneously.
3.  **Synchronization**:
    *   The Moderator listens for replies on the team channel.
    *   It must collect exactly 3 responses (one from each member).
4.  **Synthesis**:
    *   Once all responses are in, the Moderator sends them to a `synthesizer` (Claude Sonnet) to create a final consensus report.

### Key Technical Validations
*   `message-bus.teams/create-team`
*   `message-bus.teams/team-broadcast!`
*   Async concurrency (agents working in parallel).
*   State management (tracking pending replies).

---

## 2. "The Dynamic Manager" (Recursive Spawning)
**Goal:** Validate `expert-registry` dynamic capabilities and "Agent-as-Manager" pattern.

### Scenario
1.  **Setup**:
    *   Start a single `project-manager` expert (Claude Sonnet).
    *   Give it access to the `spawn-expert!` tool (via `ai-orchestrator-tools`).
2.  **Execution**:
    *   User gives a complex task: *"Create a Python script that calculates Fibonacci numbers and write a README for it."*
    *   The `project-manager` analyzes the task and decides it needs help.
    *   It calls `ai_start_instance` (or `spawn_expert`) to create:
        *   `python-coder`
        *   `tech-writer`
3.  **Delegation**:
    *   The Manager sends the coding task to `python-coder`.
    *   The Manager sends the documentation task to `tech-writer`.
4.  **Completion**:
    *   The Manager receives the outputs, combines them, and presents the final result to the user.
    *   The Manager calls `ai_stop_instance` to clean up the workers.

### Key Technical Validations
*   Agents using MCP tools to control the infrastructure.
*   Dynamic resource allocation.
*   Hierarchical task delegation.

---

## 3. "The Tool Relay" (Chain of Responsibility)
**Goal:** Validate context passing, tool usage, and data flow between agents.

### Scenario
1.  **Setup**:
    *   Spawn 3 specialized agents:
        *   `researcher`: Has access to `http` tools.
        *   `analyst`: Good at data interpretation.
        *   `poet`: Good at creative writing.
2.  **Execution**:
    *   **Step 1**: `researcher` is asked to "Get the current weather in Tokyo". It uses a tool (or mock) to get raw JSON data.
    *   **Step 2**: `researcher` passes the *raw JSON* to `analyst` via the message bus.
    *   **Step 3**: `analyst` extracts key trends (e.g., "It is raining and cold") and passes this insight to `poet`.
    *   **Step 4**: `poet` writes a haiku about the weather in Tokyo.
3.  **Verification**:
    *   The final output must accurately reflect the initial raw data, proving context was preserved across the chain.

### Key Technical Validations
*   Passing structured data (JSON) over the message bus.
*   Tool output handling.
*   Sequential dependency management.

---

## 4. "The Self-Healing System" (Error Recovery)
**Goal:** Validate error handling, timeouts, and supervision strategies.

### Scenario
1.  **Setup**:
    *   Spawn a `worker` agent that is programmed to be "flaky" (e.g., via a prompt instruction to randomly fail or timeout).
    *   Spawn a `supervisor` agent.
2.  **Execution**:
    *   The `supervisor` sends a task to the `worker`.
    *   The `worker` simulates a crash or timeout.
3.  **Recovery**:
    *   The `supervisor` detects the failure (via message bus timeout or error reply).
    *   The `supervisor` decides on a strategy:
        *   **Retry**: Send the message again.
        *   **Restart**: Kill and respawn the worker.
        *   **Escalate**: Report failure to the user.
    *   The `supervisor` executes the recovery action.

### Key Technical Validations
*   Message bus timeouts (`ask` with `:timeout-ms`).
*   Error propagation.
*   Supervisor pattern implementation.
