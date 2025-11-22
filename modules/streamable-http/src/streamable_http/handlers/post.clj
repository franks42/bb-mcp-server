(ns streamable-http.handlers.post
    "POST /mcp handler for JSON-RPC messages.

   Handles:
   - Initialize requests (creates new session)
   - Regular JSON-RPC requests (requires valid session)
   - Batch requests (array of messages)"
    (:require [streamable-http.session :as session]
              [streamable-http.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private error-codes
     {:parse-error -32700
      :invalid-request -32600
      :method-not-found -32601
      :invalid-params -32602
      :internal-error -32603
      :invalid-session -32003})

;; =============================================================================
;; Request Validation
;; =============================================================================

(defn- valid-json-rpc?
  "Check if request is valid JSON-RPC 2.0."
  [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (string? (:method msg))))

(defn- initialize-request?
  "Check if this is an initialize request."
  [msg]
  (= "initialize" (:method msg)))

(defn- notification?
  "Check if message is a notification (no id)."
  [msg]
  (not (contains? msg :id)))

;; =============================================================================
;; Response Helpers
;; =============================================================================

(defn- json-response
  "Create JSON HTTP response."
  [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (util/generate-json body)})

(defn- error-response
  "Create JSON-RPC error response."
  [id code message & [data]]
  (json-response 400
                 (util/json-rpc-error {:id id
                                       :code code
                                       :message message
                                       :data data})))

(defn- safe-call-handler
  "Call json-rpc-handler with exception handling.
   Returns [response nil] on success or [nil exception] on error."
  [json-rpc-handler msg]
  (try
   [(json-rpc-handler msg) nil]
   (catch Exception e
          (log/log! {:level :error
                     :id    ::handler-exception
                     :msg   "Exception in JSON-RPC handler"
                     :data  {:method (:method msg)
                             :error (.getMessage e)}
                     :error e})
          [nil e])))

;; =============================================================================
;; Handler Implementation
;; =============================================================================

(defn- handle-initialize
  "Handle initialize request - create new session."
  [_request msg json-rpc-handler]
  (log/log! {:level :info
             :id    ::handle-initialize
             :msg   "Processing initialize request"
             :data  {:client-info (get-in msg [:params :clientInfo])}})
  (let [start (util/current-time-ms)
        [response err] (safe-call-handler json-rpc-handler msg)]
    (if err
      ;; Handler threw an exception
      (error-response (:id msg)
                      (:internal-error error-codes)
                      "Internal error during initialize"
                      {:error (.getMessage err)})
      ;; Success - create session
      (let [session (session/create-session! (:params msg))
            duration (util/elapsed-ms start)]
        (log/log! {:level :info
                   :id    ::initialize-complete
                   :msg   "Initialize complete"
                   :data  {:session-id (:session-id session)
                           :duration-ms duration}})
        (json-response 200
                       response
                       {"Mcp-Session-Id" (:session-id session)})))))

(defn- handle-regular-request
  "Handle regular JSON-RPC request with valid session."
  [_request msg json-rpc-handler session-id]
  (log/log! {:level :debug
             :id    ::handle-request
             :msg   "Processing JSON-RPC request"
             :data  {:method (:method msg)
                     :session-id session-id}})
  (session/touch-session! session-id)
  ;; Notifications don't get responses
  (if (notification? msg)
    (do
     (safe-call-handler json-rpc-handler msg) ; fire and forget, log errors
     {:status 202
      :headers {"Content-Type" "application/json"}
      :body ""})
    ;; Regular request - need response
    (let [start (util/current-time-ms)
          [response err] (safe-call-handler json-rpc-handler msg)
          duration (util/elapsed-ms start)]
      (log/log! {:level :debug
                 :id    ::request-complete
                 :msg   "Request complete"
                 :data  {:method (:method msg)
                         :duration-ms duration}})
      (if err
        (error-response (:id msg)
                        (:internal-error error-codes)
                        "Internal error"
                        {:error (.getMessage err)})
        (json-response 200 response)))))

(defn- process-batch-msg
  "Process a single message in a batch, handling exceptions."
  [json-rpc-handler msg]
  (let [[response err] (safe-call-handler json-rpc-handler msg)]
    (if err
      ;; Return error response for this message
      (util/json-rpc-error {:id (:id msg)
                            :code (:internal-error error-codes)
                            :message "Internal error"
                            :data {:error (.getMessage err)}})
      response)))

(defn- handle-batch-request
  "Handle batch of JSON-RPC requests."
  [_request msgs json-rpc-handler session-id]
  (log/log! {:level :debug
             :id    ::handle-batch
             :msg   "Processing batch request"
             :data  {:count (count msgs)
                     :session-id session-id}})
  (session/touch-session! session-id)
  ;; Process notifications (fire and forget)
  (doseq [msg (filter notification? msgs)]
         (safe-call-handler json-rpc-handler msg))
  ;; Process requests that need responses
  (let [responses (->> msgs
                       (remove notification?)
                       (map #(process-batch-msg json-rpc-handler %)))]
    (if (empty? responses)
      {:status 202
       :headers {"Content-Type" "application/json"}
       :body ""}
      (json-response 200 responses))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn handle-post
  "Handle POST /mcp request.

   Arguments:
     request         - Ring request map
     json-rpc-handler - Function (fn [json-rpc-msg] -> json-rpc-response)

   Returns:
     Ring response map."
  [request json-rpc-handler]
  (let [session-id (util/get-header request "mcp-session-id")
        body (some-> request :body slurp)
        parsed (util/parse-json body)]

    (cond
      ;; Parse error
      (nil? parsed)
      (do
       (log/log! {:level :warn
                  :id    ::parse-error
                  :msg   "Failed to parse request body"})
       (error-response nil
                       (:parse-error error-codes)
                       "Parse error"))

      ;; Batch request (array)
      (vector? parsed)
      (cond
        ;; Empty batch
        (empty? parsed)
        (error-response nil
                        (:invalid-request error-codes)
                        "Empty batch")

        ;; Batch with initialize not allowed
        (some initialize-request? parsed)
        (error-response nil
                        (:invalid-request error-codes)
                        "Initialize not allowed in batch")

        ;; No session for batch
        (not (session/valid-session? session-id))
        (error-response nil
                        (:invalid-session error-codes)
                        "Invalid or missing session")

        ;; Valid batch
        :else
        (handle-batch-request request parsed json-rpc-handler session-id))

      ;; Single request - invalid JSON-RPC
      (not (valid-json-rpc? parsed))
      (error-response (:id parsed)
                      (:invalid-request error-codes)
                      "Invalid JSON-RPC 2.0 request")

      ;; Initialize request
      (initialize-request? parsed)
      (if session-id
        (error-response (:id parsed)
                        (:invalid-request error-codes)
                        "Session already initialized")
        (handle-initialize request parsed json-rpc-handler))

      ;; Regular request - no session
      (not (session/valid-session? session-id))
      (error-response (:id parsed)
                      (:invalid-session error-codes)
                      "Invalid or missing session")

      ;; Regular request - valid session
      :else
      (handle-regular-request request parsed json-rpc-handler session-id))))
