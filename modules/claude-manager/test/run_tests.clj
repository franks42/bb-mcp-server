#!/usr/bin/env bb
;; Test runner for claude-manager module

(ns run-tests
    "Test runner for claude-manager module."
    (:require [clojure.test :as t]))

;; Add source and test paths
(require '[claude-manager.core]
         '[claude-manager.process]
         '[claude-manager.registry]
         '[claude-manager.core-test])

(defn run-tests
  "Run all claude-manager tests and exit with appropriate code."
  []
  (let [result (t/run-tests 'claude-manager.core-test)]
    (if (and (zero? (:fail result))
             (zero? (:error result)))
      (do
       (println (format "\n✅ All tests passed: %d tests, %d assertions"
                        (:test result)
                        (:pass result)))
       (System/exit 0))
      (do
       (println (format "\n❌ Tests failed: %d failures, %d errors"
                        (:fail result)
                        (:error result)))
       (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
