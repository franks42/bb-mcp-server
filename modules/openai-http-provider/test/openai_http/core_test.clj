(ns openai-http.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-orchestrator.core :as orch]
            [ai-orchestrator.registry :as registry]
            [openai-http.core]))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest test-create-instance-validation
  (testing "Instance creation requires api-key"
    (let [result (orch/start-instance! "test-openai"
                                       {:provider-type :openai-http
                                        :model "gpt-4"})]
      (is (:error result))
      (is (re-find #"api-key" (:error result)))))

  (testing "Instance creation requires model"
    (let [result (orch/start-instance! "test-openai-2"
                                       {:provider-type :openai-http
                                        :api-key "test-key"})]
      (is (:error result))
      (is (re-find #"model" (:error result))))))

(deftest test-create-instance-success
  (testing "Can create instance with valid params (OpenAI)"
    (registry/clear-registry!)

    (let [result (orch/start-instance! "test-openai"
                                       {:provider-type :openai-http
                                        :api-key "test-key"
                                        :model "gpt-4"})]
      (is (not (:error result)))
      (is (= "test-openai" (:name result)))
      (is (= :openai-http (:provider-type result)))
      (is (= :http-rest (:protocol result)))
      (is (= "gpt-4" (:model result)))
      (is (contains? (:capabilities result) :chat))
      (is (contains? (:capabilities result) :streaming))
      (is (= "https://api.openai.com/v1" (get-in result [:transport :base-url]))))

    ;; Cleanup
    (orch/stop-instance! "test-openai")))

(deftest test-create-instance-anthropic-compat
  (testing "Can create instance with Anthropic compatibility endpoint"
    (registry/clear-registry!)

    (let [result (orch/start-instance! "test-anthropic-compat"
                                       {:provider-type :openai-http
                                        :api-key "test-key"
                                        :model "claude-3-5-sonnet-20241022"
                                        :base-url "https://api.anthropic.com/v1"})]
      (is (not (:error result)))
      (is (= "test-anthropic-compat" (:name result)))
      (is (= :openai-http (:provider-type result)))
      (is (= "claude-3-5-sonnet-20241022" (:model result)))
      (is (= "https://api.anthropic.com/v1" (get-in result [:transport :base-url]))))

    ;; Cleanup
    (orch/stop-instance! "test-anthropic-compat")))

(deftest test-stop-instance
  (testing "Can stop OpenAI HTTP instance"
    (registry/clear-registry!)

    (orch/start-instance! "test-openai"
                          {:provider-type :openai-http
                           :api-key "test-key"
                           :model "gpt-4"})

    (is (= 1 (count (orch/list-instances))))

    (is (true? (orch/stop-instance! "test-openai")))

    (is (empty? (orch/list-instances)))))

(deftest test-instance-metadata
  (testing "Instance contains correct transport metadata"
    (registry/clear-registry!)

    (let [instance (orch/start-instance! "test-openai"
                                         {:provider-type :openai-http
                                          :api-key "test-key-456"
                                          :model "gpt-4"
                                          :base-url "https://custom.api.com/v1"
                                          :max-tokens 4096
                                          :temperature 0.9
                                          :timeout-ms 45000})]

      (is (= "test-key-456" (get-in instance [:transport :api-key])))
      (is (= "https://custom.api.com/v1" (get-in instance [:transport :base-url])))
      (is (= 4096 (get-in instance [:transport :max-tokens])))
      (is (= 0.9 (get-in instance [:transport :temperature])))
      (is (= 45000 (get-in instance [:transport :timeout-ms]))))

    ;; Cleanup
    (orch/stop-instance! "test-openai")))

;; Note: Real API tests require valid API keys and are skipped in CI
;; Run manually with OPENAI_API_KEY or ANTHROPIC_API_KEY environment variables
(comment
  (deftest ^:integration test-real-openai-api-call
    (testing "Can make real OpenAI API call with valid key"
      (when-let [api-key (System/getenv "OPENAI_API_KEY")]
        (registry/clear-registry!)

        (let [instance (orch/start-instance! "test-real-openai"
                                             {:provider-type :openai-http
                                              :api-key api-key
                                              :model "gpt-3.5-turbo"})]
          (is (not (:error instance)))

          (let [response (orch/ask "test-real-openai" "Say 'Hello' and nothing else.")]
            (is (string? response))
            (is (re-find #"(?i)hello" response)))

          (orch/stop-instance! "test-real-openai")))))

  (deftest ^:integration test-real-anthropic-compat-api-call
    (testing "Can make real Anthropic compatibility API call with valid key"
      (when-let [api-key (System/getenv "ANTHROPIC_API_KEY")]
        (registry/clear-registry!)

        (let [instance (orch/start-instance! "test-real-anthropic"
                                             {:provider-type :openai-http
                                              :api-key api-key
                                              :model "claude-3-5-sonnet-20241022"
                                              :base-url "https://api.anthropic.com/v1"})]
          (is (not (:error instance)))

          (let [response (orch/ask "test-real-anthropic" "Say 'Hello' and nothing else.")]
            (is (string? response))
            (is (re-find #"(?i)hello" response)))

          (orch/stop-instance! "test-real-anthropic"))))))
