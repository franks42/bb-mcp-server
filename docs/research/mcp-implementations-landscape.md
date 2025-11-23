# Research: Babashka & Clojure MCP Landscape

**Date:** 2025-11-22
**Scope:** Existing Model Context Protocol (MCP) server implementations using Babashka or Clojure.

## Executive Summary

The ecosystem for MCP in Clojure/Babashka is emerging but distinct. While there is a dominant JVM-based solution (`clojure-mcp`), the Babashka space is less crowded. `bb-mcp-server` occupies a unique niche by combining **Babashka's fast startup** with **enterprise-grade architecture** (Streamable HTTP, Trove logging, Modular system), distinguishing it from simpler script-based implementations.

## 1. Direct Competitors (Babashka)

### `davidpham87/mcp-bb-clj`
*   **Stack:** Babashka + `http-kit`.
*   **Status:** Very Active.
*   **Architecture:** Split implementation focusing on pure data manipulation and a simple `http-kit` server.
*   **Comparison:** The closest direct architectural peer. It shares the same core stack but appears to lack the "Triple Interface" (Stdio/HTTP/REST) and the advanced logging facade (Trove) of `bb-mcp-server`.

### `franks42/mcp-nrepl-joyride`
*   **Stack:** Babashka + nREPL.
*   **Status:** Active (Predecessor).
*   **Focus:** Specifically bridges Claude to VS Code via Joyride's nREPL.
*   **Comparison:** More specialized than the general-purpose `bb-mcp-server`.

### `bmorphism/babashka-mcp-server`
*   **Stack:** Node.js (wrapping `bb`).
*   **Status:** Inactive.
*   **Comparison:** Not a native implementation; essentially a wrapper script.

## 2. The "Market Leader" (JVM)

### `bhauman/clojure-mcp`
*   **Stack:** Clojure (JVM).
*   **Status:** Highly Active (600+ stars).
*   **Focus:** A rich AI coding assistant suite with REPL-driven development tools.
*   **Comparison:** The heavyweight option. It offers deep tooling integration but suffers from JVM startup time and resource usage compared to a Babashka binary. It supports connecting *to* a Babashka nREPL, but the server itself is JVM-based.

## 3. Libraries & SDKs

### `metosin/mcp-toolkit`
*   **Stack:** CLJC (Clojure/Script/Babashka).
*   **Status:** Alpha.
*   **Focus:** A library/SDK for *building* servers, not a standalone server.
*   **Relevance:** A potential source for reference implementations (e.g., their SSE handling) or future collaboration/dependency, but currently `bb-mcp-server` implements its own transport layer.

## Strategic Positioning for `bb-mcp-server`

Your project is uniquely positioned as the **"Pro" Babashka Solution**:

1.  **Performance**: Native Babashka startup (vs. JVM).
2.  **Architecture**: Modular, "Triple Interface" design (vs. simple scripts).
3.  **Features**:
    *   **Streamable HTTP**: Implementing the latest MCP spec for server-to-client notifications.
    *   **Trove Logging**: Structured, high-performance logging facade.
    *   **Production Ready**: Designed for deployment, not just local hacking.

**Conclusion**: There is no direct equivalent that offers the same combination of performance, architectural maturity, and feature set (specifically Streamable HTTP) in the Babashka ecosystem.
