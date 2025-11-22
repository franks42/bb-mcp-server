# calculate

Mathematical expression evaluation with SCI sandbox.

## Overview

Evaluates Clojure expressions in prefix notation with 100+ pre-loaded functions for math, statistics, finance, and crypto operations.

## Tool: `calculate`

Evaluate mathematical expressions safely in a sandboxed environment.

### Usage

```clojure
;; Basic arithmetic
{:expr "(+ 2 3)"}
;; => {:result 5, :type "integer"}

;; Square root
{:expr "(sqrt 16)"}
;; => {:result 4.0, :type "float"}

;; Statistics
{:expr "(mean [1 2 3 4 5])"}
;; => {:result 3.0, :type "float"}

;; Financial
{:expr "(percent-change 100 125)"}
;; => {:result {:percent 25.0 :direction :increase}}

;; Crypto conversions
{:expr "(wei->ether 1e18)"}
;; => {:result {:ether 1.0}}
```

### Input Schema

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `expr` | string | Yes | Clojure expression in prefix notation |
| `input-base64` | boolean | No | Interpret expr as base64-encoded |
| `output-base64` | boolean | No | Return result as base64 |

### Available Functions

**Arithmetic:** `+` `-` `*` `/` `mod` `pow` `sqrt` `exp`

**Trigonometry:** `sin` `cos` `tan` (radians), `sind` `cosd` `tand` (degrees)

**Statistics:** `sum` `mean` `median` `stdev` `variance`

**Financial:** `percent-change` `roi` `compound-interest`

**Crypto:** `wei->ether` `sats->btc` `token-convert` `portfolio-value`

**DeFi:** `impermanent-loss` `staking-rewards` `liquidation-price`

**Formatting:** `with-commas` `round-to` `scientific`

**Constants:** `pi` `e` `tau` `phi` `eth-decimals` `btc-decimals`

## Module Structure

```
modules/calculate/
├── module.edn
├── README.md
├── src/calculate/
│   ├── core.clj      # Tool registration
│   ├── engine.clj    # Expression evaluation
│   └── analytics.clj # Usage logging
└── test/
    └── run_tests.clj
```

## License

Same as bb-mcp-server project.
