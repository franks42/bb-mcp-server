(ns mcp-local-eval.core
    "MCP local eval module - code execution within MCP server runtime.
   Named 'mcp-local-eval' to clarify this uses MCP (not nREPL)."
    (:require [bb-mcp-server.registry :as registry]
              [mcp-local-eval.eval :as eval]
              [mcp-local-eval.load-file :as load-file]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the mcp-local-eval module. Registers local-eval and local-load-file tools."
  [_deps config]
  (log/log! {:level :info
             :id ::mcp-local-eval-starting
             :msg "Starting mcp-local-eval module"
             :data {:config config}})
  (registry/register! eval/tool-definition)
  (registry/register! load-file/tool-definition)
  (log/log! {:level :info
             :id ::mcp-local-eval-started
             :msg "MCP local-eval module started"
             :data {:registered-tools ["local-eval" "local-load-file"]}})
  {:registered-tools ["local-eval" "local-load-file"]})

(defn stop
  "Stop the mcp-local-eval module. Unregisters tools."
  [_instance]
  (log/log! {:level :info
             :id ::mcp-local-eval-stopping
             :msg "Stopping mcp-local-eval module"})
  (registry/unregister! "mcp-local-eval.local-eval")
  (registry/unregister! "mcp-local-eval.local-load-file")
  nil)

(defn status
  "Get mcp-local-eval module status."
  [_instance]
  {:status :ok
   :registered-tools ["local-eval" "local-load-file"]})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "MCP local-eval module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
