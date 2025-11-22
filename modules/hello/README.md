# hello

Hello world MCP tool module.

## Overview

Simple greeting module demonstrating the bb-mcp-server module system. Shows how to create configurable tools with default values.

## Tool: `hello`

```clojure
{:name "World"}
;; => "Hello, World!"

{:name "Claude"}
;; => "Hello, Claude!"
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Name to greet |

## Configuration

The greeting prefix is configurable in `system.edn`:

```clojure
;; system.edn
{:modules {:hello {:greeting "Hi"}}}

;; Result
{:name "Claude"}
;; => "Hi, Claude!"
```

Default greeting: `"Hello"`

## Module Structure

```
modules/hello/
├── module.edn
├── README.md
└── src/hello/
    └── core.clj
```

## License

Same as bb-mcp-server project.
