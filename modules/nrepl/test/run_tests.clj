#!/usr/bin/env bb
;; Test runner for nrepl module
;; Usage: bb modules/nrepl/test/run_tests.clj

(require '[clojure.test :as test])

;; Add test paths
(def test-namespaces
     '[;; State management tests
       nrepl.state.connection-test
       nrepl.state.messages-test
       nrepl.state.results-test
    ;; Client tests (pure functions)
       nrepl.client.connection-test
       nrepl.client.messaging-test])

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
