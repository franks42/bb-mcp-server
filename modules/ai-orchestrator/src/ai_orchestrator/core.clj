(ns ai-orchestrator.core
    "Multi-provider AI orchestration - Public API.

   Main functions:
   - start-instance! - Start a new AI instance (any provider)
   - ask             - Send message and get response
   - stop-instance!  - Stop an instance
   - list-instances  - List all running instances

   Example:
     (require '[ai-orchestrator.core :as orch])

     ;; Start a Claude subprocess instance
     (orch/start-instance! \"my-claude\"
       {:provider-type :claude-subprocess
        :cmd [\"claude\" \"--stream-json\"]})

     ;; Send a message
     (orch/ask \"my-claude\" \"What is 2+2?\")

     ;; Stop the instance
     (orch/stop-instance! \"my-claude\")"
    (:require [ai-orchestrator.protocol :as proto]
              [ai-orchestrator.registry :as registry]
              [ai-orchestrator.router :as router]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-config
     {:max-instances 20
      :request-timeout-ms 120000})

(defonce ^:private config (atom default-config))

(defn configure!
  "Update orchestrator configuration.

   Options:
     :max-instances       - Maximum concurrent instances (default: 20)
     :request-timeout-ms  - Default timeout for ask requests (default: 120000)"
  [opts]
  (swap! config merge opts)
  ;; Propagate router config
  (when (:request-timeout-ms opts)
    (router/configure! {:request-timeout-ms (:request-timeout-ms opts)})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-instance!
  "Start a new AI instance.

   Arguments:
     name - Unique instance name string
     opts - Provider options map:
            :provider-type - Required (:claude-subprocess, :openai-http, etc.)
            :model         - Model identifier (provider-specific)
            ... additional provider-specific options

   Examples:
     ;; Claude subprocess
     (start-instance! \"researcher\"
       {:provider-type :claude-subprocess
        :cmd [\"claude\" \"--model\" \"claude-3-5-haiku-20241022\"
              \"--input-format\" \"stream-json\"
              \"--output-format\" \"stream-json\"]})

     ;; OpenAI HTTP (future)
     (start-instance! \"gpt-helper\"
       {:provider-type :openai-http
        :base-url \"https://api.openai.com/v1\"
        :api-key (System/getenv \"OPENAI_API_KEY\")
        :model \"gpt-4-turbo\"})

   Returns:
     Instance map on success, {:error ...} on failure."
  [name opts]
  (log/log! {:level :info
             :id    ::starting-instance
             :msg   "Starting AI instance"
             :data  {:name name :provider-type (:provider-type opts)}})

  ;; Check if name already exists
  (when (registry/get-instance name)
    (log/log! {:level :warn
               :id    ::instance-exists
               :msg   "Instance already exists"
               :data  {:name name}})
    (throw (ex-info "Instance already exists" {:name name})))

  ;; Check max instances
  (when (>= (count (registry/list-instances)) (:max-instances @config))
    (log/log! {:level :warn
               :id    ::max-instances-reached
               :msg   "Maximum instances reached"
               :data  {:max (:max-instances @config)
                       :current (count (registry/list-instances))}})
    (throw (ex-info "Maximum instances reached"
                    {:max (:max-instances @config)
                     :current (count (registry/list-instances))})))

  ;; Create instance via provider protocol
  (let [opts-with-name (assoc opts :name name)
        instance (proto/create-instance opts-with-name)]

    (if (:error instance)
      (do
       (log/log! {:level :error
                  :id    ::create-instance-failed
                  :msg   "Failed to create instance"
                  :data  {:name name
                          :provider-type (:provider-type opts)
                          :error (:error instance)}})
       instance)
      (do
       (registry/register-instance! instance)
       (log/log! {:level :info
                  :id    ::instance-started
                  :msg   "Instance started successfully"
                  :data  {:name name
                          :provider-type (:provider-type instance)}})
       instance))))

(defn ask
  "Send message to AI instance and wait for response.

   Works with any provider - protocol abstraction handled internally.

   Arguments:
     name    - Instance name string
     message - Message content string

   Returns:
     Response map (provider-specific format, typically has :content key)
     Or {:error true :message ...} on error/timeout.

   Example:
     (ask \"my-claude\" \"What is the capital of France?\")"
  [name message]
  (let [instance (registry/get-instance name)]
    (when-not instance
      (log/log! {:level :warn
                 :id    ::instance-not-found
                 :msg   "Instance not found for ask"
                 :data  {:name name}})
      (throw (ex-info "Instance not found" {:name name})))

    (router/send-and-await instance message)))

(defn stop-instance!
  "Stop an AI instance and remove from registry.

   Arguments:
     name - Instance name string

   Returns:
     true on success, false if not found."
  [name]
  (log/log! {:level :info
             :id    ::stopping-instance
             :msg   "Stopping AI instance"
             :data  {:name name}})

  (if-let [instance (registry/get-instance name)]
          (do
      ;; Stop via provider protocol
           (try
            (proto/stop-instance instance)
            (catch Exception e
                   (log/log! {:level :error
                              :id    ::stop-failed
                              :msg   "Error stopping instance"
                              :error e
                              :data  {:name name
                                      :provider-type (:provider-type instance)}})))

      ;; Unregister
           (registry/unregister-instance! name)
           true)
          (do
           (log/log! {:level :warn
                      :id    ::instance-not-found-for-stop
                      :msg   "Instance not found for stop"
                      :data  {:name name}})
           false)))

(defn list-instances
  "List all active AI instances.

   Returns:
     Sequence of maps with :name, :provider-type, :model, :created-at, etc."
  []
  (map registry/get-instance-info (registry/instance-names)))

(defn get-instance-info
  "Get detailed info about an instance.

   Arguments:
     name - Instance name string

   Returns:
     Instance metadata map or nil if not found."
  [name]
  (registry/get-instance-info name))

(defn shutdown-all!
  "Stop all instances and clear registry.

   Returns:
     Number of instances stopped."
  []
  (log/log! {:level :info
             :id    ::shutdown-all
             :msg   "Shutting down all instances"})
  (let [names (registry/instance-names)
        count (count names)]
    (doseq [name names]
           (stop-instance! name))
    count))

(defn find-instances-by-provider
  "Find all instances of a specific provider type.

   Arguments:
     provider-type - Provider type keyword (e.g., :claude-subprocess)

   Returns:
     Sequence of instance info maps."
  [provider-type]
  (map registry/get-instance-info
       (map :name (registry/find-instances-by-provider provider-type))))

(defn find-instances-by-capability
  "Find instances with a specific capability.

   Arguments:
     capability - Capability keyword (e.g., :vision, :tools)

   Returns:
     Sequence of instance info maps."
  [capability]
  (map registry/get-instance-info
       (map :name (registry/find-instances-by-capability capability))))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn module-start
  "Start the AI orchestrator module."
  [_deps config]
  (log/log! {:level :info
             :id    ::module-starting
             :msg   "Starting ai-orchestrator module"
             :data  {:config config}})
  (when config
    (configure! config))
  {:status :started})

(defn module-stop
  "Stop the AI orchestrator module."
  [_instance]
  (log/log! {:level :info
             :id    ::module-stopping
             :msg   "Stopping ai-orchestrator module"})
  ;; Stop all running instances
  (shutdown-all!)
  nil)

(defn module-status
  "Get AI orchestrator module status."
  [_instance]
  (let [instances (list-instances)]
    {:status :ok
     :instance-count (count instances)}))

(def module
     "Module lifecycle implementation for bb-mcp-server module system."
     {:start module-start
      :stop module-stop
      :status module-status})
