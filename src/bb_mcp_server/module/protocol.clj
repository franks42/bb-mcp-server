(ns bb-mcp-server.module.protocol
    "Module protocol for bb-mcp-server.

  Defines the interface that all modules must implement.
  Uses map-based protocol (Babashka doesn't support defprotocol).

  Each module is a map with:
  - :start  (fn [deps config] -> instance)
  - :stop   (fn [instance] -> nil)
  - :status (fn [instance] -> {:status :ok|:degraded|:error ...})"
    (:require [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Module Schema
;; -----------------------------------------------------------------------------

(def module-schema
     "Schema for module implementations.

  Each module must export a map with these keys:
  - :start  - Function to start the module
  - :stop   - Function to stop the module
  - :status - Function to check module health"
     {:start :fn
      :stop :fn
      :status :fn})

;; -----------------------------------------------------------------------------
;; Module Status
;; -----------------------------------------------------------------------------

(def status-values
     "Valid module status values."
     #{:ok :degraded :error :stopped})

(defn valid-status?
  "Check if a status map is valid."
  [status-map]
  (and (map? status-map)
       (contains? status-values (:status status-map))))

;; -----------------------------------------------------------------------------
;; Module Validation
;; -----------------------------------------------------------------------------

(defn valid-module?
  "Check if a value is a valid module implementation.

  A valid module has:
  - :start function (arity 2: deps, config)
  - :stop function (arity 1: instance)
  - :status function (arity 1: instance)"
  [module]
  (and (map? module)
       (fn? (:start module))
       (fn? (:stop module))
       (fn? (:status module))))

(defn validate-module
  "Validate a module and return errors if invalid.

  Args:
    module - The module map to validate

  Returns:
    {:valid true} or {:valid false :errors [...]}"
  [module]
  (let [errors (cond-> []
                       (not (map? module))
                       (conj "Module must be a map")

                       (and (map? module) (not (fn? (:start module))))
                       (conj "Module must have :start function")

                       (and (map? module) (not (fn? (:stop module))))
                       (conj "Module must have :stop function")

                       (and (map? module) (not (fn? (:status module))))
                       (conj "Module must have :status function"))]
    (if (empty? errors)
      {:valid true}
      {:valid false :errors errors})))

;; -----------------------------------------------------------------------------
;; Module Lifecycle Operations
;; -----------------------------------------------------------------------------

(defn start-module
  "Start a module with dependencies and configuration.

  Args:
    module - Module implementation map
    deps   - Map of dependency-name -> started instance
    config - Module configuration map

  Returns:
    Module instance (value returned by :start)

  Throws:
    ExceptionInfo if module is invalid or start fails"
  [module deps config]
  (log/log! {:level :info
             :id ::start-module
             :msg "Starting module"
             :data {:has-deps (not (empty? deps))
                    :has-config (not (empty? config))}})
  (let [validation (validate-module module)]
    (when-not (:valid validation)
      (throw (ex-info "Invalid module"
                      {:type :invalid-module
                       :errors (:errors validation)}))))
  (try
   (let [instance ((:start module) deps config)]
     (log/log! {:level :info
                :id ::module-started
                :msg "Module started successfully"})
     instance)
   (catch Exception e
          (log/log! {:level :error
                     :id ::module-start-failed
                     :msg "Module start failed"
                     :error e
                     :data {:error (ex-message e)}})
          (throw e))))

(defn stop-module
  "Stop a running module.

  Args:
    module   - Module implementation map
    instance - The value returned by start-module

  Returns:
    nil

  Side effects:
    - Calls module's :stop function
    - Logs stop events"
  [module instance]
  (log/log! {:level :info
             :id ::stop-module
             :msg "Stopping module"})
  (try
   ((:stop module) instance)
   (log/log! {:level :info
              :id ::module-stopped
              :msg "Module stopped successfully"})
   nil
   (catch Exception e
          (log/log! {:level :error
                     :id ::module-stop-failed
                     :msg "Module stop failed"
                     :error e
                     :data {:error (ex-message e)}})
          (throw e))))

(defn module-status
  "Get status of a running module.

  Args:
    module   - Module implementation map
    instance - The value returned by start-module

  Returns:
    Status map with at least :status key (:ok, :degraded, :error)"
  [module instance]
  (try
   (let [status ((:status module) instance)]
     (if (valid-status? status)
       status
       {:status :error
        :error "Invalid status returned by module"
        :actual status}))
   (catch Exception e
          (log/log! {:level :warn
                     :id ::module-status-failed
                     :msg "Module status check failed"
                     :error e
                     :data {:error (ex-message e)}})
          {:status :error
           :error (ex-message e)})))

;; -----------------------------------------------------------------------------
;; Module Instance Record
;; -----------------------------------------------------------------------------

(defn create-module-instance
  "Create a module instance record for tracking.

  Args:
    name     - Module name (string)
    module   - Module implementation map
    instance - Value returned by start
    config   - Configuration used

  Returns:
    Module instance record map"
  [name module instance config]
  {:name name
   :module module
   :instance instance
   :config config
   :started-at (System/currentTimeMillis)
   :status :running})
