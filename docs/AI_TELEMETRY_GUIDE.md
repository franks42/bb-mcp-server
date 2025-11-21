# AI Assistant Directive: Telemetry in Babashka Projects

**Target Audience:** AI Coding Assistants (Copilot, Claude, Gemini, etc.)
**Context:** Building Babashka (bb) scripts, tools, CLI apps, or MCP servers.
**Goal:** Embed observability and structured logging from the start.

## 1. The Golden Rule: Trove for Logging, Timbre for Config

*   **DO** use `taoensso.trove` (the facade) for all logging calls in your source code.
*   **DO** use `taoensso.timbre` (the backend) **ONLY** in a dedicated `telemetry` namespace for configuration.
*   **DO NOT** use `println`, `prn`, or `clojure.tools.logging` for application logs.

## 2. Implementation Pattern

### A. The Bootstrap Namespace (`src/my_project/telemetry.clj`)

Create a single namespace to wire the backend. This ensures consistent configuration (JSON output, stderr routing, etc.).

```clojure
(ns my-project.telemetry
  (:require [taoensso.timbre :as timbre]
            [taoensso.trove :as log]
            [taoensso.trove.timbre :as backend]))

(defn init! []
  ;; 1. Configure Timbre (Backend)
  ;; Critical for CLI tools/MCP: Route logs to stderr to keep stdout clean for data
  (timbre/merge-config!
   {:min-level :info
    :appenders {:println {:enabled? true
                          :fn (fn [data]
                                (binding [*out* *err*]
                                  (println (force (:output_ data))))}}}})

  ;; 2. Link Trove (Facade) to Timbre
  (log/set-log-fn! (backend/get-log-fn))
  
  (log/info {:msg "Telemetry initialized" :data {:version "1.0.0"}}))
```

### B. Usage in Application Code

Require `taoensso.trove` and use it everywhere.

```clojure
(ns my-project.core
  (:require [taoensso.trove :as log]
            [my-project.telemetry :as telemetry]))

(defn -main [& args]
  (telemetry/init!)
  (log/info {:msg "Application started" :data {:args args}})
  
  (try
    (do-work)
    (catch Exception e
      (log/error {:msg "Fatal error" :data {:error e}}))))
```

## 3. Directives for AI Agents

1.  **Structured First**: Always prefer structured logging.
    *   *Good*: `(log/info {:msg "User login" :data {:user-id 123}})`
    *   *Bad*: `(log/info (str "User login " 123))`
2.  **Stdio Hygiene**: If the app uses `stdout` for data (e.g., JSON-RPC, CLI output), **MUST** configure the logger to write to `stderr`.
3.  **Context is King**: Include relevant data maps in logs to aid future debugging.
4.  **Initialize Early**: Ensure the telemetry init function is called at the entry point (`-main`).

## 4. Why Trove?

*   **Zero-Dep Facade**: Source code depends only on the lightweight Trove API.
*   **Pluggable Backend**: Can swap Timbre for another logger later without changing source code.
*   **Babashka Compatible**: Works seamlessly in BB environments.
