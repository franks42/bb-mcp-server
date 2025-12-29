(ns bb-mcp-server.modules.clojure-lsp.core
    "Clojure LSP module - LSP integration via persistent subprocess.

   This module provides MCP tools for Clojure code intelligence via clojure-lsp."
    (:require [bb-mcp-server.registry :as registry]
              [bb-mcp-server.modules.clojure-lsp.server :as server]
              [taoensso.trove :as log]))

;; =============================================================================
;; Tool Definitions
;; =============================================================================

(def clj-init-tool
     "MCP tool definition for initializing clojure-lsp."
     {:name "clj-init"
      :module "clojure-lsp"
      :description "Initialize clojure-lsp for a project. Call this first. Initial analysis may take 30s-2min for large projects, subsequent calls are fast."
      :handler (fn [args]
                 (server/init! args))
      :inputSchema {:type "object"
                    :properties {:project-root {:type "string"
                                                :description "Absolute path to project root"}
                                 :executable-path {:type "string"
                                                   :description "Optional. Absolute path to clojure-lsp executable. Defaults to 'clojure-lsp' on PATH."}}
                    :required ["project-root"]}})

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the clojure-lsp module. Registers clj-init tool."
  [_deps config]
  (log/log! {:level :info
             :id ::clojure-lsp-starting
             :msg "Starting clojure-lsp module"
             :data {:config config}})
  (registry/register! clj-init-tool)
  (log/log! {:level :info
             :id ::clojure-lsp-started
             :msg "Clojure-lsp module started"
             :data {:registered-tools ["clj-init"]}})
  {:registered-tools ["clj-init"]})

(defn stop
  "Stop the clojure-lsp module. Stops server and unregisters tools."
  [_instance]
  (log/log! {:level :info
             :id ::clojure-lsp-stopping
             :msg "Stopping clojure-lsp module"})
  ;; Stop LSP server if running
  (try
   (server/stop!)
   (catch Exception e
          (log/log! {:level :warn
                     :id ::clojure-lsp-stop-failed
                     :msg "Failed to stop clojure-lsp server"
                     :data {:error (ex-message e)}})))
  ;; Unregister tools
  (registry/unregister! "clojure-lsp.clj-init")
  nil)

(defn status
  "Get clojure-lsp module status."
  [_instance]
  (let [server-status (server/status)]
    {:status (if (:running server-status) :running :idle)
     :registered-tools ["clj-init"]
     :server server-status}))

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Clojure-lsp module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
