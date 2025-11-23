(ns streamable-http.handlers-test
    "Tests for HTTP handlers."
    (:require [clojure.string]
              [clojure.test :refer [deftest is testing use-fixtures]]
              [streamable-http.handlers.post :as post]
              [streamable-http.handlers.delete :as delete]
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
  "Simple echo handler for testing.
   Now takes [ctx msg] per unified processor signature."
  [_ctx msg]
  {:jsonrpc "2.0"
   :result {:echo msg}
   :id (:id msg)})

(defn- make-request
  "Create a mock Ring request."
  [& {:keys [method body headers]
      :or {method :post headers {}}}]
  {:request-method method
   :uri "/mcp"
   :headers (merge {"content-type" "application/json"} headers)
   :body (when body (java.io.StringReader. body))})

(defn- json-body
  "Generate JSON body string."
  [data]
  (util/generate-json data))

;; =============================================================================
;; POST Handler Tests
;; =============================================================================

(deftest test-post-parse-error
         (testing "POST with invalid JSON returns parse error"
                  (let [request (make-request :body "not json {{{")
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "-32700")))))

(deftest test-post-invalid-jsonrpc
         (testing "POST with invalid JSON-RPC returns error"
                  (let [request (make-request :body (json-body {:foo "bar"}))
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "-32600")))))

(deftest test-post-initialize-creates-session
         (testing "POST initialize creates session and returns session ID"
                  (let [request (make-request
                                 :body (json-body {:jsonrpc "2.0"
                                                   :method "initialize"
                                                   :params {:clientInfo {:name "test"}}
                                                   :id 1}))
                        response (post/handle-post request echo-handler)]
                    (is (= 200 (:status response)))
                    (is (some? (get-in response [:headers "Mcp-Session-Id"]))))))

(deftest test-post-initialize-with-existing-session
         (testing "POST initialize with existing session returns error"
                  (let [existing-session (session/create-session! {})
                        request (make-request
                                 :body (json-body {:jsonrpc "2.0"
                                                   :method "initialize"
                                                   :params {}
                                                   :id 1})
                                 :headers {"mcp-session-id" (:session-id existing-session)})
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "already initialized")))))

(deftest test-post-request-without-session
         (testing "POST request without session returns error"
                  (let [request (make-request
                                 :body (json-body {:jsonrpc "2.0"
                                                   :method "tools/list"
                                                   :params {}
                                                   :id 1}))
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "-32003")))))

(deftest test-post-request-with-valid-session
         (testing "POST request with valid session succeeds"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :body (json-body {:jsonrpc "2.0"
                                                   :method "tools/list"
                                                   :params {}
                                                   :id 1})
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (post/handle-post request echo-handler)]
                    (is (= 200 (:status response)))
                    (is (clojure.string/includes? (:body response) "echo")))))

(deftest test-post-notification-returns-202
         (testing "POST notification (no id) returns 202"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :body (json-body {:jsonrpc "2.0"
                                                   :method "notifications/cancelled"
                                                   :params {}})
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (post/handle-post request echo-handler)]
                    (is (= 202 (:status response))))))

(deftest test-post-empty-batch
         (testing "POST empty batch returns error"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :body (json-body [])
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "Empty batch")))))

(deftest test-post-batch-with-initialize
         (testing "POST batch with initialize returns error"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :body (json-body [{:jsonrpc "2.0" :method "initialize" :id 1}
                                                   {:jsonrpc "2.0" :method "tools/list" :id 2}])
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (post/handle-post request echo-handler)]
                    (is (= 400 (:status response)))
                    (is (clojure.string/includes? (:body response) "not allowed in batch")))))

(deftest test-post-valid-batch
         (testing "POST valid batch returns array of responses"
                  (let [test-session (session/create-session! {})
                        request (make-request
                                 :body (json-body [{:jsonrpc "2.0" :method "a" :id 1}
                                                   {:jsonrpc "2.0" :method "b" :id 2}])
                                 :headers {"mcp-session-id" (:session-id test-session)})
                        response (post/handle-post request echo-handler)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (vector? body))
                    (is (= 2 (count body))))))

;; =============================================================================
;; DELETE Handler Tests
;; =============================================================================

(deftest test-delete-without-session-id
         (testing "DELETE without session ID returns 400"
                  (let [request (make-request :method :delete)
                        response (delete/handle-delete request)]
                    (is (= 400 (:status response))))))

(deftest test-delete-invalid-session
         (testing "DELETE with invalid session returns 404"
                  (let [request (make-request
                                 :method :delete
                                 :headers {"mcp-session-id" "nonexistent"})
                        response (delete/handle-delete request)]
                    (is (= 404 (:status response))))))

(deftest test-delete-valid-session
         (testing "DELETE with valid session returns 204"
                  (let [test-session (session/create-session! {})
                        session-id (:session-id test-session)
                        request (make-request
                                 :method :delete
                                 :headers {"mcp-session-id" session-id})
                        response (delete/handle-delete request)]
                    (is (= 204 (:status response)))
                    (is (not (session/valid-session? session-id))))))
