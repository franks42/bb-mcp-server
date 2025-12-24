(ns nrepl-proxy-server.session-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [nrepl-proxy-server.session :as session]))

;; Clean up sessions between tests
(use-fixtures :each
              (fn [f]
                (session/clear-all-sessions!)
                (f)
                (session/clear-all-sessions!)))

(deftest create-session-test
         (testing "create-session! returns a UUID string"
                  (let [session-id (session/create-session!)]
                    (is (string? session-id))
                    (is (re-matches #"[0-9a-f-]+" session-id)))))

(deftest get-target-default-test
         (testing "new sessions default to :bb target"
                  (let [session-id (session/create-session!)]
                    (is (= :bb (session/get-target session-id)))))

         (testing "unknown sessions return :bb"
                  (is (= :bb (session/get-target "unknown-session")))))

(deftest set-target-test
         (testing "set-target! changes the target"
                  (let [session-id (session/create-session!)]
                    (is (= :bb (session/get-target session-id)))
                    (session/set-target! session-id "browser-1-uuid")
                    (is (= "browser-1-uuid" (session/get-target session-id)))))

         (testing "set-target! returns previous target"
                  (let [session-id (session/create-session!)]
                    (is (= :bb (session/set-target! session-id "browser-1")))
                    (is (= "browser-1" (session/set-target! session-id "browser-2"))))))

(deftest switch-to-browser-test
         (testing "switch-to-browser! changes target"
                  (let [session-id (session/create-session!)]
                    (session/switch-to-browser! session-id "browser-2-uuid")
                    (is (= "browser-2-uuid" (session/get-target session-id))))))

(deftest switch-to-bb-test
         (testing "switch-to-bb! returns to :bb target"
                  (let [session-id (session/create-session!)]
                    (session/switch-to-browser! session-id "browser-1-uuid")
                    (is (= "browser-1-uuid" (session/get-target session-id)))
                    (session/switch-to-bb! session-id)
                    (is (= :bb (session/get-target session-id))))))

(deftest browser-target?-test
         (testing "browser-target? returns false for :bb"
                  (let [session-id (session/create-session!)]
                    (is (false? (session/browser-target? session-id)))))

         (testing "browser-target? returns true for browser target"
                  (let [session-id (session/create-session!)]
                    (session/switch-to-browser! session-id "browser-1")
                    (is (true? (session/browser-target? session-id))))))

(deftest remove-session-test
         (testing "remove-session! removes session from tracking"
                  (let [session-id (session/create-session!)]
                    (is (contains? (session/list-sessions) session-id))
                    (session/remove-session! session-id)
                    (is (not (contains? (session/list-sessions) session-id))))))

(deftest list-sessions-test
         (testing "list-sessions returns all active sessions"
                  (let [s1 (session/create-session!)
                        s2 (session/create-session!)
                        sessions (session/list-sessions)]
                    (is (contains? sessions s1))
                    (is (contains? sessions s2))
                    (is (= 2 (count sessions))))))

(deftest active-session-count-test
         (testing "active-session-count returns correct count"
                  (is (= 0 (session/active-session-count)))
                  (session/create-session!)
                  (is (= 1 (session/active-session-count)))
                  (session/create-session!)
                  (is (= 2 (session/active-session-count)))))

(deftest sessions-targeting-test
         (testing "sessions-targeting finds sessions with specific browser target"
                  (let [s1 (session/create-session!)
                        s2 (session/create-session!)
                        s3 (session/create-session!)]
                    (session/switch-to-browser! s1 "browser-1")
                    (session/switch-to-browser! s2 "browser-1")
                    (session/switch-to-browser! s3 "browser-2")
                    (is (= #{s1 s2} (set (session/sessions-targeting "browser-1"))))
                    (is (= #{s3} (set (session/sessions-targeting "browser-2")))))))
