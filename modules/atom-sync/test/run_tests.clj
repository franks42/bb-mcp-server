#!/usr/bin/env bb
;; Test runner for atom-sync module

(require '[babashka.classpath :as cp])

;; Add src and test to classpath
(cp/add-classpath "src")
(cp/add-classpath "test")

(require '[clojure.test :as t]
         '[atom-sync.core-test])

#_{:clj-kondo/ignore [:missing-docstring]}
(def test-results (t/run-tests 'atom-sync.core-test))

;; Exit with proper code
(let [{:keys [fail error]} test-results]
  (System/exit (if (zero? (+ fail error)) 0 1)))
