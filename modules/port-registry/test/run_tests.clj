#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'port-registry.core-test)

(def result
     "Test results from port-registry module."
     (t/run-tests 'port-registry.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
