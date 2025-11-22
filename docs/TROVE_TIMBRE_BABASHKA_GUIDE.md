# Trove + Timbre Logging Guide for Babashka

## Executive Summary

**Recommendation:** Use **Trove** as your logging facade with **Timbre** as the backend for all Clojure/ClojureScript/Babashka projects.

**Why this combination:**
- ✅ Works TODAY across CLJ, CLJS, and Babashka (v1.12.204+)
- ✅ Future-proof: Easy migration to Telemere when it supports Babashka
- ✅ Zero dependencies for Trove (100 lines of code)
- ✅ Structured logging with rich data types
- ✅ Same API as Telemere - no relearning later
- ✅ Battle-tested Timbre backend (12+ years in production)

---

### AI Agent TL;DR
- **Goal:** replace all direct `taoensso.timbre` calls with Trove so the backend can later flip to Telemere without touching application code.
- **Happy path commands:**
  1. `(require '[taoensso.trove :as log] '[taoensso.trove.timbre :as backend] '[taoensso.timbre :as timbre])`
  2. `(timbre/set-level! :info)` (or env-driven) and optional appenders.
  3. `(log/set-log-fn! (backend/get-log-fn))`
  4. Replace ` (log/info "msg")` style calls with `(log/log! {:level :info :msg "msg" :data {...}})`.
- **Verification:** run `bb test` (or app entry point) and confirm logs now emit JSON payloads with `:level`, `:id`/`:event-id`, and context map.

### Agent Checklist (keep handy)
1. ✅ Confirm `bb --version` ≥ 1.12.204 and deps include `com.taoensso/trove`.
2. ✅ Ensure no namespace requires `taoensso.timbre` directly (lint for it) except the telemetry bootstrap namespace.
3. ✅ Configure Timbre level/appenders **before** calling `log/set-log-fn!`.
4. ✅ Emit structured events using `:id` (Trove-style `:namespace.component/event`).
5. ✅ Run `bb test` + manual smoke test; inspect a log line to verify JSON fields and redaction where needed.
6. ✅ Document any per-environment overrides (env vars, bb tasks) so the next AI agent knows how to toggle verbosity.

### Migration Notes
- The migration to Trove is complete. All code now uses `log/log!` with structured maps.
- When Telemere gains Babashka support, only the backend wiring (`log/set-log-fn!`) changes—app code stays on the Trove facade.
- Treat event IDs as ABI: agree on `:bb-mcp-server.component/action` pattern now to avoid churn later.
- Sensitive data must be scrubbed **before** the `:data` map; Trove does not auto-redact.

---

## Prerequisites

**Required Babashka Version:** >= v1.12.204 (released June 24, 2025)

```bash
# Check your version
bb --version

# Should show v1.12.204 or higher
# If not, upgrade: https://github.com/babashka/babashka#installation
```

**Why?** Earlier versions had a bug where Trove + Timbre didn't work together. This was fixed in v1.12.204.

---

## Understanding the Architecture

```
Your Code
    ↓
  Trove (facade - 100 LOC, 0 deps)
    ↓
  Timbre (backend - handles actual logging)
    ↓
  Output (console, file, etc.)
```

**Trove** is a minimal logging facade that:
- Provides a clean, modern API
- Allows switching backends without code changes
- Uses the same API as Telemere (future migration path)

**Timbre** is the actual logging implementation that:
- Handles filtering, formatting, and output
- Built into Babashka (no extra dependencies needed)
- Works across JVM Clojure, ClojureScript, and Babashka

---

## Quick Start

### Minimal Babashka Script

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend])

;; Configure Trove to use Timbre backend
(log/set-log-fn! (backend/get-log-fn))

;; Start logging!
(log/log! {:level :info :msg "Application started"})
(log/log! {:level :debug :msg "Debug info" :data {:user-id 123}})
(log/log! {:level :warn :msg "Warning message"})
(log/log! {:level :error :msg "Error occurred" :error (Exception. "Oops")})
```

Run it:
```bash
chmod +x script.bb
./script.bb
```

---

## Standardized Bootstrap for bb Scripts & Tasks

You do not want every task author to remember three `require` forms and the correct `(log/set-log-fn! …)` order. Create a single helper namespace that wires Trove + Timbre once and expose an idempotent `ensure-initialized!` entry point.

### Step 1: Bootstrap helper (one-time)

`src/bb_mcp_server/telemetry/bootstrap.clj`

```clojure
(ns bb-mcp-server.telemetry.bootstrap
  (:require [taoensso.trove :as log]
            [taoensso.trove.timbre :as backend]
            [taoensso.timbre :as timbre]))

(defonce initialized? (atom false))

(defn ensure-initialized!
  "Idempotently configure Timbre and point Trove at it.
  Optional opts: {:level :info :configure! #(timbre/merge-config! ...)}"
  ([] (ensure-initialized! {}))
  ([{:keys [level configure!]
     :or {level (keyword (or (System/getenv "LOG_LEVEL") "info"))}}]
   (when-not @initialized?
     (timbre/set-level! level)
     (when configure! (configure!))
     (log/set-log-fn! (backend/get-log-fn))
     (reset! initialized? true)
     (log/log! {:level :info
                :id ::telemetry/bootstrap-complete
                :msg "Telemetry ready for bb task"}))))
```

### Step 2: Use it from `.bb` scripts

```clojure
#!/usr/bin/env bb

(require '[bb-mcp-server.telemetry.bootstrap :as telemetry])

(telemetry/ensure-initialized!)

;; task logic continues here …
```

### Step 3: Use it from `bb.edn` tasks

```clojure
{:tasks
 {dev {:doc "Run dev loop with debug telemetry"
       :task (do
               (require '[bb-mcp-server.telemetry.bootstrap :as telemetry])
               (telemetry/ensure-initialized! {:level :debug})
               (load-file "scripts/dev_start.clj"))}

  test {:doc "Run CI tests with info telemetry"
        :task (do
                (require '[bb-mcp-server.telemetry.bootstrap :as telemetry])
                (telemetry/ensure-initialized!)
                (shell "bb" "test"))}}}
```

With this pattern every new task gains structured telemetry by calling a single function, and you have a single place to tweak appenders, redaction, or future Telemere swaps.

---

## Configuration Guide

### Setting Log Levels

There are two levels to configure:
1. **Trove's minimum level** (frontend filtering)
2. **Timbre's minimum level** (backend filtering)

#### Method 1: Configure via Timbre (Recommended)

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre])

;; Set Timbre's log level BEFORE setting up Trove
(timbre/set-level! :info)  ; Options: :trace :debug :info :warn :error :fatal

;; Now configure Trove to use Timbre
(log/set-log-fn! (backend/get-log-fn))

;; These will be logged
(log/log! {:level :info :msg "This shows"})
(log/log! {:level :warn :msg "This shows"})
(log/log! {:level :error :msg "This shows"})

;; These will be filtered out
(log/log! {:level :debug :msg "This is hidden"})
(log/log! {:level :trace :msg "This is hidden"})
```

#### Method 2: Configure via Environment Variable

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre])

;; Read log level from environment
(let [log-level (keyword (or (System/getenv "LOG_LEVEL") "info"))]
  (timbre/set-level! log-level))

(log/set-log-fn! (backend/get-log-fn))

(log/log! {:level :info :msg "Application started"})
```

Run with different levels:
```bash
LOG_LEVEL=debug ./script.bb
LOG_LEVEL=warn ./script.bb
LOG_LEVEL=error ./script.bb
```

#### Method 3: Configuration in bb.edn Tasks

```clojure
;; bb.edn
{:tasks
 {dev {:doc "Run in development mode with debug logging"
       :task (do
               (require '[taoensso.trove :as log]
                        '[taoensso.trove.timbre :as backend]
                        '[taoensso.timbre :as timbre])
               (timbre/set-level! :debug)
               (log/set-log-fn! (backend/get-log-fn))
               (load-file "src/main.clj"))}
  
  prod {:doc "Run in production mode with info logging"
        :task (do
                (require '[taoensso.trove :as log]
                         '[taoensso.trove.timbre :as backend]
                         '[taoensso.timbre :as timbre])
                (timbre/set-level! :info)
                (log/set-log-fn! (backend/get-log-fn))
                (load-file "src/main.clj"))}}}
```

---

## Timbre Backend Configuration Options

### Basic Timbre Configuration

Timbre is configured via `timbre/merge-config!`:

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre])

;; Configure Timbre's behavior
(timbre/merge-config!
  {:level :info                    ; Minimum level
   :ns-filter {:allow #{"*"}       ; Allow all namespaces
               :deny #{}}          ; Deny none
   :middleware []                  ; Transform log data
   :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss"
                    :timezone :utc}})

;; Setup Trove with configured Timbre
(log/set-log-fn! (backend/get-log-fn))
```

### Filtering by Namespace

```clojure
(require '[taoensso.timbre :as timbre])

;; Only log from specific namespaces
(timbre/merge-config!
  {:ns-filter {:allow #{"my-app.core" "my-app.db"}
               :deny #{"my-app.debug.*"}}})

;; Or use wildcards
(timbre/merge-config!
  {:ns-filter {:allow #{"my-app.*"}        ; All my-app namespaces
               :deny #{"my-app.noisy.*"}}}) ; Except noisy ones
```

### Output Destinations (Appenders)

Timbre uses "appenders" to control where logs go:

#### Console Only (Default)

```clojure
;; Timbre defaults to console output
;; No configuration needed!
```

#### Console + File

```clojure
(require '[taoensso.timbre :as timbre]
         '[taoensso.timbre.appenders.core :as appenders])

(timbre/merge-config!
  {:appenders {:println {:enabled? true}  ; Console
               :spit (appenders/spit-appender
                       {:fname "logs/app.log"})}})  ; File
```

#### File Only (No Console)

```clojure
(require '[taoensso.timbre :as timbre]
         '[taoensso.timbre.appenders.core :as appenders])

(timbre/merge-config!
  {:appenders {:println {:enabled? false}  ; Disable console
               :spit (appenders/spit-appender
                       {:fname "logs/app.log"})}})
```

#### Different Levels for Different Appenders

```clojure
(require '[taoensso.timbre :as timbre]
         '[taoensso.timbre.appenders.core :as appenders])

(timbre/merge-config!
  {:appenders 
   {:println {:enabled? true
              :min-level :info}  ; Console shows info+
    
    :debug-file (merge 
                  (appenders/spit-appender {:fname "logs/debug.log"})
                  {:min-level :debug})  ; File shows debug+
    
    :error-file (merge
                  (appenders/spit-appender {:fname "logs/errors.log"})
                  {:min-level :error})}})  ; Error file shows errors only
```

---

## Usage Patterns

### Basic Logging Levels

```clojure
(require '[taoensso.trove :as log])

;; Trace - Very detailed debugging
(log/log! {:level :trace :msg "Entering function" :data {:args args}})

;; Debug - Detailed debugging
(log/log! {:level :debug :msg "Processing item" :data {:item-id id}})

;; Info - General informational messages
(log/log! {:level :info :msg "User logged in" :data {:user-id user-id}})

;; Warn - Warning conditions
(log/log! {:level :warn :msg "Rate limit approaching" :data {:requests 95}})

;; Error - Error conditions
(log/log! {:level :error :msg "Failed to save" :error e})

;; Fatal - Critical system failures
(log/log! {:level :fatal :msg "Database connection lost"})
```

### Structured Logging with Data

```clojure
(log/log! {:level :info
           :msg "Order processed"
           :data {:order-id 12345
                  :user-id 789
                  :amount 99.99
                  :items 3
                  :shipping-address {:zip "12345"}}})
```

### Logging with IDs (for filtering/searching)

```clojure
(log/log! {:level :info
           :id ::user-login           ; Namespaced keyword for searching
           :msg "User logged in"
           :data {:user-id user-id}})

(log/log! {:level :error
           :id ::payment-failed
           :msg "Payment processing failed"
           :error e
           :data {:order-id order-id}})
```

### Logging Exceptions

```clojure
(try
  (risky-operation)
  (catch Exception e
    (log/log! {:level :error
               :msg "Operation failed"
               :error e
               :data {:context "processing order"}})))
```

### Performance Tracing

```clojure
(defn expensive-operation [data]
  (log/log! {:level :debug :msg "Starting expensive operation"})
  (let [start (System/currentTimeMillis)
        result (do-work data)
        duration (- (System/currentTimeMillis) start)]
    (log/log! {:level :info
               :msg "Operation completed"
               :data {:duration-ms duration
                      :items-processed (count result)}})
    result))
```

---

## Complete Examples

### Example 1: Simple Babashka Script

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre]
         '[babashka.fs :as fs])

;; Setup logging
(timbre/set-level! :info)
(log/set-log-fn! (backend/get-log-fn))

(defn process-files [dir]
  (log/log! {:level :info :msg "Starting file processing" :data {:dir dir}})
  
  (let [files (fs/list-dir dir)]
    (log/log! {:level :debug :msg "Found files" :data {:count (count files)}})
    
    (doseq [file files]
      (try
        (log/log! {:level :debug :msg "Processing file" :data {:file (str file)}})
        ;; Process file...
        (log/log! {:level :info :msg "File processed" :data {:file (str file)}})
        (catch Exception e
          (log/log! {:level :error
                     :msg "Failed to process file"
                     :error e
                     :data {:file (str file)}}))))
    
    (log/log! {:level :info
               :msg "Processing complete"
               :data {:total (count files)}})))

;; Run it
(process-files (or (first *command-line-args*) "."))
```

### Example 2: bb.edn Task with Logging

```clojure
;; bb.edn
{:paths ["src"]
 
 :tasks
 {;; Logging setup task (reusable)
  setup-logging
  {:requires ([taoensso.trove :as log]
              [taoensso.trove.timbre :as backend]
              [taoensso.timbre :as timbre])
   :task (do
           (let [level (keyword (or (System/getenv "LOG_LEVEL") "info"))]
             (timbre/set-level! level)
             (log/set-log-fn! (backend/get-log-fn))
             (log/log! {:level :info 
                        :msg "Logging configured"
                        :data {:level level}})))}
  
  ;; Deploy task with logging
  deploy
  {:doc "Deploy application"
   :depends [setup-logging]
   :task (do
           (require '[taoensso.trove :as log])
           (log/log! {:level :info :msg "Starting deployment"})
           
           (shell "bb test")
           (log/log! {:level :info :msg "Tests passed"})
           
           (shell "bb build")
           (log/log! {:level :info :msg "Build complete"})
           
           (log/log! {:level :info :msg "Deployment complete"}))}
  
  ;; Development task with debug logging
  dev
  {:doc "Run in development mode"
   :task (do
           (require '[taoensso.trove :as log]
                    '[taoensso.trove.timbre :as backend]
                    '[taoensso.timbre :as timbre])
           (timbre/set-level! :debug)
           (log/set-log-fn! (backend/get-log-fn))
           (log/log! {:level :info :msg "Development mode"})
           (load-file "src/main.clj"))}}}
```

### Example 3: Multi-File Application

**src/logging.clj:**
```clojure
(ns logging
  (:require [taoensso.trove :as log]
            [taoensso.trove.timbre :as backend]
            [taoensso.timbre :as timbre]))

(defn init!
  "Initialize logging system"
  ([] (init! :info))
  ([level]
   (timbre/set-level! level)
   (log/set-log-fn! (backend/get-log-fn))
   (log/log! {:level :info
              :msg "Logging initialized"
              :data {:level level}})))

(defn set-level! [level]
  (timbre/set-level! level)
  (log/log! {:level :info
             :msg "Log level changed"
             :data {:new-level level}}))
```

**src/db.clj:**
```clojure
(ns db
  (:require [taoensso.trove :as log]))

(defn connect! [config]
  (log/log! {:level :info
             :msg "Connecting to database"
             :data {:host (:host config)}})
  (try
    ;; Connection logic
    (log/log! {:level :info :msg "Database connected"})
    {:connected true}
    (catch Exception e
      (log/log! {:level :error
                 :msg "Database connection failed"
                 :error e})
      (throw e))))

(defn query [sql params]
  (log/log! {:level :debug
             :msg "Executing query"
             :data {:sql sql :param-count (count params)}})
  ;; Query logic
  )
```

**src/main.clj:**
```clojure
(ns main
  (:require [logging]
            [db]
            [taoensso.trove :as log]))

(defn -main [& args]
  ;; Initialize logging
  (logging/init! :info)
  
  (log/log! {:level :info :msg "Application starting"})
  
  ;; Connect to database
  (db/connect! {:host "localhost" :port 5432})
  
  ;; Do work
  (log/log! {:level :info :msg "Processing requests"})
  
  (log/log! {:level :info :msg "Application shutdown"}))
```

**Run it:**
```bash
bb -m main
```

---

## Configuration Patterns

### Pattern 1: Environment-Based Configuration

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre])

(defn setup-logging! []
  (let [env (or (System/getenv "ENV") "development")
        log-level (case env
                    "production" :info
                    "staging" :info
                    "development" :debug
                    "test" :warn
                    :info)]
    (timbre/set-level! log-level)
    (log/set-log-fn! (backend/get-log-fn))
    (log/log! {:level :info
               :msg "Logging configured"
               :data {:env env :level log-level}})))

(setup-logging!)
```

### Pattern 2: Configuration from EDN File

```clojure
;; config/logging.edn
{:level :info
 :ns-filter {:allow #{"my-app.*"}
             :deny #{"my-app.noisy"}}
 :output :file
 :file-path "logs/app.log"}
```

```clojure
#!/usr/bin/env bb

(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre]
         '[taoensso.timbre.appenders.core :as appenders]
         '[clojure.edn :as edn])

(defn load-logging-config! [config-file]
  (let [config (edn/read-string (slurp config-file))]
    ;; Set level
    (timbre/set-level! (:level config))
    
    ;; Configure namespace filtering
    (when (:ns-filter config)
      (timbre/merge-config! {:ns-filter (:ns-filter config)}))
    
    ;; Configure output
    (when (= :file (:output config))
      (timbre/merge-config!
        {:appenders {:println {:enabled? false}
                     :spit (appenders/spit-appender
                             {:fname (:file-path config)})}}))
    
    ;; Setup Trove
    (log/set-log-fn! (backend/get-log-fn))
    (log/log! {:level :info :msg "Logging configured from file"})))

(load-logging-config! "config/logging.edn")
```

### Pattern 3: Context Wrapper Function

```clojure
(require '[taoensso.trove :as log])

(defn with-request-context [request-id f]
  "Execute function with request context in all logs"
  (binding [log/*context* {:request-id request-id}]
    (f)))

;; Usage
(with-request-context "req-12345"
  (fn []
    (log/log! {:level :info :msg "Processing request"})
    (process-order order)))
```

---

## Best Practices

### 1. Initialize Logging Early

```clojure
#!/usr/bin/env bb

;; DO THIS FIRST, before any other requires
(require '[taoensso.trove :as log]
         '[taoensso.trove.timbre :as backend]
         '[taoensso.timbre :as timbre])

(timbre/set-level! :info)
(log/set-log-fn! (backend/get-log-fn))

;; NOW require your application code
(require '[my-app.core :as core])

(core/main)
```

### 2. Use Namespaced IDs for Searchability

```clojure
;; GOOD - Easy to search logs for all auth events
(log/log! {:level :info :id ::auth/login :msg "User logged in"})
(log/log! {:level :warn :id ::auth/failed :msg "Login failed"})

;; BAD - Generic, hard to search
(log/log! {:level :info :msg "Login"})
```

### 3. Include Rich Context Data

```clojure
;; GOOD - Structured data for analysis
(log/log! {:level :error
           :id ::payment/failed
           :msg "Payment processing failed"
           :error e
           :data {:order-id order-id
                  :user-id user-id
                  :amount amount
                  :payment-method method
                  :attempt-number retries}})

;; BAD - String concatenation loses structure
(log/log! {:level :error
           :msg (str "Payment failed for order " order-id)})
```

### 4. Log at Function Boundaries

```clojure
(defn process-order [order]
  (log/log! {:level :info
             :id ::order/process-start
             :msg "Starting order processing"
             :data {:order-id (:id order)}})
  (try
    (let [result (do-processing order)]
      (log/log! {:level :info
                 :id ::order/process-complete
                 :msg "Order processing complete"
                 :data {:order-id (:id order)
                        :duration-ms (:duration result)}})
      result)
    (catch Exception e
      (log/log! {:level :error
                 :id ::order/process-failed
                 :msg "Order processing failed"
                 :error e
                 :data {:order-id (:id order)}})
      (throw e))))
```

### 5. Use Debug Level Liberally

```clojure
(defn complex-calculation [input]
  (log/log! {:level :debug :msg "Starting calculation" :data {:input input}})
  
  (let [step1 (process-step-1 input)]
    (log/log! {:level :debug :msg "Step 1 complete" :data {:result step1}})
    
    (let [step2 (process-step-2 step1)]
      (log/log! {:level :debug :msg "Step 2 complete" :data {:result step2}})
      
      step2)))
```

---

## Migration Path to Telemere

When Telemere adds Babashka support (currently not supported), migration is trivial:

### Before (Trove + Timbre):
```clojure
(ns my-app
  (:require [taoensso.trove :as log]
            [taoensso.trove.timbre :as backend]))

(log/set-log-fn! (backend/get-log-fn))

(log/log! {:level :info :msg "Hello"})
```

### After (Trove + Telemere):
```clojure
(ns my-app
  (:require [taoensso.trove :as log]
            [taoensso.trove.telemere :as backend]))  ; <- ONLY CHANGE

(log/set-log-fn! (backend/get-log-fn))  ; <- ONLY CHANGE

;; ALL YOUR LOG CALLS STAY EXACTLY THE SAME
(log/log! {:level :info :msg "Hello"})
```

**That's it!** Your entire codebase continues working unchanged.

---

## Troubleshooting

### Issue: "Could not resolve symbol: timbre/may-log?"

**Cause:** You're on Babashka < v1.12.204

**Solution:**
```bash
# Check version
bb --version

# Upgrade if needed
# macOS/Linux:
bash <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)

# Or via package manager:
brew upgrade babashka  # macOS
```

### Issue: Logs not appearing

**Cause:** Log level is set too high

**Solution:**
```clojure
;; Check what level is set
(require '[taoensso.timbre :as timbre])
(println "Current level:" timbre/*config*)

;; Lower the level
(timbre/set-level! :debug)
```

### Issue: Too many debug logs in production

**Solution:**
```clojure
;; Use environment variable
(let [level (keyword (or (System/getenv "LOG_LEVEL") "info"))]
  (timbre/set-level! level))

# Then run:
LOG_LEVEL=warn bb script.bb
```

### Issue: Want to disable logging completely

**Solution:**
```clojure
;; Method 1: Set level to :fatal (nothing logs except fatal)
(timbre/set-level! :fatal)

;; Method 2: Disable Trove entirely
(log/set-log-fn! nil)  ; All log calls become no-ops
```

---

## Performance Considerations

### Trove is Zero-Cost

Trove compiles to a simple `when-let` check:

```clojure
;; What you write:
(log/log! {:level :info :msg "Hello"})

;; What actually runs:
(when-let [log-fn trove/*log-fn*]
  (log-fn ...))
```

**Result:** If logging is disabled, there's zero overhead (not even function call).

### Lazy Evaluation of Data

Trove automatically delays expensive operations:

```clojure
;; This expensive-computation only runs if log level allows it
(log/log! {:level :debug
           :data {:expensive (expensive-computation)}})
```

### Conditional Logging for Hot Paths

For ultra-performance-critical code:

```clojure
;; Manual check to avoid even building the map
(when (timbre/may-log? :debug)
  (log/log! {:level :debug
             :msg "In hot path"
             :data {:result (calculate-result)}}))
```

---

## Reference: Complete API

### Trove Functions

```clojure
;; Setup
(log/set-log-fn! backend-fn)  ; Set logging backend
(log/set-log-fn! nil)          ; Disable all logging

;; Logging
(log/log! {:level   :info        ; Required: :trace :debug :info :warn :error :fatal
           :msg     "Message"    ; Optional: String or any value
           :id      ::my-event   ; Optional: Namespaced keyword for filtering
           :data    {...}        ; Optional: Any Clojure data
           :error   exception    ; Optional: Exception/Throwable
           :ns      "namespace"  ; Optional: Override namespace
           :line    42           ; Optional: Override line number
           :column  10})         ; Optional: Override column number
```

### Timbre Functions (Backend Configuration)

```clojure
;; Level control
(timbre/set-level! :info)      ; Set global level
(timbre/may-log? :debug)       ; Check if level would log

;; Configuration
(timbre/merge-config! {...})   ; Merge config map
(timbre/set-config! {...})     ; Replace entire config

;; Common config options:
{:level :info                  ; Global minimum level
 :ns-filter {:allow #{"*"}     ; Namespace patterns to allow
             :deny #{}}        ; Namespace patterns to deny
 :middleware []                ; Transform log data before output
 :timestamp-opts {...}         ; Timestamp formatting
 :appenders {...}}             ; Output destinations
```

### Log Levels (in order)

1. `:trace` - Most verbose, rarely used
2. `:debug` - Detailed debugging information
3. `:info` - General informational messages (default)
4. `:warn` - Warning conditions
5. `:error` - Error conditions
6. `:fatal` - Critical failures

---

## Summary: Quick Decision Guide

**Use Trove + Timbre when:**
- ✅ You want future-proof logging (easy migration to Telemere)
- ✅ You want structured logging with rich data
- ✅ You're writing Babashka scripts or applications
- ✅ You want the same API across CLJ/CLJS/BB

**Use Timbre directly when:**
- You prefer `(log/info "message")` style over maps
- You're maintaining legacy code already using Timbre
- You don't care about future backend flexibility

**Don't use:**
- ❌ tools.logging (too Java-centric, complex)
- ❌ Telemere (doesn't support Babashka yet)
- ❌ println (no control, no filtering, no structure)

---

## Resources

- [Trove GitHub](https://github.com/taoensso/trove)
- [Timbre GitHub](https://github.com/taoensso/timbre)
- [Telemere GitHub](https://github.com/taoensso/telemere) (future)
- [Babashka Book](https://book.babashka.org/)
- [Babashka GitHub](https://github.com/babashka/babashka)

---

## Conclusion

**Trove + Timbre is the recommended logging solution for Babashka projects.**

It provides:
- Modern, structured logging API
- Works everywhere (CLJ/CLJS/BB)
- Future-proof migration path
- Zero dependencies
- Battle-tested backend
- Excellent performance

Start logging properly today, and when Telemere supports Babashka tomorrow, you're ready with a one-line change.

**Happy logging! 🪵**
