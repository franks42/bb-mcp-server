(ns bb-mcp-server.kondo-types-showcase
  "Showcase file demonstrating all clj-kondo var types.

   Use this with the Code Browser to see all supported kind labels:
   - function, private-fn, variable, defonce, declare
   - macro, multimethod, method, protocol, deftype, defrecord, test"
  (:require [clojure.test :refer [deftest is]]))

;; =============================================================================
;; Basic definitions
;; =============================================================================

(def my-variable
  "A regular def - shows as 'variable'"
  42)

;; defonce - shows as 'defonce' (lint warning expected - defonce doesn't support docstring)
(defonce my-defonce (atom {}))

(declare my-declared-fn)

;; =============================================================================
;; Functions
;; =============================================================================

(defn my-public-fn
  "A public function - shows as 'function'"
  [x]
  (+ x 1))

(defn- my-private-fn
  "A private function - shows as 'private-fn'"
  [x]
  (* x 2))

(defn my-declared-fn
  "Implement the declared function - shows as 'function'"
  []
  :implemented)

;; =============================================================================
;; Macros
;; =============================================================================

(defmacro my-macro
  "A macro - shows as 'macro'"
  [& body]
  `(do ~@body))

;; =============================================================================
;; Multimethods
;; =============================================================================

(defmulti my-multimethod
  "A multimethod dispatcher - shows as 'multimethod'"
  :type)

;; Default implementation - shows as 'method'
(defmethod my-multimethod :default
  [m]
  (:value m))

;; Special implementation - shows as 'method'
(defmethod my-multimethod :special
  [m]
  (str "Special: " (:value m)))

;; =============================================================================
;; Protocols and implementations
;; =============================================================================

;; A protocol - shows as 'protocol'
(defprotocol MyProtocol
  (protocol-method [this] "A protocol method"))

;; A record - shows as 'defrecord'
(defrecord MyRecord [field1 field2]
  MyProtocol
  (protocol-method [_this]
    (str field1 "-" field2)))

;; A type - shows as 'deftype'
(deftype MyType [state]
  MyProtocol
  (protocol-method [_this]
    (str "Type: " state)))

;; =============================================================================
;; Tests
;; =============================================================================

;; A test - shows as 'test'
(deftest my-test
  (is (= 43 (my-public-fn 42))))

;; Another test - shows as 'test'
(deftest another-test
  (is (= 84 (my-private-fn 42))))
