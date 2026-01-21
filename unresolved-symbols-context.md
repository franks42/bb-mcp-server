# Unresolved Symbols Issue in bb-mcp-server

## Overview of the Issue

The primary issue encountered while attempting to start the `bb-mcp-server` using the `bb bootstrap-server` command is the persistent error related to unresolved symbols in the `main.clj` file. Despite multiple attempts to address these errors by commenting out or replacing references to external dependencies like `shttp/start-server!` and `shttp/broadcast-notification!`, the errors continue to appear with different symbols or persist with the same ones.

### What Goes Wrong

1. **Initial Errors**: The process began with errors related to missing dependencies such as `mcp.stdio.core` and `streamable-http.core`. These were commented out in the namespace declaration of `main.clj` to bypass classpath issues.
2. **Subsequent Errors**: After addressing namespace references, specific function calls like `shttp/start-server!` and `shttp/broadcast-notification!` resulted in `Unable to resolve symbol` errors. Attempts to replace these with placeholder functions were made, but the errors persisted.
3. **Current Error**: The latest error is `Unable to resolve symbol: server` in the `start-http!` function, even after renaming the placeholder function to avoid potential conflicts. This suggests a deeper issue with how Babashka interprets or caches the code, or possibly a misunderstanding in the scope or definition of variables/functions within `main.clj`.
4. **Server Not Starting**: The `lsof -i :0 -sTCP:LISTEN` command consistently shows no process listening on an ephemeral port, confirming that the server fails to start due to these unresolved symbol errors.

### Possible Reasons for the Issue

- **Babashka Caching**: Babashka might be caching previous versions of the code or not recognizing updates to `main.clj` immediately, leading to persistent errors despite changes.
- **Scope or Syntax Errors**: There could be a subtle syntax or scoping issue in how placeholder functions are defined or used within `main.clj`, causing Babashka to fail in resolving symbols.
- **Reserved Words**: Although online research did not confirm `server` as a reserved word in Babashka, there might be an undocumented conflict or restriction with certain symbol names.
- **Task Argument Passing**: The `bb.edn` task `bootstrap-server` might not be passing arguments correctly to `main/-main`, as seen with the `Unknown argument: bb-bootstrap-system.edn` error message.
- **Dependency Configuration**: Even with commented-out dependencies, there might be indirect references or configurations in other parts of the codebase or in `deps.edn`/`bb.edn` that are causing Babashka to attempt loading these symbols.

## State Information for Context

### Project Structure and Relevant Files

- **Project Root**: `/Users/franksiebenlist/Development/bb-mcp-server`
- **Main File**: `/Users/franksiebenlist/Development/bb-mcp-server/src/bb_mcp_server/main.clj`
  - Contains the entry point for the server with multiple transport options (stdio, HTTP).
  - Multiple edits were made to comment out dependencies and replace function calls with placeholders.
- **Configuration File**: `/Users/franksiebenlist/Development/bb-mcp-server/bb-bootstrap-system.edn`
  - Configured for minimal setup with only the `mcp-local-eval` module.
- **Task Definition**: `/Users/franksiebenlist/Development/bb-mcp-server/bb.edn`
  - Defines the `bootstrap-server` task to start the server with the minimal configuration.
- **Dependencies**: `/Users/franksiebenlist/Development/bb-mcp-server/deps.edn`
  - Lists project dependencies, with problematic Git dependencies like `mcp-stdio` and `mcp-http` temporarily removed.

### Current Errors

- **Latest Error Message**: `Unable to resolve symbol: server` at line 177 of `main.clj`, within the `start-http!` function definition.
- **Previous Errors**: 
  - `Could not locate mcp/stdio/core.bb, mcp/stdio/core.clj or mcp/stdio/core.cljc on classpath`
  - `Could not locate streamable_http/core.bb, streamable_http/core.clj or streamable_http/core.cljc on classpath`
  - `Unable to resolve symbol: shttp/start-server!`
  - `Unable to resolve symbol: shttp/broadcast-notification!`

### Actions Taken

1. Commented out namespace references to `mcp.stdio.core` and `streamable-http.core` in `main.clj`.
2. Replaced specific function calls like `shttp/start-server!` and `shttp/broadcast-notification!` with placeholder functions.
3. Renamed placeholder functions to avoid potential naming conflicts (e.g., `server-start` to `placeholder-start`).
4. Updated `bb-bootstrap-system.edn` for a minimal configuration focusing on the `mcp-local-eval` module.
5. Tested basic Babashka functionality with a minimal script to confirm Babashka itself is operational.
6. Researched online for Babashka reserved words, finding no explicit conflict with `server`.

### Environment and Tools

- **Operating System**: macOS
- **Babashka Version**: Not explicitly confirmed in logs, but assumed to be compatible with project requirements.
- **Clojure Version**: Reported as `{:major 1, :minor 12, :incremental 3, :qualifier SCI}` from a minimal test script.
- **Tools Used**: `bb` for running tasks, `lsof` for port checking.

## Summary for Another AI

The issue revolves around unresolved symbols in `main.clj` when attempting to start `bb-mcp-server` with a minimal configuration. Despite efforts to bypass dependency issues by commenting out references and using placeholders, errors persist, suggesting potential caching by Babashka, syntax/scoping issues, or misconfigured task arguments in `bb.edn`. The server fails to start, and no ephemeral port is in use. The next steps could involve clearing Babashka cache if possible, thoroughly reviewing `main.clj` for all symbol references, or adjusting the `bootstrap-server` task definition to ensure correct argument passing. All relevant files and state information are provided for further analysis and resolution.
