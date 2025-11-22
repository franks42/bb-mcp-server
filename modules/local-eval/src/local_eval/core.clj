(ns local-eval.core
    "Local eval module - code execution within MCP server runtime."
    (:require [bb-mcp-server.registry :as registry]
              [local-eval.eval :as eval]
              [local-eval.load-file :as load-file]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the local-eval module. Registers local-eval and local-load-file tools."
  [_deps config]
  (log/log! {:level :info
             :id ::local-eval-starting
             :msg "Starting local-eval module"
             :data {:config config}})
  (registry/register! eval/tool-definition)
  (registry/register! load-file/tool-definition)
  (log/log! {:level :info
             :id ::local-eval-started
             :msg "Local-eval module started"
             :data {:registered-tools ["local-eval" "local-load-file"]}})
  {:registered-tools ["local-eval" "local-load-file"]})

(defn stop
  "Stop the local-eval module. Unregisters tools."
  [_instance]
  (log/log! {:level :info
             :id ::local-eval-stopping
             :msg "Stopping local-eval module"})
  (registry/unregister! "local-eval")
  (registry/unregister! "local-load-file")
  nil)

(defn status
  "Get local-eval module status."
  [_instance]
  {:status :ok
   :registered-tools ["local-eval" "local-load-file"]})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Local-eval module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
