#!/usr/bin/env bb

(ns run-tests
    (:require [clojure.test :as t]))

;; Load test namespaces
(require 'message-bus.core-test)
(require 'message-bus.teams-test)

;; Run tests
(let [results (t/run-tests 'message-bus.core-test
                           'message-bus.teams-test)]
  (System/exit (if (and (zero? (:fail results))
                        (zero? (:error results)))
                 0
                 1)))
