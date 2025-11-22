# bb-mcp-server: Clean Modular MCP Server Architecture

## Vision

A **clean, modular Babashka MCP server** with lessons learned from mcp-nrepl-joyride, designed from the ground up for:
- **Triple interface** (stdio, HTTP, REST) built-in
- **Dynamic tool loading** as first-class feature
- **Modular architecture** (core + plugins)
- **Generic foundation** (not coupled to any specific domain)

---

## Lessons Learned from mcp-nrepl-joyride

### What Worked ✅
1. **FQN-based tool registration** - No require needed in SCI
2. **Dynamic tool loading** - Load tools at runtime
3. **Babashka as platform** - Fast startup, good stdlib
4. **Atom-based registry** - Simple, effective state

### What to Improve 🔧
1. **Name coupling** - "nrepl" in name limits perceived scope
2. **stdio-only initially** - HTTP support retrofitted
3. **nREPL baked into core** - Should be a module
4. **No REST API** - Only MCP protocol
5. **Single transport focus** - Triple interface should be from day 1

### New Architecture Decisions 🎯
1. **Triple interface native** - stdio, HTTP, REST from start
2. **Core + modules** - nREPL is just one module
3. **Generic name** - bb-mcp-server (not domain-specific)
4. **Cloud-first thinking** - HTTP/cloud deployment as primary use case
5. **Plugin architecture** - Easy to add new tool domains

### Critical Implementation Lessons 🔴

**LESSON: MCP Protocol Versions Are Spec Release Dates**

During Phase 1.2 testing, we discovered that `protocolVersion` is NOT a semantic version (like "1.0") but rather the **release date of the MCP specification** in YYYY-MM-DD format:

- **"2024-11-05"** - First stable MCP spec (uses SSE for long-lived connections)
- **"2025-03-26"** - Major update with OAuth 2.1, chunked HTTP streaming, breaking changes
- **"2025-06-18"** - Removed JSON-RPC batching (more breaking changes)

**What went wrong:**
- We assumed `protocolVersion: "1.0"` based on semantic versioning conventions
- Claude Code sent the actual spec version `"2024-11-05"`
- Server rejected with error -32602 "Invalid params"
- Connection failed silently from user perspective

**Correct implementation (learned from nrepl-mcp-server):**
```clojure
(defn handle-initialize [request]
  ;; Don't validate protocol version - just return a fixed response
  {:protocolVersion "2024-11-05"  ; Current stable spec version
   :serverInfo {:name "bb-mcp-server" :version "0.1.0"}
   :capabilities {:tools {}}})
```

**Why this works:**
- The MCP spec expects servers to return a supported version
- If client sends "2024-11-05" or "2025-03-26", server can respond with either
- Version negotiation happens through the response, not validation
- Strict validation breaks compatibility with newer/older clients

**Key takeaways:**
1. 📚 **RTFM: Read the specification FIRST** - Don't assume based on other protocols!
2. ✅ Always consult the official MCP specification (spec.modelcontextprotocol.io)
3. ✅ Protocol version format: YYYY-MM-DD (not semantic versioning)
4. ✅ Return a fixed supported version, don't validate client's version
5. ✅ Test with real MCP clients (Claude Code, not just curl)
6. ✅ Study working implementations (Python SDK, TypeScript SDK, nrepl-mcp-server)

**The REAL lesson:** We spent hours debugging because we assumed semantic versioning
(like "1.0") instead of reading the spec that clearly documented the date format.
The "excellent debugging" was only necessary because we didn't RTFM! 😅

**Resources:**
- Official MCP Spec: https://spec.modelcontextprotocol.io/
- Python SDK: https://github.com/modelcontextprotocol/python-sdk
- TypeScript SDK: https://github.com/modelcontextprotocol/typescript-sdk
- Changelog: https://modelcontextprotocol.io/specification/2025-03-26/changelog

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                bb-mcp-server Core                   │
│                                                     │
│  - Transport layer (stdio, HTTP, REST)             │
│  - Tool registry (dynamic loading)                 │
│  - Request routing (JSONRPC, REST)                 │
│  - Module system (load/unload)                     │
└────────────────────┬────────────────────────────────┘
                     │
         ┌───────────┼──────────┬──────────────┐
         ▼           ▼          ▼              ▼
    ┌─────────┐ ┌─────────┐ ┌─────────┐  ┌─────────┐
    │ nREPL   │ │Blockchain│ │File Ops│  │Custom   │
    │ Module  │ │ Module  │ │ Module │  │ Modules │
    └─────────┘ └─────────┘ └─────────┘  └─────────┘
     (optional)   (optional)  (optional)   (loadable)
```

---

## Project Structure

```
bb-mcp-server/
├── bb.edn                      # Tasks and deps
├── src/
│   ├── bb_mcp_server/
│   │   ├── core.clj           # Core MCP protocol
│   │   ├── registry.clj       # Tool registry
│   │   ├── telemetry.clj      # Trove logging
│   │   ├── config.clj         # Configuration management
│   │   ├── metrics.clj        # Metrics collection
│   │   ├── transport/
│   │   │   ├── stdio.clj      # stdio interface
│   │   │   ├── http.clj       # MCP HTTP interface
│   │   │   └── rest.clj       # REST API interface
│   │   ├── server/
│   │   │   ├── stdio.clj      # stdio server
│   │   │   ├── http.clj       # HTTP server
│   │   │   └── triple.clj     # All three
│   │   └── loader.clj         # Dynamic module loading
│   └── modules/
│       ├── nrepl.clj          # nREPL module (loadable)
│       ├── filesystem.clj     # File operations module
│       └── examples.clj       # Example tools
├── test/
│   ├── bb_mcp_server/
│   │   ├── registry_test.clj
│   │   ├── loader_test.clj
│   │   └── transport_test.clj
│   └── modules/
│       └── nrepl_test.clj
├── templates/
│   ├── module-template.clj    # Template for bb scaffold
│   └── module-test-template.clj
├── startup.clj                # Auto-load modules
└── docs/
    ├── architecture.md
    ├── module-development.md
    └── deployment.md
```

---

## Core Components

### 1. Tool Registry (core/registry.clj)

```clojure
(ns bb-mcp-server.registry)

(defonce registry (atom {}))

(defn register-tool!
  "Register a tool dynamically - works across all transports"
  [name handler metadata]
  (swap! registry assoc name
         {:handler handler
          :metadata metadata
          :registered-at (System/currentTimeMillis)}))

(defn unregister-tool!
  "Unregister a tool"
  [name]
  (swap! registry dissoc name))

(defn list-tools
  "Get all registered tools"
  []
  (vals @registry))

(defn get-tool
  "Get tool by name"
  [name]
  (get @registry name))

(defn call-tool
  "Invoke a tool by name"
  [name arguments]
  (if-let [tool (get-tool name)]
    ((:handler tool) arguments)
    {:error "Tool not found"}))
```

### 2. Transport Layer (core/transport/*.clj)

Each transport is a **thin adapter** over core handlers:

```clojure
;; transport/stdio.clj
(ns bb-mcp-server.transport.stdio
  (:require [bb-mcp-server.core :as core]))

(defn handle-jsonrpc-request
  "JSONRPC 2.0 handler - transport agnostic"
  [request]
  (case (:method request)
    "tools/list"
    {:jsonrpc "2.0"
     :id (:id request)
     :result {:tools (core/list-tools)}}

    "tools/call"
    {:jsonrpc "2.0"
     :id (:id request)
     :result (core/call-tool
              (get-in request [:params :name])
              (get-in request [:params :arguments]))}))

;; transport/http.clj - same handler, HTTP wrapper

;; transport/rest.clj - maps REST paths to tools
```

### 3. Module Lifecycle Protocol

**Stateful modules** (those managing connections, servers, resources) implement a lifecycle protocol:

```clojure
(ns bb-mcp-server.lifecycle)

;; Lifecycle protocol for stateful modules
(defprotocol ILifecycle
  "Lifecycle management for stateful modules"
  (start [this config]
    "Start the module with given config. Returns started module instance.")
  (stop [this]
    "Stop the module and cleanup resources. Returns stopped module instance.")
  (status [this]
    "Get current module status. Returns map with :state and details."))

;; Lifecycle states
(def lifecycle-states
  #{:stopped    ; Module loaded but not started
    :starting   ; Module is starting
    :running    ; Module running normally
    :stopping   ; Module is stopping
    :failed     ; Module failed to start or crashed
    :restarting ; Module is restarting
    })

;; Helper functions
(defn stopped? [module]
  (= :stopped (-> module status :state)))

(defn running? [module]
  (= :running (-> module status :state)))

(defn failed? [module]
  (= :failed (-> module status :state)))
```

### 4. Module System with Lifecycle (core/loader.clj)

```clojure
(ns bb-mcp-server.loader
  (:require [bb-mcp-server.registry :as registry]
            [bb-mcp-server.lifecycle :as lc]
            [bb-mcp-server.telemetry :as tel]
            [clojure.java.io :as io]))

;; Module registry: path -> module instance
(defonce loaded-modules (atom {}))

;; Module metadata: path -> {:name :version :type :tools}
(defonce module-metadata (atom {}))

(defn load-module!
  "Load a module file and optionally start it"
  ([module-path]
   (load-module! module-path nil))
  ([module-path config]
   (tel/with-telemetry "module.load"
     {:module (module-name-from-path module-path)
      :path module-path}
     (when-not (get @loaded-modules module-path)
       ;; Load the file (registers tools)
       (load-file module-path)

       ;; Get module instance if it implements lifecycle
       (let [module-ns (module-ns-from-path module-path)
             module-instance (when module-ns
                              (try
                                (requiring-resolve (symbol module-ns "module-instance"))
                                (catch Exception _ nil)))]

         ;; Store module instance
         (swap! loaded-modules assoc module-path module-instance)

         ;; Start if stateful
         (when (and module-instance (satisfies? lc/ILifecycle @module-instance))
           (swap! module-instance lc/start (or config {})))

         {:status :loaded
          :module module-path
          :stateful (some? module-instance)})))))

(defn start-module!
  "Start a loaded module (if stateful)"
  ([module-path]
   (start-module! module-path nil))
  ([module-path config]
   (tel/with-telemetry "module.start"
     {:module (module-name-from-path module-path)}
     (if-let [module-instance (get @loaded-modules module-path)]
       (when (satisfies? lc/ILifecycle @module-instance)
         (swap! module-instance lc/start (or config {}))
         {:status :started :module module-path})
       {:status :error :reason :not-loaded}))))

(defn stop-module!
  "Stop a running module"
  [module-path]
  (tel/with-telemetry "module.stop"
    {:module (module-name-from-path module-path)}
    (if-let [module-instance (get @loaded-modules module-path)]
      (when (satisfies? lc/ILifecycle @module-instance)
        (swap! module-instance lc/stop)
        {:status :stopped :module module-path})
      {:status :error :reason :not-loaded})))

(defn restart-module!
  "Restart a module (stop then start)"
  ([module-path]
   (restart-module! module-path nil))
  ([module-path config]
   (tel/with-telemetry "module.restart"
     {:module (module-name-from-path module-path)}
     (stop-module! module-path)
     (start-module! module-path config))))

(defn module-status
  "Get status of a module"
  [module-path]
  (if-let [module-instance (get @loaded-modules module-path)]
    (if (satisfies? lc/ILifecycle @module-instance)
      (lc/status @module-instance)
      {:state :stateless :module module-path})
    {:state :not-loaded :module module-path}))

(defn unload-module!
  "Stop and unload a module"
  [module-path]
  (tel/with-telemetry "module.unload"
    {:module (module-name-from-path module-path)}
    (when (get @loaded-modules module-path)
      ;; Stop if stateful
      (stop-module! module-path)

      ;; Unregister tools
      (when-let [metadata (get @module-metadata module-path)]
        (doseq [tool-name (:tools metadata)]
          (registry/unregister-tool! tool-name)))

      ;; Remove from registry
      (swap! loaded-modules dissoc module-path)
      (swap! module-metadata dissoc module-path)

      {:status :unloaded :module module-path})))

(defn list-modules
  "List all loaded modules with their status"
  []
  (into {}
    (map (fn [[path instance]]
           [path (module-status path)])
         @loaded-modules)))

(defn start-all-modules!
  "Start all loaded modules"
  []
  (tel/log-event! "modules.start-all" {})
  (doseq [[path _] @loaded-modules]
    (start-module! path)))

(defn stop-all-modules!
  "Stop all running modules (reverse order)"
  []
  (tel/log-event! "modules.stop-all" {})
  (doseq [[path _] (reverse @loaded-modules)]
    (stop-module! path)))

(defn reload-module!
  "Reload a module (unload, load, start)"
  ([module-path]
   (reload-module! module-path nil))
  ([module-path config]
   (tel/with-telemetry "module.reload"
     {:module (module-name-from-path module-path)}
     (unload-module! module-path)
     (load-module! module-path config)
     (start-module! module-path config))))

(defn health-check
  "Check health of all modules"
  []
  (into {}
    (map (fn [[path _]]
           (let [status (module-status path)]
             [path {:healthy (or (= :stateless (:state status))
                                (= :running (:state status)))
                    :status status}]))
         @loaded-modules)))

;; Graceful shutdown hook
(defn register-shutdown-hook!
  "Register JVM shutdown hook to stop all modules"
  []
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    (fn []
      (tel/log-event! "server.shutdown" {:reason "jvm-shutdown"})
      (stop-all-modules!)))))
```

### 4. Configuration System

#### Configuration File Discovery

bb-mcp-server supports **cascading configuration** with project-based tool loading:

1. **Global config** (optional): `~/.config/bb-mcp-server/config.edn`
2. **Project config** (optional): `./.bb-mcp-server.edn` in current directory
3. **Startup script** (legacy): `startup.clj` in server root

**Resolution order**: Project config → Global config → Defaults

#### Project-Based Configuration Format

```clojure
;; .bb-mcp-server.edn - Project-specific MCP tools
{:modules
 {:load-on-startup
  [;; Relative paths (relative to config file location)
   {:path "tools/project-tools.clj"
    :enabled true}

   ;; Built-in modules (from bb-mcp-server/src/modules/)
   {:path "modules/nrepl.clj"
    :enabled true
    :config {:auto-connect [{:host "localhost" :port 7890}]}}

   ;; Absolute paths for shared tools
   {:path "/Users/you/.config/bb-mcp-server/modules/custom.clj"
    :enabled true}

   ;; Conditional loading
   {:path "tools/dev-tools.clj"
    :enabled (= (System/getenv "ENV") "development")}]

  :auto-start true}  ; Start modules on server startup

 :server
 {:port 9339
  :host "localhost"
  :transports [:stdio :http :rest]}  ; Which interfaces to enable

 :telemetry
 {:level :info  ; :debug | :info | :warn | :error
  :format :json  ; :json | :edn
  :output :stdout  ; :stdout | :file | :both
  :file-path "./logs/bb-mcp-server.log"}}
```

#### Example: Clay Project Configuration

```clojure
;; clay-noj-ai/.bb-mcp-server.edn
{:modules
 {:load-on-startup
  [;; Project-specific notebook tools
   {:path "tools/clay-tools.clj"
    :enabled true
    :config {:clay-port 1971
             :nrepl-port 7890}}

   ;; Built-in nREPL support
   {:path "modules/nrepl.clj"
    :enabled true
    :config {:auto-connect [{:host "localhost" :port 7890}]}}

   ;; Blockchain tools for wallet analysis
   {:path "tools/provenance-tools.clj"
    :enabled true}]}

 :server
 {:port 9339
   :host "localhost"}

 :telemetry
 {:level :debug
  :file-path "./logs/mcp-server.log"}}
```

#### Config Loading Implementation

```clojure
;; src/bb_mcp_server/config.clj
(ns bb-mcp-server.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [bb-mcp-server.telemetry :as tel]))

(def default-config
  {:modules
   {:load-on-startup []
    :auto-start true}
   :server
   {:port 9339
    :host "localhost"
    :transports [:stdio :http :rest]}
   :telemetry
   {:level :info
    :format :json
    :output :stdout
    :file-path nil}})

(defn find-config-file
  "Find config file in order: project dir, global config, none"
  []
  (let [project-config "./.bb-mcp-server.edn"
        global-config (str (System/getProperty "user.home")
                          "/.config/bb-mcp-server/config.edn")]
    (cond
      (.exists (io/file project-config))
      {:path project-config :source :project}

      (.exists (io/file global-config))
      {:path global-config :source :global}

      :else
      {:path nil :source :default})))

(defn load-config!
  "Load and merge configs: defaults < global < project"
  []
  (let [{:keys [path source]} (find-config-file)]
    (if path
      (try
        (let [user-config (edn/read-string (slurp path))
              merged-config (merge-with merge default-config user-config)]
          (tel/log-event! "config.loaded"
                         {:source source
                          :path path
                          :modules-count (count (get-in merged-config
                                                        [:modules :load-on-startup]))})
          merged-config)
        (catch Exception e
          (tel/log-error! "config.load-failed" e {:path path})
          (println "⚠️  Config load failed, using defaults")
          default-config))
      (do
        (tel/log-event! "config.using-defaults" {})
        default-config))))

(defn resolve-module-path
  "Resolve module path relative to config file location"
  [module-path config-source config-path]
  (cond
    ;; Absolute path - use as-is
    (.isAbsolute (io/file module-path))
    module-path

    ;; Built-in module (starts with "modules/")
    (.startsWith module-path "modules/")
    (str "src/" module-path)  ; Assumes bb-mcp-server/src/modules/

    ;; Relative path - resolve relative to config file
    (= config-source :project)
    (let [config-dir (.getParent (io/file config-path))]
      (str config-dir "/" module-path))

    ;; Global config - relative paths are relative to config location
    (= config-source :global)
    (let [config-dir (.getParent (io/file config-path))]
      (str config-dir "/" module-path))

    :else
    module-path))

(defn init-from-config!
  "Initialize server from configuration file"
  []
  (let [config (load-config!)
        {:keys [path source]} (find-config-file)
        modules-to-load (get-in config [:modules :load-on-startup])]

    (tel/log-event! "server.init-from-config"
                   {:config-source source
                    :modules-count (count modules-to-load)})

    ;; Load modules if auto-start enabled
    (when (get-in config [:modules :auto-start])
      (doseq [{:keys [path enabled config]} modules-to-load]
        (when enabled
          (let [resolved-path (resolve-module-path path source
                                                   (find-config-file))]
            (try
              (require '[bb-mcp-server.loader :as loader])
              (loader/load-module! resolved-path)
              (when config
                (loader/start-module! resolved-path config))
              (tel/log-event! "module.loaded-from-config"
                             {:module path
                              :resolved-path resolved-path})
              (catch Exception e
                (tel/log-error! "module.load-failed" e
                               {:module path
                                :resolved-path resolved-path})))))))

    config))
```

#### Startup Script (Legacy Support)

```clojure
;; startup.clj - Legacy, still supported for simple cases
(load-module! "src/modules/nrepl.clj")
(load-module! "src/modules/filesystem.clj")

;; Module can configure itself
(when (loaded? "nrepl")
  (nrepl-module/set-default-port! 7890))
```

**Note**: Config files (`.bb-mcp-server.edn`) are preferred over startup scripts for better declarative configuration.

---

## Telemetry and Logging (Trove)

**📖 Implementation Guide: See `docs/AI_TELEMETRY_GUIDE.md` for coding patterns and examples.**

### Design Philosophy

**Comprehensive structured logging from day one**, not retrofitted later.

Every significant event in the system generates structured JSON logs using **Trove** (facade) with **Timbre** (backend), enabling:
- Real-time monitoring and debugging
- Performance analysis
- Security auditing
- User behavior insights
- Cloud deployment observability

### Why Trove?

**Trove** (https://github.com/askonomm/trove) is a simple, structured logging library for Clojure:

✅ **Structured JSON** - Machine-readable logs
✅ **Babashka compatible** - Works in BB and JVM Clojure
✅ **Minimal dependencies** - Lightweight
✅ **Simple API** - Easy to use
✅ **Performance** - Low overhead

Future: When Telemere supports Babashka, swap the backend binding in `telemetry/init!` - no call site changes needed.

### What to Log

#### Core Events (Always)

```clojure
;; Server lifecycle
{:event "server.started"
 :transport "triple"  ; stdio | http | triple
 :port 9339
 :timestamp "2025-11-20T10:30:00Z"}

{:event "server.shutdown"
 :reason "user_requested"
 :uptime_seconds 3600}

;; Module loading
{:event "module.loaded"
 :module "nrepl"
 :path "src/modules/nrepl.clj"
 :tools_count 3
 :load_time_ms 45}

{:event "module.load_failed"
 :module "custom"
 :error "FileNotFoundException"
 :path "/invalid/path.clj"}

;; Tool registration
{:event "tool.registered"
 :tool_name "nrepl-eval"
 :module "nrepl"
 :schema_valid true}

{:event "tool.unregistered"
 :tool_name "nrepl-eval"
 :reason "module_unloaded"}
```

#### Request/Response Events

```clojure
;; Tool invocation (all transports)
{:event "tool.call.started"
 :request_id "req-abc123"
 :tool_name "nrepl-eval"
 :transport "http"  ; stdio | http | rest
 :client_id "claude-instance-1"
 :arguments_size 256}

{:event "tool.call.completed"
 :request_id "req-abc123"
 :tool_name "nrepl-eval"
 :duration_ms 150
 :success true
 :result_size 1024}

{:event "tool.call.failed"
 :request_id "req-abc123"
 :tool_name "nrepl-eval"
 :duration_ms 45
 :error "ConnectionRefused"
 :error_details "nREPL connection not found"}
```

#### Transport-Specific Events

```clojure
;; stdio transport
{:event "stdio.connection.established"
 :pid 12345}

{:event "stdio.connection.closed"
 :duration_seconds 3600
 :requests_handled 150}

;; HTTP transport
{:event "http.request.received"
 :request_id "req-xyz789"
 :method "POST"
 :path "/mcp"
 :client_ip "192.168.1.100"
 :user_agent "Claude/1.0"}

{:event "http.request.completed"
 :request_id "req-xyz789"
 :status_code 200
 :duration_ms 89}

;; REST API
{:event "rest.request.received"
 :request_id "req-rest-456"
 :method "GET"
 :path "/api/tools"
 :query_params {:filter "nrepl"}}
```

#### Performance Metrics

```clojure
;; Periodic metrics
{:event "metrics.snapshot"
 :timestamp "2025-11-20T10:35:00Z"
 :uptime_seconds 300
 :tools_registered 15
 :modules_loaded 3
 :requests_total 450
 :requests_per_second 1.5
 :avg_response_ms 120
 :p95_response_ms 350
 :p99_response_ms 750
 :errors_total 5
 :error_rate 0.011}
```

#### Security Events

```clojure
;; Authentication/authorization (if implemented)
{:event "auth.token.validated"
 :token_id "tok-abc"
 :client_id "client-123"
 :valid true}

{:event "auth.access.denied"
 :tool_name "admin-tool"
 :client_id "client-456"
 :reason "insufficient_permissions"}

;; Rate limiting
{:event "ratelimit.exceeded"
 :client_id "client-789"
 :tool_name "nrepl-eval"
 :limit 100
 :period "1m"}
```

### Implementation

#### 1. Telemetry Namespace (core/telemetry.clj)

```clojure
(ns bb-mcp-server.telemetry
  (:require [trove.core :as log]))

;; Configuration
(def log-config
  (atom {:level :info          ; :debug | :info | :warn | :error
         :format :json         ; :json | :edn
         :output :stdout       ; :stdout | :file
         :file-path nil        ; "/var/log/bb-mcp-server.log"
         :structured true}))

;; Core logging functions
(defn log-event!
  "Log structured event"
  [event-type data]
  (log/info {:event event-type
             :timestamp (java.time.Instant/now)
             :data data}))

(defn log-error!
  "Log error event"
  [event-type error data]
  (log/error {:event event-type
              :timestamp (java.time.Instant/now)
              :error (str error)
              :error-type (type error)
              :data data}))

;; Convenience macros
(defmacro with-telemetry
  "Execute body with automatic start/completion logging"
  [event-type data & body]
  `(let [start# (System/currentTimeMillis)
         request-id# (str (random-uuid))]
     (log-event! ~(str event-type ".started")
                (assoc ~data :request_id request-id#))
     (try
       (let [result# (do ~@body)]
         (log-event! ~(str event-type ".completed")
                    {:request_id request-id#
                     :duration_ms (- (System/currentTimeMillis) start#)
                     :success true})
         result#)
       (catch Exception e#
         (log-error! ~(str event-type ".failed") e#
                    {:request_id request-id#
                     :duration_ms (- (System/currentTimeMillis) start#)})
         (throw e#)))))
```

#### 2. Integration in Core Components

**Registry with telemetry:**

```clojure
(ns bb-mcp-server.registry
  (:require [bb-mcp-server.telemetry :as tel]))

(defn register-tool!
  [name handler metadata]
  (tel/log-event! "tool.registered"
                 {:tool_name name
                  :module (:module metadata)
                  :schema_valid (validate-schema metadata)})
  (swap! registry assoc name
         {:handler handler
          :metadata metadata
          :registered-at (System/currentTimeMillis)}))

(defn call-tool
  [name arguments]
  (tel/with-telemetry "tool.call"
    {:tool_name name
     :arguments_size (count (pr-str arguments))}
    (if-let [tool (get-tool name)]
      ((:handler tool) arguments)
      (throw (ex-info "Tool not found" {:tool name})))))
```

**Module loader with telemetry:**

```clojure
(ns bb-mcp-server.loader
  (:require [bb-mcp-server.telemetry :as tel]))

(defn load-module!
  [module-path]
  (tel/with-telemetry "module.load"
    {:module (module-name-from-path module-path)
     :path module-path}
    (when-not (@loaded-modules module-path)
      (load-file module-path)
      (swap! loaded-modules conj module-path)
      {:status :loaded :module module-path})))
```

**HTTP server with telemetry:**

```clojure
(ns bb-mcp-server.server.http
  (:require [bb-mcp-server.telemetry :as tel]))

(defn handle-request
  [req]
  (let [request-id (str (random-uuid))]
    (tel/log-event! "http.request.received"
                   {:request_id request-id
                    :method (:request-method req)
                    :path (:uri req)
                    :client_ip (:remote-addr req)})
    (try
      (let [start (System/currentTimeMillis)
            response (process-request req)]
        (tel/log-event! "http.request.completed"
                       {:request_id request-id
                        :status_code (:status response)
                        :duration_ms (- (System/currentTimeMillis) start)})
        response)
      (catch Exception e
        (tel/log-error! "http.request.failed" e
                       {:request_id request-id})
        {:status 500 :body "Internal error"}))))
```

#### 3. Startup Configuration

```clojure
;; startup.clj
(require '[bb-mcp-server.telemetry :as tel])

;; Configure telemetry
(reset! tel/log-config
        {:level :info
         :format :json
         :output :file
         :file-path "/var/log/bb-mcp-server.log"})

;; Log server start
(tel/log-event! "server.started"
               {:transport "triple"
                :port 9339
                :version "1.0.0"})
```

### Log Analysis and Monitoring

#### Query Examples (using jq)

**Find slow requests:**
```bash
cat /var/log/bb-mcp-server.log | \
  jq 'select(.event == "tool.call.completed" and .data.duration_ms > 1000)'
```

**Error rate by tool:**
```bash
cat /var/log/bb-mcp-server.log | \
  jq 'select(.event == "tool.call.failed") | .data.tool_name' | \
  sort | uniq -c
```

**Module load times:**
```bash
cat /var/log/bb-mcp-server.log | \
  jq 'select(.event == "module.loaded") | {module: .data.module, load_time: .data.load_time_ms}'
```

**Request rate per minute:**
```bash
cat /var/log/bb-mcp-server.log | \
  jq -r 'select(.event == "tool.call.started") | .timestamp' | \
  cut -d: -f1,2 | sort | uniq -c
```

#### Cloud Monitoring Integration

For cloud deployments, send logs to:
- **AWS CloudWatch**: Use CloudWatch agent to ingest JSON logs
- **Google Cloud Logging**: Stream to Cloud Logging API
- **Datadog**: Use Datadog agent with JSON log parsing
- **Elasticsearch**: Ship logs via Filebeat/Fluentd

### Telemetry Best Practices

1. **Always log structured data** - No free-form strings
2. **Include request IDs** - Trace requests across system
3. **Log durations** - Track performance over time
4. **Sanitize sensitive data** - Never log passwords, tokens
5. **Use appropriate levels** - debug/info/warn/error correctly
6. **Log context** - Include enough data to diagnose issues
7. **Avoid log spam** - Rate-limit high-frequency events if needed

### Performance Considerations

**Logging overhead:**
- Structured logging: ~0.1-1ms per event
- Async logging: Can reduce to <0.1ms
- File I/O: Use buffering to minimize impact

**Volume management:**
- Implement log rotation (logrotate, or built-in)
- Compress old logs
- Set retention policies
- Sample high-frequency events if needed

### Dependencies

Add to bb.edn:

```clojure
{:deps {;; Logging
        io.github.askonomm/trove {:mvn/version "1.1.0"}
        ;; OR for advanced features:
        ;; com.taoensso/telemere {:mvn/version "1.0.0-beta24"}
        }}
```

---

## Security Model

### Overview

**Security-first design** is critical for bb-mcp-server, especially given:
- Code execution capabilities (nREPL eval)
- Network-exposed HTTP/REST endpoints
- Dynamic module loading from configuration files
- Multi-user cloud deployment scenarios

This section addresses security concerns identified during architecture review by three independent LLM reviewers (Gemini 3, GPT-5.1, Grok).

### Security Principles

1. **Defense in Depth**: Multiple security layers, not relying on single mechanism
2. **Least Privilege**: Minimal permissions by default, explicit grants required
3. **Secure by Default**: Safe configuration out of the box
4. **Fail Secure**: Errors should deny access, not grant it
5. **Auditability**: All security events logged via telemetry

---

### Transport Security (HTTP/REST)

#### Authentication Mechanisms

**Problem**: HTTP/REST endpoints expose tools (including code execution) to network without authentication.

**Solution**: Pluggable authentication middleware with multiple options.

##### 1. API Key Authentication (Default for Cloud)

```clojure
;; src/bb_mcp_server/security/auth.clj
(ns bb-mcp-server.security.auth
  (:require [bb-mcp-server.telemetry :as tel]
            [clojure.string :as str]))

(defn generate-api-key
  "Generate cryptographically secure API key"
  []
  (let [random-bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) random-bytes)
    (-> random-bytes
        (java.util.Base64/getEncoder)
        (.encodeToString)
        (str/replace #"[/+=]" ""))))  ; URL-safe

(defn hash-api-key
  "Hash API key for storage (SHA-256)"
  [api-key]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes api-key "UTF-8"))]
    (-> hash-bytes
        (java.util.Base64/getEncoder)
        (.encodeToString))))

(defonce valid-keys
  "Registry of valid API key hashes"
  (atom #{}))

(defn add-api-key!
  "Add API key to valid keys (stores hash, not plaintext)"
  [api-key]
  (let [key-hash (hash-api-key api-key)]
    (swap! valid-keys conj key-hash)
    (tel/log-event! "security.api-key.added" {:key-hash key-hash})))

(defn validate-api-key
  "Validate API key against registry"
  [api-key]
  (let [key-hash (hash-api-key api-key)]
    (contains? @valid-keys key-hash)))

(defn auth-middleware
  "Middleware to check API key in request headers"
  [handler]
  (fn [request]
    (let [api-key (get-in request [:headers "x-api-key"])
          auth-header (get-in request [:headers "authorization"])
          bearer-token (when auth-header
                        (second (str/split auth-header #"Bearer ")))]
      (cond
        ;; stdio transport - always allow (no network exposure)
        (= (:transport request) :stdio)
        (handler request)

        ;; API key in custom header
        (and api-key (validate-api-key api-key))
        (do
          (tel/log-event! "security.auth.success"
                         {:method (:request-method request)
                          :uri (:uri request)
                          :auth-type :api-key})
          (handler request))

        ;; Bearer token
        (and bearer-token (validate-api-key bearer-token))
        (do
          (tel/log-event! "security.auth.success"
                         {:method (:request-method request)
                          :uri (:uri request)
                          :auth-type :bearer})
          (handler request))

        ;; No valid auth
        :else
        (do
          (tel/log-event! "security.auth.failed"
                         {:method (:request-method request)
                          :uri (:uri request)
                          :remote-addr (:remote-addr request)
                          :reason (cond
                                    (nil? api-key) :missing-key
                                    :else :invalid-key)})
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body (cheshire.core/generate-string
                  {:error "Unauthorized"
                   :message "Valid API key required in X-API-Key header or Authorization: Bearer header"})})))))
```

**Configuration**:

```clojure
;; .bb-mcp-server.edn
{:security
 {:auth
  {:enabled true
   :type :api-key  ; :api-key | :mTLS | :none (stdio only)
   :api-keys-file "~/.config/bb-mcp-server/api-keys.edn"}  ; Secure storage

 :rate-limiting
  {:enabled true
   :requests-per-minute 60
   :burst 10}}}
```

**Usage**:

```bash
# Generate API key
bb security:generate-key
# Output: mcp_BQx8vK2NpLm9... (store securely!)

# Add to server
bb security:add-key mcp_BQx8vK2NpLm9...

# Client usage
curl -H "X-API-Key: mcp_BQx8vK2NpLm9..." \
     http://localhost:9339/api/tools
```

##### 2. mTLS (Mutual TLS) - Optional

```clojure
;; For high-security deployments
{:security
 {:auth
  {:enabled true
   :type :mTLS
   :ca-cert "certs/ca.pem"
   :server-cert "certs/server.pem"
   :server-key "certs/server-key.pem"
   :client-certs ["certs/client1.pem" "certs/client2.pem"]}}}
```

##### 3. None (stdio only - Default for Local)

```clojure
;; Safe for stdio-only usage (no network exposure)
{:security
 {:auth
  {:enabled false
   :type :none
   :stdio-only true}}}  ; Enforces stdio-only mode
```

#### Rate Limiting

**Problem**: Prevent abuse and DoS attacks on HTTP/REST endpoints.

**Solution**: Token bucket rate limiting per client IP.

```clojure
;; src/bb_mcp_server/security/rate_limit.clj
(ns bb-mcp-server.security.rate-limit
  (:require [bb-mcp-server.telemetry :as tel]))

(defonce rate-limit-state
  "Token bucket state per client IP"
  (atom {}))

(defn rate-limit-middleware
  "Middleware to enforce rate limits"
  [handler {:keys [requests-per-minute burst]}]
  (fn [request]
    (let [client-ip (:remote-addr request)
          now (System/currentTimeMillis)
          bucket (get @rate-limit-state client-ip
                     {:tokens burst
                      :last-refill now})]

      ;; Refill tokens based on elapsed time
      (let [elapsed-ms (- now (:last-refill bucket))
            tokens-to-add (* (/ elapsed-ms 60000.0) requests-per-minute)
            new-tokens (min burst (+ (:tokens bucket) tokens-to-add))]

        (if (>= new-tokens 1)
          ;; Allow request, consume 1 token
          (do
            (swap! rate-limit-state assoc client-ip
                   {:tokens (dec new-tokens)
                    :last-refill now})
            (handler request))

          ;; Rate limit exceeded
          (do
            (tel/log-event! "security.rate-limit.exceeded"
                           {:client-ip client-ip
                            :uri (:uri request)
                            :tokens new-tokens})
            {:status 429
             :headers {"Content-Type" "application/json"
                       "Retry-After" "60"}
             :body (cheshire.core/generate-string
                    {:error "Rate limit exceeded"
                     :message "Too many requests. Please try again later."})}))))))
```

---

### Configuration Security

#### Problem: load-file Vulnerability

**Issue**: `load-file` executes arbitrary Clojure code from paths in configuration files. Compromised `.bb-mcp-server.edn` or `~/.config/bb-mcp-server/config.edn` enables code injection.

**Risk Level**: HIGH 🔴

#### Solutions

##### 1. Config File Validation

```clojure
;; src/bb_mcp_server/security/config.clj
(ns bb-mcp-server.security.config
  (:require [clojure.spec.alpha :as s]
            [bb-mcp-server.telemetry :as tel]))

(s/def ::module-path string?)
(s/def ::enabled boolean?)
(s/def ::config map?)
(s/def ::module (s/keys :req-un [::module-path ::enabled]
                       :opt-un [::config]))
(s/def ::load-on-startup (s/coll-of ::module))
(s/def ::modules (s/keys :req-un [::load-on-startup]))
(s/def ::config-file (s/keys :req-un [::modules]))

(defn validate-config!
  "Validate config file against schema"
  [config]
  (if (s/valid? ::config-file config)
    config
    (let [explanation (s/explain-str ::config-file config)]
      (tel/log-event! "security.config.validation-failed"
                     {:explanation explanation})
      (throw (ex-info "Invalid configuration file"
                     {:explanation explanation})))))
```

##### 2. Module Path Whitelisting

```clojure
(defn path-in-whitelist?
  "Check if module path is in allowed directories"
  [module-path whitelist]
  (let [canonical-path (.getCanonicalPath (io/file module-path))]
    (some #(str/starts-with? canonical-path %) whitelist)))

(def default-whitelist
  "Default allowed module directories"
  [(str (System/getProperty "user.home") "/.config/bb-mcp-server/modules")
   "src/modules"  ; Built-in modules
   "modules"])    ; Project modules

(defn validate-module-path!
  "Ensure module path is in whitelist"
  [module-path {:keys [whitelist] :or {whitelist default-whitelist}}]
  (if (path-in-whitelist? module-path whitelist)
    module-path
    (do
      (tel/log-event! "security.module-path.rejected"
                     {:path module-path
                      :whitelist whitelist})
      (throw (ex-info "Module path not in whitelist"
                     {:path module-path
                      :whitelist whitelist})))))
```

##### 3. Config File Signing (Optional)

```clojure
(defn sign-config
  "Generate SHA-256 signature for config file"
  [config-path secret-key]
  (let [content (slurp config-path)
        hmac (javax.crypto.Mac/getInstance "HmacSHA256")
        secret-key-spec (javax.crypto.spec.SecretKeySpec.
                          (.getBytes secret-key "UTF-8") "HmacSHA256")]
    (.init hmac secret-key-spec)
    (-> (.doFinal hmac (.getBytes content "UTF-8"))
        (java.util.Base64/getEncoder)
        (.encodeToString))))

(defn verify-config-signature
  "Verify config file hasn't been tampered with"
  [config-path signature secret-key]
  (= signature (sign-config config-path secret-key)))
```

**Usage**:

```bash
# Sign config (stores signature in .bb-mcp-server.edn.sig)
bb security:sign-config .bb-mcp-server.edn

# Server validates on load
bb triple  # Validates signature before loading config
```

---

### Registry Security

#### Problem: Tool Name Collisions

**Issue**: Simple map-based registry allows duplicate tool names to silently overwrite earlier registrations.

```clojure
;; Current behavior (PROBLEMATIC):
(swap! registry assoc "eval" nrepl-eval)  ; Registered
(swap! registry assoc "eval" custom-eval) ; Silently overwrites!
```

**Risk Level**: HIGH 🟠

#### Solutions

##### 1. Namespaced Tool Names (Required)

**Enforce module:tool naming convention**:

```clojure
;; src/bb_mcp_server/registry.clj (UPDATED)
(ns bb-mcp-server.registry
  (:require [bb-mcp-server.telemetry :as tel]
            [clojure.string :as str]))

(defonce registry (atom {}))

(defn valid-tool-name?
  "Tool names must be namespaced: module:tool or module/tool"
  [tool-name]
  (or (str/includes? tool-name ":")
      (str/includes? tool-name "/")))

(defn register-tool!
  "Register tool with collision detection"
  [tool-name handler metadata]
  (cond
    ;; Validate namespacing
    (not (valid-tool-name? tool-name))
    (do
      (tel/log-event! "registry.tool.invalid-name"
                     {:tool-name tool-name
                      :reason :missing-namespace})
      (throw (ex-info "Tool names must be namespaced (module:tool or module/tool)"
                     {:tool-name tool-name
                      :examples ["nrepl:eval" "blockchain:fetch-wallet"]})))

    ;; Detect collision
    (contains? @registry tool-name)
    (let [existing (get @registry tool-name)]
      (tel/log-event! "registry.tool.collision-detected"
                     {:tool-name tool-name
                      :existing-module (get-in existing [:metadata :module])
                      :new-module (:module metadata)})
      (throw (ex-info "Tool name collision detected"
                     {:tool-name tool-name
                      :existing-module (get-in existing [:metadata :module])
                      :new-module (:module metadata)
                      :suggestion "Use unique namespaced name"})))

    ;; Register
    :else
    (do
      (swap! registry assoc tool-name {:handler handler :metadata metadata})
      (tel/log-event! "registry.tool.registered"
                     {:tool-name tool-name
                      :module (:module metadata)})
      {:status :registered
       :tool-name tool-name})))
```

**Module naming convention**:

```clojure
;; modules/nrepl.clj
(registry/register-tool!
 "nrepl:eval"  ; NOT "eval" - must be namespaced
 eval-code
 {:name "nrepl:eval"
  :module "nrepl"
  :description "Evaluate Clojure code in nREPL session"})

;; modules/blockchain.clj
(registry/register-tool!
 "blockchain:eval"  ; Different namespace - no collision
 blockchain-eval
 {:name "blockchain:eval"
  :module "blockchain"
  :description "Evaluate on-chain contract code"})
```

##### 2. Collision Policy Configuration

```clojure
;; .bb-mcp-server.edn
{:registry
 {:collision-policy :error     ; :error | :warn | :allow-overwrite
  :enforce-namespacing true}}  ; Require module:tool format
```

##### 3. Tool Listing by Module

```clojure
(defn list-tools-by-module
  "Group tools by module namespace"
  []
  (reduce (fn [acc [tool-name {:keys [metadata]}]]
            (let [module (or (:module metadata)
                            (first (str/split tool-name #"[:/]")))]
              (update acc module (fnil conj []) tool-name)))
          {}
          @registry))

;; bb list-tools output:
;; nrepl:
;;   - nrepl:eval
;;   - nrepl:connect
;;   - nrepl:disconnect
;; blockchain:
;;   - blockchain:fetch-wallet
;;   - blockchain:analyze-staking
```

---

### Module Trust Model

#### Principles

1. **Built-in Modules**: Trusted (shipped with bb-mcp-server)
2. **Global Config Modules**: User-trusted (in ~/.config)
3. **Project Config Modules**: Project-trusted (in project dir)
4. **Community Modules**: Untrusted until vetted

#### Module Vetting Process (Future)

```clojure
;; Module metadata for ecosystem
{:module-name "awesome-tools"
 :version "1.0.0"
 :author "user@example.com"
 :verified true        ; Vetted by maintainers
 :security-audit "2025-11-01"
 :dependencies []      ; Other modules required
 :permissions          ; Requested capabilities
  {:network true       ; Network access
   :filesystem true    ; File system access
   :code-execution false}}  ; Can execute arbitrary code
```

---

### Threat Model

#### Threats Addressed

| Threat | Risk | Mitigation |
|--------|------|------------|
| **Unauthorized API access** | Critical | API key auth, rate limiting |
| **Config file tampering** | High | Validation, signing, whitelisting |
| **Tool name collision** | High | Namespacing, collision detection |
| **Module code injection** | High | Path whitelisting, validation |
| **DoS via tool abuse** | Medium | Rate limiting, timeouts |
| **Data exfiltration** | Medium | Telemetry monitoring, network policies |

#### Threats Not Addressed (Future Work)

| Threat | Risk | Plan |
|--------|------|------|
| **Module isolation** | High | Investigate babashka.pods (v2.0) |
| **Resource exhaustion** | Medium | Per-tool resource limits |
| **Supply chain attacks** | Medium | Module signing, hash verification |

---

### Security Configuration Example

```clojure
;; .bb-mcp-server.edn - Production security config
{:security
 {:auth
  {:enabled true
   :type :api-key
   :api-keys-file "~/.config/bb-mcp-server/api-keys.edn"}

  :rate-limiting
  {:enabled true
   :requests-per-minute 60
   :burst 10}

  :config-validation
  {:enabled true
   :signature-required true
   :secret-key-file "~/.config/bb-mcp-server/secret.key"}

  :module-loading
  {:path-whitelist
   ["~/.config/bb-mcp-server/modules"
    "src/modules"
    "modules"]
   :require-signatures false  ; Future: verify module signatures
   }}

 :registry
 {:collision-policy :error
  :enforce-namespacing true}

 :telemetry
 {:security-events-only false  ; Log all events
  :alert-on-failed-auth true}}
```

### Security Checklist for Deployment

**Before deploying to cloud:**

- [ ] Enable API key authentication
- [ ] Generate strong API keys (`bb security:generate-key`)
- [ ] Enable rate limiting
- [ ] Validate config file signatures
- [ ] Review module path whitelist
- [ ] Enable security event logging
- [ ] Test unauthorized access handling
- [ ] Review and restrict network exposure
- [ ] Document key rotation policy
- [ ] Set up monitoring alerts

---

## Module Dependencies and Loading Order

### Overview

**Problem**: Modules may depend on other modules being loaded first. For example, a "data-analysis" module might require the "nrepl" module to be available, or a "dashboard" module might need both "blockchain" and "nrepl" modules.

**Solution**: Dependency declaration in module metadata with topological sort for load order resolution.

### Module Metadata Format

```clojure
;; src/modules/data-analysis.clj
(ns modules.data-analysis
  (:require [bb-mcp-server.registry :as registry]
            [bb-mcp-server.telemetry :as tel]))

;; Module metadata with dependencies
(def metadata
  {:module-name "data-analysis"
   :version "1.0.0"
   :description "Statistical analysis tools"
   :depends-on ["nrepl" "filesystem"]  ; Required modules
   :load-order 100                      ; Optional explicit ordering (higher = later)
   :optional-deps ["visualization"]     ; Optional, don't fail if missing
   :author "..."
   :license "..."})
```

### Dependency Resolution

```clojure
;; src/bb_mcp_server/loader.clj
(ns bb-mcp-server.loader
  (:require [bb-mcp-server.telemetry :as tel]))

(defn get-module-metadata
  "Extract metadata from module namespace"
  [module-path]
  (let [ns-sym (load-module-namespace module-path)]
    (or (some-> ns-sym
               (ns-resolve 'metadata)
               deref)
        {:module-name (extract-name-from-path module-path)
         :depends-on []
         :load-order 50})))  ; Default middle priority

(defn resolve-dependencies
  "Topological sort of modules based on dependencies"
  [modules]
  (let [module-map (into {} (map (fn [m]
                                   [(:module-name m) m])
                                 modules))
        sorted (atom [])
        visited (atom #{})
        temp-mark (atom #{})]

    (defn visit [module-name]
      (cond
        ;; Already processed
        (contains? @visited module-name)
        nil

        ;; Cycle detected
        (contains? @temp-mark module-name)
        (throw (ex-info "Circular dependency detected"
                       {:module module-name
                        :cycle (conj @temp-mark module-name)}))

        ;; Process dependencies first
        :else
        (let [module (get module-map module-name)]
          (swap! temp-mark conj module-name)

          ;; Visit all dependencies
          (doseq [dep (:depends-on module)]
            (when-not (contains? module-map dep)
              (if (contains? (set (:optional-deps module)) dep)
                (tel/log-event! "loader.optional-dependency.missing"
                               {:module module-name
                                :missing-dep dep})
                (throw (ex-info "Required dependency not found"
                               {:module module-name
                                :missing-dep dep
                                :available-modules (keys module-map)}))))
            (visit dep))

          ;; Add to sorted list
          (swap! temp-mark disj module-name)
          (swap! visited conj module-name)
          (swap! sorted conj module))))

    ;; Visit all modules
    (doseq [module-name (keys module-map)]
      (visit module-name))

    ;; Apply explicit load-order as secondary sort
    (sort-by :load-order @sorted)))

(defn load-modules-with-dependencies!
  "Load modules respecting dependencies"
  [module-configs]
  (try
    (tel/log-event! "loader.dependency-resolution.started"
                   {:module-count (count module-configs)})

    ;; Get metadata for all modules
    (let [modules-with-meta (map (fn [config]
                                   (assoc config
                                          :metadata
                                          (get-module-metadata (:module-path config))))
                                 module-configs)

          ;; Resolve load order
          sorted-modules (resolve-dependencies (map :metadata modules-with-meta))
          sorted-configs (map (fn [meta]
                               (first (filter #(= (:module-name meta)
                                                  (get-in % [:metadata :module-name]))
                                             modules-with-meta)))
                             sorted-modules)]

      (tel/log-event! "loader.dependency-resolution.completed"
                     {:load-order (mapv :module-name sorted-modules)})

      ;; Load in dependency order
      (doseq [config sorted-configs]
        (load-module! config))

      {:status :success
       :loaded (mapv :module-name sorted-modules)})

    (catch Exception e
      (tel/log-event! "loader.dependency-resolution.failed"
                     {:error (.getMessage e)
                      :data (ex-data e)})
      (throw e))))
```

### Dependency Graph Visualization

```clojure
;; Helper function for debugging dependency issues
(defn visualize-dependencies
  "Generate DOT format dependency graph for visualization"
  [modules]
  (let [lines (atom ["digraph modules {"])]
    (doseq [module modules]
      (let [name (:module-name module)]
        ;; Add node
        (swap! lines conj (str "  \"" name "\" [label=\"" name "\\nv" (:version module) "\"];"))

        ;; Add edges for dependencies
        (doseq [dep (:depends-on module)]
          (swap! lines conj (str "  \"" name "\" -> \"" dep "\";")))

        ;; Add dashed edges for optional deps
        (doseq [opt-dep (:optional-deps module)]
          (swap! lines conj (str "  \"" name "\" -> \"" opt-dep "\" [style=dashed];")))
      (swap! lines conj "}")))
    (clojure.string/join "\n" @lines)))

;; Usage: bb modules:graph > deps.dot && dot -Tpng deps.dot -o deps.png
```

### Configuration with Dependencies

```clojure
;; .bb-mcp-server.edn - Modules with dependencies
{:modules
 {:load-on-startup
  [{:module-path "src/modules/nrepl.clj"
    :enabled true
    :config {:default-host "localhost"
             :default-port 7890}}

   {:module-path "src/modules/filesystem.clj"
    :enabled true}

   ;; This will be loaded AFTER nrepl and filesystem
   {:module-path "src/modules/data-analysis.clj"
    :enabled true
    :config {:default-session "repl-session"}}

   ;; This might depend on data-analysis
   {:module-path "src/modules/dashboard.clj"
    :enabled true}]}}
```

### Dependency Resolution Features

1. **Topological Sort**: Ensures dependencies loaded before dependents
2. **Cycle Detection**: Prevents infinite loops from circular dependencies
3. **Missing Dependency Errors**: Clear error messages for unmet dependencies
4. **Optional Dependencies**: Graceful degradation when optional modules missing
5. **Explicit Ordering**: `load-order` field for fine-grained control
6. **Telemetry**: All dependency resolution events logged

### Error Messages

```clojure
;; Circular dependency error
{:error "Circular dependency detected"
 :module "dashboard"
 :cycle #{"dashboard" "data-analysis" "visualization" "dashboard"}
 :suggestion "Remove circular reference or make dependency optional"}

;; Missing required dependency
{:error "Required dependency not found"
 :module "data-analysis"
 :missing-dep "nrepl"
 :available-modules ["filesystem" "blockchain"]
 :suggestion "Enable nrepl module in configuration or mark as optional"}
```

---

## Error Handling Strategy

### Overview

**Problem**: Multiple failure points exist (transport errors, module crashes, invalid inputs, resource exhaustion) but no unified error handling strategy.

**Solution**: Multi-layer error handling with global exception middleware, graceful degradation, and comprehensive error recovery.

### Error Handling Layers

```
┌─────────────────────────────────────┐
│   Transport Error Handling          │  Layer 1: Network/protocol errors
├─────────────────────────────────────┤
│   Authentication Middleware          │  Layer 2: Security errors
├─────────────────────────────────────┤
│   Request Validation                 │  Layer 3: Input validation
├─────────────────────────────────────┤
│   Tool Execution Error Handling      │  Layer 4: Tool-specific errors
├─────────────────────────────────────┤
│   Module Lifecycle Error Handling    │  Layer 5: Module state errors
├─────────────────────────────────────┤
│   Global Exception Middleware        │  Layer 6: Catch-all safety net
└─────────────────────────────────────┘
```

### Global Exception Middleware

```clojure
;; src/bb_mcp_server/middleware/error_handling.clj
(ns bb-mcp-server.middleware.error-handling
  (:require [bb-mcp-server.telemetry :as tel]
            [cheshire.core :as json]))

(defn error-response
  "Generate standardized error response"
  [error-type message & {:keys [details status-code]
                         :or {status-code 500}}]
  {:status status-code
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:jsonrpc "2.0"
           :error {:code (case error-type
                          :parse-error -32700
                          :invalid-request -32600
                          :method-not-found -32601
                          :invalid-params -32602
                          :internal-error -32603
                          :server-error -32000)
                   :message message
                   :data (or details {})}})})

(defn exception-middleware
  "Global exception handler - last line of defense"
  [handler]
  (fn [request]
    (try
      (handler request)

      ;; Specific exception types
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              message (.getMessage e)]
          (tel/log-event! "error.exception-info"
                         {:message message
                          :data data
                          :request-id (:request-id request)})
          (cond
            ;; Security errors
            (= (:type data) :security)
            (error-response :server-error message
                          :details data
                          :status-code 403)

            ;; Validation errors
            (= (:type data) :validation)
            (error-response :invalid-params message
                          :details data
                          :status-code 400)

            ;; Module errors
            (= (:type data) :module)
            (error-response :server-error message
                          :details data
                          :status-code 503)

            ;; Generic ExceptionInfo
            :else
            (error-response :internal-error message
                          :details data
                          :status-code 500))))

      ;; Timeout errors
      (catch java.util.concurrent.TimeoutException e
        (tel/log-event! "error.timeout"
                       {:message (.getMessage e)
                        :request-id (:request-id request)})
        (error-response :server-error "Request timeout"
                      :status-code 504))

      ;; Resource errors
      (catch java.io.IOException e
        (tel/log-event! "error.io"
                       {:message (.getMessage e)
                        :request-id (:request-id request)})
        (error-response :server-error "I/O error occurred"
                      :status-code 500))

      ;; Catch-all for unexpected errors
      (catch Throwable t
        (tel/log-event! "error.uncaught-exception"
                       {:message (.getMessage t)
                        :class (.getName (.getClass t))
                        :stack-trace (mapv str (.getStackTrace t))
                        :request-id (:request-id request)})
        (error-response :internal-error "Internal server error"
                      :status-code 500)))))
```

### Module Lifecycle Error States

```clojure
;; Enhanced lifecycle states with error handling
(defprotocol ILifecycle
  (start! [this config] "Start with error recovery")
  (stop! [this] "Stop with cleanup guarantees")
  (status [this] "Return detailed status including errors")
  (restart! [this config] "Restart with error recovery")
  (health-check [this] "Deep health check with diagnostics"))

;; Lifecycle states
;; :stopped    - Clean shutdown state
;; :starting   - Initialization in progress
;; :running    - Fully operational
;; :degraded   - Partially operational (some features failing)
;; :failed     - Non-operational (start failed or critical error)
;; :stopping   - Shutdown in progress

(defn safe-start!
  "Start module with error recovery"
  [lifecycle-instance config]
  (try
    (tel/log-event! "lifecycle.start.attempting"
                   {:module (type lifecycle-instance)})

    (let [result (start! lifecycle-instance config)]
      (tel/log-event! "lifecycle.start.succeeded"
                     {:module (type lifecycle-instance)
                      :state (:state result)})
      result)

    (catch Exception e
      (tel/log-event! "lifecycle.start.failed"
                     {:module (type lifecycle-instance)
                      :error (.getMessage e)
                      :data (ex-data e)})
      ;; Return failed state instead of crashing
      {:state :failed
       :error {:message (.getMessage e)
               :type (type e)
               :data (ex-data e)}
       :timestamp (System/currentTimeMillis)})))

(defn safe-stop!
  "Stop module with guaranteed cleanup"
  [lifecycle-instance]
  (try
    (tel/log-event! "lifecycle.stop.attempting"
                   {:module (type lifecycle-instance)})

    ;; Set timeout for stop operation
    (let [stop-future (future (stop! lifecycle-instance))
          result (deref stop-future 30000 :timeout)]

      (if (= result :timeout)
        (do
          (tel/log-event! "lifecycle.stop.timeout"
                         {:module (type lifecycle-instance)})
          (future-cancel stop-future)
          {:state :failed
           :error {:message "Stop operation timed out"
                   :type :timeout}})
        (do
          (tel/log-event! "lifecycle.stop.succeeded"
                         {:module (type lifecycle-instance)})
          result)))

    (catch Exception e
      (tel/log-event! "lifecycle.stop.failed"
                     {:module (type lifecycle-instance)
                      :error (.getMessage e)})
      ;; Even if stop fails, mark as stopped to prevent hanging
      {:state :stopped
       :error {:message (.getMessage e)
               :type (type e)}})))
```

### Partial Module Load Failure Recovery

```clojure
;; src/bb_mcp_server/loader.clj
(defn load-modules-with-recovery!
  "Load modules with partial failure recovery"
  [module-configs]
  (let [results (atom {:succeeded []
                       :failed []
                       :total (count module-configs)})]

    (doseq [config module-configs]
      (try
        (tel/log-event! "loader.module.loading"
                       {:path (:module-path config)})

        (load-module! config)

        (swap! results update :succeeded conj
               {:path (:module-path config)
                :status :loaded})

        (tel/log-event! "loader.module.loaded"
                       {:path (:module-path config)})

        (catch Exception e
          (tel/log-event! "loader.module.failed"
                         {:path (:module-path config)
                          :error (.getMessage e)
                          :data (ex-data e)})

          (swap! results update :failed conj
                 {:path (:module-path config)
                  :error (.getMessage e)
                  :type (type e)}))))

    (let [final-results @results]
      (tel/log-event! "loader.summary"
                     {:total (:total final-results)
                      :succeeded (count (:succeeded final-results))
                      :failed (count (:failed final-results))})

      ;; Return results with warnings if partial failure
      (cond
        (empty? (:failed final-results))
        {:status :success
         :results final-results}

        (empty? (:succeeded final-results))
        {:status :total-failure
         :results final-results
         :message "All modules failed to load"}

        :else
        {:status :partial-success
         :results final-results
         :message (str (count (:failed final-results)) " modules failed to load")
         :warnings (:failed final-results)}))))
```

### Transport Error Retry Logic

```clojure
;; src/bb_mcp_server/transport/http.clj
(defn exponential-backoff-retry
  "Retry failed operations with exponential backoff"
  [operation & {:keys [max-retries initial-delay max-delay]
                :or {max-retries 3
                     initial-delay 100
                     max-delay 5000}}]
  (loop [attempt 0
         delay initial-delay]
    (let [result (try
                   {:status :success
                    :result (operation)}
                   (catch Exception e
                     {:status :error
                      :error e}))]

      (cond
        ;; Success
        (= (:status result) :success)
        (:result result)

        ;; Max retries reached
        (>= attempt max-retries)
        (do
          (tel/log-event! "retry.max-attempts-reached"
                         {:attempts (inc attempt)
                          :error (.getMessage (:error result))})
          (throw (:error result)))

        ;; Retry with backoff
        :else
        (do
          (tel/log-event! "retry.attempting"
                         {:attempt (inc attempt)
                          :delay delay
                          :error (.getMessage (:error result))})
          (Thread/sleep delay)
          (recur (inc attempt)
                 (min (* delay 2) max-delay)))))))

;; Usage in HTTP transport
(defn send-http-request
  "Send HTTP request with retry logic"
  [url payload]
  (exponential-backoff-retry
   #(http/post url {:body (json/generate-string payload)
                    :headers {"Content-Type" "application/json"}})
   :max-retries 3
   :initial-delay 100
   :max-delay 2000))
```

### Tool Execution Timeout Protection

```clojure
;; src/bb_mcp_server/registry.clj
(defn execute-tool-with-timeout
  "Execute tool with timeout protection"
  [tool-name params & {:keys [timeout-ms]
                       :or {timeout-ms 30000}}]
  (let [tool (get @registry tool-name)]
    (if-not tool
      (throw (ex-info "Tool not found"
                     {:type :validation
                      :tool-name tool-name
                      :available-tools (keys @registry)}))

      (let [execution-future (future
                              (try
                                ((:handler tool) params)
                                (catch Exception e
                                  {:error (.getMessage e)
                                   :type :tool-error})))
            result (deref execution-future timeout-ms :timeout)]

        (cond
          ;; Timeout
          (= result :timeout)
          (do
            (future-cancel execution-future)
            (tel/log-event! "tool.execution.timeout"
                           {:tool-name tool-name
                            :timeout-ms timeout-ms})
            (throw (ex-info "Tool execution timeout"
                           {:type :timeout
                            :tool-name tool-name
                            :timeout-ms timeout-ms})))

          ;; Tool error
          (:error result)
          (do
            (tel/log-event! "tool.execution.error"
                           {:tool-name tool-name
                            :error (:error result)})
            (throw (ex-info (:error result)
                           {:type :tool-error
                            :tool-name tool-name})))

          ;; Success
          :else
          (do
            (tel/log-event! "tool.execution.success"
                           {:tool-name tool-name})
            result))))))
```

### Error Handling Configuration

```clojure
;; .bb-mcp-server.edn - Error handling config
{:error-handling
 {:retry
  {:enabled true
   :max-retries 3
   :initial-delay-ms 100
   :max-delay-ms 5000}

  :timeouts
  {:tool-execution-ms 30000
   :module-start-ms 10000
   :module-stop-ms 30000
   :http-request-ms 60000}

  :module-loading
  {:fail-on-any-error false    ; Continue loading other modules
   :required-modules []          ; Must succeed or abort
   :log-all-errors true}

  :graceful-degradation
  {:enabled true
   :allow-partial-functionality true}}}
```

### Error Recovery Best Practices

1. **Fail Fast for Security**: Authentication/authorization errors immediately deny access
2. **Graceful Degradation**: Allow partial functionality when non-critical modules fail
3. **Comprehensive Logging**: All errors logged with context for debugging
4. **User-Friendly Messages**: Translate technical errors to actionable messages
5. **Timeout Protection**: Prevent hanging on blocking operations
6. **Retry with Backoff**: Retry transient failures with exponential backoff
7. **Resource Cleanup**: Guarantee cleanup even when errors occur

---

## Schema Validation

### Overview

**Problem**: Tools receive parameters from LLMs which may not match expected schemas, causing runtime errors or undefined behavior.

**Solution**: Schema validation using Malli (Babashka-compatible) with clear error messages for LLM correction.

### Why Malli?

- **Babashka Compatible**: Works in Babashka without additional dependencies
- **Expressive**: Supports complex nested schemas
- **Good Error Messages**: Detailed explanations for validation failures
- **Runtime Performance**: Fast validation suitable for request handling
- **Human Readable**: Schemas are data, easy to introspect and document

### Schema Definition

```clojure
;; src/modules/nrepl.clj
(ns modules.nrepl
  (:require [malli.core :as m]
            [malli.error :as me]
            [bb-mcp-server.registry :as registry]
            [bb-mcp-server.telemetry :as tel]))

;; Tool input schemas
(def eval-schema
  [:map
   [:code [:string {:min 1}]]
   [:ns {:optional true} :string]
   [:timeout-ms {:optional true} [:int {:min 1000 :max 300000}]]])

(def connect-schema
  [:map
   [:host [:string {:min 1}]]
   [:port [:int {:min 1 :max 65535}]]
   [:timeout-ms {:optional true} [:int {:min 1000 :max 60000}]]])

(def list-sessions-schema
  [:map])  ; No required parameters

;; Schema registry for module
(def schemas
  {"nrepl:eval" eval-schema
   "nrepl:connect" connect-schema
   "nrepl:list-sessions" list-sessions-schema})
```

### Validation Middleware

```clojure
;; src/bb_mcp_server/middleware/validation.clj
(ns bb-mcp-server.middleware.validation
  (:require [malli.core :as m]
            [malli.error :as me]
            [bb-mcp-server.telemetry :as tel]))

(defn validate-params
  "Validate tool parameters against schema"
  [tool-name params schema]
  (if (m/validate schema params)
    ;; Valid - return params unchanged
    params

    ;; Invalid - throw detailed error
    (let [explanation (me/humanize (m/explain schema params))]
      (tel/log-event! "validation.schema.failed"
                     {:tool-name tool-name
                      :params params
                      :errors explanation})
      (throw (ex-info "Invalid parameters"
                     {:type :validation
                      :tool-name tool-name
                      :errors explanation
                      :params params
                      :suggestion "Check parameter types and required fields"})))))

(defn validation-middleware
  "Middleware to validate tool inputs"
  [handler schema]
  (fn [params]
    (let [validated-params (validate-params
                            (get params :tool-name "unknown")
                            params
                            schema)]
      (handler validated-params))))
```

### Tool Registration with Schema

```clojure
;; src/bb_mcp_server/registry.clj
(defn register-tool!
  "Register tool with schema validation"
  [tool-name handler metadata]
  (let [schema (:schema metadata)
        validated-handler (if schema
                           (validation-middleware handler schema)
                           handler)]

    ;; Validate tool name and register
    (when-not (valid-tool-name? tool-name)
      (throw (ex-info "Tool names must be namespaced"
                     {:tool-name tool-name})))

    (when (contains? @registry tool-name)
      (throw (ex-info "Tool name collision"
                     {:tool-name tool-name})))

    (swap! registry assoc tool-name
           {:handler validated-handler
            :metadata (assoc metadata :has-schema (some? schema))})

    (tel/log-event! "registry.tool.registered"
                   {:tool-name tool-name
                    :has-schema (some? schema)})

    {:status :registered
     :tool-name tool-name
     :validated (some? schema)}))
```

### Schema Examples

```clojure
;; Common schema patterns

;; String with constraints
[:string {:min 1 :max 1000}]

;; Enum/options
[:enum "json" "edn" "string"]

;; Optional field
[:map
 [:required-field :string]
 [:optional-field {:optional true} :int]]

;; Nested map
[:map
 [:config [:map
           [:host :string]
           [:port :int]]]]

;; Array/vector
[:vector :string]
[:vector {:min 1} [:map [:name :string] [:value :int]]]

;; Union types
[:or :string :int]

;; Regular expression
[:re #"^[a-zA-Z0-9_-]+$"]

;; Custom validator
[:string {:min 1}
 [:fn {:error/message "Must be valid JSON"}
  #(try (cheshire.core/parse-string %) true
        (catch Exception _ false))]]
```

### Error Messages for LLMs

When validation fails, generate clear messages that help LLMs correct their requests:

```clojure
;; Example validation error response
{:error "Invalid parameters"
 :tool-name "nrepl:eval"
 :errors {:code ["should be a string with at least 1 character"
                 "missing required key"]
          :timeout-ms ["should be an integer between 1000 and 300000"]}
 :received-params {:code 123           ; Wrong: should be string
                   :timeout-ms 500000}  ; Wrong: exceeds max
 :suggestion "Check parameter types and required fields"
 :valid-example {:code "(+ 1 2)"
                 :ns "user"
                 :timeout-ms 30000}}
```

### Schema Documentation Generation

```clojure
;; Generate JSON Schema for tool documentation
(defn malli-to-json-schema
  "Convert Malli schema to JSON Schema for MCP tool descriptions"
  [malli-schema]
  ;; Simplified conversion (full implementation would handle all Malli types)
  (cond
    (= (first malli-schema) :map)
    {:type "object"
     :properties (into {}
                      (map (fn [[k opts & rest]]
                             (let [required? (not (:optional opts))
                                   schema-type (if rest (first rest) opts)]
                               [k (malli-to-json-schema schema-type)]))
                           (rest malli-schema)))
     :required (filterv #(not (get-in malli-schema [% :optional]))
                       (map first (rest malli-schema)))}

    (= (first malli-schema) :string)
    {:type "string"
     :minLength (get-in malli-schema [1 :min])
     :maxLength (get-in malli-schema [1 :max])}

    (= (first malli-schema) :int)
    {:type "integer"
     :minimum (get-in malli-schema [1 :min])
     :maximum (get-in malli-schema [1 :max])}

    ;; ... handle other types
    ))

;; Usage: Generate MCP tool description from Malli schema
(defn tool-description
  "Generate MCP tool description with schema"
  [tool-name schema description]
  {:name tool-name
   :description description
   :inputSchema (malli-to-json-schema schema)})
```

### Schema Validation Configuration

```clojure
;; .bb-mcp-server.edn - Validation config
{:schema-validation
 {:enabled true
  :strict-mode true              ; Fail on unknown keys
  :generate-examples true         ; Include valid examples in errors
  :log-validation-errors true
  :validation-timeout-ms 1000}}  ; Max time for schema validation
```

### Benefits of Schema Validation

1. **Early Error Detection**: Catch parameter errors before tool execution
2. **Better Error Messages**: LLMs get clear feedback on what to fix
3. **Self-Documenting**: Schemas serve as tool documentation
4. **Type Safety**: Prevent runtime type errors
5. **Tool Description Generation**: Auto-generate MCP tool schemas
6. **Validation is Fast**: Malli validation adds minimal overhead
7. **Comprehensive Coverage**: Support for complex nested structures

---

## Module Development Pattern

### Example: nREPL Module (Stateful with Lifecycle)

```clojure
;; src/modules/nrepl.clj
(ns modules.nrepl
  (:require [nrepl.core :as nrepl]
            [bb-mcp-server.registry :as registry]
            [bb-mcp-server.lifecycle :as lc]
            [bb-mcp-server.telemetry :as tel]))

;; Module state (managed by lifecycle)
(defonce state
  (atom {:connections {}     ; Active nREPL connections
         :lifecycle :stopped ; Current lifecycle state
         :config nil         ; Module configuration
         :started-at nil}))  ; Timestamp when started

;; ============================================================
;; Tool Handlers (Business Logic)
;; ============================================================

(defn eval-code [{:keys [code connection-id timeout]}]
  (tel/with-telemetry "nrepl.eval"
    {:connection-id connection-id
     :code-length (count code)}
    (let [conn (get-in @state [:connections connection-id])]
      (when-not conn
        (throw (ex-info "Connection not found" {:connection-id connection-id})))
      (let [result (nrepl/message conn {:op "eval" :code code})]
        {:value (first (:value result))
         :out (:out result)
         :err (:err result)}))))

(defn connect-nrepl [{:keys [host port]}]
  (tel/with-telemetry "nrepl.connect"
    {:host host :port port}
    (let [conn (nrepl/connect :host host :port port)
          conn-id (str (random-uuid))]
      (swap! state assoc-in [:connections conn-id] conn)
      {:connection-id conn-id
       :status :connected
       :host host
       :port port})))

(defn disconnect-nrepl [{:keys [connection-id]}]
  (tel/with-telemetry "nrepl.disconnect"
    {:connection-id connection-id}
    (when-let [conn (get-in @state [:connections connection-id])]
      (try
        ;; Close nREPL connection
        (.close (:transport conn))
        (catch Exception e
          (tel/log-error! "nrepl.disconnect.failed" e {:connection-id connection-id})))
      (swap! state update :connections dissoc connection-id)
      {:status :disconnected :connection-id connection-id})))

(defn list-connections [_]
  {:connections (map (fn [[id conn]]
                      {:id id
                       :status :connected
                       :host (:host conn)
                       :port (:port conn)})
                    (:connections @state))})

;; ============================================================
;; Lifecycle Implementation
;; ============================================================

(defn- start-impl
  "Start the nREPL module"
  [current-state config]
  (tel/log-event! "nrepl.module.starting" {:config config})

  ;; Optional: Auto-connect to configured servers
  (let [auto-connect-servers (:auto-connect config)]
    (doseq [{:keys [host port]} auto-connect-servers]
      (try
        (connect-nrepl {:host host :port port})
        (tel/log-event! "nrepl.auto-connect.success" {:host host :port port})
        (catch Exception e
          (tel/log-error! "nrepl.auto-connect.failed" e {:host host :port port})))))

  ;; Update state
  (assoc current-state
         :lifecycle :running
         :config config
         :started-at (System/currentTimeMillis)))

(defn- stop-impl
  "Stop the nREPL module"
  [current-state]
  (tel/log-event! "nrepl.module.stopping" {})

  ;; Close all connections
  (doseq [[conn-id _] (:connections current-state)]
    (disconnect-nrepl {:connection-id conn-id}))

  ;; Update state
  (assoc current-state
         :lifecycle :stopped
         :connections {}
         :started-at nil))

(defn- status-impl
  "Get module status"
  [current-state]
  {:state (:lifecycle current-state)
   :connections-count (count (:connections current-state))
   :uptime-ms (when (:started-at current-state)
                (- (System/currentTimeMillis) (:started-at current-state)))
   :config (:config current-state)})

;; Module instance implementing lifecycle
(defonce module-instance
  (reify lc/ILifecycle
    (start [this config]
      (swap! state start-impl config)
      this)
    (stop [this]
      (swap! state stop-impl)
      this)
    (status [this]
      (status-impl @state))))

;; ============================================================
;; Tool Registration
;; ============================================================

(registry/register-tool!
 "nrepl-eval"
 eval-code
 {:name "nrepl-eval"
  :description "Evaluate Clojure code in nREPL"
  :module "nrepl"
  :inputSchema {:type "object"
                :properties {:code {:type "string"}
                            :connection-id {:type "string"}
                            :timeout {:type "number"}}
                :required ["code" "connection-id"]}})

(registry/register-tool!
 "nrepl-connect"
 connect-nrepl
 {:name "nrepl-connect"
  :description "Connect to nREPL server"
  :module "nrepl"
  :inputSchema {:type "object"
                :properties {:host {:type "string"}
                            :port {:type "number"}}
                :required ["host" "port"]}})

(registry/register-tool!
 "nrepl-disconnect"
 disconnect-nrepl
 {:name "nrepl-disconnect"
  :description "Disconnect from nREPL server"
  :module "nrepl"
  :inputSchema {:type "object"
                :properties {:connection-id {:type "string"}}
                :required ["connection-id"]}})

(registry/register-tool!
 "nrepl-list-connections"
 list-connections
 {:name "nrepl-list-connections"
  :description "List active nREPL connections"
  :module "nrepl"
  :inputSchema {:type "object"}})

;; Module metadata
(def module-info
  {:name "nrepl"
   :version "1.0.0"
   :description "nREPL integration module with lifecycle management"
   :type :stateful
   :tools ["nrepl-eval" "nrepl-connect" "nrepl-disconnect" "nrepl-list-connections"]
   :lifecycle true})
```

### Example: Stateless Module (No Lifecycle)

```clojure
;; src/modules/math.clj
(ns modules.math
  (:require [bb-mcp-server.registry :as registry]))

;; Pure function tools - no state
(defn add [{:keys [a b]}]
  {:result (+ a b)})

(defn multiply [{:keys [a b]}]
  {:result (* a b)})

;; Register tools
(registry/register-tool!
 "math-add"
 add
 {:name "math-add"
  :description "Add two numbers"
  :module "math"
  :inputSchema {:type "object"
                :properties {:a {:type "number"}
                            :b {:type "number"}}
                :required ["a" "b"]}})

(registry/register-tool!
 "math-multiply"
 multiply
 {:name "math-multiply"
  :description "Multiply two numbers"
  :module "math"
  :inputSchema {:type "object"
                :properties {:a {:type "number"}
                            :b {:type "number"}}
                :required ["a" "b"]}})

;; Module metadata
(def module-info
  {:name "math"
   :version "1.0.0"
   :description "Mathematical operations"
   :type :stateless
   :tools ["math-add" "math-multiply"]
   :lifecycle false})
```

### Example: Blockchain Module

```clojure
;; src/modules/blockchain.clj
(ns modules.blockchain
  (:require [bb-mcp-server.registry :as registry]))

(defn fetch-wallet-summary [{:keys [address]}]
  ;; Implement blockchain queries
  {:total-aum "1000000"
   :staked "500000"})

(registry/register-tool!
 "blockchain-wallet-summary"
 fetch-wallet-summary
 {:name "blockchain-wallet-summary"
  :description "Get wallet summary"
  :inputSchema {:type "object"
                :properties {:address {:type "string"}}
                :required ["address"]}})
```

---

## Triple Interface Implementation

### stdio Server

```clojure
;; src/bb_mcp_server/server/stdio.clj
(ns bb-mcp-server.server.stdio
  (:require [bb-mcp-server.transport.stdio :as stdio]
            [bb-mcp-server.loader :as loader]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn -main []
  ;; Load startup modules
  (loader/load-startup-modules!)

  ;; stdio loop
  (let [reader (io/reader *in*)]
    (doseq [line (line-seq reader)]
      (let [request (json/parse-string line true)
            response (stdio/handle-jsonrpc-request request)]
        (println (json/generate-string response))
        (flush)))))
```

### HTTP Server

```clojure
;; src/bb_mcp_server/server/http.clj
(ns bb-mcp-server.server.http
  (:require [bb-mcp-server.transport.http :as http-transport]
            [bb-mcp-server.loader :as loader]
            [org.httpkit.server :as http]))

(defn -main [& args]
  (let [port (or (some-> args first parse-long) 9339)]
    ;; Load startup modules
    (loader/load-startup-modules!)

    ;; HTTP server
    (http/run-server http-transport/handle-mcp-http {:port port})
    (println (str "🌐 MCP HTTP server on port " port))))
```

### Triple Server (stdio + HTTP + REST)

```clojure
;; src/bb_mcp_server/server/triple.clj
(ns bb-mcp-server.server.triple
  (:require [bb-mcp-server.transport.stdio :as stdio]
            [bb-mcp-server.transport.http :as http-transport]
            [bb-mcp-server.transport.rest :as rest-transport]
            [bb-mcp-server.loader :as loader]
            [org.httpkit.server :as http]))

(defn unified-http-handler
  "Route to MCP HTTP or REST based on path"
  [req]
  (cond
    (= (:uri req) "/mcp")
    (http-transport/handle-mcp-http req)

    (clojure.string/starts-with? (:uri req) "/api/")
    (rest-transport/handle-rest req)

    :else
    {:status 404 :body "Not found"}))

(defn -main [& args]
  (let [port (or (some-> args first parse-long) 9339)]
    ;; Load startup modules
    (loader/load-startup-modules!)

    ;; stdio listener (non-blocking)
    (future
      (binding [*out* *err*]
        (println "📥 stdio listener started"))
      (stdio/start-stdio-listener!))

    ;; HTTP server (blocking)
    (http/run-server unified-http-handler {:port port})
    (println "🌐 HTTP server on port " port)
    (println "   POST /mcp (MCP HTTP)")
    (println "   * /api/* (REST API)")
    (println "✅ Triple server ready!")))
```

---

## bb.edn Configuration

```clojure
{:paths ["src" "test"]
 :deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/tools.logging {:mvn/version "1.3.0"}
        cheshire/cheshire {:mvn/version "5.12.0"}
        http-kit/http-kit {:mvn/version "2.8.0"}
        io.github.askonomm/trove {:mvn/version "1.1.0"}
        nrepl/nrepl {:mvn/version "1.3.1"}}  ; Always included, only loaded if module enabled

 :tasks
 {;; ============================================================
  ;; Server Tasks
  ;; ============================================================

  :stdio
  {:doc "Start stdio-only MCP server (for Claude Code auto-spawn)"
   :task (exec 'bb-mcp-server.server.stdio/-main)}

  :http
  {:doc "Start HTTP-only MCP server (for cloud deployment)"
   :requires ([clojure.java.io :as io])
   :task (let [port (or (parse-long (first *command-line-args*)) 9339)]
           (exec 'bb-mcp-server.server.http/-main port))}

  :triple
  {:doc "Start triple-interface server (stdio + HTTP + REST)"
   :requires ([clojure.java.io :as io])
   :task (let [port (or (parse-long (first *command-line-args*)) 9339)]
           (exec 'bb-mcp-server.server.triple/-main port))}

  :start
  {:doc "Start default server (alias for :triple)"
   :depends [:triple]}

  ;; ============================================================
  ;; Module Management Tasks
  ;; ============================================================

  :load-module
  {:doc "Load a module dynamically: bb load-module <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Loading module:" module-path)
               (loader/load-module! module-path)
               (println "✅ Module loaded successfully"))
             (println "Usage: bb load-module <path-to-module.clj>")))}

  :unload-module
  {:doc "Unload a module: bb unload-module <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Unloading module:" module-path)
               (loader/unload-module! module-path)
               (println "✅ Module unloaded"))
             (println "Usage: bb unload-module <path>")))}

  :list-modules
  {:doc "List all loaded modules"
   :requires ([bb-mcp-server.loader :as loader])
   :task (do
           (println "Loaded modules:")
           (doseq [mod (loader/list-modules)]
             (println "  -" mod)))}

  :reload-modules
  {:doc "Reload all modules from startup.clj"
   :requires ([bb-mcp-server.loader :as loader])
   :task (do
           (println "Reloading all modules...")
           (loader/reload-all-modules!)
           (println "✅ All modules reloaded"))}

  :start-module
  {:doc "Start a module: bb start-module <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Starting module:" module-path)
               (loader/start-module! module-path)
               (println "✅ Module started"))
             (println "Usage: bb start-module <path>")))}

  :stop-module
  {:doc "Stop a module: bb stop-module <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Stopping module:" module-path)
               (loader/stop-module! module-path)
               (println "✅ Module stopped"))
             (println "Usage: bb stop-module <path>")))}

  :restart-module
  {:doc "Restart a module: bb restart-module <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Restarting module:" module-path)
               (loader/restart-module! module-path)
               (println "✅ Module restarted"))
             (println "Usage: bb restart-module <path>")))}

  :module-status
  {:doc "Get module status: bb module-status <path>"
   :requires ([bb-mcp-server.loader :as loader])
   :task (let [module-path (first *command-line-args*)]
           (if module-path
             (do
               (println "Module status for:" module-path)
               (clojure.pprint/pprint (loader/module-status module-path)))
             (println "Usage: bb module-status <path>")))}

  :start-all-modules
  {:doc "Start all loaded modules"
   :requires ([bb-mcp-server.loader :as loader])
   :task (do
           (println "Starting all modules...")
           (loader/start-all-modules!)
           (println "✅ All modules started"))}

  :stop-all-modules
  {:doc "Stop all running modules"
   :requires ([bb-mcp-server.loader :as loader])
   :task (do
           (println "Stopping all modules...")
           (loader/stop-all-modules!)
           (println "✅ All modules stopped"))}

  :module-health
  {:doc "Check health of all modules"
   :requires ([bb-mcp-server.loader :as loader])
   :task (do
           (println "Module health check:")
           (clojure.pprint/pprint (loader/health-check)))}

  ;; ============================================================
  ;; Configuration Tasks
  ;; ============================================================

  :config
  {:doc "Show current configuration"
   :requires ([bb-mcp-server.config :as config])
   :task (do
           (println "Current configuration:")
           (clojure.pprint/pprint (config/load-config!)))}

  :config-init
  {:doc "Initialize project config: bb config-init [--global]"
   :requires ([bb-mcp-server.config :as config]
              [clojure.java.io :as io])
   :task (let [global? (some #{"--global"} *command-line-args*)
               config-path (if global?
                            (str (System/getProperty "user.home")
                                "/.config/bb-mcp-server/config.edn")
                            "./.bb-mcp-server.edn")
               template {:modules
                        {:load-on-startup []
                         :auto-start true}
                        :server
                        {:port 9339
                         :host "localhost"
                         :transports [:stdio :http :rest]}
                        :telemetry
                        {:level :info
                         :format :json
                         :output :stdout
                         :file-path nil}}]
           (if (.exists (io/file config-path))
             (println "⚠️  Config file already exists:" config-path)
             (do
               (when global?
                 (.mkdirs (.getParentFile (io/file config-path))))
               (spit config-path (with-out-str (clojure.pprint/pprint template)))
               (println "✅ Created config file:" config-path)
               (println "   Edit to add project-specific modules"))))}

  :config-add-module
  {:doc "Add module to project config: bb config-add-module <path>"
   :requires ([bb-mcp-server.config :as config]
              [clojure.java.io :as io]
              [clojure.edn :as edn])
   :task (let [module-path (first *command-line-args*)
               config-path "./.bb-mcp-server.edn"]
           (if-not module-path
             (println "Usage: bb config-add-module <path>")
             (if-not (.exists (io/file config-path))
               (println "⚠️  No project config found. Run: bb config-init")
               (let [current-config (edn/read-string (slurp config-path))
                     modules (get-in current-config [:modules :load-on-startup] [])
                     new-module {:path module-path :enabled true}
                     updated-config (assoc-in current-config
                                             [:modules :load-on-startup]
                                             (conj modules new-module))]
                 (spit config-path (with-out-str (clojure.pprint/pprint updated-config)))
                 (println "✅ Added module to config:" module-path)))))}

  :config-show
  {:doc "Show which config file is being used"
   :requires ([bb-mcp-server.config :as config])
   :task (let [{:keys [path source]} (config/find-config-file)]
           (if path
             (do
               (println "Using config:" path)
               (println "Source:" source))
             (println "No config file found, using defaults")))}

  :config-validate
  {:doc "Validate project config file"
   :requires ([bb-mcp-server.config :as config]
              [clojure.java.io :as io])
   :task (let [config-path "./.bb-mcp-server.edn"]
           (if-not (.exists (io/file config-path))
             (println "⚠️  No project config found")
             (try
               (config/load-config!)
               (println "✅ Config is valid")
               (catch Exception e
                 (println "❌ Config validation failed:")
                 (println "  " (.getMessage e))))))}

  :config-reset
  {:doc "Reset config to defaults (does not delete config file)"
   :requires ([bb-mcp-server.config :as config])
   :task (do
           (println "✅ Using default configuration")
           (println "   (Config file not modified)")
           (clojure.pprint/pprint config/default-config))}

  ;; ============================================================
  ;; Security Tasks
  ;; ============================================================

  :security:generate-key
  {:doc "Generate new API key for authentication"
   :requires ([bb-mcp-server.security.auth :as auth]
              [clojure.java.io :as io])
   :task (let [api-key (auth/generate-api-key)
               key-hash (auth/hash-api-key api-key)
               key-name (or (first *command-line-args*) "default")]
           (println "Generated API key:")
           (println "")
           (println "  Name:" key-name)
           (println "  Key: " api-key)
           (println "")
           (println "⚠️  Save this key securely - it won't be shown again!")
           (println "")
           (println "To add to server configuration:")
           (println "  bb security:add-key" key-name key-hash)
           (println "")
           (println "Or add manually to ~/.config/bb-mcp-server/api-keys.edn:")
           (println "  {" key-name (str "\"" key-hash "\"") "}"))}

  :security:add-key
  {:doc "Add API key to registry: bb security:add-key <name> <hash>"
   :requires ([bb-mcp-server.security.auth :as auth]
              [clojure.java.io :as io]
              [clojure.edn :as edn])
   :task (let [[key-name key-hash] *command-line-args*
               keys-file (io/file (System/getProperty "user.home")
                                 ".config/bb-mcp-server/api-keys.edn")]
           (if (or (nil? key-name) (nil? key-hash))
             (println "Usage: bb security:add-key <name> <hash>")
             (do
               ;; Create directory if needed
               (.mkdirs (.getParentFile keys-file))

               ;; Load existing keys or create new map
               (let [existing-keys (if (.exists keys-file)
                                    (edn/read-string (slurp keys-file))
                                    {})
                     updated-keys (assoc existing-keys (keyword key-name) key-hash)]

                 ;; Write updated keys
                 (spit keys-file (with-out-str (clojure.pprint/pprint updated-keys)))
                 (println "✅ API key added:" key-name)
                 (println "   Keys file:" (.getPath keys-file))))))}

  :security:list-keys
  {:doc "List all registered API keys (names only, not secrets)"
   :requires ([clojure.java.io :as io]
              [clojure.edn :as edn])
   :task (let [keys-file (io/file (System/getProperty "user.home")
                                  ".config/bb-mcp-server/api-keys.edn")]
           (if-not (.exists keys-file)
             (println "No API keys registered yet")
             (let [keys-map (edn/read-string (slurp keys-file))]
               (println "Registered API keys:")
               (doseq [[key-name _] keys-map]
                 (println "  -" (name key-name)))
               (println "")
               (println "Total:" (count keys-map) "keys"))))}

  :security:revoke-key
  {:doc "Revoke an API key: bb security:revoke-key <name>"
   :requires ([clojure.java.io :as io]
              [clojure.edn :as edn])
   :task (let [key-name (first *command-line-args*)
               keys-file (io/file (System/getProperty "user.home")
                                 ".config/bb-mcp-server/api-keys.edn")]
           (if-not key-name
             (println "Usage: bb security:revoke-key <name>")
             (if-not (.exists keys-file)
               (println "⚠️  No API keys file found")
               (let [existing-keys (edn/read-string (slurp keys-file))
                     key-keyword (keyword key-name)]
                 (if-not (contains? existing-keys key-keyword)
                   (println "⚠️  Key not found:" key-name)
                   (let [updated-keys (dissoc existing-keys key-keyword)]
                     (spit keys-file (with-out-str (clojure.pprint/pprint updated-keys)))
                     (println "✅ API key revoked:" key-name)))))))}

  :security:sign-config
  {:doc "Sign project config file: bb security:sign-config [--global]"
   :requires ([bb-mcp-server.security.config :as sec-config]
              [clojure.java.io :as io])
   :task (let [global? (some #{"--global"} *command-line-args*)
               config-path (if global?
                            (str (System/getProperty "user.home")
                                "/.config/bb-mcp-server/config.edn")
                            "./.bb-mcp-server.edn")
               config-file (io/file config-path)]
           (if-not (.exists config-file)
             (println "⚠️  Config file not found:" config-path)
             (let [signature (sec-config/sign-config-file config-path)]
               (println "✅ Config file signed")
               (println "   Signature:" signature)
               (println "")
               (println "Add to config file:")
               (println "  :signature" (str "\"" signature "\""))))))}

  :security:verify-config
  {:doc "Verify config file signature: bb security:verify-config [--global]"
   :requires ([bb-mcp-server.security.config :as sec-config]
              [clojure.java.io :as io])
   :task (let [global? (some #{"--global"} *command-line-args*)
               config-path (if global?
                            (str (System/getProperty "user.home")
                                "/.config/bb-mcp-server/config.edn")
                            "./.bb-mcp-server.edn")]
           (if-not (.exists (io/file config-path))
             (println "⚠️  Config file not found:" config-path)
             (if (sec-config/verify-config-file config-path)
               (println "✅ Config signature valid")
               (do
                 (println "❌ Config signature invalid or missing")
                 (System/exit 1)))))}

  :security:check-permissions
  {:doc "Check file permissions for security-sensitive files"
   :requires ([clojure.java.io :as io])
   :task (let [sensitive-files
               [(str (System/getProperty "user.home") "/.config/bb-mcp-server/api-keys.edn")
                (str (System/getProperty "user.home") "/.config/bb-mcp-server/secret.key")
                "./.bb-mcp-server.edn"]]
           (println "Checking file permissions:")
           (println "")
           (doseq [path sensitive-files]
             (let [file (io/file path)]
               (if (.exists file)
                 (let [readable? (.canRead file)
                       writable? (.canWrite file)
                       executable? (.canExecute file)
                       perms (str (if readable? "r" "-")
                                 (if writable? "w" "-")
                                 (if executable? "x" "-"))]
                   (println (format "  %s: %s" path perms))
                   (when executable?
                     (println "    ⚠️  WARNING: File should not be executable"))
                   (when (and readable? (not= (System/getProperty "user.name")
                                             (.getName (.toPath file))))
                     (println "    ⚠️  WARNING: File readable by others")))
                 (println (format "  %s: (not found)" path)))))
           (println "")
           (println "Recommended permissions for sensitive files:")
           (println "  - API keys file: 600 (rw-------)")
           (println "  - Secret key file: 600 (rw-------)")
           (println "  - Config files: 644 (rw-r--r--)"))}

  :security:audit-config
  {:doc "Audit security configuration for common issues"
   :requires ([bb-mcp-server.config :as config]
              [bb-mcp-server.security.audit :as audit])
   :task (do
           (println "Security Configuration Audit")
           (println "============================")
           (println "")
           (let [config (config/load-config!)
                 issues (audit/audit-config config)]
             (if (empty? issues)
               (println "✅ No security issues found")
               (do
                 (println "Security issues found:")
                 (println "")
                 (doseq [{:keys [severity message suggestion]} issues]
                   (println (format "[%s] %s" (name severity) message))
                   (when suggestion
                     (println "       Suggestion:" suggestion))
                   (println ""))
                 (println "Total issues:" (count issues))
                 (when (some #(= (:severity %) :critical) issues)
                   (println "")
                   (println "⚠️  CRITICAL issues found - address immediately!")
                   (System/exit 1))))))}

  :security:test-auth
  {:doc "Test API key authentication: bb security:test-auth <key>"
   :requires ([bb-mcp-server.security.auth :as auth])
   :task (let [test-key (first *command-line-args*)]
           (if-not test-key
             (println "Usage: bb security:test-auth <api-key>")
             (let [key-hash (auth/hash-api-key test-key)]
               (if (auth/validate-api-key test-key)
                 (do
                   (println "✅ API key is valid")
                   (println "   Hash:" key-hash))
                 (do
                   (println "❌ API key is invalid")
                   (System/exit 1))))))}

  ;; ============================================================
  ;; Tool Registry Tasks
  ;; ============================================================

  :list-tools
  {:doc "List all registered tools"
   :requires ([bb-mcp-server.registry :as registry])
   :task (do
           (println "Registered tools:")
           (doseq [tool (registry/list-tools)]
             (println (format "  - %s (%s)"
                            (:name (:metadata tool))
                            (or (:module (:metadata tool)) "core")))))}

  :tool-info
  {:doc "Show detailed info for a tool: bb tool-info <tool-name>"
   :requires ([bb-mcp-server.registry :as registry])
   :task (let [tool-name (first *command-line-args*)]
           (if tool-name
             (if-let [tool (registry/get-tool tool-name)]
               (do
                 (println "Tool:" tool-name)
                 (clojure.pprint/pprint (:metadata tool)))
               (println "Tool not found:" tool-name))
             (println "Usage: bb tool-info <tool-name>")))}

  ;; ============================================================
  ;; Testing Tasks
  ;; ============================================================

  :test
  {:doc "Run all tests"
   :extra-paths ["test"]
   :extra-deps {io.github.cognitect-labs/test-runner
                {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
   :task (exec 'cognitect.test-runner.api/test)
   :exec-args {:dirs ["test"]}}

  :test-watch
  {:doc "Run tests in watch mode"
   :requires ([babashka.fs :as fs])
   :task (do
           (println "👀 Watching for changes...")
           (loop []
             (run 'test)
             (Thread/sleep 1000)
             (recur)))}

  :test-integration
  {:doc "Run integration tests (requires servers to be running)"
   :task (exec 'cognitect.test-runner.api/test)
   :exec-args {:dirs ["test"]
               :patterns ["integration"]}}

  :test-module
  {:doc "Test a specific module: bb test-module nrepl"
   :task (let [module (first *command-line-args*)]
           (if module
             (exec 'cognitect.test-runner.api/test
                   {:dirs ["test"]
                    :patterns [(str ".*" module ".*")]})
             (println "Usage: bb test-module <module-name>")))}

  ;; ============================================================
  ;; Development Tasks
  ;; ============================================================

  :repl
  {:doc "Start nREPL server for development"
   :requires ([nrepl.server :as nrepl])
   :task (do
           (println "Starting nREPL server on port 7890...")
           (nrepl/start-server :port 7890)
           (println "✅ nREPL server started")
           (deref (promise)))}  ; Keep alive

  :check
  {:doc "Run all checks (lint, format, test)"
   :depends [:lint :test]}

  :lint
  {:doc "Run clj-kondo linter"
   :task (shell "clj-kondo --lint src test")}

  :format-check
  {:doc "Check code formatting (cljfmt)"
   :task (shell "cljfmt check src test")}

  :format
  {:doc "Auto-format code (cljfmt)"
   :task (shell "cljfmt fix src test")}

  ;; ============================================================
  ;; Telemetry Tasks
  ;; ============================================================

  :logs
  {:doc "Tail server logs"
   :task (shell "tail -f /var/log/bb-mcp-server.log")}

  :logs-errors
  {:doc "Show recent errors from logs"
   :task (shell "cat /var/log/bb-mcp-server.log | jq 'select(.level == \"error\")' | tail -20")}

  :logs-slow
  {:doc "Show slow requests (>1s) from logs"
   :task (shell "cat /var/log/bb-mcp-server.log | jq 'select(.event == \"tool.call.completed\" and .data.duration_ms > 1000)'")}

  :metrics
  {:doc "Show current metrics snapshot"
   :requires ([bb-mcp-server.metrics :as metrics])
   :task (do
           (println "Current metrics:")
           (clojure.pprint/pprint (metrics/snapshot)))}

  ;; ============================================================
  ;; Deployment Tasks
  ;; ============================================================

  :build
  {:doc "Build uberjar for deployment"
   :task (shell "clojure -T:build uber")}

  :docker-build
  {:doc "Build Docker image"
   :task (shell "docker build -t bb-mcp-server:latest .")}

  :docker-run
  {:doc "Run Docker container locally"
   :task (shell "docker run -p 9339:9339 bb-mcp-server:latest")}

  :deploy-local
  {:doc "Deploy to local systemd service"
   :task (do
           (run 'build)
           (shell "sudo cp target/bb-mcp-server.jar /opt/bb-mcp-server/")
           (shell "sudo systemctl restart bb-mcp-server")
           (println "✅ Deployed and restarted"))}

  ;; ============================================================
  ;; Utility Tasks
  ;; ============================================================

  :clean
  {:doc "Clean build artifacts"
   :task (do
           (shell "rm -rf target .cpcache")
           (println "✅ Cleaned build artifacts"))}

  :version
  {:doc "Show bb-mcp-server version"
   :requires ([bb-mcp-server.core :as core])
   :task (println "bb-mcp-server version:" core/version)}

  :health
  {:doc "Check server health (HTTP endpoint)"
   :task (shell "curl -s http://localhost:9339/health | jq")}

  :example-module
  {:doc "Create example module from template"
   :task (let [module-name (first *command-line-args*)]
           (if module-name
             (do
               (spit (str "src/modules/" module-name ".clj")
                     (slurp "templates/example-module.clj"))
               (println "✅ Created src/modules/" module-name ".clj"))
             (println "Usage: bb example-module <module-name>")))}

  :scaffold
  {:doc "Scaffold new module with all boilerplate"
   :task (let [module-name (first *command-line-args*)]
           (if module-name
             (do
               ;; Create module file
               (spit (str "src/modules/" module-name ".clj")
                     (format (slurp "templates/module-template.clj")
                             module-name module-name))
               ;; Create test file
               (spit (str "test/modules/" module-name "_test.clj")
                     (format (slurp "templates/module-test-template.clj")
                             module-name))
               (println "✅ Scaffolded module:" module-name)
               (println "   - src/modules/" module-name ".clj")
               (println "   - test/modules/" module-name "_test.clj"))
             (println "Usage: bb scaffold <module-name>")))}}}
```

---

## Usage Examples

### Quick Start

```bash
# Show all available tasks
bb tasks

# Start triple-interface server (recommended)
bb start

# Or specify port
bb triple 8080

# Or start specific interface
bb stdio    # For Claude Code
bb http     # For cloud/multi-client
```

### Project-Based Configuration

```bash
# 1. Initialize project config in current directory
cd /path/to/your/project
bb config-init
# Creates .bb-mcp-server.edn

# 2. Add project-specific tools to config
bb config-add-module tools/project-tools.clj
bb config-add-module modules/nrepl.clj

# 3. Edit config manually if needed
vim .bb-mcp-server.edn

# 4. Validate config
bb config-validate

# 5. Show which config is being used
bb config-show

# 6. Start server (auto-loads modules from config)
bb triple
# Server automatically loads:
#   - tools/project-tools.clj
#   - modules/nrepl.clj

# Alternative: Use global config for shared tools
bb config-init --global
# Creates ~/.config/bb-mcp-server/config.edn
# Edit to add tools you want available in all projects
```

**Example workflow for clay-noj-ai project:**

```bash
# Initialize clay project with custom tools
cd ~/Development/clay-noj-ai

# Create project config
bb config-init

# Add Clay-specific tools
bb config-add-module tools/clay-tools.clj
bb config-add-module tools/provenance-tools.clj

# Edit config to enable nREPL auto-connect
cat > .bb-mcp-server.edn <<'EOF'
{:modules
 {:load-on-startup
  [{:path "tools/clay-tools.clj"
    :enabled true
    :config {:clay-port 1971 :nrepl-port 7890}}
   {:path "modules/nrepl.clj"
    :enabled true
    :config {:auto-connect [{:host "localhost" :port 7890}]}}
   {:path "tools/provenance-tools.clj"
    :enabled true}]}
 :server {:port 9339}
 :telemetry {:level :debug :file-path "./logs/mcp-server.log"}}
EOF

# Start server - tools auto-load!
bb triple

# In another terminal: use with Claude Code
claude
# Claude can now use clay-tools and provenance-tools
```

**Switching between projects:**

```bash
# Project 1: Web development
cd ~/projects/webapp
bb triple  # Loads tools from ./webapp/.bb-mcp-server.edn

# Project 2: Data analysis
cd ~/projects/data-analysis
bb triple  # Loads different tools from ./data-analysis/.bb-mcp-server.edn

# Each project gets its own tool set automatically!
```

### Development Workflow

```bash
# 1. Start dev nREPL for interactive development
bb repl

# 2. List current modules
bb list-modules

# 3. Load a new module
bb load-module src/modules/custom.clj

# 4. List available tools
bb list-tools

# 5. Get tool details
bb tool-info nrepl-eval

# 6. Run tests
bb test

# 7. Watch tests
bb test-watch

# 8. Check code quality
bb check  # Runs lint + test
```

### Module Management

```bash
# Create new module from template
bb scaffold my-module
# Creates:
#   - src/modules/my-module.clj
#   - test/modules/my-module_test.clj

# Load module dynamically (while server running)
bb load-module src/modules/my-module.clj

# Unload module
bb unload-module src/modules/my-module.clj

# Reload all modules
bb reload-modules
```

### Module Lifecycle Management

```bash
# Check module status
bb module-status src/modules/nrepl.clj
# Output:
# {:state :running
#  :connections-count 2
#  :uptime-ms 45000
#  :config {:auto-connect [{:host "localhost" :port 7890}]}}

# Start a module (if stateful)
bb start-module src/modules/nrepl.clj

# Stop a module (closes connections gracefully)
bb stop-module src/modules/nrepl.clj

# Restart a module (stop + start)
bb restart-module src/modules/nrepl.clj

# Start all modules
bb start-all-modules

# Stop all modules
bb stop-all-modules

# Health check for all modules
bb module-health
# Output:
# {"src/modules/nrepl.clj" {:healthy true :status {:state :running}}
#  "src/modules/math.clj"  {:healthy true :status {:state :stateless}}}
```

### Configuration

```bash
# Show current config
bb config

# Set config value
bb config-set log-level debug
bb config-set port 9999

# Reset to defaults
bb config-reset
```

### Testing

```bash
# Run all tests
bb test

# Test specific module
bb test-module nrepl

# Integration tests (requires running server)
bb test-integration

# Watch mode
bb test-watch
```

### Telemetry and Monitoring

```bash
# Tail logs
bb logs

# Show recent errors
bb logs-errors

# Show slow requests (>1s)
bb logs-slow

# Get current metrics
bb metrics
```

### Deployment

```bash
# Build uberjar
bb build

# Build Docker image
bb docker-build

# Run Docker locally
bb docker-run

# Deploy to local systemd
bb deploy-local
```

### Development: stdio Mode (Claude Code)

```bash
# Claude Code config (.claude/config.json)
{
  "mcpServers": {
    "bb-mcp": {
      "command": "bb",
      "args": ["stdio"],
      "cwd": "/path/to/bb-mcp-server"
    }
  }
}

# Auto-spawns, loads modules from startup.clj
# Use bb tasks while developing:
bb list-modules   # See what's loaded
bb list-tools     # See available tools
```

### Production: Triple Server (Cloud Deployment)

```bash
# Start triple-interface server
bb triple 9339

# Claude Code config (HTTP mode)
{
  "mcpServers": {
    "bb-mcp": {
      "url": "https://mcp.example.com/mcp"
    }
  }
}

# REST API for dashboards
curl https://mcp.example.com/api/tools | jq
curl https://mcp.example.com/api/nrepl/connections | jq

# Health check
bb health
```

### Dynamic Module Loading (Live Server)

```bash
# Terminal 1: Start server
bb triple

# Terminal 2: Manage modules while server runs
bb list-modules
bb load-module src/modules/blockchain.clj
bb list-tools  # New tools appear!
bb tool-info blockchain-wallet-summary

# Claude Code (HTTP mode) sees new tools immediately!
# No restart needed
```

### Complete Development Session

```bash
# 1. Start with clean slate
bb clean

# 2. Check code quality
bb format
bb lint

# 3. Run tests
bb test

# 4. Start development REPL
bb repl
# Connect your editor to localhost:7890

# 5. In another terminal: start server
bb triple 8080

# 6. In another terminal: develop module
bb scaffold weather
# Edit src/modules/weather.clj
# Save changes

# 7. Load module into running server
bb load-module src/modules/weather.clj

# 8. Test the tool
curl -X POST http://localhost:8080/api/weather/forecast \
  -H "Content-Type: application/json" \
  -d '{"city":"San Francisco"}'

# 9. Check logs
bb logs-errors

# 10. Monitor performance
bb metrics

# 11. Run module tests
bb test-module weather

# All without restarting the server!
```

---

## Migration Path from mcp-nrepl-joyride

### Phase 1: Create bb-mcp-server skeleton
- [ ] Set up project structure
- [ ] Implement core registry
- [ ] Implement triple transport layer
- [ ] Add module loader

### Phase 2: Extract nREPL as module
- [ ] Port nREPL code to modules/nrepl.clj
- [ ] Test as loadable module
- [ ] Verify all nREPL tools work

### Phase 3: Add other modules
- [ ] Port agent tools (if desired)
- [ ] Add filesystem tools
- [ ] Add example modules

### Phase 4: Deployment
- [ ] Deploy to cloud
- [ ] Update Claude Code configs
- [ ] Migrate from mcp-nrepl-joyride

---

## Advantages Over mcp-nrepl-joyride

| Aspect | mcp-nrepl-joyride | bb-mcp-server |
|--------|-------------------|---------------|
| **Architecture** | nREPL-specific | Generic + modules |
| **Transports** | stdio (+ HTTP retrofitted) | stdio + HTTP + REST (built-in) |
| **Tool loading** | Added later | Native from day 1 |
| **Project-based config** | No | `.bb-mcp-server.edn` per project |
| **Config discovery** | Manual | Cascading (project → global → defaults) |
| **Relative paths** | Not supported | Relative to config file |
| **Lifecycle management** | Manual/ad-hoc | Component-like protocol |
| **Resource cleanup** | Not standardized | Automatic via lifecycle |
| **Name** | Couples to nREPL | Generic |
| **Modularity** | Monolithic | Core + plugins |
| **Cloud deployment** | Requires HTTP retrofit | Built for cloud |
| **REST API** | No | Yes |
| **Telemetry** | Not built-in | Trove from day 1 |
| **bb tasks** | Limited | Comprehensive (~50 tasks) |
| **Graceful shutdown** | Not guaranteed | Shutdown hooks |
| **Health checks** | No | Per-module status |
| **Scope** | Limited by name | Unlimited |

---

## Module Ecosystem Vision

Once bb-mcp-server exists, anyone can create modules:

```
bb-mcp-server (core)
    ↓ loads
    ├─ nrepl.clj (official)
    ├─ filesystem.clj (official)
    ├─ blockchain.clj (community)
    ├─ database.clj (community)
    ├─ aws.clj (community)
    └─ your-custom.clj (you!)
```

**Module registry** (future):
- bb-mcp-modules (like npm, crates.io)
- `bb mcp:install nrepl`
- `bb mcp:install blockchain`

---

## When to Use Each

### mcp-nrepl-joyride (Current)
✅ Keep using if:
- Only need nREPL
- stdio-only is fine
- Already working well
- Not worth migration

### bb-mcp-server (New)
✅ Switch to when:
- Want cloud deployment
- Need REST API
- Want modular architecture
- Building ecosystem of tools
- Need triple interface

---

## Next Steps

1. **Validate design** - Get feedback on architecture
2. **Create skeleton** - Basic project structure
3. **Implement core** - Registry + transport + loader
4. **Port nREPL module** - Extract from mcp-nrepl-joyride
5. **Test triple interface** - Verify stdio, HTTP, REST work
6. **Deploy to cloud** - Prove cloud deployment story
7. **Document modules** - Module development guide

---

## Conclusion

**bb-mcp-server** = Clean foundation with lessons learned:
- **Modular** - nREPL is one module among many
- **Multi-transport** - stdio, HTTP, REST built-in
- **Lifecycle management** - Component-like protocol for stateful modules
- **Cloud-ready** - Designed for deployment
- **Task-based** - ~50 bb tasks for all operations
- **Telemetry-first** - Trove structured logging from day 1
- **Extensible** - Easy to add modules
- **Generic** - Not coupled to any domain
- **Production-ready** - Graceful shutdown, health checks, resource cleanup

This is the right architecture for a **general-purpose Babashka MCP server platform**.

---

*Created: 2025-11-20*
*Supersedes: mcp-nrepl-joyride (lessons learned)*
*Related: transport-implementation-options.md, triple-interface-architecture.md*
