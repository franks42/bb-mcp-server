(ns ai-orchestrator-tools.core
    "AI Orchestrator Tools module - MCP tools for AI instance management.

   This module provides tools for:
   - Starting AI instances (ai_start_instance)
   - Sending messages (ai_ask)
   - Stopping instances (ai_stop_instance)
   - Listing instances (ai_list_instances)

   Depends on ai-orchestrator for the underlying functionality."
    (:require [bb-mcp-server.registry :as registry]
              [ai-orchestrator-tools.tools.start-instance :as start-tool]
              [ai-orchestrator-tools.tools.ask :as ask-tool]
              [ai-orchestrator-tools.tools.stop-instance :as stop-tool]
              [ai-orchestrator-tools.tools.list-instances :as list-tool]
              [taoensso.trove :as log]))

;; =============================================================================
;; Tool Definitions
;; =============================================================================

(def tools
     "All AI orchestrator tools to register."
     [{:name start-tool/tool-name
       :definition start-tool/metadata
       :handler start-tool/handle}
      {:name ask-tool/tool-name
       :definition ask-tool/metadata
       :handler ask-tool/handle}
      {:name stop-tool/tool-name
       :definition stop-tool/metadata
       :handler stop-tool/handle}
      {:name list-tool/tool-name
       :definition list-tool/metadata
       :handler list-tool/handle}])

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the AI orchestrator tools module. Registers all tools."
  [_deps config]
  (log/log! {:level :info
             :id    ::ai-orchestrator-tools-starting
             :msg   "Starting AI orchestrator tools module"
             :data  {:config config
                     :tool-count (count tools)}})
  ;; Register all tools
  (doseq [{:keys [name definition handler]} tools]
         (registry/register! (assoc definition
                                    :name name
                                    :module "ai-orchestrator-tools"
                                    :handler handler)))
  (log/log! {:level :info
             :id    ::ai-orchestrator-tools-started
             :msg   "AI orchestrator tools module started"
             :data  {:registered-tools (mapv :name tools)}})
  {:registered-tools (mapv :name tools)})

(defn stop
  "Stop the AI orchestrator tools module. Unregisters all tools."
  [_instance]
  (log/log! {:level :info
             :id    ::ai-orchestrator-tools-stopping
             :msg   "Stopping AI orchestrator tools module"})
  ;; Unregister all tools (use full name: ai-orchestrator-tools.toolname)
  (doseq [{:keys [name]} tools]
         (registry/unregister! (str "ai-orchestrator-tools." name)))
  nil)

(defn status
  "Get AI orchestrator tools module status."
  [_instance]
  {:status :ok
   :registered-tools (mapv :name tools)})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "AI orchestrator tools module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
