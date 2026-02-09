#!/usr/bin/env bb
;; Test runner for mcp-nrepl module
;; Usage: bb modules/mcp-nrepl/test/run_tests.clj

(require '[clojure.test :as test])

;; Add test paths
(def test-namespaces
     "List of test namespaces to run."
     '[;; State management tests
       mcp-nrepl.state.connection-test
       mcp-nrepl.state.messages-test
       mcp-nrepl.state.results-test
       ;; Client tests (pure functions)
       mcp-nrepl.client.connection-test
       mcp-nrepl.client.messaging-test
       ;; Phase 0: Introspection tools tests
       mcp-nrepl.tools.introspection-test
       ;; Statecharts transition tests
       mcp-nrepl.state.local-nrepl-server-test
       ;; Integration tests (starts real nREPL server)
       mcp-nrepl.state.local-nrepl-server-integration-test])

(println "=" (apply str (repeat 60 "=")))
(println "Running nREPL Module Tests")
(println "=" (apply str (repeat 60 "=")))
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
