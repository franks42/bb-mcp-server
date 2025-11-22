(ns streamable-http.session-test
    "Tests for session management."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [streamable-http.session :as session]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn clean-sessions-fixture
  "Fixture to clean up all sessions before and after each test."
  [f]
  (session/destroy-all-sessions!)
  (f)
  (session/destroy-all-sessions!))

(use-fixtures :each clean-sessions-fixture)

;; =============================================================================
;; Session Creation Tests
;; =============================================================================

(deftest test-create-session
         (testing "Create session returns session with ID"
                  (let [session (session/create-session! {:clientInfo {:name "test-client"}})]
                    (is (some? (:session-id session)))
                    (is (string? (:session-id session)))
                    (is (some? (:created-at session)))
                    (is (some? (:last-activity session)))
                    (is (= #{} (:sse-channels session))))))

(deftest test-create-multiple-sessions
         (testing "Multiple sessions have unique IDs"
                  (let [s1 (session/create-session! {:clientInfo {:name "client-1"}})
                        s2 (session/create-session! {:clientInfo {:name "client-2"}})
                        s3 (session/create-session! {:clientInfo {:name "client-3"}})]
                    (is (= 3 (count (distinct [(:session-id s1)
                                               (:session-id s2)
                                               (:session-id s3)]))))
                    (is (= 3 (session/session-count))))))

;; =============================================================================
;; Session Retrieval Tests
;; =============================================================================

(deftest test-get-session
         (testing "Get session by ID"
                  (let [session (session/create-session! {:clientInfo {:name "test"}})
                        retrieved (session/get-session (:session-id session))]
                    (is (= (:session-id session) (:session-id retrieved)))
                    (is (= {:clientInfo {:name "test"}} (:client-info retrieved))))))

(deftest test-get-nonexistent-session
         (testing "Get nonexistent session returns nil"
                  (is (nil? (session/get-session "nonexistent-id")))))

;; =============================================================================
;; Session Validation Tests
;; =============================================================================

(deftest test-valid-session
         (testing "Valid session returns true"
                  (let [session (session/create-session! {})]
                    (is (true? (session/valid-session? (:session-id session)))))))

(deftest test-invalid-session
         (testing "Invalid session returns false"
                  (is (false? (session/valid-session? nil)))
                  (is (false? (session/valid-session? "")))
                  (is (false? (session/valid-session? "nonexistent")))))

;; =============================================================================
;; Session Update Tests
;; =============================================================================

(deftest test-touch-session
         (testing "Touch session updates last-activity"
                  (let [session (session/create-session! {})
                        original-activity (:last-activity session)]
                    (Thread/sleep 10)
                    (session/touch-session! (:session-id session))
                    (let [updated (session/get-session (:session-id session))]
                      (is (> (:last-activity updated) original-activity))))))

(deftest test-update-session
         (testing "Update session with function"
                  (let [session (session/create-session! {})
                        _ (session/update-session! (:session-id session)
                                                   #(assoc-in % [:state :foo] "bar"))
                        updated (session/get-session (:session-id session))]
                    (is (= "bar" (get-in updated [:state :foo]))))))

;; =============================================================================
;; Session Destruction Tests
;; =============================================================================

(deftest test-destroy-session
         (testing "Destroy session removes it"
                  (let [session (session/create-session! {})]
                    (is (session/valid-session? (:session-id session)))
                    (is (true? (session/destroy-session! (:session-id session))))
                    (is (false? (session/valid-session? (:session-id session)))))))

(deftest test-destroy-nonexistent-session
         (testing "Destroy nonexistent session returns false"
                  (is (false? (session/destroy-session! "nonexistent")))))

(deftest test-destroy-all-sessions
         (testing "Destroy all sessions"
                  (session/create-session! {:clientInfo {:name "1"}})
                  (session/create-session! {:clientInfo {:name "2"}})
                  (session/create-session! {:clientInfo {:name "3"}})
                  (is (= 3 (session/session-count)))
                  (is (= 3 (session/destroy-all-sessions!)))
                  (is (= 0 (session/session-count)))))

;; =============================================================================
;; SSE Channel Management Tests
;; =============================================================================

(deftest test-add-sse-channel
         (testing "Add SSE channel to session"
                  (let [session (session/create-session! {})
                        fake-channel :test-channel]
                    (session/add-sse-channel! (:session-id session) fake-channel)
                    (let [channels (session/get-sse-channels (:session-id session))]
                      (is (contains? channels fake-channel))))))

(deftest test-remove-sse-channel
         (testing "Remove SSE channel from session"
                  (let [session (session/create-session! {})
                        fake-channel :test-channel]
                    (session/add-sse-channel! (:session-id session) fake-channel)
                    (is (contains? (session/get-sse-channels (:session-id session)) fake-channel))
                    (session/remove-sse-channel! (:session-id session) fake-channel)
                    (is (not (contains? (session/get-sse-channels (:session-id session)) fake-channel))))))

(deftest test-multiple-sse-channels
         (testing "Multiple SSE channels per session"
                  (let [session (session/create-session! {})]
                    (session/add-sse-channel! (:session-id session) :channel-1)
                    (session/add-sse-channel! (:session-id session) :channel-2)
                    (session/add-sse-channel! (:session-id session) :channel-3)
                    (is (= 3 (count (session/get-sse-channels (:session-id session))))))))

;; =============================================================================
;; Session Cleanup Tests
;; =============================================================================

(deftest test-cleanup-expired-sessions
         (testing "Cleanup removes expired sessions"
                  (let [session (session/create-session! {})]
                    (is (session/valid-session? (:session-id session)))
      ;; Set timeout to 0 so all sessions are expired
                    (is (= 1 (session/cleanup-expired-sessions! 0)))
                    (is (false? (session/valid-session? (:session-id session)))))))

(deftest test-cleanup-preserves-active-sessions
         (testing "Cleanup preserves non-expired sessions"
                  (let [session (session/create-session! {})]
                    (is (session/valid-session? (:session-id session)))
      ;; Set timeout to 1 hour - session should not be expired
                    (is (= 0 (session/cleanup-expired-sessions! 3600000)))
                    (is (session/valid-session? (:session-id session))))))

;; =============================================================================
;; Session Statistics Tests
;; =============================================================================

(deftest test-session-stats
         (testing "Session statistics"
                  (session/create-session! {})
                  (session/create-session! {})
                  (let [stats (session/session-stats)]
                    (is (= 2 (:count stats)))
                    (is (number? (:oldest-session-age-ms stats)))
                    (is (= 0 (:total-sse-channels stats))))))

(deftest test-session-stats-with-channels
         (testing "Session statistics with SSE channels"
                  (let [s1 (session/create-session! {})
                        s2 (session/create-session! {})]
                    (session/add-sse-channel! (:session-id s1) :ch1)
                    (session/add-sse-channel! (:session-id s1) :ch2)
                    (session/add-sse-channel! (:session-id s2) :ch3)
                    (let [stats (session/session-stats)]
                      (is (= 2 (:count stats)))
                      (is (= 3 (:total-sse-channels stats)))))))
