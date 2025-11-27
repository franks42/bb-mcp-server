#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'datalevin-pod.core-test)

(def result
     "Test results from datalevin-pod module."
     (t/run-tests 'datalevin-pod.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
