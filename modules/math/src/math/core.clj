(ns math.core
    "Math tools module - arithmetic operations."
    (:require [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Tool Handler
;; -----------------------------------------------------------------------------

(defn add-handler
  "Handler for add tool. Adds two numbers."
  [{:keys [a b]}]
  (log/log! {:level :debug
             :id ::add-request
             :msg "Add request"
             :data {:a a :b b}})
  (let [result (+ a b)]
    (log/log! {:level :debug
               :id ::add-result
               :msg "Add result"
               :data {:a a :b b :result result}})
    result))

;; -----------------------------------------------------------------------------
;; Tool Definition
;; -----------------------------------------------------------------------------

(def add-tool
     "Add tool definition with schema and handler."
     {:name "add"
      :description "Adds two numbers and returns the sum"
      :inputSchema {:type "object"
                    :properties {:a {:type "number"
                                     :description "First number"}
                                 :b {:type "number"
                                     :description "Second number"}}
                    :required ["a" "b"]}
      :handler add-handler})

;; -----------------------------------------------------------------------------
;; Module Lifecycle
;; -----------------------------------------------------------------------------

(defn start
  "Start the math module. Registers the add tool."
  [_deps config]
  (log/log! {:level :info
             :id ::math-starting
             :msg "Starting math module"
             :data {:config config}})
  (registry/register! add-tool)
  (log/log! {:level :info
             :id ::math-started
             :msg "Math module started"})
  {:registered-tools ["add"]})

(defn stop
  "Stop the math module. Unregisters the add tool."
  [_instance]
  (log/log! {:level :info
             :id ::math-stopping
             :msg "Stopping math module"})
  (registry/unregister! "add")
  nil)

(defn status
  "Get math module status."
  [_instance]
  {:status :ok
   :registered-tools ["add"]})

;; -----------------------------------------------------------------------------
;; Module Export
;; -----------------------------------------------------------------------------

(def module
     "Math module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
