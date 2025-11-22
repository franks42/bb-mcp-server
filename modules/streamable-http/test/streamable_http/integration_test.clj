(ns streamable-http.integration-test
    "Integration tests for Streamable HTTP server.

   Tests the full server lifecycle with real HTTP requests."
    (:require [clojure.string]
              [clojure.test :refer [deftest is testing use-fixtures]]
              [babashka.http-client :as http]
              [streamable-http.core :as core]
              [streamable-http.util :as util]))

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def ^{:doc "Test port for integration tests."} test-port 19876)
(def ^{:doc "Base URL for test requests."} base-url (str "http://localhost:" test-port))

;; =============================================================================
;; Test Handler
;; =============================================================================

(defn- echo-handler
  "Echo handler for integration testing."
  [{:keys [method params id]}]
  (case method
    "initialize"
    {:jsonrpc "2.0"
     :result {:protocolVersion "2025-03-26"
              :capabilities {}
              :serverInfo {:name "test-server" :version "1.0.0"}}
     :id id}

    "tools/list"
    {:jsonrpc "2.0"
     :result {:tools [{:name "echo" :description "Echo tool"}]}
     :id id}

    "tools/call"
    {:jsonrpc "2.0"
     :result {:content [{:type "text" :text (str "Echo: " (:message params))}]}
     :id id}

    ;; Default echo
    {:jsonrpc "2.0"
     :result {:echo {:method method :params params}}
     :id id}))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private test-server (atom nil))

(defn server-fixture
  "Start server before tests, stop after."
  [f]
  (reset! test-server (core/start-server! echo-handler {:port test-port}))
  (Thread/sleep 100) ; Give server time to start
  (try
   (f)
   (finally
    (when @test-server
      (core/stop-server! @test-server)
      (reset! test-server nil)
      (Thread/sleep 100))))) ; Give server time to stop

(use-fixtures :once server-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- post-json
  "Send POST request with JSON body."
  [path body & [headers]]
  (http/post (str base-url path)
             {:body (util/generate-json body)
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "application/json"}
                              headers)
              :throw false}))

(defn- delete-request
  "Send DELETE request."
  [path headers]
  (http/delete (str base-url path)
               {:headers headers
                :throw false}))

;; =============================================================================
;; Health Check Tests
;; =============================================================================

(deftest test-health-endpoint
         (testing "Health check returns 200"
                  (let [resp (http/get (str base-url "/health") {:throw false})]
                    (is (= 200 (:status resp)))
                    (is (clojure.string/includes? (:body resp) "ok")))))

;; =============================================================================
;; Initialize Tests
;; =============================================================================

(deftest test-initialize-flow
         (testing "Initialize creates session"
                  (let [resp (post-json "/mcp"
                                        {:jsonrpc "2.0"
                                         :method "initialize"
                                         :params {:clientInfo {:name "test-client"}}
                                         :id 1})
                        body (util/parse-json (:body resp))
                        session-id (get-in resp [:headers "mcp-session-id"])]
                    (is (= 200 (:status resp)))
                    (is (some? session-id) "Should return session ID header")
                    (is (= "2.0" (:jsonrpc body)))
                    (is (some? (:result body)))
                    (is (= "2025-03-26" (get-in body [:result :protocolVersion]))))))

(deftest test-request-without-session
         (testing "Request without session returns error"
                  (let [resp (post-json "/mcp"
                                        {:jsonrpc "2.0"
                                         :method "tools/list"
                                         :params {}
                                         :id 1})
                        body (util/parse-json (:body resp))]
                    (is (= 400 (:status resp)))
                    (is (= -32003 (get-in body [:error :code]))))))

;; =============================================================================
;; Session Flow Tests
;; =============================================================================

(deftest test-full-session-flow
         (testing "Complete session lifecycle"
    ;; 1. Initialize
                  (let [init-resp (post-json "/mcp"
                                             {:jsonrpc "2.0"
                                              :method "initialize"
                                              :params {:clientInfo {:name "test"}}
                                              :id 1})
                        session-id (get-in init-resp [:headers "mcp-session-id"])]
                    (is (= 200 (:status init-resp)))
                    (is (some? session-id))

      ;; 2. Make request with session
                    (let [list-resp (post-json "/mcp"
                                               {:jsonrpc "2.0"
                                                :method "tools/list"
                                                :params {}
                                                :id 2}
                                               {"mcp-session-id" session-id})
                          body (util/parse-json (:body list-resp))]
                      (is (= 200 (:status list-resp)))
                      (is (vector? (get-in body [:result :tools]))))

      ;; 3. Terminate session
                    (let [del-resp (delete-request "/mcp" {"mcp-session-id" session-id})]
                      (is (= 204 (:status del-resp))))

      ;; 4. Verify session is gone
                    (let [after-resp (post-json "/mcp"
                                                {:jsonrpc "2.0"
                                                 :method "tools/list"
                                                 :params {}
                                                 :id 3}
                                                {"mcp-session-id" session-id})
                          body (util/parse-json (:body after-resp))]
                      (is (= 400 (:status after-resp)))
                      (is (= -32003 (get-in body [:error :code])))))))

;; =============================================================================
;; Batch Request Tests
;; =============================================================================

(deftest test-batch-requests
         (testing "Batch requests work"
    ;; Initialize first
                  (let [init-resp (post-json "/mcp"
                                             {:jsonrpc "2.0"
                                              :method "initialize"
                                              :params {}
                                              :id 1})
                        session-id (get-in init-resp [:headers "mcp-session-id"])]

      ;; Send batch
                    (let [batch-resp (post-json "/mcp"
                                                [{:jsonrpc "2.0" :method "tools/list" :params {} :id 1}
                                                 {:jsonrpc "2.0" :method "echo" :params {:x 1} :id 2}]
                                                {"mcp-session-id" session-id})
                          body (util/parse-json (:body batch-resp))]
                      (is (= 200 (:status batch-resp)))
                      (is (vector? body))
                      (is (= 2 (count body))))

      ;; Cleanup
                    (delete-request "/mcp" {"mcp-session-id" session-id}))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-invalid-json
         (testing "Invalid JSON returns parse error"
                  (let [resp (http/post (str base-url "/mcp")
                                        {:body "not valid json {"
                                         :headers {"Content-Type" "application/json"}
                                         :throw false})
                        body (util/parse-json (:body resp))]
                    (is (= 400 (:status resp)))
                    (is (= -32700 (get-in body [:error :code]))))))

(deftest test-method-not-allowed
         (testing "PUT returns 405"
                  (let [resp (http/put (str base-url "/mcp")
                                       {:body "{}"
                                        :headers {"Content-Type" "application/json"}
                                        :throw false})]
                    (is (= 405 (:status resp))))))

(deftest test-not-found
         (testing "Unknown path returns 404"
                  (let [resp (http/get (str base-url "/unknown") {:throw false})]
                    (is (= 404 (:status resp))))))

;; =============================================================================
;; CORS Tests
;; =============================================================================

(deftest test-cors-headers
         (testing "OPTIONS returns CORS headers"
                  (let [resp (http/request {:method :options
                                            :url (str base-url "/mcp")
                                            :throw false})]
                    (is (= 204 (:status resp)))
                    (is (some? (get-in resp [:headers "access-control-allow-methods"]))))))

(deftest test-cors-on-response
         (testing "Responses include CORS headers"
                  (let [resp (http/get (str base-url "/health") {:throw false})]
                    (is (some? (get-in resp [:headers "access-control-allow-origin"]))))))
