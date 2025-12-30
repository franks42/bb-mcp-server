(ns nrepl.core
    "nREPL module - connect, evaluate, and manage remote Clojure REPLs.

   This module provides tools for:
   - Connecting to nREPL servers (nrepl-connection)
   - Evaluating code (nrepl-eval)
   - Loading files (nrepl-load-file, nrepl-eval-local-file)
   - Low-level messaging (nrepl-send-message, async variants)
   - Local nREPL server management (local-nrepl-server)
   - Runtime introspection (nrepl-loaded-namespaces, nrepl-introspect-ns, nrepl-var-meta, nrepl-get-value)
   - Documentation (must-read-mcp-nrepl-context)"
    (:require [bb-mcp-server.registry :as registry]
              [nrepl.tools.nrepl-connection :as conn-tool]
              [nrepl.tools.nrepl-eval :as eval-tool]
              [nrepl.tools.nrepl-send-message :as send-tool]
              [nrepl.tools.nrepl-send-message-async :as send-async-tool]
              [nrepl.tools.nrepl-get-result-async :as get-async-tool]
              [nrepl.tools.nrepl-load-file :as load-tool]
              [nrepl.tools.nrepl-eval-local-file :as eval-local-tool]
              [nrepl.tools.local-nrepl-server :as local-server-tool]
              [nrepl.tools.must-read-mcp-nrepl-context :as context-tool]
              ;; Phase 0: Runtime introspection tools
              [nrepl.tools.nrepl-loaded-namespaces :as loaded-ns-tool]
              [nrepl.tools.nrepl-introspect-ns :as introspect-ns-tool]
              [nrepl.tools.nrepl-var-meta :as var-meta-tool]
              [nrepl.tools.nrepl-get-value :as get-value-tool]
              [taoensso.trove :as log]))

;; =============================================================================
;; Tool Definitions
;; =============================================================================

(def tools
     "All nREPL tools to register."
     [{:name conn-tool/tool-name
       :definition conn-tool/metadata
       :handler conn-tool/handle}
      {:name eval-tool/tool-name
       :definition eval-tool/metadata
       :handler eval-tool/handle}
      {:name send-tool/tool-name
       :definition send-tool/metadata
       :handler send-tool/handle}
      {:name send-async-tool/tool-name
       :definition send-async-tool/metadata
       :handler send-async-tool/handle}
      {:name get-async-tool/tool-name
       :definition get-async-tool/metadata
       :handler get-async-tool/handle}
      {:name load-tool/tool-name
       :definition load-tool/metadata
       :handler load-tool/handle}
      {:name eval-local-tool/tool-name
       :definition eval-local-tool/metadata
       :handler eval-local-tool/handle}
      {:name local-server-tool/tool-name
       :definition local-server-tool/metadata
       :handler local-server-tool/handle}
      {:name context-tool/tool-name
       :definition context-tool/metadata
       :handler context-tool/handle}
      ;; Phase 0: Runtime introspection tools
      {:name loaded-ns-tool/tool-name
       :definition loaded-ns-tool/metadata
       :handler loaded-ns-tool/handle}
      {:name introspect-ns-tool/tool-name
       :definition introspect-ns-tool/metadata
       :handler introspect-ns-tool/handle}
      {:name var-meta-tool/tool-name
       :definition var-meta-tool/metadata
       :handler var-meta-tool/handle}
      {:name get-value-tool/tool-name
       :definition get-value-tool/metadata
       :handler get-value-tool/handle}])

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the nREPL module. Registers all nREPL tools."
  [_deps config]
  (log/log! {:level :info
             :id ::nrepl-starting
             :msg "Starting nREPL module"
             :data {:config config
                    :tool-count (count tools)}})
  ;; Register all tools
  (doseq [{:keys [name definition handler]} tools]
         (registry/register! (assoc definition :name name :module "nrepl" :handler handler)))
  (log/log! {:level :info
             :id ::nrepl-started
             :msg "nREPL module started"
             :data {:registered-tools (mapv :name tools)}})
  {:registered-tools (mapv :name tools)})

(defn stop
  "Stop the nREPL module. Unregisters all nREPL tools."
  [_instance]
  (log/log! {:level :info
             :id ::nrepl-stopping
             :msg "Stopping nREPL module"})
  ;; Unregister all tools (use full name: nrepl.toolname)
  (doseq [{:keys [name]} tools]
         (registry/unregister! (str "nrepl." name)))
  nil)

(defn status
  "Get nREPL module status."
  [_instance]
  {:status :ok
   :registered-tools (mapv :name tools)})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "nREPL module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
