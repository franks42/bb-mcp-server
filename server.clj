#!/usr/bin/env bb

;; bb-mcp-server - stdio server entry point
;; This script starts the MCP server and listens on stdin/stdout

(require '[bb-mcp-server.transport.stdio :as stdio]
         '[taoensso.timbre :as log])

;; CRITICAL: Configure Timbre to write to stderr, not stdout
;; MCP stdio protocol requires ONLY JSON on stdout
;; All logs must go to stderr to avoid JSON parsing errors in clients
(log/merge-config!
 {:appenders
  {:println
   {:enabled? true
    :fn (fn [data]
          (let [{:keys [output_]} data]
            (binding [*out* *err*]  ; ← Force output to stderr
              (println (force output_))
              (flush))))}}})

(stdio/run-stdio-server!)
