#!/usr/bin/env bb

;; Test runner for nrepl-direct module
;; Runs integration tests which start a temporary nREPL server

;; Load test namespace
(require 'bb-mcp-server.nrepl-direct.client-test)

(println "Starting nREPL direct tests (with integration server)...")

(def result
     "Test results from nrepl-direct integration tests."
     (bb-mcp-server.nrepl-direct.client-test/run-integration-tests))

(println "\n" (if (or (pos? (:fail result 0)) (pos? (:error result 0)))
                "FAILED" "PASSED"))

(when (or (pos? (:fail result 0)) (pos? (:error result 0)))
  (System/exit 1))
