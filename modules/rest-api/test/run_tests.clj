#!/usr/bin/env bb
;; Test runner for rest-api module

(require '[clojure.test :as test])

;; Load test namespaces
(require 'rest-api.handlers-test)

#_{:clj-kondo/ignore [:redefined-var]}
(defn run-all-tests
  "Run all rest-api module tests and return results."
  []
  (println "========================================")
  (println "Running rest-api module tests")
  (println "========================================\n")

  (let [result (test/run-tests 'rest-api.handlers-test)
        {:keys [test pass fail error]} result
        success? (and (zero? fail) (zero? error))]

    (println)
    (println "========================================")
    (if success?
      (println (format "rest-api: %d tests, %d passed, %d failed, %d errors"
                       test pass fail error))
      (println (format "FAILURES: %d tests, %d passed, %d failed, %d errors"
                       test pass fail error)))
    (println "========================================")

    (when-not success?
      (System/exit 1))))

(run-all-tests)
