# math

Basic math operations MCP tool module.

## Overview

Simple arithmetic operations for testing and basic calculations.

## Tool: `add`

Add two numbers together.

```clojure
{:a 2 :b 3}
;; => 5

{:a 1.5 :b 2.5}
;; => 4.0
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `a` | number | Yes | First number |
| `b` | number | Yes | Second number |

## Module Structure

```
modules/math/
├── module.edn
├── README.md
└── src/math/
    └── core.clj
```

## See Also

For more advanced mathematical operations, see the `calculate` module which provides 100+ functions including statistics, financial calculations, and crypto conversions.

## License

Same as bb-mcp-server project.
