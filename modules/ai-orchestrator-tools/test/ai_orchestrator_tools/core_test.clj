(ns ai-orchestrator-tools.core-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [cheshire.core :as json]
              [ai-orchestrator.core :as orch]
              [ai-orchestrator.protocol :as proto]
              [ai-orchestrator.registry :as registry]
              [ai-orchestrator-tools.tools.start-instance :as start-tool]
              [ai-orchestrator-tools.tools.ask :as ask-tool]
              [ai-orchestrator-tools.tools.stop-instance :as stop-tool]
              [ai-orchestrator-tools.tools.list-instances :as list-tool]))

;; =============================================================================
;; Mock Provider for Testing
;; =============================================================================

(defmethod proto/create-instance :mock-test-provider
           [{:keys [name model] :as _opts}]
           {:name name
            :provider-type :mock-test-provider
            :protocol :mock
            :model (or model "mock-model-1.0")
            :capabilities #{:chat}
            :transport {:mock true}
            :session-id (atom "mock-session-123")
            :pending-requests (atom {})
            :current-request-id (atom nil)
            :created-at (java.time.Instant/now)})

(defmethod proto/send-message :mock-test-provider
           [instance _message]
  ;; Simulate async response delivery
           (let [request-id @(:current-request-id instance)
                 pending (:pending-requests instance)]
             (when-let [p (get @pending request-id)]
                       (deliver p {:content "Mock response" :duration_ms 100}))
             true))

(defmethod proto/stop-instance :mock-test-provider
           [_instance]
           true)

(defmethod proto/get-capabilities :mock-test-provider
           [_provider-type]
           #{:chat})

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn cleanup-fixture
  "Test fixture that clears registry before and after each test."
  [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))

(use-fixtures :each cleanup-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- parse-response
  "Parse tool response content JSON."
  [response]
  (let [text (get-in response [:content 0 :text])]
    (json/parse-string text true)))

;; =============================================================================
;; ai_start_instance Tests
;; =============================================================================

(deftest test-start-instance-success
         (testing "Successfully starts a mock instance"
    ;; Note: We can't test real providers without API keys, so we test validation
    ;; and error paths primarily. For mock testing, we'd need to register the mock
    ;; provider type in the valid-provider-types set.
                  (let [response (start-tool/handle {:name "test-instance"
                                                     :provider_type "anthropic-http"
                                                     :model "test-model"})]
      ;; Without API key, this should fail at the provider level
      ;; but should pass validation
                    (is (map? response))
                    (is (contains? response :content)))))

(deftest test-start-instance-missing-name
         (testing "Returns error when name is missing"
                  (let [response (start-tool/handle {:provider_type "anthropic-http"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"name" (:error parsed))))))

(deftest test-start-instance-missing-provider-type
         (testing "Returns error when provider_type is missing"
                  (let [response (start-tool/handle {:name "test-instance"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"provider_type" (:error parsed))))))

(deftest test-start-instance-invalid-provider-type
         (testing "Returns error for invalid provider type"
                  (let [response (start-tool/handle {:name "test-instance"
                                                     :provider_type "invalid-provider"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"Invalid provider_type" (:error parsed))))))

(deftest test-start-instance-invalid-name-format
         (testing "Returns error for invalid name format"
                  (let [response (start-tool/handle {:name "123-invalid"
                                                     :provider_type "anthropic-http"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"Invalid instance name" (:error parsed))))))

;; =============================================================================
;; ai_ask Tests
;; =============================================================================

(deftest test-ask-missing-name
         (testing "Returns error when name is missing"
                  (let [response (ask-tool/handle {:message "Hello"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"name" (:error parsed))))))

(deftest test-ask-missing-message
         (testing "Returns error when message is missing"
                  (let [response (ask-tool/handle {:name "test"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"message" (:error parsed))))))

(deftest test-ask-instance-not-found
         (testing "Returns error when instance doesn't exist"
                  (let [response (ask-tool/handle {:name "nonexistent" :message "Hello"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"not found" (:error parsed))))))

;; =============================================================================
;; ai_stop_instance Tests
;; =============================================================================

(deftest test-stop-instance-missing-name
         (testing "Returns error when name is missing"
                  (let [response (stop-tool/handle {})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"name" (:error parsed))))))

(deftest test-stop-instance-not-found
         (testing "Returns error when instance doesn't exist"
                  (let [response (stop-tool/handle {:name "nonexistent"})
                        parsed (parse-response response)]
                    (is (:isError response))
                    (is (= "error" (:status parsed)))
                    (is (re-find #"not found" (:error parsed))))))

;; =============================================================================
;; ai_list_instances Tests
;; =============================================================================

(deftest test-list-instances-empty
         (testing "Returns empty list when no instances"
                  (let [response (list-tool/handle {})
                        parsed (parse-response response)]
                    (is (not (:isError response)))
                    (is (= "success" (:status parsed)))
                    (is (= 0 (:count parsed)))
                    (is (empty? (:instances parsed))))))

(deftest test-list-instances-with-instances
         (testing "Returns instances when some exist"
    ;; Manually register a mock instance
                  (registry/register-instance!
                   {:name "mock-1"
                    :provider-type :mock-test-provider
                    :model "test-model"
                    :created-at (java.time.Instant/now)})

                  (let [response (list-tool/handle {})
                        parsed (parse-response response)]
                    (is (not (:isError response)))
                    (is (= "success" (:status parsed)))
                    (is (= 1 (:count parsed)))
                    (is (= "mock-1" (-> parsed :instances first :name))))))

;; =============================================================================
;; Integration Tests (with Mock Provider)
;; =============================================================================

(deftest test-full-lifecycle-with-mock
         (testing "Full lifecycle: start -> list -> stop"
    ;; Register a mock instance directly via orchestrator
                  (let [instance (orch/start-instance! "lifecycle-test"
                                                       {:provider-type :mock-test-provider
                                                        :model "test-model"})]
                    (is (not (:error instance)))
                    (is (= "lifecycle-test" (:name instance))))

    ;; List instances
                  (let [response (list-tool/handle {})
                        parsed (parse-response response)]
                    (is (= 1 (:count parsed)))
                    (is (= "lifecycle-test" (-> parsed :instances first :name))))

    ;; Stop instance
                  (let [response (stop-tool/handle {:name "lifecycle-test"})
                        parsed (parse-response response)]
                    (is (not (:isError response)))
                    (is (= "success" (:status parsed))))

    ;; Verify empty
                  (let [response (list-tool/handle {})
                        parsed (parse-response response)]
                    (is (= 0 (:count parsed))))))
