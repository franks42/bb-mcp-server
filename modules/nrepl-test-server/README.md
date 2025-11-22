# nrepl-test-server

Auto-starting Babashka nREPL server for testing.

## Overview

Automatically starts a Babashka nREPL server when the module loads. Useful for testing nREPL tools without manually starting a server.

## Configuration

Default configuration in `module.edn`:

```clojure
{:port 7888
 :host "localhost"}
```

Override in `system.edn`:

```clojure
{:modules {:nrepl-test-server {:port 9999}}}
```

## Behavior

- **On start:** Launches Babashka nREPL server on configured port
- **On stop:** Gracefully shuts down the server

## Use Cases

- **Development** - Quick nREPL server for testing
- **Integration tests** - Predictable nREPL endpoint for test suites
- **Demo** - Showcase nREPL tools without external dependencies

## Module Structure

```
modules/nrepl-test-server/
├── module.edn
├── README.md
└── src/nrepl_test_server/
    └── core.clj
```

## License

Same as bb-mcp-server project.
