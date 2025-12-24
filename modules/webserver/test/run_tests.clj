#!/usr/bin/env bb
;; Test runner for webserver module

(require '[clojure.test :as t])

;; Ensure module paths are available
(require '[bb-mcp-server.bootstrap :as boot])
(boot/ensure-module-paths!)

;; Load test namespaces
(require 'webserver.core-test)

(def test-results
     (t/run-tests 'webserver.core-test))

(when (or (pos? (:fail test-results))
          (pos? (:error test-results)))
  (System/exit 1))
