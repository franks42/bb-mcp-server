(ns bb-mcp-server.modules.clojure-lsp.tools
    "High-level Clojure API for clojure-lsp operations.

   All functions use the stateless open/close pattern:
   1. didOpen - send current file content to LSP
   2. Perform operation
   3. didClose - tell LSP to drop the file

   This ensures LSP always analyzes current disk state."
    (:require [bb-mcp-server.modules.clojure-lsp.client :as client]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Helpers
;; -----------------------------------------------------------------------------

(defn- uri
  "Convert file path to file:// URI."
  [path]
  (str "file://" path))

(defn- pos
  "Convert 1-indexed line/column to 0-indexed LSP position."
  [line column]
  {:line (dec line) :character (dec column)})

(defn with-file
  "Execute function f with file open in LSP, then close it.
   Ensures LSP sees current disk content for every operation."
  [file f]
  (log/log! {:level :debug
             :id ::with-file
             :msg "Opening file for LSP operation"
             :data {:file file}})
  (client/did-open! file)
  (try
   (f)
   (finally
    (client/did-close! file)
    (log/log! {:level :debug
               :id ::with-file-closed
               :msg "Closed file after LSP operation"
               :data {:file file}}))))

;; -----------------------------------------------------------------------------
;; Navigation Functions
;; -----------------------------------------------------------------------------

(defn definition
  "Go to definition of symbol at position.

   Args:
     {:file   - Absolute file path
      :line   - 1-indexed line number
      :column - 1-indexed column number}

   Returns:
     Location(s) of definition or nil if not found."
  [{:keys [file line column]}]
  (log/log! {:level :info
             :id ::definition
             :msg "Finding definition"
             :data {:file file :line line :column column}})
  (with-file file
             (fn []
               (client/request! "textDocument/definition"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)}))))

(defn references
  "Find all references to symbol at position.

   Args:
     {:file                - Absolute file path
      :line                - 1-indexed line number
      :column              - 1-indexed column number
      :include-declaration - Include declaration in results (default true)}

   Returns:
     List of locations where symbol is referenced."
  [{:keys [file line column include-declaration]
    :or {include-declaration true}}]
  (log/log! {:level :info
             :id ::references
             :msg "Finding references"
             :data {:file file :line line :column column}})
  (with-file file
             (fn []
               (client/request! "textDocument/references"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)
                                 :context {:includeDeclaration include-declaration}}))))

(defn hover
  "Get documentation and type information for symbol at position.

   Args:
     {:file   - Absolute file path
      :line   - 1-indexed line number
      :column - 1-indexed column number}

   Returns:
     Hover information with contents and range."
  [{:keys [file line column]}]
  (log/log! {:level :info
             :id ::hover
             :msg "Getting hover info"
             :data {:file file :line line :column column}})
  (with-file file
             (fn []
               (client/request! "textDocument/hover"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)}))))

;; -----------------------------------------------------------------------------
;; Completion & Refactoring
;; -----------------------------------------------------------------------------

(defn completions
  "Get completion suggestions at position.

   Args:
     {:file   - Absolute file path
      :line   - 1-indexed line number
      :column - 1-indexed column number}

   Returns:
     Completion items or completion list."
  [{:keys [file line column]}]
  (log/log! {:level :info
             :id ::completions
             :msg "Getting completions"
             :data {:file file :line line :column column}})
  (with-file file
             (fn []
               (client/request! "textDocument/completion"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)}))))

(defn code-actions
  "Get available refactorings and quick fixes at position.

   Args:
     {:file   - Absolute file path
      :line   - 1-indexed line number
      :column - 1-indexed column number}

   Returns:
     List of available code actions."
  [{:keys [file line column]}]
  (log/log! {:level :info
             :id ::code-actions
             :msg "Getting code actions"
             :data {:file file :line line :column column}})
  (with-file file
             (fn []
               (client/request! "textDocument/codeAction"
                                {:textDocument {:uri (uri file)}
                                 :range {:start (pos line column)
                                         :end (pos line column)}
                                 :context {:diagnostics []}}))))

(defn rename
  "Rename symbol at position across the entire project.

   Args:
     {:file     - Absolute file path
      :line     - 1-indexed line number
      :column   - 1-indexed column number
      :new-name - New name for the symbol}

   Returns:
     Workspace edit with all changes needed."
  [{:keys [file line column new-name]}]
  (log/log! {:level :info
             :id ::rename
             :msg "Renaming symbol"
             :data {:file file :line line :column column :new-name new-name}})
  (with-file file
             (fn []
               (client/request! "textDocument/rename"
                                {:textDocument {:uri (uri file)}
                                 :position (pos line column)
                                 :newName new-name}))))

;; -----------------------------------------------------------------------------
;; Analysis Functions
;; -----------------------------------------------------------------------------

(defn document-symbols
  "Get all symbols (functions, vars, etc.) defined in a file.

   Args:
     {:file - Absolute file path}

   Returns:
     List of document symbols with their locations."
  [{:keys [file]}]
  (log/log! {:level :info
             :id ::document-symbols
             :msg "Getting document symbols"
             :data {:file file}})
  (with-file file
             (fn []
               (client/request! "textDocument/documentSymbol"
                                {:textDocument {:uri (uri file)}}))))

(defn diagnostics
  "Get cached diagnostics (errors, warnings) for a file or all files.

   Args:
     {} - Get all diagnostics
     {:file path} - Get diagnostics for specific file

   Returns:
     Map of {uri -> diagnostics} or list of diagnostics for file."
  ([]
   (log/log! {:level :info
              :id ::diagnostics-all
              :msg "Getting all diagnostics"})
   (client/get-diagnostics))
  ([{:keys [file]}]
   (log/log! {:level :info
              :id ::diagnostics-file
              :msg "Getting diagnostics for file"
              :data {:file file}})
   (client/get-diagnostics file)))

(defn call-hierarchy
  "Get incoming or outgoing call hierarchy for function at position.

   Args:
     {:file      - Absolute file path
      :line      - 1-indexed line number
      :column    - 1-indexed column number
      :direction - :incoming or :outgoing (default :incoming)}

   Returns:
     List of call hierarchy items."
  [{:keys [file line column direction]
    :or {direction :incoming}}]
  (log/log! {:level :info
             :id ::call-hierarchy
             :msg "Getting call hierarchy"
             :data {:file file :line line :column column :direction direction}})
  (with-file file
             (fn []
               (let [items (client/request! "textDocument/prepareCallHierarchy"
                                            {:textDocument {:uri (uri file)}
                                             :position (pos line column)})]
                 (when (seq items)
                   (let [method (if (= direction :incoming)
                                  "callHierarchy/incomingCalls"
                                  "callHierarchy/outgoingCalls")]
                     (client/request! method {:item (first items)})))))))
