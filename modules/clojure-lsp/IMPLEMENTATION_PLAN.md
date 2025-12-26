# Implementation Plan: Clojure LSP Module

This document outlines the step-by-step implementation plan for the `clojure-lsp` MCP module.

## Guiding Principles

1.  **Strict Linting & Formatting**:
    *   Run `clj-kondo --lint modules/clojure-lsp/src modules/clojure-lsp/test` after every edit.
    *   Run `cljfmt check modules/clojure-lsp/src modules/clojure-lsp/test` after every edit.
    *   **Zero Warnings Policy**: Warnings are not acceptable. If a warning is unavoidable, suppress it with an inline config (`#_{:clj-kondo/ignore [...]}`) and a comment explaining why.
2.  **Testing**:
    *   Every feature implementation must include accompanying tests.
    *   Maintain a test runner/script to run all tests for this module easily.
3.  **Module Isolation**:
    *   All source code, tests, and documentation must reside within `modules/clojure-lsp/`.
4.  **Git Workflow**:
    *   Commit, tag, and push after completing each phase.
    *   Tag format: `clojure-lsp-v0.1.0-phaseN`.
5.  **Telemetry**:
    *   Use `com.taoensso.trove/log!` for monitoring and debugging.
    *   Log key lifecycle events, errors, and significant operations.

## Phase 1: Foundation & Process Management

*   [ ] **Scaffold Module Structure**: Create `src/`, `test/`.
*   [ ] **Implement Dynamic Loading**:
    *   Ensure namespace follows `bb-mcp-server.modules.clojure-lsp` convention.
    *   Verify `module.edn` is correctly structured for dynamic discovery.
*   [ ] **Implement Process Lifecycle**:
    *   Create `bb_mcp_server.modules.clojure_lsp.server` namespace.
    *   Implement `start!` to spawn `clojure-lsp` process (configurable path).
    *   Implement `stop!` to gracefully shutdown.
    *   Implement `clj-init` MCP tool handler.
*   [ ] **Tests**:
    *   Test process spawning (mocked or actual if `clojure-lsp` is present).
    *   Test lifecycle management (start/stop repeatedly).
    *   Test dynamic loading (verify namespaces can be required dynamically).
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**

## Phase 2: JSON-RPC & Async Client

*   [ ] **Implement JSON-RPC Framing**:
    *   Create `bb_mcp_server.modules.clojure_lsp.jsonrpc` namespace.
    *   Implement `write-message!` (Content-Length header).
    *   Implement `read-message!` (header parsing).
*   [ ] **Implement Client State**:
    *   Create `bb_mcp_server.modules.clojure_lsp.client` namespace.
    *   Implement `request!` (async with promise).
    *   Implement `notify!` (fire and forget).
    *   Implement Response handler loop (separate thread).
*   [ ] **Tests**:
    *   Unit tests for message serialization/deserialization.
    *   Test `request!` / response matching mechanism.
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**

## Phase 3: Basic Navigation Features

*   [ ] **Implement Stateless Sync**:
    *   Create helper `with-file` (didOpen -> op -> didClose).
*   [ ] **Implement Tools**:
    *   Create `bb_mcp_server.modules.clojure_lsp.tools` namespace.
    *   `clj-definition`
    *   `clj-references`
    *   `clj-hover`
*   [ ] **Tests**:
    *   Integration tests with a dummy project or mocked LSP responses.
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**

## Phase 4: Advanced Features

*   [ ] **Implement Tools**:
    *   `clj-completions`
    *   `clj-code-actions`
    *   `clj-rename`
    *   `clj-document-symbols`
*   [ ] **Tests**:
    *   Specific tests for each tool handler.
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**

## Phase 5: Diagnostics & Hierarchy

*   [ ] **Implement Diagnostics**:
    *   Handle `textDocument/publishDiagnostics` notifications.
    *   Implement `clj-diagnostics` tool (pull from state).
*   [ ] **Implement Call Hierarchy**:
    *   `clj-call-hierarchy` (incoming/outgoing).
*   [ ] **Tests**:
    *   Test diagnostic accumulation.
    *   Test hierarchy requests.
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**

## Phase 6: Polish & Documentation

*   [ ] **Error Handling**: Robust implementations for timeouts, process crashes.
*   [ ] **Module Metadata**: Finalize `module.edn`.
*   [ ] **Documentation**: Update `README.md` for the module with usage instructions.
*   [ ] **Final Suite Run**: Ensure all tests pass.
*   [ ] **Lint & Format Check**
*   [ ] **Git Commit/Tag/Push**
