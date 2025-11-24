(ns mcp-stdio.core
    "MCP Stdio Transport Module.

  Implements the stdio transport protocol for MCP:
  - Read JSON-RPC requests from stdin (one per line)
  - Write JSON-RPC responses to stdout (one per line)
  - Handle errors gracefully without crashing
  - Log all operations for telemetry

  This transport is used for:
  - Claude Desktop integration
  - Subprocess-based MCP server spawning
  - Single-client scenarios without network overhead

  Usage:
    (require '[mcp-stdio.core :as stdio])
    (require '[bb-mcp-server.protocol.processor :as processor])

    ;; Create handler that processes JSON-RPC
    (let [ctx (processor/make-stdio-ctx)
          handler (fn [line] (processor/process-request-str ctx line))]
      (stdio/run-stdio-loop! handler))

  The loop reads from *in* and writes to *out*, blocking until EOF.

  This module has NO dependencies on bb-mcp-server internals - it only
  depends on the handler function signature: (fn [line] -> response-or-nil)"
    (:require [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Definition
;; =============================================================================

(def module
     "Module metadata for mcp-stdio."
     {:name "mcp-stdio"
      :version "0.1.0"
      :description "MCP Stdio Transport - stdin/stdout JSON-RPC"})

;; =============================================================================
;; Stdio Transport
;; =============================================================================

(defn run-stdio-loop!
  "Run the stdio request/response loop with provided handler.

  Args:
    handler-fn - Function (fn [line] -> response-string-or-nil)
                 Returns JSON string response, or nil for notifications

  The handler is responsible for:
  - Parsing JSON-RPC request
  - Processing the request
  - Returning JSON string response (or nil for notifications)

  This transport handles:
  - Reading lines from stdin
  - Writing responses to stdout
  - Error handling for handler exceptions
  - Graceful shutdown on EOF

  I/O Protocol:
  - Input: One JSON-RPC request per line on stdin
  - Output: One JSON-RPC response per line on stdout
  - Each response is followed by flush to ensure delivery

  This function blocks until stdin is closed."
  [handler-fn]
  (log/log! {:level :info :msg "Starting stdio request loop"})
  (try
    ;; Main request/response loop
   (doseq [line (line-seq (java.io.BufferedReader. *in*))]
          (log/log! {:level :debug :msg "Received request line" :data {:length (count line)}})

          (try
        ;; Call handler and get response
           (let [response (handler-fn line)]
          ;; Only send response if not nil (nil = notification, don't respond)
             (when response
               (log/log! {:level :debug :msg "Sending response" :data {:length (count response)}})
            ;; Write response to stdout
               (println response)
               (flush)
               (log/log! {:level :debug :msg "Response sent successfully"})))

           (catch Exception e
          ;; Handle request processing errors
                  (log/log! {:level :error :msg "Error processing request" :error e :data {:line line}})
          ;; Send JSON-RPC error response
                  (let [error-response {:jsonrpc "2.0"
                                        :error {:code -32603
                                                :message "Internal error"
                                                :data (ex-message e)}
                                        :id nil}
                        response-json (json/generate-string error-response)]
                    (println response-json)
                    (flush)
                    (log/log! {:level :debug :msg "Error response sent"})))))

   (catch java.io.IOException _e
      ;; EOF or I/O error - normal shutdown
          (log/log! {:level :info :msg "Stdio stream closed"}))

   (catch Exception e
      ;; Unexpected error in main loop
          (log/log! {:level :error :msg "Fatal error in stdio server main loop" :error e}))

   (finally
    (log/log! {:level :info :msg "Stdio MCP server shutdown complete"}))))
