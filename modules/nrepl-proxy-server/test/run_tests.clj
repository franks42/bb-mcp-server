#!/usr/bin/env bb

;; Test runner for nrepl-proxy-server module

(require '[clojure.test :as t])

;; Ensure module paths are set up
(require '[bb-mcp-server.bootstrap :as boot])
(boot/ensure-module-paths!)

;; Load test namespaces
(require '[nrepl-proxy-server.session-test]
         '[nrepl-proxy-server.api-test])

;; Run tests
(let [result (t/run-tests 'nrepl-proxy-server.session-test
                          'nrepl-proxy-server.api-test)]
  (when (or (pos? (:fail result)) (pos? (:error result)))
    (System/exit 1)))
