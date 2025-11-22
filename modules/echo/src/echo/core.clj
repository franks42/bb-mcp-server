(ns echo.core
    "Echo tool module - returns input unchanged."
    (:require [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Tool Handler
;; -----------------------------------------------------------------------------

(defn echo-handler
  "Handler for echo tool. Returns the input message unchanged."
  [{:keys [message]}]
  (log/log! {:level :debug
             :id ::echo-request
             :msg "Echo request"
             :data {:message message}})
  message)

;; -----------------------------------------------------------------------------
;; Tool Definition
;; -----------------------------------------------------------------------------

(def echo-tool
     "Echo tool definition with schema and handler."
     {:name "echo"
      :description "Returns the input message unchanged"
      :inputSchema {:type "object"
                    :properties {:message {:type "string"
                                           :description "Message to echo back"}}
                    :required ["message"]}
      :handler echo-handler})

;; -----------------------------------------------------------------------------
;; Module Lifecycle
;; -----------------------------------------------------------------------------

(defn start
  "Start the echo module. Registers the echo tool."
  [_deps config]
  (log/log! {:level :info
             :id ::echo-starting
             :msg "Starting echo module"
             :data {:config config}})
  (registry/register! echo-tool)
  (log/log! {:level :info
             :id ::echo-started
             :msg "Echo module started"})
  {:registered-tools ["echo"]})

(defn stop
  "Stop the echo module. Unregisters the echo tool."
  [_instance]
  (log/log! {:level :info
             :id ::echo-stopping
             :msg "Stopping echo module"})
  (registry/unregister! "echo")
  nil)

(defn status
  "Get echo module status."
  [_instance]
  {:status :ok
   :registered-tools ["echo"]})

;; -----------------------------------------------------------------------------
;; Module Export
;; -----------------------------------------------------------------------------

(def module
     "Echo module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
