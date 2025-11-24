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

    ;; In your main function, after setup:
    (stdio/run-stdio-loop!)

  The loop reads from *in* and writes to *out*, blocking until EOF."
    (:require [bb-mcp-server.protocol.processor :as processor]
              [bb-mcp-server.protocol.message :as msg]
              [cheshire.core :as json]
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
  "Run the stdio request/response loop.

  This function assumes setup has already been done (handlers registered,
  tools loaded via module system). It:
  1. Reads JSON-RPC requests from stdin (one per line)
  2. Processes each request and writes response to stdout
  3. Handles errors gracefully (logs but continues)
  4. Shuts down gracefully on EOF

  I/O Protocol:
  - Input: One JSON-RPC request per line on stdin
  - Output: One JSON-RPC response per line on stdout
  - Each response is followed by flush to ensure delivery

  This function blocks until stdin is closed."
  []
  (log/log! {:level :info :msg "Starting stdio request loop"})
  ;; Create stdio context - notifications print to stdout
  (let [ctx (processor/make-stdio-ctx)]
    (try
      ;; Main request/response loop
     (doseq [line (line-seq (java.io.BufferedReader. *in*))]
            (log/log! {:level :debug :msg "Received request line" :data {:length (count line)}})

            (try
          ;; Process request and get response
             (let [response (processor/process-request-str ctx line)]
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
            ;; Send error response
                    (let [error-response (msg/create-error-response
                                          nil
                                          (:internal-error msg/error-codes)
                                          "Internal error"
                                          (ex-message e))
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
      (log/log! {:level :info :msg "Stdio MCP server shutdown complete"})))))
