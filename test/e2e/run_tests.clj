#!/usr/bin/env bb
;; E2E Test Runner for MCP Client Tests
;;
;; Prerequisites:
;;   Start a test server first:
;;     bb server --http 0 --nickname e2e-test
;;
;; Run with:
;;   bb test:e2e

(require '[clojure.test :as test])

;; Ensure module paths are available
((requiring-resolve 'bb-mcp-server.bootstrap/ensure-module-paths!))

(def test-namespaces
     "List of E2E test namespaces to run."
     '[e2e.mcp-client-test])

(println "=" (apply str (repeat 60 "=")))
(println "Running MCP Client E2E Tests")
(println "=" (apply str (repeat 60 "=")))
(println)
(println "NOTE: These tests require a running MCP server.")
(println "      Start with: bb server --http 0 --nickname e2e-test")
(println)

;; Load and run tests
(doseq [ns-sym test-namespaces]
       (println "Loading:" ns-sym)
       (require ns-sym))

(println)
(println "-" (apply str (repeat 60 "-")))
(println)

(let [results (apply test/run-tests test-namespaces)]
  (println)
  (println "=" (apply str (repeat 60 "=")))
  (println "Summary:")
  (println "  Tests:" (:test results 0))
  (println "  Assertions:" (:pass results 0))
  (println "  Failures:" (:fail results 0))
  (println "  Errors:" (:error results 0))
  (println "=" (apply str (repeat 60 "=")))

  ;; Exit with error code if tests failed
  (when (or (pos? (:fail results 0))
            (pos? (:error results 0)))
    (System/exit 1)))
