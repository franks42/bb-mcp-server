(ns nrepl.state.watchers
  "Watchers for async message processing - Phase 2b.2&3 (DUMB WATCHERS + RECEIVE)"
  (:require [nrepl.state.messages :as msg-state]
            [nrepl.state.connection :as conn-state]
            [nrepl.state.results :as results]
            [nrepl.client.operations :as nrepl-ops]
            [nrepl.client.messaging :as messaging]
            [bencode.core]
            [taoensso.trove :as log]))

;; =============================================================================
;; Send Queue Watcher - Phase 2b.2
;; =============================================================================

(defn- process-send-queue!
  "DUMB WATCHER: Process one READY-TO-SEND message from connection-specific queue.
   All formatting and validation was done at enqueue time.
   This just dequeues and sends - no logic, no adaptation."
  [connection-id]
  (when-let [ready-to-send (msg-state/dequeue-message! connection-id)]
    (let [{:keys [message-id connection message]} ready-to-send]
      (try
        ;; Update status to sending
        (msg-state/update-message-status! message-id :sending
                                          :sent-at (System/currentTimeMillis))

        ;; Fire-and-forget send - don't wait for responses
        (log/log! {:level :debug
                   :id ::send-fire-and-forget
                   :msg "Fire-and-forget sending READY message"
                   :data {:message-id message-id}})

        ;; Use fire-and-forget send (receive-watcher will handle responses)
        (nrepl-ops/send-message-fire-and-forget connection message)

        ;; Update status to sent (not waiting for response)
        (msg-state/update-message-status! message-id :sent
                                          :sent-at (System/currentTimeMillis))

        (log/log! {:level :debug
                   :id ::send-message-sent
                   :msg "READY message sent (fire-and-forget)"
                   :data {:message-id message-id}})

        (catch Exception e
          ;; Handle fire-and-forget send errors
          (let [error-msg (str "Failed to fire-and-forget send: "
                               (or (.getMessage e)
                                   (.toString e)
                                   "Unknown error"))]
            (msg-state/update-message-status! message-id :error
                                              :error error-msg)
            (results/deliver-error! message-id error-msg)
            (log/log! {:level :error
                       :id ::send-error
                       :msg "Fire-and-forget send error"
                       :data {:message-id message-id :error error-msg :exception (str e)}})))))))

(defn- send-queue-watcher
  "Watcher function that processes connection send queues when they change.
   Called whenever connection-message-queues atom changes."
  [_ _ old-state new-state]
  (doseq [[connection-id queue-state] new-state]
    (let [old-queue-state (get old-state connection-id)
          old-queue-count (if old-queue-state (count (:send-queue old-queue-state)) 0)
          new-queue-count (count (:send-queue queue-state))]
      ;; Only process if this connection's send queue has grown (new messages added)
      (when (> new-queue-count old-queue-count)
        (log/log! {:level :debug
                   :id ::send-queue-changed
                   :msg "Send queue changed, processing"
                   :data {:connection-id connection-id}})
        ;; Process all available messages in this connection's queue
        (while (> (count (:send-queue (get @msg-state/connection-message-queues connection-id))) 0)
          (process-send-queue! connection-id))))))

;; =============================================================================
;; Watcher Management
;; =============================================================================

(defn start-send-queue-watcher!
  "Start the send queue watcher to automatically process queued messages"
  []
  ;; Remove any existing watcher first to prevent duplicates
  (msg-state/remove-message-watcher :send-queue-watcher)
  (msg-state/add-message-watcher :send-queue-watcher send-queue-watcher)
  (log/log! {:level :info
             :id ::send-queue-watcher-started
             :msg "Send queue watcher started"}))

(defn stop-send-queue-watcher!
  "Stop the send queue watcher"
  []
  (msg-state/remove-message-watcher :send-queue-watcher)
  (log/log! {:level :info
             :id ::send-queue-watcher-stopped
             :msg "Send queue watcher stopped"}))

;; =============================================================================
;; Receive Watcher - Phase 2b.3 Implementation
;; =============================================================================

;; Forward declaration for recursive reference
(declare stop-receive-watcher!)

(defonce ^{:doc "Per-connection receive watcher state.
                Structure: {connection-id {:running boolean
                                          :thread Thread
                                          :connection connection-object}}"}
  receive-watcher-state
  (atom {}))

(defn- process-nrepl-response!
  "Process a single nREPL response and deliver to the appropriate promise.
   Accumulates responses per message-id and merges them when 'done' status received."
  [response]
  (let [message-id (:id response)]
    (log/log! {:level :debug
               :id ::receive-processing-response
               :msg "Processing response for message"
               :data {:message-id message-id}})

    (if message-id
      ;; Try to find the pending message
      (if-let [pending-msg (msg-state/get-pending-message message-id)]
        ;; Add this response to the accumulated responses for this message
        (let [current-responses (get pending-msg :accumulated-responses [])
              updated-responses (conj current-responses response)]

          ;; Update the pending message with accumulated responses
          (msg-state/update-message-status! message-id :partial-response
                                            :accumulated-responses updated-responses)

          ;; Check if this is a final response (has "done" status)
          (if (and (:status response)
                   (some #(= "done" %) (:status response)))
            ;; Final response - merge all accumulated responses and deliver
            (let [merged-response (#'messaging/merge-responses updated-responses)]
              (log/log! {:level :debug
                         :id ::receive-merging-responses
                         :msg "Merging responses"
                         :data {:message-id message-id
                                :response-count (count updated-responses)
                                :merged-response merged-response}})

              (msg-state/update-message-status! message-id :completed
                                                :completed-at (System/currentTimeMillis))
              (results/deliver-result! message-id {:status :success :response merged-response})
              (msg-state/remove-pending-message! message-id)
              (log/log! {:level :debug
                         :id ::receive-final-delivered
                         :msg "Final merged response delivered"
                         :data {:message-id message-id}}))

            ;; Partial response - just log and continue accumulating
            (log/log! {:level :debug
                       :id ::receive-accumulated-partial
                       :msg "Accumulated partial response"
                       :data {:message-id message-id :response-count (count updated-responses)}})))

        ;; No pending message found - orphaned response
        (log/log! {:level :warn
                   :id ::receive-orphaned-response
                   :msg "Orphaned response (no pending message)"
                   :data {:message-id message-id}}))

      ;; No message ID in response
      (log/log! {:level :warn
                 :id ::receive-missing-id
                 :msg "Response missing :id field"
                 :data {:response response}}))))

(defn- receive-loop!
  "Background receive loop that listens for nREPL responses.

   Uses surgically extracted bencode reading logic from messaging.clj
   instead of duplicating the conversion logic."
  [connection-id connection]
  (log/log! {:level :info
             :id ::receive-loop-starting
             :msg "Starting receive loop"
             :data {:connection-id connection-id}})

  (try
    (let [input-stream (:in connection)]
      (while (get-in @receive-watcher-state [connection-id :running])
        (try
          ;; USING EXTRACTED LOGIC: Use messaging/convert-bencode-response (private, but available via namespace)
          ;; Read bencode response from nREPL
          (let [raw-response (bencode.core/read-bencode input-stream)
                ;; Use the same conversion logic as the extracted result-processing-async
                converted-response (#'messaging/convert-bencode-response raw-response)]
            (log/log! {:level :debug
                       :id ::receive-raw-response
                       :msg "Raw response received"
                       :data {:response converted-response}})

            ;; Process the response
            (process-nrepl-response! converted-response))

          (catch java.io.EOFException _e
            (log/log! {:level :info
                       :id ::receive-eof
                       :msg "EOF - connection closed"
                       :data {:connection-id connection-id}})
            (swap! receive-watcher-state assoc-in [connection-id :running] false))

          (catch Exception e
            (log/log! {:level :error
                       :id ::receive-read-error
                       :msg "Error reading response"
                       :data {:connection-id connection-id :error (.getMessage e) :exception (str e)}})
            ;; Continue the loop unless it's a fatal error
            (Thread/sleep 100)))))

    (catch Exception e
      (log/log! {:level :error
                 :id ::receive-fatal-error
                 :msg "Fatal error in receive loop"
                 :data {:connection-id connection-id :error (.getMessage e)}})
      (swap! receive-watcher-state assoc-in [connection-id :running] false))

    (finally
      (log/log! {:level :info
                 :id ::receive-loop-terminated
                 :msg "Receive loop terminated"
                 :data {:connection-id connection-id}}))))

(defn start-receive-watcher!
  "Start the receive watcher for a specific connection.
   Runs in a background thread and listens for incoming responses."
  [connection-id]
  ;; Always stop any existing watcher for this connection first
  (when (get-in @receive-watcher-state [connection-id :running])
    (log/log! {:level :debug
               :id ::receive-stopping-existing
               :msg "Stopping existing watcher for connection"
               :data {:connection-id connection-id}})
    (stop-receive-watcher! connection-id))

  (if-let [raw-connection (conn-state/get-connection-by-id connection-id)]
    (if-let [connection (msg-state/adapt-connection-for-messaging raw-connection)]
      (do
        ;; Start the background receive thread for this connection
        (swap! receive-watcher-state assoc connection-id {:running true :connection connection :thread nil})
        (let [receive-thread (Thread. #(receive-loop! connection-id connection))]
          (.setDaemon receive-thread true)  ; Daemon thread for clean shutdown
          (.setName receive-thread (str "nREPL-Receive-Watcher-" connection-id))
          (.start receive-thread)
          (swap! receive-watcher-state assoc-in [connection-id :thread] receive-thread)
          (log/log! {:level :info
                     :id ::receive-watcher-started
                     :msg "Started background receive thread"
                     :data {:connection-id connection-id}})))

      (log/log! {:level :error
                 :id ::receive-adapt-failed
                 :msg "Failed to adapt connection"
                 :data {:connection-id connection-id}}))

    (log/log! {:level :error
               :id ::receive-connection-not-found
               :msg "Connection not found"
               :data {:connection-id connection-id}})))

(defn stop-receive-watcher!
  "Stop the receive watcher background thread for a specific connection"
  [connection-id]
  (when (get-in @receive-watcher-state [connection-id :running])
    (log/log! {:level :info
               :id ::receive-stopping
               :msg "Stopping background receive thread"
               :data {:connection-id connection-id}})

    ;; Signal stop
    (swap! receive-watcher-state assoc-in [connection-id :running] false)

    ;; Wait for thread to finish (with timeout)
    (when-let [thread (get-in @receive-watcher-state [connection-id :thread])]
      (try
        (.join thread 5000)  ; Wait up to 5 seconds
        (when (.isAlive thread)
          (log/log! {:level :warn
                     :id ::receive-thread-interrupt
                     :msg "Thread didn't stop gracefully, interrupting"
                     :data {:connection-id connection-id}})
          (.interrupt thread))
        (catch Exception e
          (log/log! {:level :error
                     :id ::receive-stop-error
                     :msg "Error stopping thread"
                     :data {:connection-id connection-id :error (.getMessage e)}}))))

    ;; Remove connection from state
    (swap! receive-watcher-state dissoc connection-id)
    (log/log! {:level :info
               :id ::receive-watcher-stopped
               :msg "Stopped watcher for connection"
               :data {:connection-id connection-id}})))

(defn stop-all-receive-watchers!
  "Stop all receive watchers for all connections"
  []
  (let [connection-ids (keys @receive-watcher-state)]
    (doseq [connection-id connection-ids]
      (stop-receive-watcher! connection-id))
    (log/log! {:level :info
               :id ::receive-all-stopped
               :msg "Stopped all receive watchers"})))

;; =============================================================================
;; Combined Watcher Management
;; =============================================================================

(defn start-connection-watchers!
  "Start watchers for a specific connection (typically called after successful connection)"
  [connection-id]
  (start-receive-watcher! connection-id)
  (log/log! {:level :info
             :id ::connection-watchers-started
             :msg "Started watchers for connection"
             :data {:connection-id connection-id}}))

(defn stop-connection-watchers!
  "Stop watchers for a specific connection (typically called before disconnection)"
  [connection-id]
  (stop-receive-watcher! connection-id)
  (log/log! {:level :info
             :id ::connection-watchers-stopped
             :msg "Stopped watchers for connection"
             :data {:connection-id connection-id}}))

(defn start-all-watchers!
  "Start all global message processing watchers.
   Note: Only starts send-queue-watcher immediately.
   Per-connection receive-watchers are started when connections are established."
  []
  (start-send-queue-watcher!)
  (log/log! {:level :info
             :id ::global-watchers-started
             :msg "Started global send queue watcher"}))

(defn stop-all-watchers!
  "Stop all message processing watchers"
  []
  (stop-send-queue-watcher!)
  (stop-all-receive-watchers!)
  (log/log! {:level :info
             :id ::all-watchers-stopped
             :msg "Stopped all watchers"}))
