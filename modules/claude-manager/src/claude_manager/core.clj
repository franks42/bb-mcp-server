(ns claude-manager.core
    "Claude subprocess spawning and management - Public API.

   Provides tools for spawning, communicating with, and managing
   Claude CLI instances as subprocesses.

   Main functions:
   - spawn!  - Start a new Claude instance
   - ask     - Send message and wait for response
   - kill!   - Stop an instance
   - list-instances - List all running instances

   Example:
     (require '[claude-manager.core :as cm])

     ;; Spawn an instance
     (cm/spawn! \"my-claude\" {:cmd [\"bb\" \"mock_claude.clj\"]})

     ;; Send a message and get response
     (cm/ask \"my-claude\" \"What is 2 + 2?\")
     ;; => {:content \"Mock response to: What is 2 + 2?\" :cost_usd 0.0}

     ;; Kill the instance
     (cm/kill! \"my-claude\")"
    (:require [claude-manager.process :as process]
              [claude-manager.registry :as registry]
              [taoensso.trove :as log]))

;; =============================================================================
;; Module Definition
;; =============================================================================

(def module
     "Module manifest for bb-mcp-server module system."
     {:name "claude-manager"
      :version "0.1.0"
      :description "Claude subprocess spawning and management"})

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-config
     {:request-timeout-ms 120000
      :max-instances 10})

(defonce ^:private config (atom default-config))

(defn configure!
  "Update configuration.

   Options:
     :request-timeout-ms - Timeout for ask requests (default: 120000)
     :max-instances      - Maximum concurrent instances (default: 10)"
  [opts]
  (swap! config merge opts))

;; =============================================================================
;; Message Dispatcher
;; =============================================================================

(defn- make-dispatcher
  "Create a message dispatcher for an instance.

   The dispatcher handles incoming messages from the reader loop:
   - system/init: Store session ID
   - assistant: Accumulate response content
   - result: Complete pending request with accumulated response
   - error: Complete pending request with error"
  [instance]
  (fn [msg-type msg]
    (case msg-type
      :system
      (when (= "init" (:subtype msg))
        (registry/set-session-id! instance (:session_id msg)))

      :assistant
      (registry/accumulate-response! instance msg)

      :result
      (let [request-id (registry/get-current-request-id instance)
            responses (registry/get-accumulated-response instance)
            ;; Extract content from assistant messages
            content (apply str (map #(get-in % [:message :content] "") responses))
            result {:content content
                    :cost_usd (:cost_usd msg 0)
                    :duration_ms (:duration_ms msg 0)
                    :subtype (:subtype msg)}]
        (when request-id
          (registry/complete-pending-request! instance request-id result)))

      :error
      (let [request-id (registry/get-current-request-id instance)
            error-result {:error true
                          :message (get-in msg [:error :message] "Unknown error")}]
        (when request-id
          (registry/complete-pending-request! instance request-id error-result)))

      :parse-error
      (log/log! {:level :warn
                 :id    ::parse-error-received
                 :msg   "Parse error in reader loop"
                 :data  msg})

      ;; Default: log unknown message types
      (log/log! {:level :debug
                 :id    ::unknown-message-type
                 :msg   "Unknown message type"
                 :data  {:type msg-type :msg msg}}))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn spawn!
  "Spawn a new Claude instance.

   Arguments:
     name - Unique name for this instance
     opts - Options map:
            :cmd - Command vector to spawn (required)
                   e.g. [\"bb\" \"mock_claude.clj\"] or [\"claude\" \"--stream-json\"]

   Returns:
     Instance map on success, or {:error ...} on failure.

   Example:
     (spawn! \"test\" {:cmd [\"bb\" \"test/mock_claude.clj\"]})"
  [name {:keys [cmd] :as _opts}]
  (log/log! {:level :info
             :id    ::spawning-instance
             :msg   "Spawning instance"
             :data  {:name name :cmd cmd}})

  ;; Check if name already exists
  (when (registry/get-instance name)
    (log/log! {:level :warn
               :id    ::instance-exists
               :msg   "Instance already exists"
               :data  {:name name}})
    (throw (ex-info "Instance already exists" {:name name})))

  ;; Check max instances
  (when (>= (count (registry/list-instances)) (:max-instances @config))
    (throw (ex-info "Maximum instances reached"
                    {:max (:max-instances @config)
                     :current (count (registry/list-instances))})))

  ;; Spawn process
  (let [proc-map (process/spawn-process! cmd)
        instance (registry/make-instance name proc-map)
        dispatcher (make-dispatcher instance)
        reader-future (process/start-reader-loop! proc-map dispatcher)]

    ;; Store reader future in instance
    (reset! (:reader-future instance) reader-future)

    ;; Register instance
    (registry/register-instance! instance)

    (log/log! {:level :info
               :id    ::instance-spawned
               :msg   "Instance spawned successfully"
               :data  {:name name}})
    instance))

(defn ask
  "Send a message to an instance and wait for response.

   Arguments:
     name    - Instance name
     message - Message content string

   Returns:
     Response map with :content, :cost_usd, :duration_ms
     Or {:error true :message ...} on error/timeout.

   Example:
     (ask \"my-claude\" \"What is the capital of France?\")"
  [name message]
  (let [instance (registry/get-instance name)]
    (when-not instance
      (throw (ex-info "Instance not found" {:name name})))

    (let [request-id (registry/next-request-id name)
          p (promise)
          timeout-ms (:request-timeout-ms @config)]

      ;; Register pending request
      (registry/register-pending-request! instance request-id p)

      ;; Write message to stdin
      (let [msg {:type "user"
                 :message {:role "user" :content message}}]
        (when-not (process/write-message! (:proc-map instance) msg)
          (registry/complete-pending-request! instance request-id
                                              {:error true :message "Write failed"})
          (throw (ex-info "Failed to write message" {:name name}))))

      ;; Wait for response with timeout
      (let [result (deref p timeout-ms {:error true :message "Timeout"})]
        (log/log! {:level :debug
                   :id    ::ask-complete
                   :msg   "Ask completed"
                   :data  {:name name :request-id request-id :has-error (:error result)}})
        result))))

(defn kill!
  "Kill an instance and remove from registry.

   Arguments:
     name - Instance name

   Returns:
     true on success, false if instance not found."
  [name]
  (log/log! {:level :info
             :id    ::killing-instance
             :msg   "Killing instance"
             :data  {:name name}})
  (if-let [instance (registry/get-instance name)]
          (do
      ;; Kill the process
           (process/kill-process! (:proc-map instance))
      ;; Cancel reader future
           (when-let [f @(:reader-future instance)]
                     (future-cancel f))
      ;; Unregister
           (registry/unregister-instance! name)
           true)
          false))

(defn list-instances
  "List all running instances.

   Returns:
     Sequence of maps with :name, :session-id, :created-at, :alive?"
  []
  (for [instance (registry/list-instances)]
       {:name (:name instance)
        :session-id (registry/get-session-id instance)
        :created-at (:created-at instance)
        :alive? (process/process-alive? (:proc-map instance))}))

(defn get-instance-info
  "Get detailed info about an instance.

   Arguments:
     name - Instance name

   Returns:
     Instance info map or nil."
  [name]
  (when-let [instance (registry/get-instance name)]
            {:name (:name instance)
             :session-id (registry/get-session-id instance)
             :created-at (:created-at instance)
             :alive? (process/process-alive? (:proc-map instance))
             :pending-requests (count @(:pending-requests instance))}))

(defn shutdown-all!
  "Kill all instances and clear registry.

   Returns:
     Number of instances killed."
  []
  (log/log! {:level :info
             :id    ::shutdown-all
             :msg   "Shutting down all instances"})
  (let [names (registry/instance-names)
        count (count names)]
    (doseq [name names]
           (kill! name))
    count))
