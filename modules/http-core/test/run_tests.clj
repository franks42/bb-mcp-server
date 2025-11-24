#!/usr/bin/env bb
;; Test runner for http-core module

(ns run-tests
    "Test runner for http-core module."
    (:require [clojure.test :as t]
              ;; Load test namespaces
              [http-core.util-test]
              [http-core.sse-test]
              [http-core.middleware-test]))

(defn run-tests
  "Run all http-core tests and report results."
  []
  (println "\n========================================")
  (println "Running http-core module tests")
  (println "========================================\n")

  (let [result (t/run-tests 'http-core.util-test
                            'http-core.sse-test
                            'http-core.middleware-test)]
    (println "\n========================================")
    (println (str "http-core: " (:test result) " tests, "
                  (:pass result) " passed, "
                  (:fail result) " failed, "
                  (:error result) " errors"))
    (println "========================================\n")

    (when (or (pos? (:fail result)) (pos? (:error result)))
      (System/exit 1))

    result))

(run-tests)
