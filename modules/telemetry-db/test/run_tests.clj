#!/usr/bin/env bb
;; Test runner for telemetry-db module

(require '[babashka.classpath :as cp])

;; Add src and test to classpath
(cp/add-classpath "src")
(cp/add-classpath "test")

(require '[clojure.test :as t]
         '[telemetry-db.core-test])

#_{:clj-kondo/ignore [:missing-docstring]}
(def test-results (t/run-tests 'telemetry-db.core-test))

;; Exit with proper code
(let [{:keys [fail error]} test-results]
  (System/exit (if (zero? (+ fail error)) 0 1)))
