(ns nrepl.state.messages
    "Message queue state management for async nREPL operations"
    (:require [nrepl.utils.uuid-v7 :as uuid]
              [nrepl.state.results :as results]
              [nrepl.state.connection :as conn-state]
              [taoensso.trove :as log]))

;; =============================================================================
;; Per-Connection Message Queue State Atom
;; =============================================================================

(def connection-message-queues
     "Per-connection message send queues for async operations.
   
   Structure:
   {connection-id {:send-queue PersistentQueue ; FIFO queue of messages waiting to be sent
                   :pending-messages {}         ; Map of message-id -> detailed message record
                   :message-counter 0}          ; Counter for debugging/metrics
    ...}"
     (atom {}))

;; =============================================================================
;; Connection Adapter - moved from watchers for cleaner separation
;; =============================================================================

(defn adapt-connection-for-messaging
  "Convert connection state format to messaging client format.
   Wraps socket InputStream with PushbackInputStream for bencode compatibility.
   Returns nil for browser connections (they use WebSocket, not socket)."
  [connection]
  (when (and connection (not= :browser (:type connection)))
    (when-let [socket (:socket connection)]
              {:out (.getOutputStream socket)
               :in (java.io.PushbackInputStream. (.getInputStream socket))
               :id (:connection-id connection)
               :socket socket})))

;; =============================================================================
;; Browser Connection Adapter Registry
;; =============================================================================

;; Registered function to send messages to browser connections.
;; Set by sente-browser module on startup.
;; Signature: (fn [sente-conn-id event] ...)
(defonce ^:private !browser-send-fn (atom nil))

(defn register-browser-send-fn!
  "Register a function for sending messages to browser connections.
   Called by sente-browser module on startup."
  [send-fn]
  (reset! !browser-send-fn send-fn)
  (log/log! {:level :info
             :id ::browser-send-fn-registered
             :msg "Browser send function registered"}))

(defn unregister-browser-send-fn!
  "Unregister the browser send function.
   Called by sente-browser module on shutdown."
  []
  (reset! !browser-send-fn nil)
  (log/log! {:level :info
             :id ::browser-send-fn-unregistered
             :msg "Browser send function unregistered"}))

(defn get-browser-send-fn
  "Get the registered browser send function, or nil if not registered."
  []
  @!browser-send-fn)

;; =============================================================================
;; Connection Queue Management
;; =============================================================================

(defn ensure-connection-queue!
  "Ensure a message queue exists for the given connection-id.
   Creates an empty queue structure if it doesn't exist."
  [connection-id]
  (swap! connection-message-queues
         (fn [queues]
           (if (contains? queues connection-id)
             queues
             (assoc queues connection-id
                    {:send-queue clojure.lang.PersistentQueue/EMPTY
                     :pending-messages {}
                     :message-counter 0}))))
  connection-id)

(defn cleanup-connection-queue!
  "Remove message queue for a connection that has been closed."
  [connection-id]
  (swap! connection-message-queues dissoc connection-id)
  (log/log! {:level :debug
             :id ::queue-cleaned-up
             :msg "Cleaned up message queue for connection"
             :data {:connection-id connection-id}}))

;; =============================================================================
;; Queue Operations - Phase 4 Multi-Connection Implementation
;; =============================================================================

(defn- enqueue-message-internal!
  "Internal helper to add a message to the queue.
   Called after connection-type-specific setup."
  [connection-id message-id ready-to-send timestamp]
  ;; Ensure connection queue exists
  (ensure-connection-queue! connection-id)

  ;; Create result promise first
  (results/create-result-promise! connection-id message-id)

  ;; Add to connection-specific queue and pending messages
  (swap! connection-message-queues
         (fn [queues]
           (update-in queues [connection-id]
                      (fn [queue-state]
                        (-> queue-state
                            (update :send-queue conj ready-to-send)
                            (assoc-in [:pending-messages message-id]
                                      (assoc ready-to-send
                                             :created-at timestamp
                                             :status :pending))
                            (update :message-counter inc))))))

  ;; Log for debugging - elevated to :info for tracing
  (log/log! {:level :info
             :id ::message-enqueued
             :msg "Enqueued message"
             :data {:message-id message-id
                    :connection-id connection-id
                    :type (:type ready-to-send)
                    :status :pending}})
  message-id)

(defn enqueue-message!
  "Add a READY-TO-SEND message to the send queue with pre-formatted connection.
   This makes watchers simple - they just dequeue and send without any logic.

   Handles both socket connections (nREPL servers) and browser connections (sente).

   Args:
     connection-id - Connection ID to enqueue message for
     message - nREPL message map to send

   Returns:
     message-id - UUID v7 string for tracking this message
     nil - if connection not found or connection formatting fails"
  [connection-id message]
  ;; Get and validate specific connection by connection-id
  (if-let [raw-connection (conn-state/get-connection-by-id connection-id)]
          (let [message-id (uuid/uuid-v7-with-tag :tag "msg")
                timestamp (System/currentTimeMillis)
                message-with-id (assoc message :id message-id)]

      ;; Branch based on connection type
            (if (= :browser (:type raw-connection))
        ;; Browser connection - use sente WebSocket
              (let [sente-conn-id (:sente-conn-id raw-connection)
                    ready-to-send {:message-id message-id
                                   :connection-id connection-id
                                   :type :browser
                                   :sente-conn-id sente-conn-id
                                   :message message-with-id
                                   :timestamp timestamp
                                   :attempts 0
                                   :status :pending}]
                (log/log! {:level :info
                           :id ::browser-enqueue-attempt
                           :msg "Attempting to enqueue browser message"
                           :data {:connection-id connection-id
                                  :sente-conn-id sente-conn-id
                                  :message-id message-id}})
                (if sente-conn-id
                  (enqueue-message-internal! connection-id message-id ready-to-send timestamp)
                  (do
                   (log/log! {:level :error
                              :id ::browser-missing-sente-id
                              :msg "Browser connection missing sente-conn-id"
                              :data {:connection-id connection-id}})
                   nil)))

        ;; Socket connection - use nREPL bencode protocol
              (if-let [formatted-connection (adapt-connection-for-messaging raw-connection)]
                      (let [ready-to-send {:message-id message-id
                                           :connection-id connection-id
                                           :type :socket
                                           :connection formatted-connection
                                           :message message-with-id
                                           :timestamp timestamp
                                           :attempts 0
                                           :status :pending}]
                        (enqueue-message-internal! connection-id message-id ready-to-send timestamp))

          ;; Connection adaptation failed
                      (do
                       (log/log! {:level :error
                                  :id ::connection-adapt-failed
                                  :msg "Failed to adapt connection for message"
                                  :data {:connection-id connection-id}})
                       nil))))

    ;; Connection not found
          (do
           (log/log! {:level :error
                      :id ::connection-not-found
                      :msg "Connection not found for enqueue"
                      :data {:connection-id connection-id}})
           nil)))

(defn dequeue-message!
  "Remove and return the next message from the connection-specific send queue (FIFO).
   Returns nil if queue is empty."
  [connection-id]
  (let [result (atom nil)]
    (swap! connection-message-queues
           (fn [queues]
             (if-let [queue-state (get queues connection-id)]
                     (if-let [queue-entry (peek (:send-queue queue-state))]  ; peek gets first from PersistentQueue
                             (do
                              (reset! result queue-entry)
                              (update-in queues [connection-id :send-queue] pop))  ; pop removes first from PersistentQueue
                             queues)
                     queues)))
    @result))

(defn find-message-connection
  "Find which connection a message-id belongs to.
   Returns connection-id or nil if message not found."
  [message-id]
  (let [queues @connection-message-queues]
    (->> queues
         (filter (fn [[_conn-id queue-state]]
                   (contains? (:pending-messages queue-state) message-id)))
         first
         first)))

(defn get-pending-message
  "Get a pending message by ID without removing it."
  [message-id]
  (when-let [connection-id (find-message-connection message-id)]
            (get-in @connection-message-queues [connection-id :pending-messages message-id])))

(defn remove-pending-message!
  "Remove a pending message from the tracking map."
  [message-id]
  (when-let [connection-id (find-message-connection message-id)]
            (swap! connection-message-queues update-in [connection-id :pending-messages] dissoc message-id)))

(defn update-message-status!
  "Update the status of a pending message.
   Status can be :pending, :sending, :sent, :partial, :done, :failed, :timeout, :error"
  [message-id new-status & {:keys [error bencode-sent sent-at completed-at accumulated-responses]}]
  (when-let [connection-id (find-message-connection message-id)]
            (swap! connection-message-queues
                   (fn [queues]
                     (if-let [_msg (get-in queues [connection-id :pending-messages message-id])]
                             (update-in queues [connection-id :pending-messages message-id]
                                        (fn [entry]
                                          (cond-> (assoc entry :status new-status)
                                                  error (assoc :error error)
                                                  bencode-sent (assoc :bencode-sent bencode-sent)
                                                  sent-at (assoc :sent-at sent-at)
                                                  completed-at (assoc :completed-at completed-at)
                                                  accumulated-responses (assoc :accumulated-responses accumulated-responses))))
                             queues)))
    ;; Log status change
            (log/log! {:level :debug
                       :id ::message-status-updated
                       :msg "Message status updated"
                       :data {:message-id message-id :new-status new-status :error error}})))

;; =============================================================================
;; Watcher Management
;; =============================================================================

(defn add-message-watcher
  "Add a watcher to the connection message queues atom"
  [key watch-fn]
  (add-watch connection-message-queues key watch-fn))

(defn remove-message-watcher
  "Remove a watcher from the connection message queues atom"
  [key]
  (remove-watch connection-message-queues key))

;; =============================================================================
;; Cleanup Support
;; =============================================================================

(defn clear-all-messages!
  "Clear all message queues and pending messages - used during complete system cleanup"
  []
  (reset! connection-message-queues {})
  (log/log! {:level :info
             :id ::all-messages-cleared
             :msg "Cleared all connection message queues and pending messages"}))

(defn clear-connection-messages!
  "Clear message queues for a specific connection"
  [connection-id]
  (swap! connection-message-queues
         (fn [queues]
           (if (contains? queues connection-id)
             (assoc-in queues [connection-id]
                       {:send-queue clojure.lang.PersistentQueue/EMPTY
                        :pending-messages {}
                        :message-counter 0})
             queues)))
  (log/log! {:level :debug
             :id ::connection-messages-cleared
             :msg "Cleared message queue for connection"
             :data {:connection-id connection-id}}))

;; =============================================================================
;; Debug Support
;; =============================================================================

(defn get-message-summary
  "Get a summary of message queue state for debugging"
  []
  (let [queues @connection-message-queues]
    {:connection-count (count queues)
     :connections (into {} (map (fn [[conn-id queue-state]]
                                  [conn-id {:queue-length (count (:send-queue queue-state))
                                            :pending-count (count (:pending-messages queue-state))
                                            :message-counter (:message-counter queue-state)}])
                                queues))
     :total-pending (reduce + (map (fn [[_conn-id queue-state]]
                                     (count (:pending-messages queue-state)))
                                   queues))
     :total-queued (reduce + (map (fn [[_conn-id queue-state]]
                                    (count (:send-queue queue-state)))
                                  queues))
     :watchers (keys (.getWatches connection-message-queues))}))

;; =============================================================================
;; Backward Compatibility Support for Single Connection (Phase 4)
;; =============================================================================

(defn enqueue-message-single-connection!
  "Backward compatible function that enqueues message for active connection.
   Used during Phase 4 transition to maintain compatibility with existing callers."
  [message]
  (if-let [active-connection (conn-state/get-active-connection)]
          (enqueue-message! (:connection-id active-connection) message)
          (do
           (log/log! {:level :error
                      :id ::no-active-connection
                      :msg "No active connection available for message"})
           nil)))