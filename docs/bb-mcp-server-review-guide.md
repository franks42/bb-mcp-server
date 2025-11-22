# bb-mcp-server Architecture Review Guide

## Purpose of This Document

This guide provides the essential background and context needed to review the `bb-mcp-server-architecture.md` design document. It ensures reviewers understand:
- What problem we're solving
- Why this architecture was chosen
- What feedback we're seeking

---

## Essential Background

### What is MCP (Model Context Protocol)?

**MCP** is Anthropic's standardized protocol for connecting AI assistants (like Claude) to external tools and data sources.

**Key concepts:**
- **MCP Server**: Exposes tools/resources via JSONRPC 2.0 protocol
- **MCP Client**: AI assistant (Claude Code, Claude Desktop) that calls tools
- **Transport**: stdio (pipes) or HTTP/SSE (network)
- **Tools**: Functions the AI can call (like `nrepl-eval`, `fetch-wallet-summary`)

**Example MCP tool call:**
```json
// Client → Server
{"jsonrpc":"2.0","method":"tools/call","params":{"name":"nrepl-eval","arguments":{"code":"(+ 1 2)"}}}

// Server → Client
{"jsonrpc":"2.0","result":{"value":"3","out":"","err":""}}
```

### What is mcp-nrepl-joyride?

**mcp-nrepl-joyride** is our existing Babashka-based MCP server that bridges Claude Code to nREPL (Clojure REPL servers).

**What it does:**
- Connects Claude to running Clojure REPL sessions
- Enables Claude to evaluate Clojure code
- Manages multiple nREPL connections
- Uses stdio transport (Claude spawns it as subprocess)

**What works well:**
- Fast startup (Babashka)
- Dynamic tool loading via SCI
- FQN-based tool registration
- Stable and functional

**What's limiting:**
- Name implies it's nREPL-specific only
- stdio transport only (initially)
- HTTP support was retrofitted later
- No REST API for web dashboards
- nREPL code baked into core (not modular)

---

## The Problem Statement

### Current Limitations

1. **Name coupling**: "mcp-nrepl-joyride" suggests it's only for nREPL, limiting perceived scope
2. **Transport limitations**: stdio is local-only, can't deploy to cloud
3. **Lack of modularity**: nREPL is in the core, hard to add other tool domains
4. **No REST API**: Can't integrate with web dashboards without MCP protocol
5. **Retrofitted design**: HTTP support added later, not designed-in from start

### What We Want

A **generic, modular Babashka MCP server platform** that:
- Supports multiple tool domains (nREPL, blockchain, filesystem, etc.) as loadable modules
- Provides three concurrent interfaces: MCP stdio, MCP HTTP, REST API
- Works locally (stdio) AND in cloud (HTTP)
- Makes it easy to add new tool modules
- Has clean architecture from day one (not retrofitted)

---

## Goals and Requirements

### Primary Goals

1. **Generic Foundation**: Not coupled to any specific domain (nREPL, blockchain, etc.)
2. **Triple Interface Native**: stdio + HTTP + REST built-in from start
3. **Modular Architecture**: Core + loadable modules pattern
4. **Cloud-Ready**: HTTP transport enables cloud deployment
5. **Dynamic Tool Loading**: Add/remove tools at runtime
6. **Clean Migration Path**: Reuse lessons from mcp-nrepl-joyride

### Non-Goals

- ❌ Build a competing framework to Anthropic's Python MCP SDK
- ❌ Support protocols other than MCP (this is MCP-specific)
- ❌ Replace mcp-nrepl-joyride immediately (migration path, not replacement)

### Technical Requirements

1. **Babashka-based**: Fast startup, good stdlib, native image optional
2. **Transport agnostic**: Same handlers work for stdio, HTTP, REST
3. **Module system**: Load/unload modules dynamically
4. **Tool registry**: Central registry all transports query
5. **JSONRPC 2.0**: MCP protocol compliance
6. **HTTP server**: For MCP HTTP and REST endpoints
7. **Startup configuration**: User-configurable module loading

---

## Architecture Overview (Summary)

### Core Components

```
┌─────────────────────────────────────┐
│         bb-mcp-server Core          │
│  • Transport layer (stdio/HTTP/REST)│
│  • Tool registry                    │
│  • Module loader                    │
└──────────────┬──────────────────────┘
               │
    ┌──────────┴──────────┐
    ▼                     ▼
┌─────────┐         ┌─────────┐
│ Modules │         │ Modules │
│ (nREPL) │         │ (other) │
└─────────┘         └─────────┘
```

### Triple Interface Pattern

**Same core handlers, three transport wrappers:**

1. **MCP stdio**: Claude Code spawns as subprocess (local only)
2. **MCP HTTP**: HTTP POST to `/mcp` (local or cloud)
3. **REST API**: HTTP GET/POST to `/api/*` (web dashboards)

All three call the same tool registry and handlers.

### Module Pattern

Modules register tools with core registry:

```clojure
;; modules/nrepl.clj
(ns modules.nrepl
  (:require [bb-mcp-server.registry :as registry]))

(defn eval-code [{:keys [code connection-id]}]
  ;; Implementation
  ...)

(registry/register-tool!
 "nrepl-eval"
 eval-code
 {:name "nrepl-eval"
  :description "Evaluate Clojure code"
  :inputSchema {...}})
```

---

## Key Design Decisions

### 1. Why Babashka?

- ✅ Fast startup (~100ms vs ~3s JVM)
- ✅ Good standard library
- ✅ Native scripting capabilities
- ✅ Can load modules dynamically via SCI
- ✅ Proven in mcp-nrepl-joyride

### 2. Why Triple Interface?

**stdio**: Local use, automatic lifecycle, secure by default
**HTTP**: Cloud deployment, multi-client, debugging
**REST**: Web dashboards, traditional HTTP clients

Different clients need different transports. Supporting all three maximizes flexibility.

### 3. Why Modular (Core + Modules)?

- **Separation of concerns**: nREPL is one use case, not the only one
- **Extensibility**: Anyone can write modules
- **Optional dependencies**: nREPL module pulls in nrepl/nrepl dependency only if loaded
- **Future ecosystem**: Module registry (like npm, crates.io)

### 4. Why Not Just Use Python MCP SDK?

Python MCP SDK is excellent, but:
- Requires Python runtime (we want Babashka for Clojure ecosystem)
- Harder to deploy as single binary
- We want Clojure-native solution for Clojure tools
- Babashka startup is faster for frequent spawning

### 5. Why Not Extend mcp-nrepl-joyride?

We could, but:
- Name is limiting ("nrepl" in name)
- Architecture wasn't designed for triple interface from start
- Clean slate allows us to apply all lessons learned
- Migration path exists (port nREPL code as module)

---

## Success Criteria

How do we know if this architecture is good?

### Must Have (P0)

- ✅ Tool registry that works across all transports
- ✅ stdio server that works with Claude Code
- ✅ HTTP server that can be deployed to cloud
- ✅ REST API for web dashboards
- ✅ Module loader that works at runtime
- ✅ nREPL module that provides all current functionality
- ✅ Comprehensive telemetry with Trove (structured JSON logging)

### Should Have (P1)

- ✅ Example modules (filesystem, blockchain)
- ✅ Startup configuration for auto-loading
- ✅ Documentation for module developers
- ✅ Migration guide from mcp-nrepl-joyride

### Nice to Have (P2)

- ✅ Module registry/package manager
- ✅ Health checks and monitoring
- ✅ Metrics and telemetry
- ✅ Module dependency management

---

## Specific Questions for Reviewers

### Architecture

1. **Is the core/module separation clear and well-defined?**
   - Are responsibilities cleanly separated?
   - Is the registry interface sufficient?

2. **Is the triple interface pattern sound?**
   - Do all three transports share handlers correctly?
   - Are there edge cases where this breaks down?

3. **Is the module loading mechanism robust?**
   - Can modules be loaded/unloaded safely?
   - How do we handle module conflicts (same tool name)?

4. **Is the lifecycle management pattern sound?**
   - Does the ILifecycle protocol cover all necessary operations?
   - Is start/stop/restart implemented correctly?
   - Are resources cleaned up properly on shutdown?
   - Is the graceful shutdown mechanism sufficient?

5. **Is the project-based configuration system well-designed?**
   - Is cascading config (project → global → defaults) clear?
   - Is relative path resolution intuitive?
   - Are use cases for project-specific vs global tools addressed?
   - Is the `.bb-mcp-server.edn` format appropriate?

6. **What's missing from the core?**
   - Error handling strategy?
   - Configuration management gaps?
   - Security considerations?

### Implementation

7. **Are the code examples idiomatic and clear?**
   - Clojure style issues?
   - Better patterns available?

8. **Is the project structure logical?**
   - File organization make sense?
   - Any missing directories/files?

9. **Are dependencies appropriate?**
   - http-kit vs other HTTP servers?
   - Cheshire vs other JSON libraries?
   - Trove + Timbre for logging (migration complete)

10. **Is the telemetry implementation comprehensive?**
    - Are the right events being logged?
    - Is structured logging done correctly?
    - Performance overhead acceptable?
    - Cloud integration strategy sound?

### Deployment

11. **Is the cloud deployment story realistic?**
    - Docker/container deployment clear?
    - Security considerations addressed?

12. **Is the migration path from mcp-nrepl-joyride feasible?**
    - Can existing users migrate smoothly?
    - Backwards compatibility needed?

### Concerns and Risks

13. **What could go wrong?**
    - Performance bottlenecks?
    - Scalability issues?
    - Security vulnerabilities?

14. **Are there simpler alternatives?**
    - Is this over-engineered?
    - Could we achieve goals with less complexity?

15. **What's the long-term maintenance burden?**
    - Is this sustainable?
    - Clear upgrade path?

---

## Comparison to Alternatives

### vs Python MCP SDK

| Aspect | Python MCP SDK | bb-mcp-server |
|--------|----------------|---------------|
| Runtime | Python | Babashka |
| Startup | ~500ms | ~100ms |
| Ecosystem | Python packages | Clojure libraries |
| Deployment | Python app | Native binary possible |
| Triple interface | Manual | Built-in |

### vs Custom per-domain servers

| Aspect | Custom servers | bb-mcp-server |
|--------|----------------|---------------|
| Code reuse | None | Core shared |
| Maintenance | Multiple codebases | One codebase |
| Triple interface | Implement each time | Free |
| Learning curve | High (per server) | Low (one pattern) |

### vs Extending mcp-nrepl-joyride

| Aspect | Extend existing | Clean slate |
|--------|-----------------|-------------|
| Name | Limited by "nrepl" | Generic |
| Architecture | Retrofitted | Designed-in |
| Migration | Gradual | Fresh start |
| Breaking changes | Risk | None (new project) |

---

## Context: Related Projects

### pb-fm-mcp (Proven Pattern)

Our **pb-fm-mcp** server (Python-based) already uses the triple interface pattern:
- MCP tools for Claude
- REST API for debugging/dashboards
- Same handlers, different wrappers

**This proves the pattern works in production.**

### sente_lite (Transport)

We have **sente_lite** for WebSocket/long-polling, which could be used for real-time features (future).

### clay-noj-ai (Consumer)

This architecture supports the **clay-noj-ai** project which needs:
- Claude editing Clojure notebooks
- nREPL integration for code execution
- Web dashboards for visualization

---

## Evaluation Framework

Use this checklist when reviewing:

### Architecture Clarity
- [ ] Goals are clear and well-motivated
- [ ] Requirements are specific and testable
- [ ] Component boundaries are well-defined
- [ ] Data flow is understandable

### Technical Soundness
- [ ] Design patterns are appropriate
- [ ] Technology choices are justified
- [ ] Performance considerations addressed
- [ ] Security considerations addressed

### Implementability
- [ ] Code examples are clear and complete
- [ ] Project structure is logical
- [ ] Migration path is realistic
- [ ] Dependencies are reasonable

### Maintainability
- [ ] Modular and extensible
- [ ] Clear separation of concerns
- [ ] Documentation is sufficient
- [ ] Testing strategy is implied/stated

### Risks and Concerns
- [ ] Potential issues identified
- [ ] Mitigation strategies proposed
- [ ] Simpler alternatives considered
- [ ] Long-term sustainability assessed

---

## How to Provide Feedback

### Structured Feedback Format

Please organize feedback into these categories:

1. **Strengths**: What's good about this design?
2. **Weaknesses**: What's problematic or unclear?
3. **Questions**: What needs clarification?
4. **Suggestions**: Specific improvements
5. **Alternatives**: Different approaches to consider
6. **Risks**: What could go wrong?

### Example Feedback

```markdown
## Strengths
- Clear separation between core and modules
- Triple interface pattern is elegant
- Code examples are concrete and helpful

## Weaknesses
- Error handling strategy not specified
- Module conflict resolution unclear
- No discussion of testing approach

## Questions
- How do modules declare dependencies on other modules?
- What happens if two modules register same tool name?
- How is module load order determined?

## Suggestions
- Add error handling patterns to core
- Define module metadata format (version, deps, etc.)
- Include testing strategy section

## Alternatives
- Consider using deps.edn instead of custom loader?
- Could babashka.pods provide module isolation?

## Risks
- Dynamic loading could have security implications
- Module conflicts might be hard to debug
- HTTP server lifecycle in triple mode needs careful handling
```

---

## Reviewer Background Assumptions

We assume reviewers have:
- ✅ Basic understanding of JSONRPC 2.0
- ✅ Familiarity with HTTP REST APIs
- ✅ Understanding of client-server architecture
- ✅ General programming knowledge

We do NOT assume:
- ❌ Deep Clojure expertise (code should be readable)
- ❌ MCP protocol knowledge (explained in this guide)
- ❌ Babashka familiarity (context provided)
- ❌ Knowledge of our existing projects

---

## Summary for Reviewers

**What we're building:**
A generic, modular Babashka MCP server with triple interface (stdio/HTTP/REST) and dynamic module loading.

**Why we're building it:**
Current server (mcp-nrepl-joyride) is functional but limited by name, architecture, and single transport.

**What we need from you:**
Critical review of architecture, identification of issues, suggestions for improvement.

**Key questions:**
1. Is this architecture sound?
2. What's missing or unclear?
3. Are there simpler alternatives?
4. What could go wrong?

---

## Next Steps After Review

Based on feedback, we will:
1. **Revise architecture** - Address concerns and incorporate suggestions
2. **Create prototype** - Build minimal viable version
3. **Test triple interface** - Verify all three transports work
4. **Port nREPL module** - Validate module pattern
5. **Deploy to cloud** - Prove cloud deployment works
6. **Document lessons** - Update architecture based on implementation

---

**Thank you for reviewing!** Your feedback will help us build a solid foundation for the Babashka MCP ecosystem.

---

*Created: 2025-11-20*
*Related: bb-mcp-server-architecture.md*
*Audience: LLM reviewers, technical architects*
