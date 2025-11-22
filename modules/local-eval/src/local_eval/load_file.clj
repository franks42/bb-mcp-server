(ns local-eval.load-file
    "Local load-file handler - load and evaluate Clojure files in MCP server runtime."
    (:require [local-eval.shared :as shared])
    (:import [java.io StringWriter PrintWriter]))

;; =============================================================================
;; Handler
;; =============================================================================

(defn handle
  "Load and evaluate a Clojure file in the MCP server's runtime environment.

   Uses Clojure's built-in load-file function to load and evaluate the specified
   file within the MCP server's own runtime. This is useful for:
   - Loading development scripts and toolkits into the MCP server
   - Extending MCP server functionality with additional Clojure code
   - Testing code in the runtime environment

   Parameters:
   - file-path: Absolute path to the Clojure file to load

   Returns MCP-formatted response with evaluation result."
  [{:keys [file-path]}]
  (try
    ;; Validate parameters using shared utilities
   (shared/validate-parameters {:file-path file-path} ["file-path"])
   (shared/validate-file-path file-path)

    ;; Execute load-file with output capture
   (let [stdout-writer (StringWriter.)
         stderr-writer (StringWriter.)]
     (binding [*out* (PrintWriter. stdout-writer)
               *err* (PrintWriter. stderr-writer)]
              (let [result (load-file file-path)
                    captured-stdout (str stdout-writer)
                    captured-stderr (str stderr-writer)]
                (shared/format-load-file-response
                 {:value (pr-str result)
                  :out captured-stdout
                  :err captured-stderr}
                 "local-load-file"
                 file-path))))

   (catch Exception e
          (shared/handle-load-file-error e "local-load-file" file-path))))

;; =============================================================================
;; Tool Definition
;; =============================================================================

(def tool-name "local-load-file")

(def tool-definition
     {:name tool-name
      :description "Load and evaluate Clojure files in MCP server's runtime.

Uses Clojure's built-in load-file function to load and evaluate the specified
file within the MCP server's own runtime environment.

**Use cases:**
- Loading development scripts and toolkits
- Extending MCP server functionality
- Testing code in the runtime environment

**Parameters:**
- file-path: Absolute path to the Clojure file to load

**Example:**
  {\"file-path\": \"/path/to/script.clj\"}"
      :inputSchema {:type "object"
                    :properties {:file-path {:type "string"
                                             :description "Absolute path to Clojure file to load"}}
                    :required ["file-path"]}
      :handler handle})
