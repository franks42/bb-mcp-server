# strings

String manipulation MCP tool module.

## Overview

Provides string manipulation tools for common operations like concatenation.

## Tool: `concat`

Concatenate multiple strings with optional separator.

```clojure
{:strings ["Hello" "World"]}
;; => "HelloWorld"

{:strings ["Hello" "World"] :separator " "}
;; => "Hello World"

{:strings ["a" "b" "c"] :separator ", "}
;; => "a, b, c"
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `strings` | array | Yes | Array of strings to concatenate |
| `separator` | string | No | Separator between strings (default: "") |

## Module Structure

```
modules/strings/
├── module.edn
├── README.md
└── src/strings/
    └── core.clj
```

## License

Same as bb-mcp-server project.
