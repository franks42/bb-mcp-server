(ns bb-mcp-server.transport.stdio
    "Stdio transport for MCP server.

  Implements the stdio transport protocol:
  - Read JSON-RPC requests from stdin (one per line)
  - Write JSON-RPC responses to stdout (one per line)
  - Handle errors gracefully without crashing
  - Log all operations for telemetry

  This is the main entry point for running the MCP server with Claude Code."
    (:require [bb-mcp-server.test-harness :as test-harness]
              [bb-mcp-server.protocol.message :as msg]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

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
  (try
    ;; Main request/response loop
   (doseq [line (line-seq (java.io.BufferedReader. *in*))]
          (log/log! {:level :debug :msg "Received request line" :data {:length (count line)}})

          (try
        ;; Process request and get response
           (let [response (test-harness/process-json-rpc line)]
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
    (log/log! {:level :info :msg "Stdio MCP server shutdown complete"}))))


