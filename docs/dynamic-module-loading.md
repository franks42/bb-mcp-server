# Dynamic Module Loading in bb-mcp-server

## Overview

The bb-mcp-server uses an elegant dynamic module loading system that leverages Babashka's native classpath and namespace resolution capabilities. Instead of manually parsing namespace dependencies or maintaining complex load orders, we simply:

1. Add the module's `src` directory to the classpath
2. Require the entry namespace - Babashka auto-resolves all dependencies

This is the Clojure-idiomatic way: tell bb where source files are, then require what you need.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                         bb-mcp-server                                  │
├────────────────────────────────────────────────────────────────────────┤
│  CORE (always present)                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐     │
│  │   MCP Protocol   │  │    ns_loader     │  │  module.protocol │     │
│  │     Handler      │  │   (this file)    │  │                  │     │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘     │
│           │                    │                      │               │
│           └────────────────────┼──────────────────────┘               │
│                                │                                       │
│                                ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │                    DYNAMICALLY LOADED MODULES                    │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │  │
│  │  │  echo   │ │  math   │ │ strings │ │  nrepl  │ │calculate│   │  │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘   │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────────────────┐               │  │
│  │  │  hello  │ │local-eval│ │ nrepl-test-server  │  ...more      │  │
│  │  └─────────┘ └─────────┘ └─────────────────────┘               │  │
│  └─────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

## Module Structure

Each module lives in its own directory under `modules/`:

```
modules/
└── my-module/
    ├── module.edn          # Module manifest (required)
    ├── src/
    │   └── my_module/      # Source files (namespace convention)
    │       ├── core.clj    # Entry point namespace
    │       └── utils.clj   # Additional namespaces (auto-loaded)
    └── test/               # Optional tests
        └── run_tests.clj
```

## module.edn Schema

The simplified module manifest:

```clojure
{:name "my-module"              ; Required: unique module identifier
 :version "0.1.0"               ; Required: semantic version
 :description "What it does"    ; Optional: human description
 :requires ["other-module"]     ; Optional: module-level dependencies
 :entry "my-module.core/module" ; Required: entry point "namespace/var"
 :defaults {:option "value"}}   ; Optional: default configuration
```

### Example: math module

```clojure
{:name "math"
 :version "0.1.0"
 :description "Basic math operations MCP tool module"
 :requires []
 :entry "math.core/module"
 :defaults {}}
```

### Example: module with dependencies

```clojure
{:name "advanced-calc"
 :version "0.1.0"
 :description "Advanced calculations requiring other modules"
 :requires ["local-eval" "calculate"]  ; Must be loaded first!
 :entry "advanced-calc.core/module"
 :defaults {:precision 8}}
```

## Two Types of Dependencies

### 1. Namespace Dependencies (Automatic)

These are `require` statements within your Clojure code:

```clojure
(ns my-module.core
  (:require [clojure.string :as str]     ; Standard library
            [my-module.utils :as utils]   ; Within same module
            [cheshire.core :as json]))    ; External dep (in deps.edn)
```

**Handled automatically by Babashka's `require`** - no manual ordering needed!

### 2. Module Dependencies (Application Layer)

These are cross-module dependencies declared in `:requires`:

```clojure
{:requires ["local-eval" "calculate"]}
```

**Checked by ns_loader before loading** - must load dependencies first!

Why the distinction?
- Namespace deps are about code compilation/loading
- Module deps are about application-level initialization order
- A module might need another module's *instance* running, not just its code

## The Loading Process

```
┌─────────────────────────────────────────────────────────────────────┐
│                    load-module "modules/math"                        │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 1: Read module.edn                                             │
│         → {:name "math" :entry "math.core/module" ...}              │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 2: Check module dependencies                                    │
│         → Are all :requires modules already loaded?                  │
│         → If missing: return {:error {:type :missing-dependencies}}  │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 3: Add src directory to classpath                              │
│         → (add-classpath "modules/math/src")                        │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 4: Require entry namespace                                      │
│         → (require 'math.core)                                       │
│         → Babashka auto-loads ALL namespace dependencies!            │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 5: Resolve entry var                                            │
│         → (ns-resolve 'math.core 'module)                            │
│         → Returns the module definition map                          │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 6: Validate module protocol                                     │
│         → Must have :tools, :start, :stop, :status                   │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 7: Register in loaded-modules atom                              │
│         → {:math {:manifest ... :module ... :instance nil}}          │
└─────────────────────────────────────────────────────────────────────┘
```

## API Reference

### Core Functions

#### `load-module`
```clojure
(load-module module-dir)
```
Dynamically load a module from a directory.

**Returns:**
- `{:success {:manifest ... :module ... :load-time-ms ...}}`
- `{:error {:type :missing-dependencies :missing ["mod1" "mod2"]}}`
- `{:error {:type :require-failed :message "..."}}`

**Example:**
```clojure
(require '[bb-mcp-server.module.ns-loader :as loader])

(loader/load-module "modules/math")
;; => {:success {:manifest {...} :module {...} :load-time-ms 102}}
```

#### `start-module!`
```clojure
(start-module! module-name deps config)
```
Start a loaded module with dependencies and configuration.

**Parameters:**
- `module-name` - Name of module to start
- `deps` - Map of dependency instances (from other modules)
- `config` - Configuration map (merged with `:defaults`)

**Example:**
```clojure
(loader/start-module! "math" {} {:precision 4})
;; => {:success <instance>}
```

#### `stop-module!`
```clojure
(stop-module! module-name)
```
Stop a running module.

#### `reload-module`
```clojure
(reload-module module-name)
```
Hot-reload a module by re-requiring its namespace with `:reload`.

**Process:**
1. Stop current instance (if running)
2. Require namespace with `:reload` flag
3. Re-resolve entry point
4. Update loaded-modules registry

#### `get-loaded-modules`
```clojure
(get-loaded-modules)
;; => {"math" {:manifest {...} :module {...} :instance <or nil>}
;;     "nrepl" {...}}
```

#### `get-module-status`
```clojure
(get-module-status "math")
;; => {:name "math"
;;     :version "0.1.0"
;;     :loaded true
;;     :running true
;;     :status {:requests-handled 42}}
```

#### `reset-state!`
```clojure
(reset-state!)
```
Reset all loader state. Stops all modules, clears registry. Use for testing.

### Bulk Operations

#### `load-modules-from-system`
```clojure
(load-modules-from-system {:modules-dir "modules"
                           :modules ["math" "echo" "nrepl"]})
;; => {"math" {:success ...}
;;     "echo" {:success ...}
;;     "nrepl" {:success ...}}
```

#### `start-all-modules!`
```clojure
(start-all-modules! {"math" {:precision 4}
                     "nrepl" {:timeout 30000}})
```

#### `stop-all-modules!`
```clojure
(stop-all-modules!)
```

## Minimum Bootstrap Configuration

To enable fully dynamic module loading via MCP, you need:

```
┌────────────────────────────────────────────────────────────────────────┐
│  CORE (always present):                                                │
│  - MCP Protocol handling                                               │
│  - ns_loader.clj                                                       │
│  - module/protocol.clj                                                 │
├────────────────────────────────────────────────────────────────────────┤
│  BOOTSTRAP MODULE (minimum ONE):                                       │
│                                                                        │
│  Option A: local-eval (RECOMMENDED)                                    │
│    → Direct SCI execution in bb-mcp-server runtime                     │
│    → Can call: (loader/load-module "modules/...")                      │
│                                                                        │
│  Option B: nrepl + local nREPL server                                  │
│    → Connect to bb instance's own nREPL                                │
│    → More overhead but enables remote introspection                    │
├────────────────────────────────────────────────────────────────────────┤
│  DYNAMICALLY LOADED (via bootstrap module):                            │
│    All other modules loaded on demand                                  │
└────────────────────────────────────────────────────────────────────────┘
```

### Example: Dynamic Loading via local-eval

```clojure
;; Via local-eval MCP tool, an AI agent can:

;; 1. Load the ns_loader (already on core classpath)
(require '[bb-mcp-server.module.ns-loader :as loader])

;; 2. Dynamically load any module
(loader/load-module "modules/calculate")
(loader/load-module "modules/nrepl")

;; 3. Start with configuration
(loader/start-module! "calculate" {} {:precision 8})

;; 4. Use the module's tools...

;; 5. Hot-reload after code changes
(loader/reload-module "calculate")
```

## Module Protocol

Each module must implement this protocol via its entry point var:

```clojure
(def module
  {:name "my-module"
   :description "What this module does"

   ;; MCP tools provided by this module
   :tools
   [{:name "my-tool"
     :description "Tool description"
     :input-schema {:type "object"
                    :properties {:arg {:type "string"}}
                    :required ["arg"]}
     :handler (fn [{:keys [arg]}]
                {:result (process arg)})}]

   ;; Lifecycle functions
   :start (fn [deps config]
            ;; Initialize and return instance
            {:state (atom {})
             :config config})

   :stop (fn [instance]
           ;; Cleanup
           :stopped)

   :status (fn [instance]
             ;; Return current status
             {:running true
              :requests (:requests @(:state instance))})})
```

## Error Handling

### Missing Dependencies
```clojure
(loader/load-module "modules/advanced")
;; => {:error {:type :missing-dependencies
;;             :module "advanced"
;;             :missing ["local-eval" "calculate"]
;;             :message "Module advanced requires: local-eval, calculate"}}
```

### Namespace Not Found
```clojure
;; => {:error {:type :require-failed
;;             :module "broken"
;;             :namespace "broken.core"
;;             :message "Could not locate broken/core.clj on classpath."}}
```

### Invalid Module Protocol
```clojure
;; => {:error {:type :invalid-module
;;             :module "incomplete"
;;             :errors ["Missing required key: :tools"
;;                      "Missing required key: :start"]}}
```

## Metrics

The loader tracks operational metrics:

```clojure
(loader/get-metrics)
;; => {:total-loads 15
;;     :successful-loads 14
;;     :failed-loads 1
;;     :reload-count 3
;;     :last-operation {:module "math"
;;                      :operation :reload
;;                      :time 1700000000000}}
```

## Best Practices

### 1. Keep modules independent
Minimize `:requires` dependencies between modules. Most modules should have `[]`.

### 2. Use namespace deps for code
Let Babashka handle namespace resolution automatically.

### 3. Use module deps for runtime
Only use `:requires` when you need another module's *running instance*.

### 4. Test modules in isolation
```clojure
(loader/reset-state!)
(loader/load-module "modules/my-module")
(loader/start-module! "my-module" {} {})
;; test...
(loader/stop-module! "my-module")
```

### 5. Hot-reload during development
```clojure
;; After editing source files:
(loader/reload-module "my-module")
;; No restart needed!
```

## Why This Approach?

### Before (manual load order) - REMOVED
```clojure
;; Old module.edn required explicit ordering:
{:load-order ["utils" "helpers" "core"]}

;; The old loader.clj had to:
;; 1. Parse each file to find ns dependencies
;; 2. Build dependency graph
;; 3. Topologically sort
;; 4. Load in correct order
;; This code has been deleted - it was redundant complexity.
```

### After (elegant approach)
```clojure
;; New module.edn - just entry point:
{:entry "my-module.core/module"}

;; ns_loader simply:
;; 1. add-classpath src/
;; 2. (require 'my-module.core)
;; Done! Babashka handles the rest.
```

As borkdude designed it - Babashka's require already handles dependency resolution!

## Files

- `src/bb_mcp_server/module/ns_loader.clj` - The elegant module loader
- `src/bb_mcp_server/module/protocol.clj` - Module protocol definition
- `modules/*/module.edn` - Module manifests

## See Also

- [Babashka Classpath](https://book.babashka.org/#_primitives_for_classes)
- [Babashka Deps](https://book.babashka.org/#babashkadeps)
- [Module Protocol](./module-protocol.md)
