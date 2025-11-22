(ns strings.core
    "String tools module - string manipulation."
    (:require [bb-mcp-server.registry :as registry]
              [clojure.string :as str]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Tool Handler
;; -----------------------------------------------------------------------------

(defn concat-handler
  "Handler for concat tool. Concatenates strings with optional separator."
  [{:keys [strings separator]}]
  (log/log! {:level :debug
             :id ::concat-request
             :msg "Concat request"
             :data {:strings strings :separator separator}})
  (let [sep (or separator "")
        result (str/join sep strings)]
    (log/log! {:level :debug
               :id ::concat-result
               :msg "Concat result"
               :data {:count (count strings) :result result}})
    result))

;; -----------------------------------------------------------------------------
;; Tool Definition
;; -----------------------------------------------------------------------------

(def concat-tool
     "Concat tool definition with schema and handler."
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
;; Module Lifecycle
;; -----------------------------------------------------------------------------

(defn start
  "Start the strings module. Registers the concat tool."
  [_deps config]
  (log/log! {:level :info
             :id ::strings-starting
             :msg "Starting strings module"
             :data {:config config}})
  (registry/register! concat-tool)
  (log/log! {:level :info
             :id ::strings-started
             :msg "Strings module started"})
  {:registered-tools ["concat"]})

(defn stop
  "Stop the strings module. Unregisters the concat tool."
  [_instance]
  (log/log! {:level :info
             :id ::strings-stopping
             :msg "Stopping strings module"})
  (registry/unregister! "concat")
  nil)

(defn status
  "Get strings module status."
  [_instance]
  {:status :ok
   :registered-tools ["concat"]})

;; -----------------------------------------------------------------------------
;; Module Export
;; -----------------------------------------------------------------------------

(def module
     "Strings module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
