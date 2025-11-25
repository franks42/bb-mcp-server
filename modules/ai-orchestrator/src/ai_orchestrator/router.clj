(ns ai-orchestrator.router
    "Message routing and request/response correlation.

   Handles sending messages through provider-specific protocol
   and waiting for async responses with timeout support."
    (:require [ai-orchestrator.protocol :as proto]
              [ai-orchestrator.registry :as registry]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-config
     {:request-timeout-ms 120000})

(defonce ^:private config (atom default-config))

(defn configure!
  "Update router configuration.

   Options:
     :request-timeout-ms - Timeout for requests (default: 120000)"
  [opts]
  (swap! config merge opts))

;; =============================================================================
;; Request Correlation
;; =============================================================================

(defn- register-pending-request!
  "Register a pending request with its promise.

   Arguments:
     instance   - Instance map
     request-id - Request ID string
     promise    - Promise to deliver result to

   Returns:
     request-id"
  [instance request-id promise]
  (swap! (:pending-requests instance) assoc request-id promise)
  ;; NOTE: We no longer set current-request-id here - it's passed directly
  ;; to send-message via :active-request-id to avoid race conditions.
  ;; Clear response buffer if instance has one (for subprocess providers)
  (when-let [resp-buf (:response-buffer instance)]
            (reset! resp-buf []))
  request-id)

(defn get-pending-request
  "Get the promise for a pending request.

   Arguments:
     instance   - Instance map
     request-id - Request ID string

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
           ;; NOTE: No need to clear current-request-id - we use :active-request-id now
           true)
          false))

;; =============================================================================
;; Message Routing
;; =============================================================================

(defn send-and-await
  "Send a message to an instance and wait for response.

   This is the provider-agnostic message sending function.
   It dispatches to the provider-specific send-message implementation
   and handles correlation of async responses.

   Arguments:
     instance - Instance map from registry
     message  - Message content string

   Returns:
     Response map (provider-specific format)
     Or {:error true :message ...} on error/timeout."
  [instance message]
  (let [name (:name instance)
        request-id (registry/next-request-id name)
        p (promise)
        timeout-ms (:request-timeout-ms @config)]

    (log/log! {:level :debug
               :id    ::sending-message
               :msg   "Sending message to instance"
               :data  {:name name
                       :provider-type (:provider-type instance)
                       :request-id request-id}})

    ;; Register pending request
    (register-pending-request! instance request-id p)

    ;; Send message via provider protocol
    ;; Pass request-id via :active-request-id to avoid race conditions
    (try
     (when-not (proto/send-message (assoc instance :active-request-id request-id) message)
       (log/log! {:level :error
                  :id    ::send-failed
                  :msg   "Provider send-message returned false"
                  :data  {:name name :request-id request-id}})
       (complete-pending-request! instance request-id
                                  {:error true :message "Send failed"})
       (throw (ex-info "Failed to send message"
                       {:name name :provider-type (:provider-type instance)})))
     (catch Exception e
            (log/log! {:level :error
                       :id    ::send-error
                       :msg   "Error sending message"
                       :error e
                       :data  {:name name :request-id request-id}})
            (complete-pending-request! instance request-id
                                       {:error true :message (ex-message e)})
            (throw e)))

    ;; Wait for response with timeout
    (let [result (deref p timeout-ms {:error true :message "Timeout"})]
      (log/log! {:level :debug
                 :id    ::message-complete
                 :msg   "Message request completed"
                 :data  {:name name
                         :request-id request-id
                         :has-error (:error result false)}})
      result)))
