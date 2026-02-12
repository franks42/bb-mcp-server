#!/usr/bin/env bb

(require '[clojure.test :as t])

;; Unit tests (no external dependencies)
(require 'code-browser.uri-test)
(require 'code-browser.db.protocol-test)

;; Integration tests (require Datalevin pod)
(require 'code-browser.db.datalevin-test)

;; Handler tests (require Datalevin pod)
(require 'code-browser.handlers-test)

;; Source adapter tests
(require 'code-browser.sources.directory-test)
(require 'code-browser.sources.runtime-test)
(require 'code-browser.sources.nrepl-test)

(def result
     "Test results from code-browser-v2 module."
     (t/run-tests 'code-browser.uri-test
                  'code-browser.db.protocol-test
                  'code-browser.db.datalevin-test
                  'code-browser.handlers-test
                  'code-browser.sources.directory-test
                  'code-browser.sources.runtime-test
                  'code-browser.sources.nrepl-test))

(when (or (pos? (:fail result)) (pos? (:error result)))
  (System/exit 1))
