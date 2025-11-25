(ns message-bus.teams-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [message-bus.core :as bus]
              [message-bus.teams :as teams]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-fixture
  "Reset teams and bus state before and after each test."
  [f]
  (teams/reset-teams!)
  (bus/reset-bus!)
  (f)
  (teams/reset-teams!)
  (bus/reset-bus!))

(use-fixtures :each reset-fixture)

;; =============================================================================
;; Team Creation Tests
;; =============================================================================

(deftest test-create-team
         (testing "Create team with members"
                  (let [team (teams/create-team :deploy-app #{:clojure :aws :docs})]
                    (is (= :deploy-app (:id team)))
                    (is (= #{:clojure :aws :docs} (:members team)))
                    (is (= "team:deploy-app:" (:prefix team)))
                    (is (some? (:created-at team))))))

(deftest test-create-team-with-coordinator
         (testing "Create team with coordinator"
                  (let [team (teams/create-team :review #{:reviewer :author}
                                                :coordinator :reviewer)]
                    (is (= :reviewer (:coordinator team))))))

(deftest test-get-team
         (testing "Get team by ID"
                  (teams/create-team :test-team #{:a :b})
                  (let [team (teams/get-team :test-team)]
                    (is (some? team))
                    (is (= :test-team (:id team))))
                  (is (nil? (teams/get-team :nonexistent)))))

(deftest test-list-teams
         (testing "List all teams"
                  (teams/create-team :team-1 #{:a :b})
                  (teams/create-team :team-2 #{:c :d :e} :coordinator :c)
                  (let [team-list (teams/list-teams)]
                    (is (= 2 (count team-list)))
                    (is (= 2 (:members (get team-list :team-1))))
                    (is (= 3 (:members (get team-list :team-2))))
                    (is (= :c (:coordinator (get team-list :team-2)))))))

(deftest test-destroy-team
         (testing "Destroy team cleans up"
                  (let [_team (teams/create-team :temp-team #{:a})]
                    (is (some? (teams/get-team :temp-team)))
                    (is (true? (teams/destroy-team! :temp-team)))
                    (is (nil? (teams/get-team :temp-team)))
      ;; Destroying again returns false
                    (is (false? (teams/destroy-team! :temp-team))))))

;; =============================================================================
;; Team Communication Tests
;; =============================================================================

(deftest test-team-subscribe-publish
         (testing "Team-scoped subscribe and publish"
                  (let [team (teams/create-team :comm-test #{:sender :receiver})
                        received (atom nil)
                        unsub (teams/team-subscribe! team :receiver
                                                     (fn [msg] (reset! received msg)))]
                    (teams/team-publish! team :receiver {:content "team message"})
                    (Thread/sleep 50)
                    (is (some? @received))
                    (is (= "team message" (:content @received)))
                    (is (= :comm-test (:team-id @received)))
                    (unsub))))

(deftest test-team-isolation
         (testing "Different teams don't interfere"
                  (let [team-a (teams/create-team :team-a #{:member})
                        team-b (teams/create-team :team-b #{:member})
                        received-a (atom nil)
                        received-b (atom nil)
                        unsub-a (teams/team-subscribe! team-a :member
                                                       (fn [msg] (reset! received-a msg)))
                        unsub-b (teams/team-subscribe! team-b :member
                                                       (fn [msg] (reset! received-b msg)))]
      ;; Publish to team-a
                    (teams/team-publish! team-a :member {:content "for-a"})
                    (Thread/sleep 50)
                    (is (= "for-a" (:content @received-a)))
                    (is (nil? @received-b))
      ;; Publish to team-b
                    (teams/team-publish! team-b :member {:content "for-b"})
                    (Thread/sleep 50)
                    (is (= "for-b" (:content @received-b)))
                    (unsub-a)
                    (unsub-b))))

(deftest test-team-broadcast
         (testing "Broadcast reaches all team subscribers"
                  (let [team (teams/create-team :bcast #{:a :b :c})
                        received (atom [])
                        unsub (teams/team-subscribe! team :broadcast
                                                     (fn [msg] (swap! received conj msg)))]
                    (teams/team-broadcast! team {:content "announcement"})
                    (Thread/sleep 50)
                    (is (= 1 (count @received)))
                    (is (= "announcement" (:content (first @received))))
                    (unsub))))

;; =============================================================================
;; Team Ask/Reply Tests
;; =============================================================================

(deftest test-team-ask-reply
         (testing "Ask/reply within team"
                  (let [team (teams/create-team :qa #{:asker :responder})
                        unsub (teams/team-subscribe! team :responder
                                                     (fn [{:keys [request-id content]}]
                                                       (teams/team-reply! request-id
                                                                          (str "Answer: " content))))]
                    (let [result (teams/team-ask team :responder "Question?"
                                                 :timeout-ms 1000 :from :asker)]
                      (is (:success result))
                      (is (= "Answer: Question?" (:content result))))
                    (unsub))))

;; =============================================================================
;; Team Introspection Tests
;; =============================================================================

(deftest test-team-members
         (testing "Get team members"
                  (let [team (teams/create-team :intro #{:x :y :z})]
                    (is (= #{:x :y :z} (teams/team-members team))))))

(deftest test-team-topics
         (testing "List team topics"
                  (let [team (teams/create-team :topics-test #{:a :b})
                        unsub1 (teams/team-subscribe! team :a (fn [_]))
                        unsub2 (teams/team-subscribe! team :b (fn [_]))]
                    (let [topics (teams/team-topics team)]
                      (is (= 2 (count topics)))
                      (is (some #(= :team:topics-test:a %) topics))
                      (is (some #(= :team:topics-test:b %) topics)))
                    (unsub1)
                    (unsub2))))

(deftest test-destroy-team-cleans-subscriptions
         (testing "Destroying team unsubscribes handlers"
                  (let [team (teams/create-team :cleanup #{:member})
                        received (atom nil)]
                    (teams/team-subscribe! team :member (fn [msg] (reset! received msg)))
      ;; Verify subscription works
                    (teams/team-publish! team :member {:content "before"})
                    (Thread/sleep 50)
                    (is (= "before" (:content @received)))
      ;; Destroy team
                    (teams/destroy-team! :cleanup)
      ;; Reset and publish again
                    (reset! received nil)
      ;; Publish to same topic - should not be received
                    (bus/publish! :team:cleanup:member {:content "after"})
                    (Thread/sleep 50)
                    (is (nil? @received)))))
