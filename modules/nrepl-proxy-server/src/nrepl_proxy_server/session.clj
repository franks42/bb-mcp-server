(ns nrepl-proxy-server.session
    "Session state management for nREPL proxy.

   Each nREPL session (UUID) maps to a target:
   - :bb - evaluate locally in babashka
   - browser-id - forward to specific browser connection

   Starts in :bb context. User switches with (browser/repl :browser-2),
   returns with :cljs/quit."
    (:require [taoensso.trove :as log]))

;; =============================================================================
;; State
;; =============================================================================

;; Session -> target mapping
;; {session-id {:target :bb | browser-id, :switched-at epoch-ms}}
(defonce ^:private !sessions (atom {}))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn create-session!
  "Create a new session with default :bb target.
   Returns the session-id."
  []
  (let [session-id (str (java.util.UUID/randomUUID))]
    (swap! !sessions assoc session-id {:target :bb
                                       :created-at (System/currentTimeMillis)})
    (log/log! {:level :debug
               :id ::session-created
               :msg "nREPL session created"
               :data {:session-id session-id :target :bb}})
    session-id))

(defn get-target
  "Get the current target for a session. Returns :bb if session unknown."
  [session-id]
  (get-in @!sessions [session-id :target] :bb))

(defn set-target!
  "Set the target for a session. Returns previous target."
  [session-id target]
  (let [old-target (get-target session-id)]
    (swap! !sessions update session-id
           (fn [s]
             (-> (or s {:created-at (System/currentTimeMillis)})
                 (assoc :target target
                        :switched-at (System/currentTimeMillis)))))
    (log/log! {:level :info
               :id ::session-target-changed
               :msg "Session target changed"
               :data {:session-id session-id
                      :old-target old-target
                      :new-target target}})
    old-target))

(defn switch-to-browser!
  "Switch session to target a specific browser.
   Returns true if successful, false if browser not found."
  [session-id browser-id]
  (set-target! session-id browser-id)
  true)

(defn switch-to-bb!
  "Switch session back to bb context.
   Returns the previous target."
  [session-id]
  (set-target! session-id :bb))

(defn browser-target?
  "Check if session is targeting a browser (not :bb)."
  [session-id]
  (let [target (get-target session-id)]
    (and target (not= target :bb))))

(defn remove-session!
  "Remove a session from tracking."
  [session-id]
  (swap! !sessions dissoc session-id)
  (log/log! {:level :debug
             :id ::session-removed
             :msg "Session removed"
             :data {:session-id session-id}}))

(defn list-sessions
  "List all active sessions with their targets."
  []
  @!sessions)

(defn clear-all-sessions!
  "Clear all session state. Used on server shutdown."
  []
  (reset! !sessions {})
  (log/log! {:level :info
             :id ::sessions-cleared
             :msg "All sessions cleared"}))

;; =============================================================================
;; Query Functions
;; =============================================================================

(defn sessions-targeting
  "Get all sessions targeting a specific browser."
  [browser-id]
  (->> @!sessions
       (filter (fn [[_ {:keys [target]}]] (= target browser-id)))
       (map first)))

(defn active-session-count
  "Get count of active sessions."
  []
  (count @!sessions))
