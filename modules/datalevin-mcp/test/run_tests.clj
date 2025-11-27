#!/usr/bin/env bb
;; Run datalevin-mcp module tests

(require '[clojure.test :as test])

;; Load the test namespace
(require '[datalevin-mcp.core-test])

;; Run tests and report
(let [result (test/run-tests 'datalevin-mcp.core-test)]
  (System/exit (if (and (zero? (:fail result))
                        (zero? (:error result)))
                 0
                 1)))
