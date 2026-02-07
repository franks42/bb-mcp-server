(ns message-bus.core
    "Simple message bus for AI expert communication.

   Uses atoms for state management and promises for request/response.
   Designed for debuggability over performance.

   Key features:
   - Pub/Sub messaging with topic-based routing
   - Request/Response with timeout via Global Response Router
   - Message logging for debugging
   - Thread-safe with standard Clojure concurrency primitives"
    (:require [com.github.franks42.uuidv7.core :as uuidv7]
              [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Map of topic -> set of handler functions.
;; {topic #{handler-fn ...}}
(defonce ^:private subscribers (atom {}))

;; Global Response Router: map of request-id -> promise.
;; Used by ask for O(1) response correlation.
;; {request-id promise}
(defonce ^:private pending-responses (atom {}))

;; Bounded queue of recent messages for debugging.
(defonce ^:private message-log (atom clojure.lang.PersistentQueue/EMPTY))

;; Maximum messages to keep in log.
(def ^:private max-log-size 1000)

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- generate-id
  "Generate a unique message/request ID."
  []
  (str (uuidv7/uuidv7)))

(defn- enrich-message
  "Add metadata to a message."
  [topic msg]
  (assoc msg
         :topic topic
         :msg-id (generate-id)
         :ts (System/currentTimeMillis)))

(defn- log-message!
  "Add message to bounded log queue."
  [enriched-msg]
  (swap! message-log
         (fn [q]
           (let [q' (conj q enriched-msg)]
             (if (> (count q') max-log-size)
               (pop q')
               q')))))

(defn- dispatch-to-handlers!
  "Dispatch message to all handlers for a topic asynchronously."
  [topic enriched-msg]
  (let [handlers (get @subscribers topic #{})]
    (log/log! {:level :trace
               :id    ::dispatch
               :msg   "Dispatching message"
               :data  {:topic topic
                       :handler-count (count handlers)
                       :msg-id (:msg-id enriched-msg)}})
    (doseq [handler handlers]
           (future
            (try
             (handler enriched-msg)
             (catch Exception e
                    (log/log! {:level :error
                               :id    ::handler-error
                               :msg   "Handler threw exception"
                               :data  {:topic topic
                                       :msg-id (:msg-id enriched-msg)
                                       :error (.getMessage e)}})))))))

;; =============================================================================
;; Core API
;; =============================================================================

(defn subscribe!
  "Subscribe a handler function to a topic.

   Arguments:
     topic      - Keyword identifying the topic
     handler-fn - Function (fn [msg] ...) called for each message

   Returns:
     Function to call to unsubscribe.

   Example:
     (def unsub (subscribe! :code-review
                            (fn [msg] (println \"Got:\" msg))))
     ;; Later:
     (unsub)"
  [topic handler-fn]
  (log/log! {:level :debug
             :id    ::subscribe
             :msg   "Subscribing to topic"
             :data  {:topic topic}})
  (swap! subscribers update topic (fnil conj #{}) handler-fn)
  ;; Return unsubscribe function
  (fn []
    (log/log! {:level :debug
               :id    ::unsubscribe
               :msg   "Unsubscribing from topic"
               :data  {:topic topic}})
    (swap! subscribers update topic disj handler-fn)))

(defn publish!
  "Publish a message to a topic.

   Dispatches asynchronously to all subscribers.
   Fire-and-forget - returns immediately.

   Arguments:
     topic - Keyword identifying the topic
     msg   - Message map to publish

   Returns:
     The enriched message (with :msg-id, :topic, :ts).

   Example:
     (publish! :code-review {:from \"expert-1\" :content \"Please review\"})"
  [topic msg]
  (let [enriched (enrich-message topic msg)]
    (log-message! enriched)
    (dispatch-to-handlers! topic enriched)
    enriched))

(defn ask
  "Send a request and wait for a response.

   Uses Global Response Router for O(1) correlation.
   The responder should call (reply!) with the same :request-id.

   Arguments:
     topic   - Topic to send request to
     message - Request content (map or string)

   Options:
     :timeout-ms - Timeout in milliseconds (default: 30000)
     :from       - Sender identifier (default: \"unknown\")

   Returns:
     {:success true :content ... :duration-ms ...}
     or {:error :timeout :topic ...}

   Example:
     (ask :expert-clojure \"Review this code\" :timeout-ms 60000)"
  [topic message & {:keys [timeout-ms from]
                    :or   {timeout-ms 30000
                           from       "unknown"}}]
  (let [request-id (generate-id)
        response-promise (promise)
        start-time (System/currentTimeMillis)]

    ;; Register in Global Response Router
    (swap! pending-responses assoc request-id response-promise)

    (log/log! {:level :debug
               :id    ::ask-sending
               :msg   "Sending ask request"
               :data  {:topic topic
                       :request-id request-id
                       :timeout-ms timeout-ms}})

    ;; Publish request
    (publish! topic {:type :request
                     :request-id request-id
                     :from from
                     :content message})

    ;; Wait for response
    (let [result (deref response-promise timeout-ms ::timeout)]
      ;; Cleanup pending response
      (swap! pending-responses dissoc request-id)

      (if (= result ::timeout)
        (do
         (log/log! {:level :warn
                    :id    ::ask-timeout
                    :msg   "Ask request timed out"
                    :data  {:topic topic
                            :request-id request-id
                            :timeout-ms timeout-ms}})
         {:error :timeout
          :topic topic
          :request-id request-id})
        (do
         (log/log! {:level :debug
                    :id    ::ask-complete
                    :msg   "Ask request completed"
                    :data  {:topic topic
                            :request-id request-id
                            :duration-ms (- (System/currentTimeMillis) start-time)}})
         {:success true
          :content result
          :request-id request-id
          :duration-ms (- (System/currentTimeMillis) start-time)})))))

(defn reply!
  "Send a response to an ask request.

   Looks up the request-id in Global Response Router and delivers.

   Arguments:
     request-id - The request-id from the original request
     response   - Response content

   Returns:
     true if request was found and response delivered,
     false if request-id not found (already timed out or invalid).

   Example:
     ;; In handler:
     (subscribe! :expert-clojure
       (fn [{:keys [request-id content]}]
         (let [result (process content)]
           (reply! request-id result))))"
  [request-id response]
  (if-let [p (get @pending-responses request-id)]
          (do
           (log/log! {:level :debug
                      :id    ::reply-delivered
                      :msg   "Delivering reply"
                      :data  {:request-id request-id}})
           (deliver p response)
           true)
          (do
           (log/log! {:level :warn
                      :id    ::reply-not-found
                      :msg   "Reply target not found (timed out or invalid)"
                      :data  {:request-id request-id}})
           false)))

;; =============================================================================
;; Introspection API
;; =============================================================================

(defn list-topics
  "List all topics with subscriber counts.

   Returns:
     Map of {topic subscriber-count}"
  []
  (into {} (map (fn [[k v]] [k (count v)]) @subscribers)))

(defn get-recent-messages
  "Get recent messages from log.

   Arguments:
     n - Number of messages to return (default: 10)

   Returns:
     Vector of recent messages (newest last)."
  ([] (get-recent-messages 10))
  ([n] (vec (take-last n (seq @message-log)))))

(defn get-pending-requests
  "Get count of pending ask requests.

   Returns:
     Number of requests waiting for responses."
  []
  (count @pending-responses))

(defn clear-log!
  "Clear the message log."
  []
  (reset! message-log clojure.lang.PersistentQueue/EMPTY)
  nil)

(defn reset-bus!
  "Reset all bus state. USE WITH CAUTION.

   Clears subscribers, pending responses, and message log."
  []
  (log/log! {:level :warn
             :id    ::reset-bus
             :msg   "Resetting message bus state"})
  (reset! subscribers {})
  (reset! pending-responses {})
  (reset! message-log clojure.lang.PersistentQueue/EMPTY)
  nil)

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn module-start
  "Start the message bus module."
  [_deps _config]
  (log/log! {:level :info
             :id    ::module-start
             :msg   "Message bus module started"})
  {:status :started})

(defn module-stop
  "Stop the message bus module."
  [_instance]
  (log/log! {:level :info
             :id    ::module-stop
             :msg   "Message bus module stopped"})
  ;; Don't reset state - allow graceful inspection
  nil)

(defn module-status
  "Get message bus module status."
  [_instance]
  {:status :ok
   :topics (count @subscribers)
   :pending-requests (count @pending-responses)
   :log-size (count @message-log)})

(def module
     "Module lifecycle implementation."
     {:start module-start
      :stop module-stop
      :status module-status})
