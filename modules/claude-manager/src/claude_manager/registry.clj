(ns claude-manager.registry
    "Instance registry and state management.

   Tracks all spawned Claude instances with their:
   - Process handles
   - Session IDs
   - Pending requests (for async correlation)
   - Response accumulators"
    (:require [taoensso.trove :as log]))

;; =============================================================================
;; Registry State
;; =============================================================================

;; Main registry: name -> instance map
(defonce ^:private instances (atom {}))

;; Request ID counter for unique IDs
(defonce ^:private request-counter (atom 0))

;; =============================================================================
;; Instance Structure
;; =============================================================================

(defn make-instance
  "Create a new instance record.

   Arguments:
     name     - Unique instance name
     proc-map - Process map from process/spawn-process!

   Returns:
     Instance map with all tracking state."
  [name proc-map]
  {:name name
   :proc-map proc-map
   :session-id (atom nil)           ; Set when init message received
   :pending-requests (atom {})       ; request-id -> promise
   :current-request-id (atom nil)    ; Currently processing request
   :response-buffer (atom [])        ; Accumulated assistant messages
   :created-at (System/currentTimeMillis)
   :reader-future (atom nil)})       ; Reader loop future

;; =============================================================================
;; Registry Operations
;; =============================================================================

(defn register-instance!
  "Add an instance to the registry.

   Returns:
     The registered instance."
  [instance]
  (log/log! {:level :info
             :id    ::registering-instance
             :msg   "Registering instance"
             :data  {:name (:name instance)}})
  (swap! instances assoc (:name instance) instance)
  instance)

(defn get-instance
  "Get an instance by name.

   Returns:
     Instance map or nil if not found."
  [name]
  (get @instances name))

(defn list-instances
  "List all registered instances.

   Returns:
     Sequence of instance maps."
  []
  (vals @instances))

(defn instance-names
  "Get names of all registered instances.

   Returns:
     Sequence of instance name strings."
  []
  (keys @instances))

(defn unregister-instance!
  "Remove an instance from the registry.

   Returns:
     The removed instance or nil."
  [name]
  (log/log! {:level :info
             :id    ::unregistering-instance
             :msg   "Unregistering instance"
             :data  {:name name}})
  (let [instance (get-instance name)]
    (swap! instances dissoc name)
    instance))

(defn clear-registry!
  "Remove all instances from the registry.

   Warning: Does not kill processes - use core/shutdown-all! instead."
  []
  (log/log! {:level :warn
             :id    ::clearing-registry
             :msg   "Clearing all instances from registry"})
  (reset! instances {}))

;; =============================================================================
;; Request ID Generation
;; =============================================================================

(defn next-request-id
  "Generate a unique request ID.

   Format: {instance-name}-{counter}-{uuid-prefix}

   Arguments:
     instance-name - Name of the instance making the request

   Returns:
     Unique request ID string."
  [instance-name]
  (let [n (swap! request-counter inc)
        uuid-prefix (subs (str (random-uuid)) 0 8)]
    (format "%s-%06d-%s" instance-name n uuid-prefix)))

;; =============================================================================
;; Pending Request Management
;; =============================================================================

(defn register-pending-request!
  "Register a pending request with its promise.

   Arguments:
     instance   - Instance map
     request-id - Request ID string
     promise    - Promise to deliver result to

   Returns:
     request-id"
  [instance request-id promise]
  (swap! (:pending-requests instance) assoc request-id promise)
  (reset! (:current-request-id instance) request-id)
  (reset! (:response-buffer instance) [])
  request-id)

(defn get-pending-request
  "Get the promise for a pending request.

   Returns:
     Promise or nil if not found."
  [instance request-id]
  (get @(:pending-requests instance) request-id))

(defn complete-pending-request!
  "Complete a pending request by delivering result to promise.

   Arguments:
     instance   - Instance map
     request-id - Request ID string
     result     - Result to deliver

   Returns:
     true if request was found and completed, false otherwise."
  [instance request-id result]
  (if-let [p (get-pending-request instance request-id)]
          (do
           (deliver p result)
           (swap! (:pending-requests instance) dissoc request-id)
           (reset! (:current-request-id instance) nil)
           true)
          false))

(defn get-current-request-id
  "Get the currently active request ID for an instance.

   Returns:
     Request ID string or nil."
  [instance]
  @(:current-request-id instance))

;; =============================================================================
;; Response Accumulation
;; =============================================================================

(defn accumulate-response!
  "Add a message to the response buffer.

   Used for accumulating multi-line assistant responses."
  [instance msg]
  (swap! (:response-buffer instance) conj msg))

(defn get-accumulated-response
  "Get all accumulated response messages.

   Returns:
     Vector of messages."
  [instance]
  @(:response-buffer instance))

(defn clear-response-buffer!
  "Clear the response buffer."
  [instance]
  (reset! (:response-buffer instance) []))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn set-session-id!
  "Set the session ID for an instance (from init message)."
  [instance session-id]
  (log/log! {:level :info
             :id    ::session-id-set
             :msg   "Session ID set"
             :data  {:name (:name instance) :session-id session-id}})
  (reset! (:session-id instance) session-id))

(defn get-session-id
  "Get the session ID for an instance.

   Returns:
     Session ID string or nil."
  [instance]
  @(:session-id instance))
