(ns bb-mcp-server.modules.clojure-lsp.core
    "Clojure LSP module - LSP integration via persistent subprocess.

   This module provides MCP tools for Clojure code intelligence via clojure-lsp."
    (:require [bb-mcp-server.registry :as registry]
              [bb-mcp-server.modules.clojure-lsp.server :as server]
              [bb-mcp-server.modules.clojure-lsp.tools :as tools]
              [bb-mcp-server.modules.clojure-lsp.watcher :as watcher]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Helpers
;; =============================================================================

(defn- json-response
  "Wrap result in MCP content block with JSON."
  [result]
  {:content [{:type "text"
              :text (json/generate-string result {:pretty true})}]})

(defn- error-response
  "Create MCP error response."
  [message]
  {:content [{:type "text"
              :text (json/generate-string {:error message} {:pretty true})}]
   :isError true})

(defn- require-initialized
  "Check if LSP is initialized, return error response if not."
  []
  (when-not (:running (server/status))
    (error-response "clojure-lsp not initialized. Call clj-init first.")))

;; =============================================================================
;; Tool Definitions
;; =============================================================================

(def clj-init-tool
     "MCP tool definition for initializing clojure-lsp."
     {:name "clj-init"
      :module "clojure-lsp"
      :description "Initialize clojure-lsp for a project. Call this first. Initial analysis may take 30s-2min for large projects, subsequent calls are fast. Set watch:true to auto-start file watcher."
      :handler (fn [{:keys [watch] :as args}]
                 (let [result (server/init! (dissoc args :watch))]
                   (when (and watch (not (:error result)))
                     (try
                      (watcher/start!)
                      (log/log! {:level :info
                                 :id ::watcher-auto-started
                                 :msg "File watcher auto-started"})
                      (catch Exception e
                             (log/log! {:level :warn
                                        :id ::watcher-auto-start-failed
                                        :msg "Failed to auto-start watcher"
                                        :data {:error (ex-message e)}}))))
                   result))
      :inputSchema {:type "object"
                    :properties {:project-root {:type "string"
                                                :description "Absolute path to project root"}
                                 :executable-path {:type "string"
                                                   :description "Optional. Absolute path to clojure-lsp executable. Defaults to 'clojure-lsp' on PATH."}
                                 :watch {:type "boolean"
                                         :description "Optional. Auto-start file watcher to keep index fresh (default: false)"}}
                    :required ["project-root"]}})

(def clj-definition-tool
     "MCP tool for go-to-definition."
     {:name "clj-definition"
      :module "clojure-lsp"
      :description "Go to definition of symbol at position. Returns location(s) of the definition."
      :handler (fn [{:keys [file line column]}]
                 (or (require-initialized)
                     (json-response (tools/definition {:file file
                                                       :line line
                                                       :column column}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}}
                    :required ["file" "line" "column"]}})

(def clj-references-tool
     "MCP tool for find-references."
     {:name "clj-references"
      :module "clojure-lsp"
      :description "Find all references to symbol at position across the project."
      :handler (fn [{:keys [file line column include-declaration]
                     :or {include-declaration true}}]
                 (or (require-initialized)
                     (json-response (tools/references {:file file
                                                       :line line
                                                       :column column
                                                       :include-declaration include-declaration}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}
                                 :include-declaration {:type "boolean"
                                                       :description "Include declaration in results (default: true)"}}
                    :required ["file" "line" "column"]}})

(def clj-hover-tool
     "MCP tool for hover documentation."
     {:name "clj-hover"
      :module "clojure-lsp"
      :description "Get documentation and type information for symbol at position."
      :handler (fn [{:keys [file line column]}]
                 (or (require-initialized)
                     (json-response (tools/hover {:file file
                                                  :line line
                                                  :column column}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}}
                    :required ["file" "line" "column"]}})

(def clj-completions-tool
     "MCP tool for code completions."
     {:name "clj-completions"
      :module "clojure-lsp"
      :description "Get completion suggestions at position."
      :handler (fn [{:keys [file line column]}]
                 (or (require-initialized)
                     (json-response (tools/completions {:file file
                                                        :line line
                                                        :column column}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}}
                    :required ["file" "line" "column"]}})

(def clj-code-actions-tool
     "MCP tool for code actions/refactorings."
     {:name "clj-code-actions"
      :module "clojure-lsp"
      :description "Get available refactorings and quick fixes at position."
      :handler (fn [{:keys [file line column]}]
                 (or (require-initialized)
                     (json-response (tools/code-actions {:file file
                                                         :line line
                                                         :column column}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}}
                    :required ["file" "line" "column"]}})

(def clj-rename-tool
     "MCP tool for rename refactoring."
     {:name "clj-rename"
      :module "clojure-lsp"
      :description "Rename symbol at position across the entire project. Returns workspace edit with all changes."
      :handler (fn [{:keys [file line column new-name]}]
                 (or (require-initialized)
                     (json-response (tools/rename {:file file
                                                   :line line
                                                   :column column
                                                   :new-name new-name}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}
                                 :new-name {:type "string"
                                            :description "New name for the symbol"}}
                    :required ["file" "line" "column" "new-name"]}})

(def clj-diagnostics-tool
     "MCP tool for diagnostics."
     {:name "clj-diagnostics"
      :module "clojure-lsp"
      :description "Get diagnostics (errors, warnings) for a file or all files."
      :handler (fn [{:keys [file]}]
                 (or (require-initialized)
                     (json-response (if file
                                      (tools/diagnostics {:file file})
                                      (tools/diagnostics)))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Optional. Absolute file path. Omit for all diagnostics."}}
                    :required []}})

(def clj-document-symbols-tool
     "MCP tool for document symbols."
     {:name "clj-document-symbols"
      :module "clojure-lsp"
      :description "Get all symbols (functions, vars, etc.) defined in a file."
      :handler (fn [{:keys [file]}]
                 (or (require-initialized)
                     (json-response (tools/document-symbols {:file file}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}}
                    :required ["file"]}})

(def clj-call-hierarchy-tool
     "MCP tool for call hierarchy."
     {:name "clj-call-hierarchy"
      :module "clojure-lsp"
      :description "Get incoming or outgoing call hierarchy for function at position."
      :handler (fn [{:keys [file line column direction]
                     :or {direction "incoming"}}]
                 (or (require-initialized)
                     (json-response (tools/call-hierarchy {:file file
                                                           :line line
                                                           :column column
                                                           :direction (keyword direction)}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}
                                 :direction {:type "string"
                                             :description "Direction: 'incoming' or 'outgoing' (default: incoming)"}}
                    :required ["file" "line" "column"]}})

(def clj-find-symbol-tool
     "MCP tool for workspace symbol search."
     {:name "clj-find-symbol"
      :module "clojure-lsp"
      :description "Search for symbols by name across the workspace. Unlike definition/references which need file:line:col, this searches by symbol name."
      :handler (fn [{:keys [query]}]
                 (or (require-initialized)
                     (json-response (tools/find-symbol {:query query}))))
      :inputSchema {:type "object"
                    :properties {:query {:type "string"
                                         :description "Symbol name or pattern to search for"}}
                    :required ["query"]}})

(def clj-implementations-tool
     "MCP tool for finding implementations."
     {:name "clj-implementations"
      :module "clojure-lsp"
      :description "Find implementations of a protocol or interface at position."
      :handler (fn [{:keys [file line column]}]
                 (or (require-initialized)
                     (json-response (tools/implementations {:file file
                                                            :line line
                                                            :column column}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}
                                 :line {:type "integer"
                                        :description "Line number (1-indexed)"}
                                 :column {:type "integer"
                                          :description "Column number (1-indexed)"}}
                    :required ["file" "line" "column"]}})

(def clj-format-tool
     "MCP tool for formatting files."
     {:name "clj-format"
      :module "clojure-lsp"
      :description "Format a file using clojure-lsp formatting. Returns list of text edits to apply."
      :handler (fn [{:keys [file]}]
                 (or (require-initialized)
                     (json-response (tools/format-file {:file file}))))
      :inputSchema {:type "object"
                    :properties {:file {:type "string"
                                        :description "Absolute file path"}}
                    :required ["file"]}})

(def clj-execute-command-tool
     "MCP tool for executing refactoring commands."
     {:name "clj-execute-command"
      :module "clojure-lsp"
      :description "Execute a refactoring command. Use clj-code-actions to discover available commands. Common commands: cycle-privacy, extract-function, introduce-let, move-to-let, thread-first, thread-last, clean-ns."
      :handler (fn [{:keys [command arguments]}]
                 (or (require-initialized)
                     (json-response (tools/execute-command {:command command
                                                            :arguments arguments}))))
      :inputSchema {:type "object"
                    :properties {:command {:type "string"
                                           :description "Command name (e.g., 'cycle-privacy', 'extract-function')"}
                                 :arguments {:type "array"
                                             :description "Command arguments (typically [uri, line, column, ...]))"}}
                    :required ["command"]}})

(def clj-watch-tool
     "MCP tool for file watching."
     {:name "clj-watch"
      :module "clojure-lsp"
      :description "Control file watcher for keeping clojure-lsp index fresh. Actions: start, stop, status."
      :handler (fn [{:keys [action]}]
                 (case action
                   "start" (try
                            (json-response (watcher/start!))
                            (catch Exception e
                                   (error-response (ex-message e))))
                   "stop" (json-response (or (watcher/stop!)
                                             {:status "not-running"}))
                   "status" (json-response {:watching (watcher/watching?)
                                            :stats (watcher/stats)})
                   (error-response (str "Unknown action: " action ". Use start, stop, or status."))))
      :inputSchema {:type "object"
                    :properties {:action {:type "string"
                                          :description "Action: 'start', 'stop', or 'status'"}}
                    :required ["action"]}})

(def clj-status-tool
     "MCP tool for checking clojure-lsp status."
     {:name "clj-status"
      :module "clojure-lsp"
      :description "Get clojure-lsp server status and project info."
      :handler (fn [_args]
                 (json-response (assoc (server/status)
                                       :watcher {:watching (watcher/watching?)
                                                 :stats (watcher/stats)})))
      :inputSchema {:type "object"
                    :properties {}
                    :required []}})

;; All tools for registration
(def all-tools
     "All clojure-lsp MCP tools."
     [clj-init-tool
      clj-definition-tool
      clj-references-tool
      clj-hover-tool
      clj-completions-tool
      clj-code-actions-tool
      clj-rename-tool
      clj-diagnostics-tool
      clj-document-symbols-tool
      clj-call-hierarchy-tool
      clj-find-symbol-tool
      clj-implementations-tool
      clj-format-tool
      clj-execute-command-tool
      clj-watch-tool
      clj-status-tool])

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(def tool-names
     "Names of all registered tools."
     (mapv :name all-tools))

(defn start
  "Start the clojure-lsp module. Registers all MCP tools."
  [_deps config]
  (log/log! {:level :info
             :id ::clojure-lsp-starting
             :msg "Starting clojure-lsp module"
             :data {:config config}})
  ;; Register all tools
  (doseq [tool all-tools]
         (registry/register! tool))
  (log/log! {:level :info
             :id ::clojure-lsp-started
             :msg "Clojure-lsp module started"
             :data {:registered-tools tool-names}})
  {:registered-tools tool-names})

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
  ;; Unregister all tools
  (doseq [tool-name tool-names]
         (registry/unregister! (str "clojure-lsp." tool-name)))
  nil)

(defn status
  "Get clojure-lsp module status."
  [_instance]
  (let [server-status (server/status)]
    {:status (if (:running server-status) :running :idle)
     :registered-tools tool-names
     :server server-status}))

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "Clojure-lsp module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
