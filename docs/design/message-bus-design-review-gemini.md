# Message Bus Design Review

**Reviewer:** Gemini 3 Pro (Preview)
**Date:** 2025-11-25
**Target Document:** `docs/design/message-bus-design.md`

## 1. Executive Summary

The design document provides a comprehensive analysis of message bus implementation options for the AI Expert Framework. The recommendation to start with **Option 3 (Atoms + Promises)** for the MVP is **sound and pragmatic**. It prioritizes simplicity, debuggability, and Babashka compatibility, which are the correct constraints for the current phase.

The proposed API surface is clean and covers the essential patterns: Pub/Sub, Request/Response, and Team Isolation.

## 2. Architectural Validation

### Strengths
*   **Babashka Compatibility**: Explicitly verifying compatibility with `bb` primitives is crucial. Avoiding JVM-only libraries like Manifold prevents future headaches.
*   **Simplicity First**: Choosing atoms/promises over `core.async` for the MVP reduces cognitive load. `core.async` is powerful but can be opaque to debug (especially in `bb` where tooling is limited).
*   **Team Isolation**: The namespacing strategy (`team:id:topic`) is a simple but effective way to handle multi-tenant communication without complex routing logic.
*   **Introspection**: Built-in message logging and subscriber listing will be invaluable for debugging multi-agent interactions.

### Weaknesses / Risks
*   **Unbounded Queues**: The "Atoms + Promises" approach has no backpressure. If an expert floods the bus, the `future` thread pool could become saturated.
    *   *Mitigation*: The implementation plan includes `try/catch` blocks inside the `future` to log errors. This is essential.

## 3. Critical Recommendations

### A. Request/Response Correlation
The design uses a unique topic for replies: `reply-topic (keyword (str "reply-" request-id))`.
*   **Optimization**: Instead of creating a new subscription for *every* request (which modifies the `subscribers` atom frequently), consider a single **Reply Bus**.
    *   Experts subscribe to their own ID: `expert:my-id`.
    *   Requests include `reply-to: expert:sender-id` and `correlation-id: uuid`.
    *   The sender listens on its own ID and matches the `correlation-id` to the pending promise.
    *   *Benefit*: Reduces churn on the subscription registry.

### B. The "Expert Driver" Integration
The document describes the bus in isolation. It needs to explicitly mention how the **Expert Driver** (from the previous review) connects to it.
*   **Requirement**: The `ai-experts-framework` needs a bridge component that:
    1.  Subscribes to the expert's topic.
    2.  Forwards messages to the Claude process (via `ai-orchestrator`).
    3.  Publishes Claude's response back to the bus.

### C. Timeout Management
The `ask` function uses `deref` with a timeout.
*   **Edge Case**: If a timeout occurs, the subscription to the reply topic should be cleaned up. The proposed code does this in `finally`, which is correct.
*   **Zombie Processes**: If an expert crashes while processing a request, the sender times out. Ensure the orchestrator can detect this and perhaps send a "system error" to the bus.

## 4. Implementation Suggestions

### 1. Refine `ask` Implementation
The proposed `ask` creates a temporary subscription.
```clojure
(defn ask [topic message timeout]
  (let [reply-topic ...]
    (subscribe! reply-topic ...) ;; <--- Writes to atom
    ...
    (finally (unsubscribe! ...)))) ;; <--- Writes to atom
```
If you have high throughput, this atom contention might be a bottleneck.
**Alternative**: Use a global `response-router` that maps `request-id -> promise`.
1.  Global `(def pending-requests (atom {}))`
2.  Single subscription to `replies` topic.
3.  `ask` registers `request-id` in atom.
4.  `replies` handler looks up promise and delivers.
This is O(1) atom swap vs O(N) subscription scan.

### 2. Structured Messages
Enforce a schema for messages early.
```clojure
{:id "uuid"
 :topic :keyword
 :from "sender-id"
 :type :command|:event|:query|:response
 :payload {...}
 :ts 123456789}
```
This helps with debugging and routing.

## 5. Conclusion

The design is **Approved**. Proceed with **Option 3**.

**Action Items:**
1.  Create `modules/message-bus/`.
2.  Implement the core `subscribe!`, `publish!`, `ask` using atoms/futures.
3.  Consider the "Global Response Router" optimization for `ask` to reduce atom contention.
4.  Ensure `try/catch` logging inside all futures.

**Next Step**: Implementation of Phase 13F.
