#!/usr/bin/env bb

(require '[clojure.test :as t])

(require 'anthropic-http.core-test)

(def result
     "Test results from anthropic-http-provider module."
     (t/run-tests 'anthropic-http.core-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
