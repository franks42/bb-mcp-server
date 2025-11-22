(ns streamable-http.session
    "Session management for MCP Streamable HTTP transport.

   Provides session creation, validation, and cleanup functionality.
   Sessions track client connections and associated SSE channels."
    (:require [org.httpkit.server :as http]
              [streamable-http.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; Session State
;; =============================================================================

;; Atom containing all active sessions.
;; Map of session-id -> session-data.
(defonce ^:private sessions (atom {}))

;; Future for the background cleanup task.
(defonce ^:private cleanup-future (atom nil))

;; =============================================================================
;; Session Structure
;; =============================================================================

(defn- make-session
  "Create a new session data structure."
  [session-id client-info]
  {:session-id session-id
   :created-at (util/current-time-ms)
   :last-activity (util/current-time-ms)
   :client-info client-info
   :sse-channels #{}
   :state {}})

;; =============================================================================
;; Session CRUD Operations
;; =============================================================================

(defn create-session!
  "Create a new session.

   Arguments:
     client-info - Map containing client information from initialize request

   Returns:
     Session data map with :session-id."
  [client-info]
  (let [session-id (util/generate-uuid)
        session (make-session session-id client-info)]
    (swap! sessions assoc session-id session)
    (log/log! {:level :info
               :id    ::session-created
               :msg   "Session created"
               :data  {:session-id session-id
                       :client-name (get-in client-info [:clientInfo :name])}})
    session))

(defn get-session
  "Get session by ID.

   Arguments:
     session-id - Session identifier string

   Returns:
     Session data map or nil if not found."
  [session-id]
  (get @sessions session-id))

(defn list-sessions
  "List all active session IDs.

   Returns:
     Sequence of session ID strings."
  []
  (keys @sessions))

(defn valid-session?
  "Check if session exists and is valid.

   Arguments:
     session-id - Session identifier string

   Returns:
     true if session exists, false otherwise."
  [session-id]
  (boolean (get-session session-id)))

(defn touch-session!
  "Update session's last activity timestamp.

   Arguments:
     session-id - Session identifier string

   Returns:
     Updated session or nil if not found."
  [session-id]
  (when (valid-session? session-id)
    (swap! sessions update session-id
           assoc :last-activity (util/current-time-ms))
    (get-session session-id)))

(defn update-session!
  "Update session with a function.

   Arguments:
     session-id - Session identifier string
     f          - Function to apply to session data

   Returns:
     Updated session or nil if not found."
  [session-id f]
  (when (valid-session? session-id)
    (swap! sessions update session-id f)
    (get-session session-id)))

(defn destroy-session!
  "Destroy a session and close all its SSE channels.

   Arguments:
     session-id - Session identifier string

   Returns:
     true if session was destroyed, false if not found."
  [session-id]
  (if-let [session (get-session session-id)]
          (do
      ;; Close all SSE channels for this session
           (doseq [channel (:sse-channels session)]
                  (try
                   (http/close channel)
                   (catch Exception e
                          (log/log! {:level :warn
                                     :id    ::channel-close-failed
                                     :msg   "Failed to close SSE channel"
                                     :error e}))))
           (swap! sessions dissoc session-id)
           (log/log! {:level :info
                      :id    ::session-destroyed
                      :msg   "Session destroyed"
                      :data  {:session-id session-id}})
           true)
          false))

(defn destroy-all-sessions!
  "Destroy all active sessions.

   Returns:
     Number of sessions destroyed."
  []
  (let [session-ids (keys @sessions)
        count (count session-ids)]
    (doseq [session-id session-ids]
           (destroy-session! session-id))
    (log/log! {:level :info
               :id    ::all-sessions-destroyed
               :msg   "All sessions destroyed"
               :data  {:count count}})
    count))

;; =============================================================================
;; SSE Channel Management
;; =============================================================================

(defn add-sse-channel!
  "Add SSE channel to session.

   Arguments:
     session-id - Session identifier string
     channel    - http-kit async channel

   Returns:
     Updated session or nil if session not found."
  [session-id channel]
  (when (valid-session? session-id)
    (swap! sessions update-in [session-id :sse-channels] conj channel)
    (log/log! {:level :debug
               :id    ::sse-channel-added
               :msg   "SSE channel added to session"
               :data  {:session-id session-id}})
    (get-session session-id)))

(defn remove-sse-channel!
  "Remove SSE channel from session.

   Arguments:
     session-id - Session identifier string
     channel    - http-kit async channel

   Returns:
     Updated session or nil if session not found."
  [session-id channel]
  (when (valid-session? session-id)
    (swap! sessions update-in [session-id :sse-channels] disj channel)
    (log/log! {:level :debug
               :id    ::sse-channel-removed
               :msg   "SSE channel removed from session"
               :data  {:session-id session-id}})
    (get-session session-id)))

(defn get-sse-channels
  "Get all SSE channels for a session.

   Arguments:
     session-id - Session identifier string

   Returns:
     Set of channels or empty set if session not found."
  [session-id]
  (get-in @sessions [session-id :sse-channels] #{}))

;; =============================================================================
;; Session Cleanup
;; =============================================================================

(defn- expired?
  "Check if session has expired based on timeout.

   Arguments:
     session    - Session data map
     timeout-ms - Timeout in milliseconds

   Returns:
     true if session has expired."
  [session timeout-ms]
  (let [last-activity (:last-activity session)
        now (util/current-time-ms)]
    (>= (- now last-activity) timeout-ms)))

(defn cleanup-expired-sessions!
  "Remove all expired sessions.

   Arguments:
     timeout-ms - Session timeout in milliseconds

   Returns:
     Number of sessions cleaned up."
  [timeout-ms]
  (let [all-sessions @sessions
        expired-ids (->> all-sessions
                         (filter (fn [[_ session]] (expired? session timeout-ms)))
                         (map first))]
    (when (seq expired-ids)
      (log/log! {:level :info
                 :id    ::cleanup-expired
                 :msg   "Cleaning up expired sessions"
                 :data  {:count (count expired-ids)}}))
    (doseq [session-id expired-ids]
           (destroy-session! session-id))
    (count expired-ids)))

(defn start-cleanup-task!
  "Start background task to clean up expired sessions.

   Arguments:
     timeout-ms  - Session timeout in milliseconds
     interval-ms - Cleanup check interval in milliseconds

   Returns:
     Future running the cleanup task."
  [timeout-ms interval-ms]
  (when-let [existing @cleanup-future]
            (future-cancel existing))
  (let [task (future
              (log/log! {:level :info
                         :id    ::cleanup-task-started
                         :msg   "Session cleanup task started"
                         :data  {:timeout-ms timeout-ms
                                 :interval-ms interval-ms}})
              (loop []
                    (Thread/sleep interval-ms)
                    (try
                     (cleanup-expired-sessions! timeout-ms)
                     (catch Exception e
                            (log/log! {:level :error
                                       :id    ::cleanup-task-error
                                       :msg   "Session cleanup task error"
                                       :error e})))
                    (recur)))]
    (reset! cleanup-future task)
    task))

(defn stop-cleanup-task!
  "Stop the background cleanup task.

   Returns:
     true if task was stopped, false if no task was running."
  []
  (if-let [task @cleanup-future]
          (do
           (future-cancel task)
           (reset! cleanup-future nil)
           (log/log! {:level :info
                      :id    ::cleanup-task-stopped
                      :msg   "Session cleanup task stopped"})
           true)
          false))

;; =============================================================================
;; Session Statistics
;; =============================================================================

(defn session-count
  "Get the number of active sessions.

   Returns:
     Number of active sessions."
  []
  (count @sessions))

(defn session-stats
  "Get session statistics.

   Returns:
     Map with :count, :oldest-session-age-ms, :total-sse-channels."
  []
  (let [all-sessions (vals @sessions)
        now (util/current-time-ms)]
    {:count (count all-sessions)
     :oldest-session-age-ms (if (seq all-sessions)
                              (- now (apply min (map :created-at all-sessions)))
                              0)
     :total-sse-channels (reduce + (map #(count (:sse-channels %)) all-sessions))}))
