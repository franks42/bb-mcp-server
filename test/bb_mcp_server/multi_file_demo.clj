(ns bb-mcp-server.multi-file-demo
  "Demonstrates multi-file namespace patterns for code browser testing.
   Shows how (in-ns), (load), and (require) appear as top-level forms."
  (:require [clojure.string :as str]))

(comment
  "This file demonstrates the multi-file namespace pattern used by
   clojure.core and other large namespaces. In file-order view,
   you should see distinct kind labels for each top-level form type.")

;; Simulating switching to another namespace mid-file
;; (This is how secondary files in multi-file namespaces start)
(in-ns 'bb-mcp-server.multi-file-demo)

;; Loading additional implementation files
;; (This is how clojure.core loads core_deftype.clj, core_print.clj, etc.)
(load "multi_file_impl")

;; Additional require after ns declaration
(require '[clojure.set :as set])

;; Side effect for initialization
(println "multi-file-demo loaded")

;; Regular function definitions
(defn primary-fn
  "A function defined in the primary file."
  [x]
  (str/upper-case (str x)))

(defn another-fn
  "Another function in the primary file."
  [a b]
  (+ a b))

;; Demonstrate in-ns again (switching context)
(in-ns 'bb-mcp-server.multi-file-demo)

(defn after-in-ns-fn
  "Defined after an in-ns call."
  []
  :after-switch)
