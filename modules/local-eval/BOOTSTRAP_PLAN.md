# Bootstrap Server Plan for bb-mcp-server with Local-Eval

## Objective
The goal is to create a bootstrap server setup for `bb-mcp-server` that operates as a persistent live MCP server capable of dynamically loading modules. Central to this setup is the `local-eval` module, which must be configured and enabled by default in the live server. With `local-eval`'s MCP tools, users can dynamically load additional modules and build up the server's functionality as needed, ensuring a flexible and extensible system.

## Importance of Local-Eval
The `local-eval` module provides critical tools (`local-eval` and `local-load-file`) for executing Clojure code and loading files directly within the server's runtime environment. This capability allows for server introspection, debugging, dynamic module loading, and configuration, making it the cornerstone for bootstrapping and extending the server's functionality on-the-fly.

## Current State
- The `local-eval` module is included in the default configuration as per `system.edn` at `/Users/franksiebenlist/Development/bb-mcp-server/system.edn:1-26`, listed under the `:modules` key.
- The server can be started with HTTP transport on port 3000 (confirmed via network connections), allowing persistent access without stdio attachment issues.
- Stdio transport, while functional, poses challenges for persistence without direct terminal attachment, making HTTP a more seamless default for a bootstrap server.

## Steps and Tasks to Achieve the Goal

### 1. Establish HTTP Transport as Default
   - **Rationale**: HTTP transport provides a persistent, accessible interface for interacting with the server without the complexities of stdio attachment, ideal for a live bootstrap server.
   - **Task**: Modify the server startup logic in `main.clj` to default to HTTP transport (port 3000) when no transport is specified, instead of stdio.
   - **Location**: Update `parse-args` function around line 37-39 in `main.clj` at `/Users/franksiebenlist/Development/bb-mcp-server/src/bb_mcp_server/main.clj`.

### 2. Ensure Local-Eval is Enabled by Default
   - **Rationale**: `local-eval` must be active in the bootstrap server to enable dynamic module loading and runtime configuration.
   - **Task**: Verify and reinforce that `local-eval` remains in the `:modules` list in `system.edn`. If necessary, document this requirement for users creating custom configurations.
   - **Location**: Confirm presence in `system.edn` at `/Users/franksiebenlist/Development/bb-mcp-server/system.edn:13`.

### 3. Enhance Port File Writing for Accessibility
   - **Rationale**: Users need a reliable way to discover the HTTP port of a running server, especially for automation or remote access.
   - **Task**: Investigate and extend the existing PID file writing mechanism to include port information explicitly, or create a new `portfile` in the project root with the HTTP port number.
   - **Location**: Check `pid-util/write-pid-file!` call around line 179 in `main.clj` at `/Users/franksiebenlist/Development/bb-mcp-server/src/bb_mcp_server/main.clj`. Propose adding code to write a `bb-mcp-server.port` file with the port number.

### 4. Document Bootstrap Server Setup and Usage
   - **Rationale**: Clear documentation will help users understand how to start and interact with the bootstrap server using `local-eval` for dynamic module loading.
   - **Task**: Create or update documentation in the project root (e.g., `README.md` or a new `BOOTSTRAP_GUIDE.md`) to explain starting the server with HTTP transport, accessing `local-eval` tools, and loading modules dynamically.
   - **Location**: Target `/Users/franksiebenlist/Development/bb-mcp-server/README.md` or a new file.

### 5. Test Dynamic Module Loading with Local-Eval
   - **Rationale**: Ensure that `local-eval` functions as expected in a live server for loading modules dynamically, validating the bootstrap concept.
   - **Task**: Develop a test procedure or script to start the bootstrap server, use `local-eval` to load a sample module, and verify functionality.
   - **Location**: Create a test script in `/Users/franksiebenlist/Development/bb-mcp-server/scripts/test_bootstrap.sh` or similar.

### 6. Optimize Local-Eval for User Experience
   - **Rationale**: `local-eval` should be user-friendly to facilitate seamless module loading and server configuration.
   - **Task**: Review and potentially enhance `local-eval` tool interfaces, error messages, or documentation to assist users in common tasks like module loading.
   - **Location**: Focus on `/Users/franksiebenlist/Development/bb-mcp-server/modules/local-eval/src/local_eval/` files, especially `eval.clj` and `load_file.clj`.

## Summary of Changes Needed
- Modify `main.clj` to accept a custom configuration file path via a command-line argument (e.g., `--config`) to override the default `system.edn`.
- Create a root-level configuration file `bb-bootstrap-system.edn` with only `local-eval` in the `:modules` list for minimal setup.
- Define a `bb.edn` task (e.g., `bootstrap-server`) to start the server using the custom configuration file, potentially with a workaround if direct `--config` support is not added.
- Enhance server startup in `main.clj` to use ephemeral ports by default (e.g., port 0 for OS assignment) unless a specific port is provided, to prevent conflicts with multiple instances.
- Implement a port discovery mechanism by writing config-specific port files (e.g., `.mcp-server-<config-name>-http-port`) in the execution directory with port details in JSON/EDN format.
- Add support for a `--nickname` command-line argument in `main.clj` to specify a human-friendly deployment name (e.g., `franks-minimal-mcp-server`), storing port files as `.ports/.<nickname>` for easy management of multiple instances.
- Update or create documentation for bootstrap server setup and `local-eval` usage, emphasizing the use of root-level `bb-*.edn` files for different configurations, port discovery practices, and nickname usage.
- Test dynamic module loading to validate the bootstrap server concept with the minimal configuration.
- Optimize `local-eval` tools for better usability in a live environment.
- Run the existing test suite before and after code changes to ensure no functionality is broken.
- Develop and add tests for different configurations, including the minimal setup with `local-eval`, to validate behavior across setups.

## Next Steps
Pending user approval, the next actions are to:
- Draft specific code changes to `main.clj` to support a `--config` command-line argument for custom configuration file paths.
- Create or update a `bb.edn` file at the project root with a `bootstrap-server` task to start the server with `bb-bootstrap-system.edn`.
- Modify server startup logic to use ephemeral ports by default and write config-specific port files to the execution directory for discovery.
- Add support for `--nickname` argument to manage port files as `.ports/.<nickname>` for user-friendly instance tracking.
- Run the full test suite (`bb test:all`) before making changes to establish a baseline.
- Test the setup by starting the server with the minimal configuration and verifying only `local-eval` is loaded.
- Run the test suite again after changes to confirm no regressions.
- Plan and implement specific tests for different configurations and port discovery to ensure robustness with multiple instances.
- Document the use of root-level `bb-*.edn` files for various server configurations, port file conventions, and nickname-based instance management to guide users.

This plan aims to make `local-eval` work seamlessly within a persistent MCP server, enabling easy dynamic module loading and server customization as envisioned, while maintaining a clean separation between module code and system configurations using root-level `bb-*.edn` files, ensuring quality through comprehensive testing, and preventing port conflicts through ephemeral ports and robust discovery mechanisms with nickname support.
