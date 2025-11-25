#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'expert-registry.core-test)

(def result
     "Test results from expert-registry module."
     (t/run-tests 'expert-registry.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
