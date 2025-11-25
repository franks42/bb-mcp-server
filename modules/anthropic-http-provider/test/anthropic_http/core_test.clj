(ns anthropic-http.core-test
    (:require [clojure.test :refer [deftest is testing]]
              [ai-orchestrator.core :as orch]
              [ai-orchestrator.registry :as registry]
              [anthropic-http.core]))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest test-create-instance-validation
         (testing "Instance creation requires api-key"
                  (let [result (orch/start-instance! "test-anthropic"
                                                     {:provider-type :anthropic-http
                                                      :model "claude-3-5-sonnet-20241022"})]
                    (is (:error result))
                    (is (re-find #"api-key" (:error result)))))

         (testing "Instance creation requires model"
                  (let [result (orch/start-instance! "test-anthropic-2"
                                                     {:provider-type :anthropic-http
                                                      :api-key "test-key"})]
                    (is (:error result))
                    (is (re-find #"model" (:error result))))))

(deftest test-create-instance-success
         (testing "Can create instance with valid params"
                  (registry/clear-registry!)

                  (let [result (orch/start-instance! "test-anthropic"
                                                     {:provider-type :anthropic-http
                                                      :api-key "test-key"
                                                      :model "claude-3-5-sonnet-20241022"})]
                    (is (not (:error result)))
                    (is (= "test-anthropic" (:name result)))
                    (is (= :anthropic-http (:provider-type result)))
                    (is (= :http-rest (:protocol result)))
                    (is (= "claude-3-5-sonnet-20241022" (:model result)))
                    (is (contains? (:capabilities result) :chat))
                    (is (contains? (:capabilities result) :streaming)))

    ;; Cleanup
                  (orch/stop-instance! "test-anthropic")))

(deftest test-stop-instance
         (testing "Can stop Anthropic HTTP instance"
                  (registry/clear-registry!)

                  (orch/start-instance! "test-anthropic"
                                        {:provider-type :anthropic-http
                                         :api-key "test-key"
                                         :model "claude-3-5-sonnet-20241022"})

                  (is (= 1 (count (orch/list-instances))))

                  (is (true? (orch/stop-instance! "test-anthropic")))

                  (is (empty? (orch/list-instances)))))

(deftest test-instance-metadata
         (testing "Instance contains correct transport metadata"
                  (registry/clear-registry!)

                  (let [instance (orch/start-instance! "test-anthropic"
                                                       {:provider-type :anthropic-http
                                                        :api-key "test-key-123"
                                                        :model "claude-3-5-sonnet-20241022"
                                                        :max-tokens 2048
                                                        :temperature 0.7
                                                        :timeout-ms 60000})]

                    (is (= "test-key-123" (get-in instance [:transport :api-key])))
                    (is (= 2048 (get-in instance [:transport :max-tokens])))
                    (is (= 0.7 (get-in instance [:transport :temperature])))
                    (is (= 60000 (get-in instance [:transport :timeout-ms]))))

    ;; Cleanup
                  (orch/stop-instance! "test-anthropic")))

;; Note: Real API tests require valid API key and are skipped in CI
;; Run manually with ANTHROPIC_API_KEY environment variable set
(comment
 (deftest ^:integration test-real-api-call
          (testing "Can make real API call with valid key"
                   (when-let [api-key (System/getenv "ANTHROPIC_API_KEY")]
                             (registry/clear-registry!)

                             (let [instance (orch/start-instance! "test-real"
                                                                  {:provider-type :anthropic-http
                                                                   :api-key api-key
                                                                   :model "claude-3-5-sonnet-20241022"})]
                               (is (not (:error instance)))

          ;; Note: send-message in current impl returns response directly
          ;; In future, will use router/promise system
                               (let [response (orch/ask "test-real" "Say 'Hello' and nothing else.")]
                                 (is (string? response))
                                 (is (re-find #"(?i)hello" response)))

                               (orch/stop-instance! "test-real"))))))
