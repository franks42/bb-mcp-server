#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'ai-orchestrator.core-test)

(def result
     "Test results from ai-orchestrator module."
     (t/run-tests 'ai-orchestrator.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
