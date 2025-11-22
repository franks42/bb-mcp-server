(ns bb-mcp-server.tools.examples
    "Example tools demonstrating the unified registry API.

  Provides simple, well-documented tools for testing and reference:
  - echo: Returns input unchanged
  - add: Adds two numbers
  - concat: Concatenates strings"
    (:require [bb-mcp-server.registry :as registry]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Echo Tool
;; -----------------------------------------------------------------------------

(defn echo-handler
  "Handler for echo tool. Returns the input message unchanged."
  [{:keys [message]}]
  (log/log! {:level :info
             :id ::echo-request
             :msg "Echo request"
             :data {:message message}})
  message)

(def echo-tool
  "Tool definition for echo - returns input unchanged."
  {:name "echo"
   :description "Returns the input message unchanged"
   :inputSchema {:type "object"
                 :properties {:message {:type "string"
                                        :description "Message to echo back"}}
                 :required ["message"]}
   :handler echo-handler})

;; -----------------------------------------------------------------------------
;; Add Tool
;; -----------------------------------------------------------------------------

(defn add-handler
  "Handler for add tool. Adds two numbers."
  [{:keys [a b]}]
  (log/log! {:level :info
             :id ::add-request
             :msg "Add request"
             :data {:a a :b b}})
  (let [result (+ a b)]
    (log/log! {:level :info
               :id ::add-result
               :msg "Add result"
               :data {:a a :b b :result result}})
    result))

(def add-tool
  "Tool definition for add - adds two numbers."
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
;; Concat Tool
;; -----------------------------------------------------------------------------

(defn concat-handler
  "Handler for concat tool. Concatenates strings with optional separator."
  [{:keys [strings separator]}]
  (log/log! {:level :info
             :id ::concat-request
             :msg "Concat request"
             :data {:strings strings :separator separator}})
  (let [sep (or separator "")
        result (str/join sep strings)]
    (log/log! {:level :info
               :id ::concat-result
               :msg "Concat result"
               :data {:count (count strings) :result result}})
    result))

(def concat-tool
  "Tool definition for concat - concatenates strings."
  {:name "concat"
   :description "Concatenates an array of strings with optional separator"
   :inputSchema {:type "object"
                 :properties {:strings {:type "array"
                                        :items {:type "string"}
                                        :description "Array of strings to concatenate"}
                              :separator {:type "string"
                                          :description "Optional separator between strings"}}
                 :required ["strings"]}
   :handler concat-handler})

;; -----------------------------------------------------------------------------
;; Registration
;; -----------------------------------------------------------------------------

(def all-tools
  "All example tools for bulk registration."
  [echo-tool add-tool concat-tool])

(defn init!
  "Register all example tools with the unified registry."
  []
  (log/log! {:level :info
             :id ::examples-init-start
             :msg "Initializing example tools"
             :data {:count (count all-tools)}})
  (registry/register-all! all-tools)
  (log/log! {:level :info
             :id ::examples-init-complete
             :msg "Example tools initialized"
             :data {:tools (mapv :name all-tools)}})
  true)
