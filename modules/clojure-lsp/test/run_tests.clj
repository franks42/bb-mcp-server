#!/usr/bin/env bb
;; Test runner for clojure-lsp module
;; Usage: bb modules/clojure-lsp/test/run_tests.clj

(require '[clojure.test :as test]
         '[babashka.classpath :as cp])

;; Add module source and test paths to classpath
(def src-path "Path to module source." "modules/clojure-lsp/src")
(def test-path "Path to module tests." "modules/clojure-lsp/test")

(cp/add-classpath src-path)
(cp/add-classpath test-path)

;; Configure logging
(require '[taoensso.trove :as trove]
         '[taoensso.trove.console :as console])
(trove/set-log-fn! (console/get-log-fn {:min-level :debug}))

;; Dynamic Loading Verification
(println "Verifying dynamic loading...")
(try
 (require 'bb-mcp-server.modules.clojure-lsp.server)
 (println "✓ Successfully loaded bb-mcp-server.modules.clojure-lsp.server")
 (catch Exception e
        (println "✗ Failed to load module dynamically:" (.getMessage e))
        (System/exit 1)))

(def test-namespaces
     "List of test namespaces to run."
     '[bb-mcp-server.modules.clojure-lsp.server-test])

(println "=" (apply str (repeat 60 "=")))
(println "Running clojure-lsp Module Tests")
(println "=" (apply str (repeat 60 "=")))
(println)

(doseq [ns-sym test-namespaces]
       (println "Loading:" ns-sym)
       (require ns-sym))

(println)
(println "-" (apply str (repeat 60 "-")))
(println)

(let [results (apply test/run-tests test-namespaces)]
  (println)
  (println "=" (apply str (repeat 60 "=")))
  (println "Summary:")
  (println "  Tests:" (:test results 0))
  (println "  Assertions:" (:pass results 0))
  (println "  Failures:" (:fail results 0))
  (println "  Errors:" (:error results 0))
  (println "=" (apply str (repeat 60 "=")))

  (when (or (pos? (:fail results 0))
            (pos? (:error results 0)))
    (System/exit 1)))
