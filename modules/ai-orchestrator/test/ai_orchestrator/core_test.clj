(ns ai-orchestrator.core-test
    (:require [clojure.test :refer [deftest is testing]]
              [ai-orchestrator.core :as orch]
              [ai-orchestrator.protocol :as proto]
              [ai-orchestrator.registry :as registry]))

;; =============================================================================
;; Mock Provider for Testing
;; =============================================================================

(defmethod proto/create-instance :mock-provider
           [{:keys [name] :as _opts}]
           {:name name
            :provider-type :mock-provider
            :protocol :mock
            :model "mock-1.0"
            :capabilities #{:chat}
            :transport {:mock true}
            :session-id (atom "mock-session-123")
            :pending-requests (atom {})
            :created-at (System/currentTimeMillis)})

(defmethod proto/send-message :mock-provider
           [_instance _message]
           true)

(defmethod proto/stop-instance :mock-provider
           [_instance]
           true)

(defmethod proto/get-capabilities :mock-provider
           [_provider-type]
           #{:chat})

;; =============================================================================
;; Registry Tests
;; =============================================================================

(deftest test-registry-operations
         (testing "Registry starts empty"
                  (registry/clear-registry!)
                  (is (empty? (registry/list-instances))))

         (testing "Can register and retrieve instance"
                  (let [instance {:name "test-1" :provider-type :mock-provider}]
                    (registry/register-instance! instance)
                    (is (= instance (registry/get-instance "test-1")))
                    (is (= 1 (count (registry/list-instances))))))

         (testing "Can unregister instance"
                  (registry/unregister-instance! "test-1")
                  (is (nil? (registry/get-instance "test-1")))
                  (is (empty? (registry/list-instances)))))

;; =============================================================================
;; Orchestrator API Tests
;; =============================================================================

(deftest test-start-stop-instance
         (testing "Can start and stop a mock instance"
                  (registry/clear-registry!)

                  ;; Start instance
                  (let [result (orch/start-instance! "test-mock"
                                                     {:provider-type :mock-provider})]
                    (is (not (:error result)))
                    (is (= "test-mock" (:name result)))
                    (is (= :mock-provider (:provider-type result))))

                  ;; Verify it's in registry
                  (is (= 1 (count (orch/list-instances))))

                  ;; Stop instance
                  (is (true? (orch/stop-instance! "test-mock")))

                  ;; Verify it's removed
                  (is (empty? (orch/list-instances)))))

(deftest test-instance-name-uniqueness
         (testing "Cannot start instance with duplicate name"
                  (registry/clear-registry!)

                  (orch/start-instance! "duplicate-test" {:provider-type :mock-provider})

                  (is (thrown? Exception
                               (orch/start-instance! "duplicate-test"
                                                     {:provider-type :mock-provider})))

                  ;; Cleanup
                  (orch/stop-instance! "duplicate-test")))

(deftest test-unknown-provider
         (testing "Returns error for unknown provider type"
                  (let [result (orch/start-instance! "unknown-test"
                                                     {:provider-type :nonexistent})]
                    (is (:error result))
                    (is (string? (:error result))))))

(deftest test-list-instances
         (testing "list-instances returns correct info"
                  (registry/clear-registry!)

                  (orch/start-instance! "mock-1" {:provider-type :mock-provider})
                  (orch/start-instance! "mock-2" {:provider-type :mock-provider})

                  (let [instances (orch/list-instances)]
                    (is (= 2 (count instances)))
                    (is (every? #(contains? % :name) instances))
                    (is (every? #(contains? % :provider-type) instances)))

                  ;; Cleanup
                  (orch/shutdown-all!)))
