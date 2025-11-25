#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'ai-orchestrator-tools.core-test)

(def result
     "Test results from ai-orchestrator-tools module."
     (t/run-tests 'ai-orchestrator-tools.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
