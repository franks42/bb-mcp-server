(ns hello.core
    "Hello world MCP tool module.

  Demonstrates the module system by implementing a simple hello tool.
  This module registers a 'hello' tool that greets users by name."
    (:require [bb-mcp-server.registry :as registry]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Module State
;; -----------------------------------------------------------------------------

;; Atom holding module-local state
(defonce ^:private module-state
         (atom {:greeting "Hello"
                :call-count 0}))

;; -----------------------------------------------------------------------------
;; Tool Handler
;; -----------------------------------------------------------------------------

(defn hello-handler
  "Handle hello tool calls.

  Args:
    arguments - Map with :name key

  Returns:
    Greeting string"
  [{:keys [name]}]
  (let [{:keys [greeting]} @module-state]
    (swap! module-state update :call-count inc)
    (log/log! {:level :debug
               :id ::hello-called
               :msg "Hello tool called"
               :data {:name name
                      :call-count (:call-count @module-state)}})
    (str greeting ", " name "!")))

;; -----------------------------------------------------------------------------
;; Tool Definition
;; -----------------------------------------------------------------------------

(def hello-tool
     "MCP tool definition for hello."
     {:name "hello"
      :description "Greet a user by name"
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Name of the person to greet"}}
                    :required ["name"]}
      :handler hello-handler})

;; -----------------------------------------------------------------------------
;; Module Lifecycle
;; -----------------------------------------------------------------------------

(defn start
  "Start the hello module.

  Args:
    deps   - Map of dependency module instances
    config - Module configuration {:greeting \"...\"}

  Returns:
    Module instance state

  Side effects:
    - Registers hello tool with MCP registry
    - Initializes module state"
  [_deps config]
  (log/log! {:level :info
             :id ::hello-starting
             :msg "Starting hello module"
             :data {:config config}})

  ;; Apply configuration
  (when-let [greeting (:greeting config)]
            (swap! module-state assoc :greeting greeting))

  ;; Register tool
  (registry/register! hello-tool)

  (log/log! {:level :info
             :id ::hello-started
             :msg "Hello module started"
             :data {:greeting (:greeting @module-state)}})

  ;; Return instance state
  {:registered-tools ["hello"]
   :config config})

(defn stop
  "Stop the hello module.

  Args:
    instance - Module instance from start

  Side effects:
    - Unregisters hello tool
    - Resets module state"
  [instance]
  (log/log! {:level :info
             :id ::hello-stopping
             :msg "Stopping hello module"
             :data {:instance instance}})

  ;; Unregister tool
  (registry/unregister! "hello")

  ;; Reset state
  (reset! module-state {:greeting "Hello" :call-count 0})

  (log/log! {:level :info
             :id ::hello-stopped
             :msg "Hello module stopped"})
  nil)

(defn status
  "Get hello module status.

  Args:
    instance - Module instance from start

  Returns:
    Status map with :status and metrics"
  [_instance]
  {:status :ok
   :greeting (:greeting @module-state)
   :call-count (:call-count @module-state)
   :registered-tools ["hello"]})

;; -----------------------------------------------------------------------------
;; Module Export
;; -----------------------------------------------------------------------------

(def module
     "Module implementation conforming to IModule protocol."
     {:start start
      :stop stop
      :status status})
