(ns streamable-http.router-test
    "Tests for request router."
    (:require [clojure.string]
              [clojure.test :refer [deftest is testing use-fixtures]]
              [streamable-http.router :as router]
              [streamable-http.session :as session]
              [streamable-http.util :as util]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn clean-sessions-fixture
  "Clean up all sessions before and after each test."
  [f]
  (session/destroy-all-sessions!)
  (f)
  (session/destroy-all-sessions!))

(use-fixtures :each clean-sessions-fixture)

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- echo-handler
  "Simple echo handler for testing."
  [msg]
  {:jsonrpc "2.0"
   :result {:echo msg}
   :id (:id msg)})

(def ^{:doc "Test router instance for router tests."}
 test-router (router/create-router echo-handler {}))

(defn- make-request
  "Create a mock Ring request."
  [& {:keys [method uri body headers]
      :or {method :get uri "/mcp" headers {}}}]
  {:request-method method
   :uri uri
   :headers headers
   :body (when body (java.io.StringReader. body))})

;; =============================================================================
;; Router Tests
;; =============================================================================

(deftest test-health-endpoint
         (testing "GET /health returns 200"
                  (let [request (make-request :method :get :uri "/health")
                        response (test-router request)]
                    (is (= 200 (:status response)))
                    (is (clojure.string/includes? (:body response) "ok")))))

(deftest test-options-preflight
         (testing "OPTIONS /mcp returns 204 with CORS headers"
                  (let [request (make-request :method :options :uri "/mcp")
                        response (test-router request)]
                    (is (= 204 (:status response)))
                    (is (some? (get-in response [:headers "Access-Control-Allow-Methods"]))))))

(deftest test-not-found
         (testing "Unknown path returns 404"
                  (let [request (make-request :method :get :uri "/unknown")
                        response (test-router request)]
                    (is (= 404 (:status response))))))

(deftest test-method-not-allowed
         (testing "Unsupported method returns 405"
                  (let [request (make-request :method :put :uri "/mcp")
                        response (test-router request)]
                    (is (= 405 (:status response)))
                    (is (some? (get-in response [:headers "Allow"]))))))

(deftest test-post-route
         (testing "POST /mcp routes to post handler"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :method :post
                                 :uri "/mcp"
                                 :body (util/generate-json {:jsonrpc "2.0"
                                                            :method "test"
                                                            :id 1})
                                 :headers {"mcp-session-id" (:session-id test-session)
                                           "content-type" "application/json"})
                        response (test-router request)]
                    (is (= 200 (:status response))))))

(deftest test-delete-route
         (testing "DELETE /mcp routes to delete handler"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :method :delete
                                 :uri "/mcp"
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (test-router request)]
                    (is (= 204 (:status response))))))

(deftest test-custom-paths
         (testing "Router respects custom path configuration"
                  (let [custom-router (router/create-router echo-handler
                                                            {:path "/api/mcp"
                                                             :health-path "/api/health"})
                        health-req (make-request :method :get :uri "/api/health")
                        mcp-req (make-request :method :options :uri "/api/mcp")]
                    (is (= 200 (:status (custom-router health-req))))
                    (is (= 204 (:status (custom-router mcp-req)))))))

;; =============================================================================
;; CORS Wrapper Tests
;; =============================================================================

(deftest test-wrap-cors
         (testing "wrap-cors adds CORS headers to response"
                  (let [handler (fn [_] {:status 200 :headers {} :body "ok"})
                        wrapped (router/wrap-cors handler)
                        response (wrapped {})]
                    (is (= "ok" (:body response)))
                    (is (some? (get-in response [:headers "Access-Control-Allow-Origin"]))))))
