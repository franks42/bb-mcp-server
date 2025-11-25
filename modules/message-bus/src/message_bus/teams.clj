(ns message-bus.teams
    "Team-based message isolation for AI expert groups.

   Teams provide namespaced topics so multiple expert groups can
   communicate without interference.

   Example:
     (def team (create-team :deploy-app #{:clojure-expert :aws-expert}))
     (team-publish! team :clojure-expert {:content \"Ready\"})
     (team-broadcast! team {:content \"Starting deployment\"})"
    (:require [clojure.string :as str]
              [message-bus.core :as bus]
              [taoensso.trove :as log]))

;; =============================================================================
;; Team Registry
;; =============================================================================

;; Registry of active teams.
;; {team-id team-map}
(defonce ^:private teams (atom {}))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- team-topic
  "Get namespaced topic for a team.

   Arguments:
     team  - Team map
     topic - Local topic name

   Returns:
     Namespaced keyword like :team:deploy-app:clojure-expert"
  [team topic]
  (keyword (str (:prefix team) (name topic))))

;; =============================================================================
;; Team Management
;; =============================================================================

(defn create-team
  "Create a new team with isolated namespace.

   Arguments:
     team-id    - Keyword identifying the team
     member-ids - Set of member identifiers (keywords or strings)

   Options:
     :coordinator - ID of the coordinating member (optional)

   Returns:
     Team map suitable for team-* functions.

   Example:
     (create-team :code-review #{:reviewer :author :ci-bot}
                  :coordinator :reviewer)"
  [team-id member-ids & {:keys [coordinator]}]
  (let [team {:id team-id
              :members (set (map keyword member-ids))
              :coordinator (when coordinator (keyword coordinator))
              :prefix (str "team:" (name team-id) ":")
              :broadcast-topic (keyword (str "team:" (name team-id) ":broadcast"))
              :created-at (System/currentTimeMillis)
              :subscriptions (atom [])}]
    (log/log! {:level :info
               :id    ::create-team
               :msg   "Creating team"
               :data  {:team-id team-id
                       :members member-ids
                       :coordinator coordinator}})
    (swap! teams assoc team-id team)
    team))

(defn get-team
  "Get a team by ID.

   Arguments:
     team-id - Team identifier keyword

   Returns:
     Team map or nil if not found."
  [team-id]
  (get @teams team-id))

(defn list-teams
  "List all registered teams.

   Returns:
     Map of {team-id {:members count :created-at timestamp}}"
  []
  (into {}
        (map (fn [[k v]]
               [k {:members (count (:members v))
                   :coordinator (:coordinator v)
                   :created-at (:created-at v)}])
             @teams)))

(defn destroy-team!
  "Destroy a team and clean up subscriptions.

   Arguments:
     team-id - Team identifier keyword

   Returns:
     true if team was destroyed, false if not found."
  [team-id]
  (if-let [team (get @teams team-id)]
          (do
           (log/log! {:level :info
                      :id    ::destroy-team
                      :msg   "Destroying team"
                      :data  {:team-id team-id}})
      ;; Unsubscribe all team subscriptions
           (doseq [unsub @(:subscriptions team)]
                  (unsub))
           (swap! teams dissoc team-id)
           true)
          false))

;; =============================================================================
;; Team Communication
;; =============================================================================

(defn team-subscribe!
  "Subscribe to a team-scoped topic.

   Arguments:
     team       - Team map
     topic      - Local topic (will be namespaced)
     handler-fn - Handler function

   Returns:
     Unsubscribe function.

   Example:
     (team-subscribe! team :code-review
       (fn [msg] (println \"Review request:\" msg)))"
  [team topic handler-fn]
  (let [full-topic (team-topic team topic)
        unsub (bus/subscribe! full-topic handler-fn)]
    ;; Track subscription for cleanup
    (swap! (:subscriptions team) conj unsub)
    unsub))

(defn team-publish!
  "Publish to a team-scoped topic.

   Arguments:
     team  - Team map
     topic - Local topic (will be namespaced)
     msg   - Message map

   Returns:
     Enriched message.

   Example:
     (team-publish! team :clojure-expert {:content \"Code review done\"})"
  [team topic msg]
  (bus/publish! (team-topic team topic)
                (assoc msg :team-id (:id team))))

(defn team-broadcast!
  "Broadcast message to all team members.

   Publishes to the team's broadcast topic.

   Arguments:
     team - Team map
     msg  - Message map

   Returns:
     Enriched message.

   Example:
     (team-broadcast! team {:type :status :content \"Deployment starting\"})"
  [team msg]
  (log/log! {:level :debug
             :id    ::team-broadcast
             :msg   "Broadcasting to team"
             :data  {:team-id (:id team)
                     :member-count (count (:members team))}})
  (bus/publish! (:broadcast-topic team)
                (assoc msg :team-id (:id team))))

(defn team-ask
  "Send a request to a team member and wait for response.

   Arguments:
     team    - Team map
     member  - Target member keyword
     message - Request content

   Options:
     :timeout-ms - Timeout in milliseconds (default: 30000)
     :from       - Sender identifier

   Returns:
     Response map from bus/ask.

   Example:
     (team-ask team :clojure-expert \"Review this function\"
               :timeout-ms 60000 :from :coordinator)"
  [team member message & opts]
  (apply bus/ask (team-topic team member) message opts))

(defn team-reply!
  "Reply to a team request.

   Convenience wrapper around bus/reply!

   Arguments:
     request-id - Request ID from original message
     response   - Response content

   Returns:
     true if delivered, false if not found."
  [request-id response]
  (bus/reply! request-id response))

;; =============================================================================
;; Team Introspection
;; =============================================================================

(defn team-members
  "Get members of a team.

   Arguments:
     team - Team map

   Returns:
     Set of member keywords."
  [team]
  (:members team))

(defn team-topics
  "List all active topics for a team.

   Arguments:
     team - Team map

   Returns:
     Vector of namespaced topic keywords that have subscribers."
  [team]
  (let [prefix (:prefix team)
        all-topics (keys (bus/list-topics))]
    (vec (filter #(str/starts-with? (name %) prefix)
                 all-topics))))

;; =============================================================================
;; Utility
;; =============================================================================

(defn reset-teams!
  "Reset all teams. USE WITH CAUTION."
  []
  (log/log! {:level :warn
             :id    ::reset-teams
             :msg   "Resetting all teams"})
  (doseq [[team-id _] @teams]
         (destroy-team! team-id))
  (reset! teams {})
  nil)
