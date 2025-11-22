(ns streamable-http.middleware-test
    "Tests for Ring middleware."
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [clojure.string :as str]
              [streamable-http.middleware :as mw]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-rate-limits-fixture
  "Reset rate limit buckets before each test."
  [f]
  (mw/reset-rate-limits!)
  (f)
  (mw/reset-rate-limits!))

(use-fixtures :each reset-rate-limits-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- echo-handler
  "Simple handler that echoes request info."
  [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (str "{\"uri\":\"" (:uri request) "\"}")})

(defn- make-request
  "Create a minimal Ring request map."
  [& {:keys [method uri headers remote-addr]
      :or {method :get
           uri "/test"
           headers {}
           remote-addr "127.0.0.1"}}]
  {:request-method method
   :uri uri
   :headers headers
   :remote-addr remote-addr})

;; =============================================================================
;; CORS Middleware Tests
;; =============================================================================

(deftest test-wrap-cors-preflight
         (testing "OPTIONS request returns 204 with CORS headers"
                  (let [handler (mw/wrap-cors echo-handler)
                        request (make-request :method :options
                                              :headers {"origin" "http://example.com"})
                        response (handler request)]
                    (is (= 204 (:status response)))
                    (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
                    (is (some? (get-in response [:headers "Access-Control-Allow-Methods"])))
                    (is (some? (get-in response [:headers "Access-Control-Allow-Headers"]))))))

(deftest test-wrap-cors-allowed-origins
         (testing "Allowed origin gets CORS headers"
                  (let [handler (mw/wrap-cors echo-handler
                                              {:allowed-origins #{"http://allowed.com"}})
                        request (make-request :headers {"origin" "http://allowed.com"})
                        response (handler request)]
                    (is (= 200 (:status response)))
                    (is (= "http://allowed.com"
                           (get-in response [:headers "Access-Control-Allow-Origin"])))))

         (testing "Disallowed origin does not get CORS headers"
                  (let [handler (mw/wrap-cors echo-handler
                                              {:allowed-origins #{"http://allowed.com"}})
                        request (make-request :headers {"origin" "http://evil.com"})
                        response (handler request)]
                    (is (= 200 (:status response)))
      ;; Response still returned but without CORS headers added
                    (is (nil? (get-in response [:headers "Access-Control-Allow-Origin"]))))))

(deftest test-wrap-cors-wildcard
         (testing "Wildcard origin allows all"
                  (let [handler (mw/wrap-cors echo-handler
                                              {:allowed-origins #{"*"}})
                        request (make-request :headers {"origin" "http://any.com"})
                        response (handler request)]
                    (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"]))))))

(deftest test-wrap-cors-custom-headers
         (testing "Custom allowed headers are included"
                  (let [handler (mw/wrap-cors echo-handler
                                              {:allowed-headers #{"x-custom-header" "content-type"}
                                               :expose-headers #{"x-response-header"}})
                        request (make-request :method :options
                                              :headers {"origin" "http://example.com"})
                        response (handler request)
                        allowed (get-in response [:headers "Access-Control-Allow-Headers"])
                        exposed (get-in response [:headers "Access-Control-Expose-Headers"])]
                    (is (str/includes? allowed "x-custom-header"))
                    (is (str/includes? exposed "x-response-header")))))

(deftest test-wrap-cors-max-age
         (testing "Custom max-age is set"
                  (let [handler (mw/wrap-cors echo-handler {:max-age 3600})
                        request (make-request :method :options
                                              :headers {"origin" "http://example.com"})
                        response (handler request)]
                    (is (= "3600" (get-in response [:headers "Access-Control-Max-Age"]))))))

;; =============================================================================
;; Rate Limiting Tests
;; =============================================================================

(deftest test-wrap-rate-limit-allows-requests
         (testing "Requests within limit are allowed"
                  (let [handler (mw/wrap-rate-limit echo-handler {:requests-per-minute 60})
                        request (make-request :remote-addr "10.0.0.1")
                        response (handler request)]
                    (is (= 200 (:status response))))))

(deftest test-wrap-rate-limit-blocks-excess
         (testing "Excess requests are blocked"
                  (let [handler (mw/wrap-rate-limit echo-handler
                                                    {:requests-per-minute 2
                                                     :burst 2})
                        request (make-request :remote-addr "10.0.0.2")]
      ;; First 2 requests should succeed
                    (is (= 200 (:status (handler request))))
                    (is (= 200 (:status (handler request))))
      ;; Third request should be rate limited
                    (let [response (handler request)]
                      (is (= 429 (:status response)))
                      (is (= "60" (get-in response [:headers "Retry-After"])))))))

(deftest test-wrap-rate-limit-per-client
         (testing "Rate limits are per-client"
                  (let [handler (mw/wrap-rate-limit echo-handler
                                                    {:requests-per-minute 1
                                                     :burst 1})
                        req1 (make-request :remote-addr "client-1")
                        req2 (make-request :remote-addr "client-2")]
      ;; Both clients can make one request
                    (is (= 200 (:status (handler req1))))
                    (is (= 200 (:status (handler req2))))
      ;; Second requests should be limited
                    (is (= 429 (:status (handler req1))))
                    (is (= 429 (:status (handler req2)))))))

(deftest test-wrap-rate-limit-custom-key-fn
         (testing "Custom key function works"
                  (let [handler (mw/wrap-rate-limit echo-handler
                                                    {:requests-per-minute 1
                                                     :burst 1
                                                     :key-fn #(get-in % [:headers "x-api-key"])})
                        req1 (make-request :headers {"x-api-key" "key-1"})
                        req2 (make-request :headers {"x-api-key" "key-2"})]
      ;; Different keys = different buckets
                    (is (= 200 (:status (handler req1))))
                    (is (= 200 (:status (handler req2))))
                    (is (= 429 (:status (handler req1)))))))

(deftest test-reset-rate-limits
         (testing "reset-rate-limits! clears all buckets"
                  (let [handler (mw/wrap-rate-limit echo-handler
                                                    {:requests-per-minute 1
                                                     :burst 1})
                        request (make-request :remote-addr "10.0.0.3")]
      ;; Exhaust the limit
                    (handler request)
                    (is (= 429 (:status (handler request))))
      ;; Reset
                    (mw/reset-rate-limits!)
      ;; Should work again
                    (is (= 200 (:status (handler request)))))))

;; =============================================================================
;; Basic Auth Tests
;; =============================================================================

(deftest test-wrap-basic-auth-valid-credentials
         (testing "Valid credentials are accepted"
                  (let [handler (mw/wrap-basic-auth echo-handler
                                                    {:credentials {"admin" "secret"}})
          ;; Base64 encode "admin:secret"
                        auth-header (str "Basic " (.encodeToString
                                                   (java.util.Base64/getEncoder)
                                                   (.getBytes "admin:secret" "UTF-8")))
                        request (make-request :headers {"authorization" auth-header})
                        response (handler request)]
                    (is (= 200 (:status response))))))

(deftest test-wrap-basic-auth-invalid-credentials
         (testing "Invalid credentials are rejected"
                  (let [handler (mw/wrap-basic-auth echo-handler
                                                    {:credentials {"admin" "secret"}})
                        auth-header (str "Basic " (.encodeToString
                                                   (java.util.Base64/getEncoder)
                                                   (.getBytes "admin:wrong" "UTF-8")))
                        request (make-request :headers {"authorization" auth-header})
                        response (handler request)]
                    (is (= 401 (:status response)))
                    (is (str/includes? (get-in response [:headers "WWW-Authenticate"]) "Basic realm")))))

(deftest test-wrap-basic-auth-missing-header
         (testing "Missing auth header returns 401"
                  (let [handler (mw/wrap-basic-auth echo-handler
                                                    {:credentials {"admin" "secret"}})
                        request (make-request)
                        response (handler request)]
                    (is (= 401 (:status response)))
                    (is (str/includes? (:body response) "Authentication required")))))

(deftest test-wrap-basic-auth-excluded-paths
         (testing "Excluded paths bypass auth"
                  (let [handler (mw/wrap-basic-auth echo-handler
                                                    {:credentials {"admin" "secret"}
                                                     :exclude #{"/health" "/public"}})
                        request (make-request :uri "/health")
                        response (handler request)]
                    (is (= 200 (:status response))))))

(deftest test-wrap-basic-auth-custom-realm
         (testing "Custom realm is used"
                  (let [handler (mw/wrap-basic-auth echo-handler
                                                    {:credentials {"admin" "secret"}
                                                     :realm "My API"})
                        request (make-request)
                        response (handler request)]
                    (is (str/includes? (get-in response [:headers "WWW-Authenticate"]) "My API")))))

;; =============================================================================
;; Request Logging Tests
;; =============================================================================

(deftest test-wrap-request-logging-passes-through
         (testing "Request logging passes request through"
                  (let [handler (mw/wrap-request-logging echo-handler)
                        request (make-request :uri "/test-logging")
                        response (handler request)]
                    (is (= 200 (:status response)))
                    (is (str/includes? (:body response) "/test-logging")))))

(deftest test-wrap-request-logging-with-options
         (testing "Request logging accepts options"
                  (let [handler (mw/wrap-request-logging echo-handler
                                                         {:level :debug
                                                          :include-body false})
                        request (make-request)
                        response (handler request)]
                    (is (= 200 (:status response))))))

;; =============================================================================
;; Origin Validation Tests
;; =============================================================================

(deftest test-wrap-origin-validation-allows-valid
         (testing "Valid host is allowed"
                  (let [handler (mw/wrap-origin-validation echo-handler
                                                           {:allowed-hosts #{"localhost:3000"}})
                        request (make-request :headers {"host" "localhost:3000"})
                        response (handler request)]
                    (is (= 200 (:status response))))))

(deftest test-wrap-origin-validation-blocks-invalid
         (testing "Invalid host is blocked"
                  (let [handler (mw/wrap-origin-validation echo-handler
                                                           {:allowed-hosts #{"localhost:3000"}})
                        request (make-request :headers {"host" "evil.com"})
                        response (handler request)]
                    (is (= 403 (:status response)))
                    (is (str/includes? (:body response) "Invalid host")))))

(deftest test-wrap-origin-validation-empty-allows-all
         (testing "Empty allowed-hosts allows all"
                  (let [handler (mw/wrap-origin-validation echo-handler
                                                           {:allowed-hosts #{}})
                        request (make-request :headers {"host" "any-host.com"})
                        response (handler request)]
                    (is (= 200 (:status response))))))

;; =============================================================================
;; Middleware Composition Tests
;; =============================================================================

(deftest test-apply-middleware-order
         (testing "Middleware applied in correct order"
                  (let [calls (atom [])
                        mw1 (fn [h]
                              (fn [req]
                                (swap! calls conj :mw1-before)
                                (let [resp (h req)]
                                  (swap! calls conj :mw1-after)
                                  resp)))
                        mw2 (fn [h]
                              (fn [req]
                                (swap! calls conj :mw2-before)
                                (let [resp (h req)]
                                  (swap! calls conj :mw2-after)
                                  resp)))
                        handler (mw/apply-middleware echo-handler [mw1 mw2])
                        _response (handler (make-request))]
      ;; mw1 is outermost, so its :before runs first
                    (is (= [:mw1-before :mw2-before :mw2-after :mw1-after] @calls)))))

(deftest test-apply-middleware-empty
         (testing "Empty middleware list returns handler unchanged"
                  (let [handler (mw/apply-middleware echo-handler [])
                        response (handler (make-request))]
                    (is (= 200 (:status response))))))

(deftest test-apply-middleware-composition
         (testing "Multiple middleware compose correctly"
                  (let [handler (mw/apply-middleware
                                 echo-handler
                                 [(fn [h] (mw/wrap-cors h))
                                  (fn [h] (mw/wrap-request-logging h))])
                        request (make-request :headers {"origin" "http://test.com"})
                        response (handler request)]
                    (is (= 200 (:status response)))
      ;; CORS headers should be present
                    (is (some? (get-in response [:headers "Access-Control-Allow-Origin"]))))))
