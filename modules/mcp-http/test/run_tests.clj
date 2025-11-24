#!/usr/bin/env bb
;; Test runner for mcp-http module

(require '[clojure.test :as t])

;; Load test namespaces
(require 'mcp-http.session-test)
(require 'mcp-http.handlers-test)

#_{:clj-kondo/ignore [:redefined-var]}
(defn run-all-tests
  "Run all mcp-http tests and report results."
  []
  (println "\n========================================")
  (println "Running mcp-http module tests")
  (println "========================================\n")

  (let [result (t/run-tests 'mcp-http.session-test
                            'mcp-http.handlers-test)]
    (println "\n========================================")
    (println (str "mcp-http: " (:test result) " tests, "
                  (:pass result) " passed, "
                  (:fail result) " failed, "
                  (:error result) " errors"))
    (println "========================================\n")

    (when (or (pos? (:fail result)) (pos? (:error result)))
      (System/exit 1))

    result))

(run-all-tests)
