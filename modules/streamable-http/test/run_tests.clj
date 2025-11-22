#!/usr/bin/env bb

;; Test runner for streamable-http module
;;
;; Run with: bb modules/streamable-http/test/run_tests.clj

(require '[babashka.classpath :as cp]
         '[clojure.test :as t])

;; Add module paths to classpath
(let [module-src "modules/streamable-http/src"
      module-test "modules/streamable-http/test"]
  (cp/add-classpath module-src)
  (cp/add-classpath module-test))

;; Load test namespaces
(require 'streamable-http.sse-test)
(require 'streamable-http.session-test)
(require 'streamable-http.handlers-test)
(require 'streamable-http.router-test)
(require 'streamable-http.integration-test)

(defn run-tests
  "Run all streamable-http module tests."
  []
  (let [result (t/run-tests 'streamable-http.sse-test
                            'streamable-http.session-test
                            'streamable-http.handlers-test
                            'streamable-http.router-test
                            'streamable-http.integration-test)]
    (if (and (zero? (:fail result))
             (zero? (:error result)))
      (do
       (println "\n✓ All tests passed.")
       (System/exit 0))
      (do
       (println "\n✗ Tests failed.")
       (System/exit 1)))))

(run-tests)
