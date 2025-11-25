(ns message-bus.core-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [message-bus.core :as bus]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-fixture
  "Reset bus state before and after each test."
  [f]
  (bus/reset-bus!)
  (f)
  (bus/reset-bus!))

(use-fixtures :each reset-fixture)

;; =============================================================================
;; Subscribe/Publish Tests
;; =============================================================================

(deftest test-subscribe-publish
         (testing "Basic subscribe and publish"
                  (let [received (atom nil)
                        unsub (bus/subscribe! :test-topic
                                              (fn [msg] (reset! received msg)))]
                    (bus/publish! :test-topic {:content "hello"})
      ;; Wait for async dispatch
                    (Thread/sleep 50)
                    (is (some? @received))
                    (is (= "hello" (:content @received)))
                    (is (= :test-topic (:topic @received)))
                    (is (some? (:msg-id @received)))
                    (is (some? (:ts @received)))
                    (unsub))))

(deftest test-unsubscribe
         (testing "Unsubscribe stops receiving messages"
                  (let [received (atom [])
                        unsub (bus/subscribe! :test-topic
                                              (fn [msg] (swap! received conj msg)))]
                    (bus/publish! :test-topic {:content "first"})
                    (Thread/sleep 50)
                    (unsub)
                    (bus/publish! :test-topic {:content "second"})
                    (Thread/sleep 50)
                    (is (= 1 (count @received)))
                    (is (= "first" (:content (first @received)))))))

(deftest test-multiple-subscribers
         (testing "Multiple subscribers receive same message"
                  (let [received1 (atom nil)
                        received2 (atom nil)
                        unsub1 (bus/subscribe! :test-topic (fn [msg] (reset! received1 msg)))
                        unsub2 (bus/subscribe! :test-topic (fn [msg] (reset! received2 msg)))]
                    (bus/publish! :test-topic {:content "broadcast"})
                    (Thread/sleep 50)
                    (is (= "broadcast" (:content @received1)))
                    (is (= "broadcast" (:content @received2)))
                    (unsub1)
                    (unsub2))))

(deftest test-multiple-topics
         (testing "Messages routed to correct topics"
                  (let [received-a (atom nil)
                        received-b (atom nil)
                        unsub-a (bus/subscribe! :topic-a (fn [msg] (reset! received-a msg)))
                        unsub-b (bus/subscribe! :topic-b (fn [msg] (reset! received-b msg)))]
                    (bus/publish! :topic-a {:content "for-a"})
                    (bus/publish! :topic-b {:content "for-b"})
                    (Thread/sleep 50)
                    (is (= "for-a" (:content @received-a)))
                    (is (= "for-b" (:content @received-b)))
                    (unsub-a)
                    (unsub-b))))

;; =============================================================================
;; Ask/Reply Tests
;; =============================================================================

(deftest test-ask-reply
         (testing "Ask with successful reply"
                  (let [unsub (bus/subscribe! :responder
                                              (fn [{:keys [request-id content]}]
                                                (bus/reply! request-id (str "Echo: " content))))]
                    (let [result (bus/ask :responder "Hello" :timeout-ms 1000)]
                      (is (:success result))
                      (is (= "Echo: Hello" (:content result)))
                      (is (some? (:duration-ms result))))
                    (unsub))))

(deftest test-ask-timeout
         (testing "Ask times out when no reply"
                  (let [result (bus/ask :nonexistent "Hello" :timeout-ms 100)]
                    (is (= :timeout (:error result)))
                    (is (= :nonexistent (:topic result))))))

(deftest test-ask-concurrent
         (testing "Multiple concurrent ask requests"
                  (let [unsub (bus/subscribe! :echo
                                              (fn [{:keys [request-id content]}]
                                  ;; Simulate variable processing time
                                                (Thread/sleep (rand-int 50))
                                                (bus/reply! request-id content)))]
                    (let [f1 (future (bus/ask :echo "msg-1" :timeout-ms 1000))
                          f2 (future (bus/ask :echo "msg-2" :timeout-ms 1000))
                          f3 (future (bus/ask :echo "msg-3" :timeout-ms 1000))
                          r1 @f1
                          r2 @f2
                          r3 @f3]
                      (is (:success r1))
                      (is (:success r2))
                      (is (:success r3))
                      (is (= "msg-1" (:content r1)))
                      (is (= "msg-2" (:content r2)))
                      (is (= "msg-3" (:content r3))))
                    (unsub))))

(deftest test-reply-after-timeout
         (testing "Late reply is ignored gracefully"
                  (let [late-request-id (atom nil)
                        unsub (bus/subscribe! :slow
                                              (fn [{:keys [request-id]}]
                                                (reset! late-request-id request-id)
                                  ;; Don't reply immediately
                                                ))]
      ;; Ask with short timeout
                    (let [result (bus/ask :slow "Hello" :timeout-ms 50)]
                      (is (= :timeout (:error result))))
      ;; Try to reply late
                    (Thread/sleep 100)
                    (is (false? (bus/reply! @late-request-id "Too late")))
                    (unsub))))

;; =============================================================================
;; Handler Error Tests
;; =============================================================================

(deftest test-handler-error-isolation
         (testing "Handler error doesn't crash bus or affect other handlers"
                  (let [received (atom nil)
                        unsub1 (bus/subscribe! :test-topic
                                               (fn [_] (throw (Exception. "Boom!"))))
                        unsub2 (bus/subscribe! :test-topic
                                               (fn [msg] (reset! received msg)))]
                    (bus/publish! :test-topic {:content "test"})
                    (Thread/sleep 100)
      ;; Second handler should still receive message
                    (is (some? @received))
                    (is (= "test" (:content @received)))
                    (unsub1)
                    (unsub2))))

;; =============================================================================
;; Introspection Tests
;; =============================================================================

(deftest test-list-topics
         (testing "List topics shows subscribers"
                  (let [unsub1 (bus/subscribe! :topic-a (fn [_]))
                        unsub2 (bus/subscribe! :topic-a (fn [_]))
                        unsub3 (bus/subscribe! :topic-b (fn [_]))]
                    (let [topics (bus/list-topics)]
                      (is (= 2 (get topics :topic-a)))
                      (is (= 1 (get topics :topic-b))))
                    (unsub1)
                    (unsub2)
                    (unsub3))))

(deftest test-message-log
         (testing "Message log captures recent messages"
                  (dotimes [i 5]
                           (bus/publish! :log-test {:n i}))
                  (Thread/sleep 50)
                  (let [msgs (bus/get-recent-messages 3)]
                    (is (= 3 (count msgs)))
      ;; Last 3 messages
                    (is (= 2 (:n (first msgs))))
                    (is (= 4 (:n (last msgs)))))))

(deftest test-clear-log
         (testing "Clear log removes all messages"
                  (bus/publish! :test {:content "test"})
                  (Thread/sleep 50)
                  (is (pos? (count (bus/get-recent-messages))))
                  (bus/clear-log!)
                  (is (zero? (count (bus/get-recent-messages))))))

(deftest test-pending-requests
         (testing "Pending requests count"
                  (is (zero? (bus/get-pending-requests)))
    ;; Start ask but don't reply
                  (let [f (future (bus/ask :no-reply "Hello" :timeout-ms 500))]
                    (Thread/sleep 50)
                    (is (= 1 (bus/get-pending-requests)))
                    @f ;; Wait for timeout
                    (is (zero? (bus/get-pending-requests))))))
