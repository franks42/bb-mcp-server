# Module System Design

## Overview

bb-mcp-server uses a directory-per-module architecture where each module is a self-contained unit with its own namespace, configuration, and documentation. Modules follow Component-style lifecycle management.

---

## Directory Structure

```
bb-mcp-server/
├── modules/                      ; All modules live here
│   ├── hello/                    ; Module directory (kebab-case)
│   │   ├── module.edn            ; Module manifest (required)
│   │   ├── README.md             ; Module documentation
│   │   └── src/
│   │       └── hello/            ; Namespace (matches module name)
│   │           └── core.clj      ; Entry point (implements IModule)
│   │
│   ├── crypto-tools/
│   │   ├── module.edn
│   │   ├── README.md
│   │   └── src/
│   │       └── crypto_tools/     ; Note: underscores in namespace
│   │           ├── core.clj
│   │           └── hash.clj      ; Additional namespaces
│   │
│   └── .gitkeep
│
├── modules.edn                   ; Global module configuration (optional)
└── src/
    └── bb_mcp_server/
        └── module/
            ├── protocol.clj      ; IModule protocol
            ├── ns_loader.clj     ; Elegant module loading (add-classpath + require)
            ├── deps.clj          ; Dependency resolution
            └── system.clj        ; System lifecycle management
```

---

## Module Manifest (module.edn)

Every module must have a `module.edn` file in its root:

```clojure
{;; Required fields
 :name "hello"                    ; Unique identifier (kebab-case)
 :version "1.0.0"                 ; SemVer
 :description "A friendly greeting tool"
 :entry-ns hello.core             ; Namespace implementing IModule

 ;; Optional fields
 :author "Your Name"
 :license "MIT"
 :homepage "https://github.com/..."

 ;; Dependencies
 :depends-on []                   ; Other module names (load order)
 :bb-mcp-server ">= 0.2.0"        ; Required server version

 ;; Tools provided (for documentation/validation)
 :tools ["hello"]                 ; Tool names this module registers

 ;; Configuration schema (Malli)
 :config-schema [:map
                 [:greeting {:optional true} :string]]

 ;; Default configuration
 :default-config {:greeting "Hello"}}
```

---

## IModule Protocol

Every module's entry namespace must implement:

```clojure
(ns hello.core
  "Hello module - provides greeting tool."
  (:require [bb-mcp-server.module.protocol :as module]
            [bb-mcp-server.registry :as registry]
            [taoensso.trove :as log]))

;; Module state (if needed)
(defonce ^:private state (atom nil))

;; Tool handler
(defn hello-handler [{:keys [name]}]
  (let [{:keys [greeting]} @state]
    (str greeting ", " name "!")))

;; Tool definition
(def hello-tool
  {:name "hello"
   :description "Returns a greeting message"
   :inputSchema {:type "object"
                 :properties {:name {:type "string"}}
                 :required ["name"]}
   :handler hello-handler})

;; =============================================================================
;; IModule Implementation
;; =============================================================================

(defn start
  "Start the module with injected dependencies and config.

  Args:
    deps   - Map of dependency-name -> started module instance
    config - Module configuration (merged default + user config)

  Returns: Module instance (can be any value, stored for stop/status)

  Side effects:
    - Register tools with registry
    - Initialize any resources"
  [deps config]
  (log/log! {:level :info
             :id ::start
             :msg "Starting hello module"
             :data {:config config}})

  ;; Store config in state
  (reset! state config)

  ;; Register tools
  (registry/register! hello-tool)

  ;; Return module instance (for stop)
  {:tools ["hello"]
   :config config})

(defn stop
  "Stop the module and cleanup resources.

  Args:
    instance - The value returned by start

  Returns: nil

  Side effects:
    - Unregister tools
    - Release any resources"
  [instance]
  (log/log! {:level :info
             :id ::stop
             :msg "Stopping hello module"})

  ;; Unregister tools
  (doseq [tool-name (:tools instance)]
    (registry/unregister! tool-name))

  ;; Clear state
  (reset! state nil)
  nil)

(defn status
  "Return module health/status information.

  Args:
    instance - The value returned by start

  Returns: Map with :status (:ok, :degraded, :error) and optional details"
  [instance]
  {:status :ok
   :tools (:tools instance)
   :config (:config instance)})

;; Export the module interface
(def module
  "Module interface map - required export."
  {:start start
   :stop stop
   :status status})
```

---

## Module Loader (ns_loader.clj)

The elegant module loader leverages Babashka's native classpath and namespace resolution:

```clojure
(require '[bb-mcp-server.module.ns-loader :as loader])

;; Load single module (adds src/ to classpath, requires entry namespace)
(loader/load-module "modules/hello")
;; => {:success {:manifest {...} :module {...} :load-time-ms 102}}

;; Start module with dependencies and config
(loader/start-module! "hello" {} {:greeting "Hi"})
;; => {:success <instance>}

;; Get module status
(loader/get-module-status "hello")
;; => {:name "hello" :version "1.0.0" :loaded true :running true :status {...}}

;; Hot-reload after code changes (no restart needed!)
(loader/reload-module "hello")
```

**Key insight:** Babashka's `require` already handles namespace dependency resolution automatically.
We just add the module's `src/` to classpath and require the entry namespace.

---

## System Lifecycle

The system manages all modules as a unit:

```clojure
;; System map (like Component)
(def system-config
  {:modules ["hello" "crypto-tools"]  ; Load order (or auto from deps)
   :config {:hello {:greeting "Hi"}
            :crypto-tools {:algorithm "sha256"}}})

;; Start all modules in dependency order
(system/start! system-config)

;; Stop all modules in reverse order
(system/stop!)

;; Reload changed modules
(system/reload! ["hello"])

;; Get system status
(system/status)
;; => {:hello {:status :ok ...}
;;     :crypto-tools {:status :ok ...}}
```

---

## Creating a New Module

### Step 1: Create directory structure

```bash
mkdir -p modules/my-tool/src/my_tool
```

### Step 2: Create module.edn

```clojure
;; modules/my-tool/module.edn
{:name "my-tool"
 :version "1.0.0"
 :description "My awesome tool"
 :entry-ns my-tool.core
 :depends-on []
 :tools ["my-tool"]}
```

### Step 3: Implement core.clj

```clojure
;; modules/my-tool/src/my_tool/core.clj
(ns my-tool.core
  (:require [bb-mcp-server.registry :as registry]
            [taoensso.trove :as log]))

(defn my-handler [{:keys [input]}]
  ;; Your tool logic here
  (str "Processed: " input))

(def my-tool
  {:name "my-tool"
   :description "Processes input"
   :inputSchema {:type "object"
                 :properties {:input {:type "string"}}
                 :required ["input"]}
   :handler my-handler})

(defn start [deps config]
  (log/log! {:level :info :id ::start :msg "Starting my-tool"})
  (registry/register! my-tool)
  {:tools ["my-tool"]})

(defn stop [instance]
  (log/log! {:level :info :id ::stop :msg "Stopping my-tool"})
  (doseq [t (:tools instance)] (registry/unregister! t))
  nil)

(defn status [instance]
  {:status :ok :tools (:tools instance)})

(def module {:start start :stop stop :status status})
```

### Step 4: Add README.md

```markdown
# my-tool

My awesome tool for bb-mcp-server.

## Installation

Copy this directory to `modules/my-tool` in your bb-mcp-server installation.

## Configuration

```clojure
{:my-tool {}}  ; Add to modules.edn
```

## Tools

### my-tool

Processes input.

**Parameters:**
- `input` (string, required): The input to process

**Example:**
```json
{"name": "my-tool", "arguments": {"input": "hello"}}
```
```

### Step 5: Test

```bash
bb -e '
(require (quote [bb-mcp-server.module.ns-loader :as loader]))
(let [result (loader/load-module "modules/my-tool")]
  (if (:success result)
    (println "✅ Module loaded successfully")
    (println "❌ Error:" (:error result))))
'
```

---

## Module Checklist

Before publishing a module:

- [ ] `module.edn` is valid and complete
- [ ] Entry namespace implements `start`, `stop`, `status`
- [ ] Exports `module` map with all three functions
- [ ] All tools registered in `start`, unregistered in `stop`
- [ ] README.md documents all tools and configuration
- [ ] `clj-kondo --lint modules/my-tool/src` passes
- [ ] Works with `bb` (no JVM-only dependencies)
- [ ] Tested with bb-mcp-server integration

---

## Dependency Resolution & Load Order

Modules declare dependencies via `:depends-on` in module.edn:

```clojure
;; modules/api-client/module.edn
{:name "api-client"
 :depends-on ["auth" "http-utils"]  ; Must start before api-client
 ...}

;; modules/auth/module.edn
{:name "auth"
 :depends-on []  ; No dependencies
 ...}

;; modules/dashboard/module.edn
{:name "dashboard"
 :depends-on ["api-client" "auth"]  ; Transitive: also needs http-utils
 ...}
```

### Topological Sort

The system's dependency resolver handles load order automatically:

```clojure
;; Module dependencies declared in :requires are checked before loading
;; The system sorts modules by dependencies automatically

;; Example: If dashboard requires api-client which requires auth:
;; Load order: auth → api-client → dashboard
;; Stop order: dashboard → api-client → auth (reverse)
```

**Note:** Namespace-level dependencies within a module are resolved automatically
by Babashka's `require` - no manual ordering needed!

### Start Order vs Stop Order

- **Start:** Dependencies first (topological order)
- **Stop:** Dependents first (reverse topological order)

```
Start: http-utils → auth → api-client → dashboard
Stop:  dashboard → api-client → auth → http-utils
```

### Cycle Detection

Circular dependencies are detected and rejected:

```clojure
;; module-a depends-on module-b
;; module-b depends-on module-a
;; => ERROR: Circular dependency detected: module-a -> module-b -> module-a
```

### Dependency Injection

Started modules are passed to dependents:

```clojure
;; In api-client/core.clj
(defn start [deps config]
  ;; deps contains started instances of declared dependencies
  (let [auth-instance (get deps "auth")
        http-instance (get deps "http-utils")]
    ;; Use them...
    ))
```

### Optional Dependencies

Use `:optional-deps` for soft dependencies:

```clojure
{:name "my-tool"
 :depends-on ["core"]           ; Required - fails if missing
 :optional-deps ["analytics"]   ; Optional - nil in deps if missing
 ...}
```

```clojure
(defn start [deps config]
  (let [analytics (get deps "analytics")]  ; May be nil
    (when analytics
      (analytics/track "module-started" {:module "my-tool"}))
    ;; Continue without analytics if not present
    ))
```

---

## Configuration Hierarchy

Configuration is merged in order (later overrides earlier):

1. Module's `:default-config` in module.edn
2. Global `modules.edn` configuration
3. Environment variables (MODULE_NAME__KEY=value)
4. Runtime configuration passed to `system/start!`

---

## Error Handling

Modules should:

1. Throw `ex-info` with `:type` key for categorized errors
2. Log errors with `:level :error` before throwing
3. Clean up partial state if `start` fails
4. Handle missing optional dependencies gracefully

```clojure
(defn start [deps config]
  (when-not (:api-key config)
    (throw (ex-info "Missing required config: api-key"
                    {:type :config-error
                     :module "my-tool"
                     :missing [:api-key]})))
  ;; ... rest of start
  )
```

---

*Status: Design complete, ready for Phase 4.3 implementation*
