(ns run-tests
    "Test runner for rest-api module."
    (:require [clojure.test :as test]
              [rest-api.handlers-test]))

(defn run-tests
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

;; Run tests when loaded
(run-tests)
