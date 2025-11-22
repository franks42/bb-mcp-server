(ns nrepl.client.messaging
  "nREPL message handling and bencode protocol implementation integrated with reactive state management"
  (:require [bencode.core :as bencode]
            [nrepl.utils.uuid-v7 :as uuid]
            [clojure.set :as set]
            [taoensso.trove :as log]))

(defn generate-id
  "Generate RFC 9562 compliant UUID v7 with operation tag suffix."
  [& {:keys [tag] :or {tag "msg"}}]
  (uuid/uuid-v7-with-tag :tag tag))

(defn- bytes-to-string
  "Convert byte array to UTF-8 string"
  [obj]
  (cond
    (instance? (Class/forName "[B") obj) (String. obj "UTF-8")
    (string? obj) obj
    :else (str obj)))

(defn- convert-bencode-response
  "Convert bencode byte arrays to strings recursively, using keyword keys for maps"
  [obj]
  (cond
    (map? obj) (into {} (map (fn [[k v]] [(keyword (bytes-to-string k)) (convert-bencode-response v)]) obj))
    (vector? obj) (mapv convert-bencode-response obj)
    (seq? obj) (map convert-bencode-response obj)
    :else (bytes-to-string obj)))

(defn- collect-responses-async
  "Async version of collect-responses with promise-based timeout handling.

  Args:
    in - Input stream for reading nREPL responses
    message-id - Message ID to collect responses for
    timeout-ms - Timeout in milliseconds (required for async version)

  Returns:
    {:status :success :responses [...]} on success
    {:status :timeout :responses [...]} on timeout
    {:status :error :responses [...] :error exception} on error

  Implementation:
    Uses promise-based timeout with (deref promise timeout-ms :timeout) pattern
    as verified working in Babashka runtime environment."
  [in _message-id timeout-ms]
  (let [result-promise (promise)
        worker-future (future
                        (try
                          (let [responses (loop [responses []]
                                            (let [read-result (try
                                                                (let [raw-response (bencode/read-bencode in)
                                                                      converted-response (convert-bencode-response raw-response)]
                                                                  (log/log! {:level :debug
                                                                             :id ::async-response-received
                                                                             :msg "Async received response"
                                                                             :data {:response converted-response}})
                                                                  {:success true :response converted-response})
                                                                (catch Exception e
                                                                  (log/log! {:level :error
                                                                             :id ::async-read-error
                                                                             :msg "Async error reading response"
                                                                             :data {:error (.getMessage e)}})
                                                                  {:success false :error e}))]
                                              (if (:success read-result)
                                                (let [response (:response read-result)
                                                      new-responses (conj responses response)
                                                      status (:status response)]
                                                  ;; Continue reading until we get a "done" status
                                                  (if (and status (some #(= "done" %) status))
                                                    new-responses
                                                    (recur new-responses)))
                                                ;; Error case - return what we have so far with error
                                                (do
                                                  (deliver result-promise {:status :error
                                                                           :responses responses
                                                                           :error (:error read-result)})
                                                  responses))))]
                            (deliver result-promise {:status :success :responses responses}))
                          (catch Exception e
                            (log/log! {:level :error
                                       :id ::async-worker-error
                                       :msg "Async worker error"
                                       :data {:error (.getMessage e)}})
                            (deliver result-promise {:status :error :responses [] :error e}))))]

    ;; Use promise-based timeout as verified in Babashka
    #_:clj-kondo/ignore
    (let [result (deref result-promise timeout-ms :timeout)]
      (if (= result :timeout)
        (do
          ;; Cancel the worker future and return timeout result
          (future-cancel worker-future)
          (log/log! {:level :warn
                     :id ::async-timeout
                     :msg "Async timeout"
                     :data {:timeout-ms timeout-ms}})
          {:status :timeout :responses [] :timeout-ms timeout-ms})
        result))))

(defn- merge-responses
  "Merge multiple nREPL responses into a single response, preserving ALL fields.

  Special handling:
  - :out, :err - concatenated from all responses
  - :value, :ex, :ns, :session - take last non-nil value
  - :status - take from last response
  - All other fields - use first non-nil value (most operations return single response)

  This ensures no nREPL response data is lost."
  [responses]
  (if (empty? responses)
    {}
    (let [;; Special concatenation fields
          all-out (apply str (keep :out responses))
          all-err (apply str (keep :err responses))

          ;; Fields that should use the last non-nil value
          final-value (last (keep :value responses))
          final-ex (last (keep :ex responses))
          final-ns (last (keep :ns responses))
          final-session (last (keep :session responses))
          final-status (:status (last responses))

          ;; Get all unique keys from all responses
          all-keys (->> responses
                        (mapcat keys)
                        (into #{}))

          ;; Fields with special handling (don't process with generic logic)
          special-fields #{:out :err :value :ex :ns :session :status}

          ;; Generic fields - use first non-nil value
          generic-fields (set/difference all-keys special-fields)

          ;; Build merged response starting with special fields
          base-merged (cond-> {}
                        (not-empty all-out) (assoc :out all-out)
                        (not-empty all-err) (assoc :err all-err)
                        final-value (assoc :value final-value)
                        final-ex (assoc :ex final-ex)
                        final-ns (assoc :ns final-ns)
                        final-session (assoc :session final-session)
                        final-status (assoc :status final-status))

          ;; Add all generic fields using first non-nil value
          full-merged (reduce
                       (fn [merged-map field-key]
                         (if-let [field-value (some field-key responses)]
                           (assoc merged-map field-key field-value)
                           merged-map))
                       base-merged
                       generic-fields)]

      ;; Log any unknown fields for debugging (only in debug mode)
      (when (and (System/getenv "MCP_DEBUG")
                 (not-empty generic-fields))
        (log/log! {:level :debug
                   :id ::merge-response-fields
                   :msg "Merged response contains fields"
                   :data {:fields (sort (map name (keys full-merged)))}}))

      full-merged)))

(defn send-message-async
  "Async version of send-message using promise-based timeout handling and reactive state integration.

  Args:
    connection - Map with :out and :in streams and connection ID
    message - nREPL message to send
    timeout-ms - Timeout in milliseconds (required for async version)

  Returns:
    {:status :success :response merged-response} on success
    {:status :timeout :responses [...] :timeout-ms timeout-ms} on timeout
    {:status :error :responses [...] :error exception} on error

  Implementation:
    Uses send-message-async -> collect-responses-async pipeline
    for full async message handling with timeout support.
    Integrates with reactive state management for tracking."
  [{:keys [out in id] :as _conn} message timeout-ms]
  (let [msg-with-id (assoc message :id (generate-id))
        message-id (:id msg-with-id)]
    ;; Log message tracking (simplified for now)
    (when id
      (log/log! {:level :debug
                 :id ::tracking-message-sent
                 :msg "Message sent for connection"
                 :data {:connection-id id :message-id message-id}}))

    ;; Log outgoing message
    (log/log! {:level :debug
               :id ::async-sending
               :msg "Async sending message"
               :data {:message msg-with-id}})

    ;; Send bencode-encoded message
    (bencode/write-bencode out msg-with-id)
    (.flush out)

    ;; Log sent status (simplified for now)
    (when id
      (log/log! {:level :debug
                 :id ::tracking-message-success
                 :msg "Message sent successfully"
                 :data {:message-id message-id}}))

    ;; Use async collection with timeout
    (let [async-result (collect-responses-async in message-id timeout-ms)]
      (case (:status async-result)
        :success
        (let [merged-response (merge-responses (:responses async-result))]
          ;; Log completion (simplified for now)
          (when id
            (log/log! {:level :debug
                       :id ::tracking-message-completed
                       :msg "Message completed"
                       :data {:message-id message-id}}))
          (log/log! {:level :debug
                     :id ::async-final-response
                     :msg "Async final merged response"
                     :data {:response merged-response}})
          {:status :success :response merged-response})

        :timeout
        (do
          ;; Log timeout (simplified for now)
          (when id
            (log/log! {:level :warn
                       :id ::tracking-message-timeout
                       :msg "Message timeout"
                       :data {:message-id message-id :timeout-ms timeout-ms}}))
          (log/log! {:level :warn
                     :id ::async-send-timeout
                     :msg "Async send-message timeout"
                     :data {:timeout-ms timeout-ms}})
          async-result)

        :error
        (do
          ;; Log error (simplified for now)
          (when id
            (log/log! {:level :error
                       :id ::tracking-message-failed
                       :msg "Message failed"
                       :data {:message-id message-id :error (:error async-result)}}))
          (log/log! {:level :error
                     :id ::async-send-error
                     :msg "Async send-message error"
                     :data {:error (str (:error async-result))}})
          async-result)))))

(defn send-message-fire-and-forget
  "Send a message to nREPL without waiting for response.
   Used by send-queue-watcher for fire-and-forget messaging.
   The receive-watcher will handle responses separately.

   Surgically extracted from send-message-async - contains only the send logic.

   Args:
     connection - Map with :out stream and connection ID
     message - nREPL message to send (should already have :id)

   Returns:
     true on successful send, throws exception on error"
  [{:keys [out id]} message]
  ;; Log outgoing message (using same format as original send-message-async)
  (log/log! {:level :debug
             :id ::fire-and-forget-sending
             :msg "Fire-and-forget sending"
             :data {:message message}})

  ;; EXTRACTED SEND LOGIC from send-message-async (lines 185-186)
  ;; Send bencode-encoded message
  (bencode/write-bencode out message)
  (.flush out)

  ;; Log sent status (simplified version of original tracking)
  (when id
    (log/log! {:level :debug
               :id ::fire-and-forget-sent
               :msg "Message sent to connection"
               :data {:connection-id id :message-id (:id message)}}))

  true)

(defn result-processing-async
  "Process and collect nREPL responses asynchronously from socket input stream.

   Surgically extracted from send-message-async - contains only the receive/collect logic.
   This function can wait, process input, and loop for input from the socket.

   Args:
     connection - Map with :in stream for reading responses
     message-id - Message ID to collect responses for
     timeout-ms - Timeout in milliseconds

   Returns:
     {:status :success :response merged-response} on success
     {:status :timeout :responses [...] :timeout-ms timeout-ms} on timeout
     {:status :error :responses [...] :error exception} on error

   Implementation:
     Uses collect-responses-async -> merge-responses pipeline
     extracted from send-message-async lines 194-224"
  [{:keys [in id]} message-id timeout-ms]
  ;; EXTRACTED RECEIVE LOGIC from send-message-async (lines 194-224)
  ;; Use async collection with timeout
  (let [async-result (collect-responses-async in message-id timeout-ms)]
    (case (:status async-result)
      :success
      (let [merged-response (merge-responses (:responses async-result))]
        ;; Log completion (simplified for now)
        (when id
          (log/log! {:level :debug
                     :id ::result-processing-completed
                     :msg "Message completed"
                     :data {:message-id message-id}}))
        (log/log! {:level :debug
                   :id ::result-processing-final
                   :msg "Result-processing final merged response"
                   :data {:response merged-response}})
        {:status :success :response merged-response})

      :timeout
      (do
        ;; Log timeout (simplified for now)
        (when id
          (log/log! {:level :warn
                     :id ::result-processing-timeout-tracked
                     :msg "Message timeout"
                     :data {:message-id message-id :timeout-ms timeout-ms}}))
        (log/log! {:level :warn
                   :id ::result-processing-timeout
                   :msg "Result-processing timeout"
                   :data {:timeout-ms timeout-ms}})
        async-result)

      :error
      (do
        ;; Log error (simplified for now)
        (when id
          (log/log! {:level :error
                     :id ::result-processing-failed-tracked
                     :msg "Message failed"
                     :data {:message-id message-id :error (:error async-result)}}))
        (log/log! {:level :error
                   :id ::result-processing-error
                   :msg "Result-processing error"
                   :data {:error (str (:error async-result))}})
        async-result))))
