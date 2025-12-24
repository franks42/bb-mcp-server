# LLM Integration Strategy Comparison

**Date:** November 26, 2025
**Context:** Comparative analysis of `bb-mcp-server`'s custom AI Orchestrator vs. existing Clojure ecosystems (`litellm-clj`, `LangChain4Clj`).

## Executive Summary

The `bb-mcp-server` project is building an **MCP-native Orchestrator**, whereas `litellm-clj` is a **Model Gateway** and `LangChain4Clj` is an **Application Framework**.

The key differentiator is that the `bb-mcp-server` architecture treats **"Experts" as dedicated MCP Servers**, not just software objects. This is a higher-level architectural pattern than what standard libraries provide.

## Detailed Comparison

### 1. vs. `litellm-clj` (The "Unified Interface" Approach)

*   **Their Goal:** Provide a single, consistent function call (e.g., `completion`) that works across OpenAI, Anthropic, Azure, etc. It abstracts away API differences.
*   **Our Approach:** We have built a similar abstraction layer in `ai-orchestrator.protocol` (`create-instance`, `send-message`).
*   **Key Difference:**
    *   **Scope:** `litellm-clj` focuses purely on the *I/O layer* (getting text in/out of models).
    *   **Our Project:** Our provider abstraction is tightly coupled with **MCP lifecycle management**. The `anthropic-http-provider` isn't just sending text; it's designed to support the "Expert" lifecycle (spawning, health checks, port management) which a generic library wouldn't handle.

### 2. vs. `LangChain4Clj` (The "Framework" Approach)

*   **Their Goal:** Provide primitives for building LLM apps: Chains, Memory, Document Loaders, and Agents. They have their own "Tool" abstraction.
*   **Our Approach:** We are building an "Agent" system where the **Model Context Protocol (MCP)** is the native language for everything.
*   **Key Difference:**
    *   **Tooling Standard:** `LangChain4Clj` uses internal Clojure protocols for tools. Our project uses the **MCP Standard** (JSON-RPC) for tools. This means our "Experts" can theoretically be used by *any* MCP client (Claude Desktop, Cursor, etc.), whereas LangChain agents are confined to the LangChain runtime.
    *   **Architecture:** LangChain typically runs agents as in-memory objects. Our design (per `ai-experts-framework.md`) runs experts as **independent processes/servers**. This allows for a much more robust, microservices-like architecture where an expert can crash without taking down the whole system.

## The "Expert as MCP Server" Advantage

Our current implementation is more complex than using a library, but it buys specific advantages for this project:

| Feature | Standard Library Approach | `bb-mcp-server` Approach |
| :--- | :--- | :--- |
| **Unit of Composition** | Class / Function / Chain | **MCP Server** (Process) |
| **Tool Interface** | Library-specific Protocol | **MCP Protocol** (Universal) |
| **Isolation** | Shared Memory / Threading | **Process Isolation** (via Subprocess/HTTP) |
| **Interoperability** | Clojure-only | **Any MCP Client** |

## Recommendation

**Keep the custom abstraction.**

Integrating `litellm-clj` might save some code in `anthropic-http-provider/core.clj` (handling HTTP retries/auth), but it wouldn't solve the core architectural challenges (Port Registry, Expert Lifecycle, MCP Protocol translation).

Integrating `LangChain4Clj` would likely fight against the architecture. It would require wrapping their "Tools" into "MCP Tools" and their "Agents" into "MCP Servers," adding a layer of indirection without gaining much benefit for this specific use case.

## Opportunities for Borrowing (Elaborated)

### 1. Prompt Management & Datalevin

**The Problem:** Currently, your `curriculum` likely consists of hardcoded strings or text files loaded at startup. This makes it hard to version, test, or dynamically update the "personality" or instructions of an expert.

**LangChain's Pattern:** They use `PromptTemplate` objects that separate the *structure* of the prompt from the *data*.
*   *Example:* `System: You are a {role}. User: Help me with {task}.`

**Leveraging Datalevin:**
You can significantly improve on this by using Datalevin as a **Dynamic Prompt Store**. Instead of static files, store prompts as database entities.

*   **Proposed Schema:**
    ```clojure
    {:prompt/id :clojure-expert-system-v1
     :prompt/template "You are an expert in Clojure. Focus on {topic}."
     :prompt/variables [:topic]
     :prompt/version 1
     :prompt/created-at #inst "..."}
    ```
*   **Benefits:**
    *   **Hot-swapping:** Update an expert's instructions without redeploying code.
    *   **Versioning:** Keep history of prompt performance (e.g., "v2 made better code reviews than v1").
    *   **Personalization:** Fetch different prompt templates based on the user's profile or past interactions.

### 2. Text Splitters (for RAG)

**The Concept:** "Text Splitting" is the art of breaking large documents into small, semantically meaningful chunks.

**Why you need it:**
If you build a "Documentation Expert" that knows about the entire `bb-mcp-server` codebase, you cannot feed all files into the LLM at once (context window limits). You must use **RAG (Retrieval Augmented Generation)**:
1.  Split documents into chunks.
2.  Search for relevant chunks.
3.  Feed only those chunks to the LLM.

**The Challenge:** A naive split (e.g., every 1000 characters) often breaks code or sentences in half, confusing the LLM.
*   *Bad Split:* `... (defn calculate-total [x y` | `] (+ x y)) ...`

**LangChain's Solution:** They offer "Recursive Character Splitters" and "Code Splitters".
*   **Recursive:** Tries to split by paragraph `\n\n` first. If the chunk is still too big, it tries `\n`, then `space`. This preserves semantic meaning.
*   **Code Aware:** Splits Clojure code at top-level forms `(defn ...)` rather than inside them.

**Recommendation:**
*   **For Prose (Docs, PDFs):** Use LangChain-style recursive splitters.
*   **For Code (Clojure):** **Use your Datalevin strategy.** Storing top-level forms as distinct entities is superior to text splitting. This is "Structure-Aware RAG."
    *   *Why it's better:* You never break syntax. You can retrieve specific functions by name. You can store metadata (docstrings, arity) as Datalevin attributes.
    *   *Hybrid Approach:* Use the "Text Splitter" only for the `docstring` or large comment blocks *within* the code, but keep the code structure intact in the DB.

### 3. The "Codebase Expert" Use Case

**User Question:** "Is it better to point the LLM at the Datalevin DB of forms rather than raw files?"

**Answer:** **Yes.** This enables a "Smart Codebase Expert" rather than just a "Text Search".

| Feature | Raw File RAG (Standard) | Datalevin Form Store (Your Edge) |
| :--- | :--- | :--- |
| **Retrieval Unit** | Arbitrary text chunk (e.g., lines 50-100) | **Semantic Unit** (Function, Var, Namespace) |
| **Completeness** | Often cuts off function bodies | **Guaranteed complete forms** |
| **Context Noise** | Includes license headers, whitespace | **Pure Code/Docs** |
| **Graph Traversal** | Impossible | **Trivial.** Query: "Find all forms called by `start-server`" |

**Implementation Strategy:**
Don't just "dump" the DB to the LLM. Create a **Code Retrieval Tool** (MCP Tool) that allows the LLM to query the DB.
See [Code Retrieval Tool Design](code-retrieval-tool-design.md) for the full specification.

*   `get_code_context(symbol)` -> Returns exact code.
*   `find_usages(symbol)` -> Returns list of other forms that call this one.
*   `explore_namespace(ns)` -> Returns list of public vars and docstrings (lightweight context).

This allows the LLM to "explore" the codebase intelligently, following references just like a human developer using an IDE.
