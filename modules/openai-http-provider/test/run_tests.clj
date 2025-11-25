#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'openai-http.core-test)

(def result
     "Test results from openai-http-provider module."
     (t/run-tests 'openai-http.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
