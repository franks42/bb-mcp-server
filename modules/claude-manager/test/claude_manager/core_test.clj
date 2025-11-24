(ns claude-manager.core-test
    "Tests for claude-manager using mock Claude process."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [clojure.string :as str]
              [claude-manager.core :as cm]
              [claude-manager.registry :as registry]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:private mock-script-path
     "Path to mock Claude script."
     "modules/claude-manager/test/mock_claude.clj")

(defn cleanup-fixture
  "Cleanup all instances after each test."
  [f]
  (try
   (f)
   (finally
    (cm/shutdown-all!)
    (registry/clear-registry!))))

(use-fixtures :each cleanup-fixture)

;; =============================================================================
;; Spawn Tests
;; =============================================================================

(deftest spawn-basic-test
         (testing "Can spawn a mock instance"
                  (let [instance (cm/spawn! "test-1" {:cmd ["bb" mock-script-path]})]
                    (is (some? instance))
                    (is (= "test-1" (:name instance)))
                    (is (some? (:proc-map instance))))))

(deftest spawn-duplicate-name-test
         (testing "Cannot spawn with duplicate name"
                  (cm/spawn! "test-dup" {:cmd ["bb" mock-script-path]})
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Instance already exists"
                                        (cm/spawn! "test-dup" {:cmd ["bb" mock-script-path]})))))

(deftest spawn-max-instances-test
         (testing "Cannot exceed max instances"
                  (cm/configure! {:max-instances 2})
                  (cm/spawn! "max-1" {:cmd ["bb" mock-script-path]})
                  (cm/spawn! "max-2" {:cmd ["bb" mock-script-path]})
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Maximum instances"
                                        (cm/spawn! "max-3" {:cmd ["bb" mock-script-path]})))
    ;; Reset config
                  (cm/configure! {:max-instances 10})))

;; =============================================================================
;; Ask Tests
;; =============================================================================

(deftest ask-basic-test
         (testing "Can ask and get response"
                  (cm/spawn! "ask-test" {:cmd ["bb" mock-script-path]})
    ;; Give reader loop time to start
                  (Thread/sleep 100)
                  (let [response (cm/ask "ask-test" "Hello world")]
                    (is (map? response))
                    (is (string? (:content response)))
                    (is (str/includes? (:content response) "Hello world"))
                    (is (number? (:cost_usd response))))))

(deftest ask-multiple-messages-test
         (testing "Can ask multiple messages to same instance"
                  (cm/spawn! "multi-ask" {:cmd ["bb" mock-script-path]})
                  (Thread/sleep 100)
                  (let [r1 (cm/ask "multi-ask" "First message")
                        r2 (cm/ask "multi-ask" "Second message")]
                    (is (str/includes? (:content r1) "First message"))
                    (is (str/includes? (:content r2) "Second message")))))

(deftest ask-nonexistent-instance-test
         (testing "Ask to nonexistent instance throws"
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Instance not found"
                                        (cm/ask "does-not-exist" "Hello")))))

;; =============================================================================
;; Kill Tests
;; =============================================================================

(deftest kill-basic-test
         (testing "Can kill an instance"
                  (cm/spawn! "kill-test" {:cmd ["bb" mock-script-path]})
                  (is (true? (cm/kill! "kill-test")))
                  (is (nil? (registry/get-instance "kill-test")))))

(deftest kill-nonexistent-test
         (testing "Kill nonexistent returns false"
                  (is (false? (cm/kill! "does-not-exist")))))

;; =============================================================================
;; List Tests
;; =============================================================================

(deftest list-instances-test
         (testing "Can list all instances"
                  (cm/spawn! "list-1" {:cmd ["bb" mock-script-path]})
                  (cm/spawn! "list-2" {:cmd ["bb" mock-script-path]})
                  (let [instances (cm/list-instances)]
                    (is (= 2 (count instances)))
                    (is (some #(= "list-1" (:name %)) instances))
                    (is (some #(= "list-2" (:name %)) instances)))))

(deftest list-empty-test
         (testing "Empty list when no instances"
                  (is (empty? (cm/list-instances)))))

;; =============================================================================
;; Session ID Tests
;; =============================================================================

(deftest session-id-captured-test
         (testing "Session ID is captured from init message"
                  (cm/spawn! "session-test" {:cmd ["bb" mock-script-path]})
                  (Thread/sleep 100)
    ;; Send a message to trigger init
                  (cm/ask "session-test" "trigger init")
                  (Thread/sleep 100)
                  (let [instance (registry/get-instance "session-test")
                        session-id (registry/get-session-id instance)]
                    (is (some? session-id))
                    (is (str/starts-with? session-id "mock-session-")))))

;; =============================================================================
;; Shutdown All Tests
;; =============================================================================

(deftest shutdown-all-test
         (testing "Shutdown all kills all instances"
                  (cm/spawn! "shutdown-1" {:cmd ["bb" mock-script-path]})
                  (cm/spawn! "shutdown-2" {:cmd ["bb" mock-script-path]})
                  (cm/spawn! "shutdown-3" {:cmd ["bb" mock-script-path]})
                  (let [count (cm/shutdown-all!)]
                    (is (= 3 count))
                    (is (empty? (cm/list-instances))))))
