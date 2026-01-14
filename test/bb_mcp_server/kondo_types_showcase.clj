(ns bb-mcp-server.kondo-types-showcase
  "Showcase file demonstrating all clj-kondo var types and code browser features.

   Use this with the Code Browser to see all supported kind labels:
   - function, private-fn, variable, defonce, declare
   - macro, multimethod, method, protocol, deftype, defrecord, test
   - protocol-impl (protocol implementations in defrecord/deftype)
   - Top-level forms: comment, side-effect, require, config (file-order view only)"
  (:require [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Top-level forms (Phase 1.5E.9)
;; These show only in file-order view, filtered out in alphabetical view
;; =============================================================================

;; A comment block - shows as 'comment' in file-order view
(comment
  ;; This is a scratch area for REPL-driven development
  (+ 1 2 3)
  (my-public-fn 10)
  :end)

;; Side-effect at load time - shows as 'side-effect'
(println "Loading kondo-types-showcase namespace...")

;; =============================================================================
;; Basic definitions
;; =============================================================================

(def my-variable
  "A regular def - shows as 'variable'"
  42)

(def another-variable
  "Another variable for variety"
  {:key "value"})

;; defonce - shows as 'defonce' (lint warning expected - defonce doesn't support docstring)
(defonce my-defonce (atom {}))

(defonce my-other-defonce (atom []))

(declare my-declared-fn forward-declared-helper)

;; =============================================================================
;; Functions
;; =============================================================================

(defn my-public-fn
  "A public function - shows as 'function'"
  [x]
  (+ x 1))

(defn multi-arity-fn
  "A function with multiple arities - shows as 'function'"
  ([] (multi-arity-fn 0))
  ([x] (multi-arity-fn x 1))
  ([x y] (+ x y)))

(defn- my-private-fn
  "A private function - shows as 'private-fn'"
  [x]
  (* x 2))

(defn- another-private-fn
  "Another private function"
  [s]
  (str "private: " s))

(defn my-declared-fn
  "Implement the declared function - shows as 'function'"
  []
  :implemented)

(defn forward-declared-helper
  "Helper that was forward-declared"
  [x]
  (inc x))

;; =============================================================================
;; Macros
;; =============================================================================

(defmacro my-macro
  "A macro - shows as 'macro'"
  [& body]
  `(do ~@body))

(defmacro with-timing
  "A utility macro that times execution - shows as 'macro'"
  [label & body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)
         elapsed# (- (System/currentTimeMillis) start#)]
     (println ~label "took" elapsed# "ms")
     result#))

;; =============================================================================
;; Multimethods (Phase 1.5E.6)
;; =============================================================================

(defmulti my-multimethod
  "A multimethod dispatcher - shows as 'multimethod'"
  :type)

;; Default implementation - shows as 'method' with dispatch value
(defmethod my-multimethod :default
  [m]
  (:value m))

;; Keyword dispatch - shows as 'my-multimethod :special'
(defmethod my-multimethod :special
  [m]
  (str "Special: " (:value m)))

;; Another keyword dispatch
(defmethod my-multimethod :enhanced
  [m]
  (str "Enhanced: " (get m :value "none") "!"))

;; A second multimethod to show multiple in one file
(defmulti process-event
  "Process different event types"
  :event-type)

(defmethod process-event :click
  [{:keys [x y]}]
  (str "Click at " x "," y))

(defmethod process-event :keypress
  [{:keys [key]}]
  (str "Key pressed: " key))

(defmethod process-event :scroll
  [{:keys [delta]}]
  (str "Scroll by " delta))

;; =============================================================================
;; Protocols and implementations (Phase 1.5E.7)
;; =============================================================================

;; A protocol - shows as 'protocol'
(defprotocol MyProtocol
  "A sample protocol with one method"
  (protocol-method [this] "A protocol method"))

;; A more complex protocol with multiple methods
(defprotocol Describable
  "Protocol for things that can describe themselves"
  (describe [this] "Return a description string")
  (short-name [this] "Return a short name"))

;; A protocol for mathematical operations
(defprotocol Calculable
  "Protocol for calculable things"
  (add [this other] "Add two things")
  (multiply [this factor] "Multiply by a factor"))

;; A record implementing one protocol - shows as 'defrecord'
;; Protocol method shows as 'protocol-method (MyRecord)'
(defrecord MyRecord [field1 field2]
  MyProtocol
  (protocol-method [_this]
    (str field1 "-" field2)))

;; A type implementing one protocol - shows as 'deftype'
;; Protocol method shows as 'protocol-method (MyType)'
(deftype MyType [state]
  MyProtocol
  (protocol-method [_this]
    (str "Type: " state)))

;; A record implementing multiple protocols
;; Each method shows as 'method-name (RichRecord)'
(defrecord RichRecord [name value]
  MyProtocol
  (protocol-method [_this]
    (str "RichRecord: " name))

  Describable
  (describe [_this]
    (str "A rich record named '" name "' with value " value))
  (short-name [_this]
    name)

  Calculable
  (add [_this other]
    (->RichRecord name (+ value (:value other))))
  (multiply [_this factor]
    (->RichRecord name (* value factor))))

;; Another record with different protocol set
(defrecord SimpleRecord [id]
  Describable
  (describe [_this]
    (str "Simple record #" id))
  (short-name [_this]
    (str "SR" id)))

;; A type implementing multiple protocols
(deftype ComplexType [data metadata]
  MyProtocol
  (protocol-method [_this]
    (str "ComplexType with " (count data) " items"))

  Describable
  (describe [_this]
    (str "Complex type containing: " data " with meta: " metadata))
  (short-name [_this]
    "CT"))

;; =============================================================================
;; More top-level forms
;; =============================================================================

;; Another comment block with examples
(comment
  ;; Testing the protocols
  (def r (->MyRecord "a" "b"))
  (protocol-method r)

  (def rich (->RichRecord "test" 100))
  (describe rich)
  (short-name rich)

  ;; Multimethod examples
  (my-multimethod {:type :special :value "hello"})
  (process-event {:event-type :click :x 10 :y 20})
  :end)

;; Print statement showing we're done loading
(println "Showcase namespace loaded successfully!")

;; =============================================================================
;; Tests (shows as 'test')
;; =============================================================================

(deftest my-test
  (is (= 43 (my-public-fn 42))))

(deftest another-test
  (is (= 84 (my-private-fn 42))))

(deftest multi-arity-test
  (testing "zero arity"
    (is (= 1 (multi-arity-fn))))
  (testing "one arity"
    (is (= 2 (multi-arity-fn 1))))
  (testing "two arity"
    (is (= 5 (multi-arity-fn 2 3)))))

(deftest protocol-test
  (testing "MyRecord implements MyProtocol"
    (is (= "a-b" (protocol-method (->MyRecord "a" "b")))))
  (testing "RichRecord implements multiple protocols"
    (let [r (->RichRecord "test" 42)]
      (is (= "RichRecord: test" (protocol-method r)))
      (is (= "test" (short-name r))))))

(deftest multimethod-test
  (testing "default dispatch"
    (is (= 10 (my-multimethod {:value 10}))))
  (testing "special dispatch"
    (is (= "Special: hello" (my-multimethod {:type :special :value "hello"}))))
  (testing "process-event dispatches"
    (is (= "Click at 5,10" (process-event {:event-type :click :x 5 :y 10})))))
