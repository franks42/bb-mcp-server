# bb-mcp-server Architecture – AI Agent Briefing

## Project Snapshot
- **Goal:** Build a generic, modular Babashka MCP server that cleanly separates the core platform from domain-specific tool modules.
- **Key Capabilities:** Triple-interface transport (STDIO for local Claude, HTTP for cloud clients, REST for dashboards), dynamic module loading, telemetry-first design, and project-based configuration.
- **Motivation:** Replace the tightly coupled `mcp-nrepl-joyride` architecture with a scalable foundation that can host nREPL plus any future tool domains without rewrites.

## Core Architecture
| Layer | Responsibilities |
| --- | --- |
| **Core Runtime** | Registry of tools, transport adapters, request routing, lifecycle management, telemetry hooks. |
| **Transports** | STDIO (Claude Code subprocess), HTTP `/mcp`, REST `/api/*`. All share the same core handlers. |
| **Module Loader** | Dynamically loads Babashka/Clojure files, tracks metadata, enforces lifecycle (`ILifecycle` protocol), supports start/stop/restart/health. |
| **Modules** | Self-contained tool packs (e.g., nREPL, filesystem, blockchain) that register handlers with the core and optionally manage state (connections). |

## Configuration Model
- **Cascading Files:** `./.bb-mcp-server.edn` (project) → `~/.config/bb-mcp-server/config.edn` (global) → defaults.
- **Module Entries:** `{:path "modules/nrepl.clj" :enabled true :config {...}}` with relative-path resolution based on the config file location.
- **Server Options:** Port/host/transport list, telemetry level/output, module auto-start toggle.

## Lifecycle & Operations
1. **Startup:** Load config, resolve module paths, load modules, start those that satisfy `ILifecycle`.
2. **Runtime:** Registry exposes tools to any transport; telemetry records requests, module events, and metrics snapshots.
3. **Management Tasks (bb.edn):** `bb load-module`, `bb list-tools`, `bb triple`, `bb module-health`, etc., provide CLI automation for humans/agents.
4. **Shutdown:** Registered JVM hook stops modules in reverse order and flushes telemetry.

## Telemetry & Observability
**📖 Implementation Guide: See `docs/AI_TELEMETRY_GUIDE.md` for coding patterns and examples.**

- Uses **Trove** (logging facade) + **Timbre** (backend) for structured JSON logs to stderr.
- Mandatory events: server lifecycle, module load/unload, tool registration, tool invocation (start/complete/fail), transport-specific request logs, metrics snapshots, security/ratelimiting hooks when present.
- Designed for log shipping to CloudWatch, GCP Logging, Datadog, or ELK via JSON pipelines.

## Module Pattern Highlights
- **Stateless Modules:** Register pure functions (e.g., math tools).
- **Stateful Modules:** Provide `module-instance` that implements `ILifecycle` for resource control (e.g., nREPL connection pools).
- **Metadata:** Each module declares name/version/type, tool list, and lifecycle flag for bookkeeping.
- **Conflict Handling:** (To-do) Need naming/namespace conventions; currently last writer wins.

## Deployment Modes
- **STDIO-only:** For Claude Code local sessions.
- **HTTP-only:** For cloud-hosted deployments; relies on HTTP transport plus REST diagnostics.
- **Triple Server:** Runs STDIO listener + HTTP/REST in one process for maximum flexibility.
- **Packaging:** Babashka scripts for dev, JVM uberjar + Docker path for production.

## Risks & TODOs for Agents
1. **Security:** HTTP/REST auth/rate-limiting unspecified; confirm requirements before exposing to the internet.
2. **Tool Collisions:** Registry currently overwrites duplicate names silently—coordinate naming per module.
3. **Module Isolation:** All modules share one Babashka runtime; runaway modules can crash the server.
4. **Config Errors:** Relative path resolution depends on config location—double-check after editing configs.
5. **Observability Costs:** Telemetry volume can grow quickly; use sampling/rotation in production.

## Agent Workflow Tips
- Start by running `bb tasks` to see available automation hooks.
- Use `bb config-show` to confirm which config file is active before modifying modules.
- When adding modules, update both the config and the loader/telemetry metadata.
- Always document new tools with schema metadata so transports (and Claude) can describe them accurately.
- Prefer extending existing lifecycle helpers instead of rolling custom start/stop logic.

## References
- Full architecture spec: `docs/bb-mcp-server-architecture.md`
- Review guide & questions: `docs/bb-mcp-server-review-guide.md`
- **Telemetry guide: `docs/AI_TELEMETRY_GUIDE.md`** (mandatory for all code)
- Module templates & examples: `templates/` directory
