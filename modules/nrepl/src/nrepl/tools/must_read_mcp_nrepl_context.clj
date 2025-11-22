(ns nrepl.tools.must-read-mcp-nrepl-context
  "🎯 MUST-READ Context Tool: Essential AI Agent Onboarding for MCP-nREPL System"
  (:require [cheshire.core :as json]))

(defn handle
  "🎯 ESSENTIAL AI AGENT ONBOARDING: Read this FIRST before using any other tools!
   Provides comprehensive overview of MCP-nREPL system capabilities and usage patterns."
  [_]
  (let [markdown-content "# 🎯 MCP-nREPL System: Essential AI Agent Guide

## 🚀 What is MCP-nREPL?

**MCP-nREPL Bridge v0.7.5+** connects AI agents to live Clojure/VS Code environments via Model Context Protocol.

### Key Achievements
- ✅ **100% reliable quote-escape-free evaluation** with base64 encoding
- ✅ **Full VS Code API access** via Joyride integration
- ✅ **Multi-connection architecture** for switching between nREPL servers
- ✅ **Comprehensive debugging tools** for server introspection

## 🏗️ Architecture Overview

```
┌─────────────┐    JSON-RPC     ┌──────────────┐    nREPL     ┌─────────────┐
│  AI Agent   │ ◄──────────────► │ MCP-nREPL    │ ◄───────────► │ Clojure     │
│  (Claude)   │                  │ Bridge       │               │ nREPL       │
└─────────────┘                  └──────────────┘               └─────────────┘
                                        │                              │
                                        ▼                              ▼
                                 ┌──────────────┐               ┌─────────────┐
                                 │ Base64       │               │ VS Code API │
                                 │ Encoding     │               │ Integration │
                                 └──────────────┘               └─────────────┘
```

### Components
- 🌉 **MCP Bridge**: JSON-RPC server translating between AI and nREPL protocols
- 📡 **nREPL Client**: Custom Babashka implementation for reliable connections
- 🔐 **Base64 Layer**: Eliminates quote escaping for complex code and data
- 🎮 **VS Code Integration**: Full access to Joyride/Calva APIs and workspace

## 🎯 Quick Start for AI Agents

### Step 1: Connect
```
Tool: nrepl-connection
Args: {\"op\": \"connect\", \"connection\": \"56789\"}
```

### Step 2: Health Check
```
Tool: nrepl-health-check
Purpose: Verify system capabilities and environment
```

### Step 3: Choose Evaluation Method
- **nrepl-eval**: Full Clojure + VS Code API access (recommended)
- **local-eval**: Server introspection and debugging (limited SCI environment)

### Step 4: Use Base64 for Complex Code
```
Add output-base64: true for reliable data handling
Use input-base64: true for complex code with quotes/newlines
```

## 🛠️ Tool Selection Guide

### Primary Tools

#### nrepl-eval
**Purpose**: Execute Clojure code in connected nREPL server
**When to use**:
- Most Clojure evaluation tasks
- VS Code API operations
- File system operations
- Application development and testing

**Capabilities**:
- Full Clojure language support
- Complete VS Code API access via Joyride
- Multi-stream output (stdout/stderr/result)
- Base64 encoding for complex data
- Timeout protection (1-300 seconds)

**Example**: `{\"code\": \"(+ 1 2 3)\", \"timeout\": 30}`

#### local-eval
**Purpose**: Execute code within MCP server runtime for debugging
**When to use**:
- Server state inspection
- Debug tool development
- MCP server introspection
- Simple calculations

**Limitations**:
- SCI interpreter (subset of Clojure)
- No VS Code API access
- Limited stderr capture

**Example**: `{\"code\": \"(keys @server-state)\"}`

#### nrepl-connection
**Purpose**: Manage nREPL server connections
**Operations**:
- `connect`: Establish connection to nREPL server
- `disconnect`: Clean connection shutdown
- `status`: Check connection health

**Example**: `{\"op\": \"connect\", \"connection\": \"7890\"}`

#### nrepl-health-check
**Purpose**: Comprehensive system diagnostics
**Provides**:
- Environment information
- Connection status
- Available operations
- Performance benchmarks
- Tool integration status

**When to use**:
- Troubleshooting connection issues
- Verifying system capabilities
- Performance monitoring
- Environment discovery

### Specialized Tools

#### Async Tools
- `nrepl-send-message-async`: Fire-and-forget message sending
- `nrepl-get-result-async`: Retrieve results by message ID

#### File Tools
- `nrepl-load-file`: Load Clojure files into nREPL
- `local-load-file`: Load files into MCP server runtime

#### Debug Tools
- Advanced introspection and state inspection
- Live server debugging capabilities

## 🔐 Base64 Enhancement Guide

### Problem Solved
**Quote escaping nightmares eliminated!**

Before: `BROKEN: (println \"Hello \\\"quoted\\\" world!\")`
After: `PERFECT: Use output-base64 flag for automatic encoding`

### Usage Patterns

#### Input Encoding
- **Flag**: `input-base64: true`
- **Use case**: Send complex code with quotes, newlines, special chars
- **Workflow**: 1. Base64 encode your code → 2. Set input-base64: true → 3. Send safely

#### Output Encoding
- **Flag**: `output-base64: true`
- **Use case**: Receive complex results without JSON corruption
- **Benefits**:
  - No quote escaping errors
  - Preserve exact formatting
  - Handle binary data safely
  - AI-friendly structured output

#### Auto-Decode
- **Flag**: `decode-output: true` (with client tools)
- **Convenience**: Automatic base64 decoding for human reading

### Client Integration
Use `mcp_nrepl_client.py --output-base64` for instant base64 handling

## 🎮 VS Code Integration Patterns

### Joyride Basics
```clojure
;; Command execution
(joyride.core/execute-command \"workbench.action.quickOpen\")

;; File access
(-> js/vscode.window.activeTextEditor .-document .-fileName)

;; Workspace operations
(joyride/workspace-root)
```

### File Operations
- Reading/writing files in current workspace
- Directory navigation and file discovery
- Project-wide search and replace

### UI Automation
- Command palette execution
- Panel and sidebar management
- Editor operations and text manipulation

## 📋 Common Usage Patterns

### Pattern 1: Quick Clojure Evaluation
1. `nrepl-connection` → connect to port
2. `nrepl-eval` → execute code
3. Read result from 'value' field

Example: Simple math: `(+ 1 2 3)` → `6`

### Pattern 2: Complex Code with Base64
1. Encode complex code as base64
2. `nrepl-eval` with `input-base64: true`
3. Use `output-base64: true` for safe results

Example: Code with quotes, newlines, special characters

### Pattern 3: VS Code Workspace Operations
1. Connect to Joyride nREPL server
2. Use joyride.core functions for VS Code API
3. Access files, commands, UI elements

Example: Automated refactoring and file operations

### Pattern 4: Server Debugging and Introspection
1. `local-eval` for server state inspection
2. Debug tools for deep introspection
3. Health checks for system validation

Example: Understanding server behavior and troubleshooting

## 🚨 Troubleshooting Guide

### Connection Issues
- ❓ Can't connect → Check nREPL server is running
- ❓ Port conflicts → Use explicit port numbers
- ❓ Timeouts → Check server responsiveness

### Evaluation Problems
- ❓ Quote errors → Use base64 encoding
- ❓ Complex output → Enable output-base64
- ❓ Performance → Check nrepl-health-check benchmarks

### VS Code Integration
- ❓ No VS Code API → Ensure connected to Joyride nREPL
- ❓ Missing commands → Verify Joyride extension active
- ❓ File access → Check workspace permissions

## ⚡ Performance Considerations

- **Evaluation speed**: Typical 50-200ms per evaluation
- **Base64 overhead**: Minimal <5% encoding/decoding cost
- **Connection reuse**: Recommended - keep connections alive for multiple operations
- **Timeout settings**: Default 30s, Range 1-300s, adjust based on task complexity

## 🎯 Next Steps for AI Agents

1. 🔗 Start with `nrepl-connection` to establish link
2. 💡 Run `nrepl-health-check` to understand environment
3. 🚀 Use `nrepl-eval` for most Clojure tasks
4. 🛠️ Try `local-eval` for server introspection
5. 📦 Use base64 encoding for complex code
6. 🎮 Explore VS Code integration patterns
7. 🔧 Debug with specialized tools when needed

## ⚠️ Important Reminders

- **Must connect first**: Always establish nREPL connection before evaluation
- **Use base64 for complex code**: Encoding for quotes, newlines, special chars
- **Read tool descriptions**: Each tool has specific capabilities and limitations
- **Check health regularly**: Use health checks for troubleshooting
- **Respect timeouts**: Set appropriate timeouts for long-running operations

---

## 🎉 You're Ready!

You now understand the MCP-nREPL system architecture, capabilities, and usage patterns. Use this knowledge to select the right tools and approaches for your tasks. Happy coding! 🚀"]
    {:content [{:type "text"
                :text markdown-content}]}))

(def tool-name "must-read-mcp-nrepl-context")

(def metadata
  {:description "🎯 MUST-READ FIRST: Essential AI agent onboarding guide for MCP-nREPL system. Provides comprehensive overview of architecture, tool selection, base64 enhancements, VS Code integration, and usage patterns. READ THIS BEFORE using any other tools!"
   :inputSchema {:type "object"
                 :properties {}
                 :required []}})