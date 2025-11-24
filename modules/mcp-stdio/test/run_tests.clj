#!/usr/bin/env bb

;; Test runner for mcp-stdio module
;;
;; Run with: bb modules/mcp-stdio/test/run_tests.clj

(require '[babashka.classpath :as cp]
         '[clojure.test :as t])

;; Add module paths to classpath
(let [module-src "modules/mcp-stdio/src"
      module-test "modules/mcp-stdio/test"]
  (cp/add-classpath module-src)
  (cp/add-classpath module-test))

;; Load test namespaces
(require 'mcp-stdio.core-test)

(defn run-tests
  "Run all mcp-stdio module tests."
  []
  (let [result (t/run-tests 'mcp-stdio.core-test)]
    (if (and (zero? (:fail result))
             (zero? (:error result)))
      (do
       (println "\n✓ All tests passed.")
       (System/exit 0))
      (do
       (println "\n✗ Tests failed.")
       (System/exit 1)))))

(run-tests)
