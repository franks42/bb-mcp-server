(ns bb-mcp-server.modules.clojure-lsp.server
    "Server interface for clojure-lsp module.
   Provides the clj-init MCP tool handler and process management."
    (:require [bb-mcp-server.modules.clojure-lsp.client :as client]
              [taoensso.trove :as trove]))

;; Re-export core functions from client
(def start! "Start clojure-lsp subprocess and perform LSP initialization." client/start!)
(def stop! "Shutdown clojure-lsp subprocess gracefully." client/stop!)
(def running? "Check if the clojure-lsp process is running." client/running?)
(def initialized? "Check if the LSP client has completed initialization." client/initialized?)

(defn init!
  "Tool handler for clj-init MCP tool.
   Starts clojure-lsp and performs LSP initialization."
  [{:keys [project-root executable-path]}]
  (trove/log! {:level :info
               :id :clojure-lsp/init
               :msg "Initializing clojure-lsp module"
               :data {:project-root project-root}})
  (client/start! {:project-root project-root
                  :executable-path (or executable-path "clojure-lsp")}))
