#!/usr/bin/env bb
;; HTTP Integration Test Script
;; Usage: bb scripts/http_integration_test.clj [port]
;; Default port: 3000
;;
;; Tests the MCP HTTP server with JSON-RPC requests.
;; Starts a server, runs tests, reports results, shuts down.

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def test-port (or (some-> (first *command-line-args*) parse-long) 3000))
(def base-url (str "http://localhost:" test-port))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(def test-results (atom {:passed 0 :failed 0 :tests []}))

(defn record-result! [test-name passed? message]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name test-name :passed passed? :message message})
  (println (str (if passed? "✅" "❌") " " test-name ": " message)))

(defn json-rpc-request [method params & {:keys [id] :or {id 1}}]
  {:jsonrpc "2.0"
   :method method
   :params (or params {})
   :id id})

(defn send-request [request]
  (try
    (let [response (http/post (str base-url "/mcp")
                              {:headers {"Content-Type" "application/json"}
                               :body (json/generate-string request)
                               :throw false})]
      {:status (:status response)
       :body (when (:body response)
               (try
                 (json/parse-string (:body response) true)
                 (catch Exception _
                   (:body response))))})
    (catch Exception e
      {:error (ex-message e)})))

;; =============================================================================
;; Test Cases
;; =============================================================================

(defn test-health-check []
  (let [response (http/get (str base-url "/health") {:throw false})]
    (if (= 200 (:status response))
      (record-result! "Health Check" true "Server is healthy")
      (record-result! "Health Check" false (str "Status: " (:status response))))))

(defn test-initialize []
  (let [req (json-rpc-request "initialize"
                               {:protocolVersion "1.0"
                                :clientInfo {:name "integration-test" :version "1.0.0"}})
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "Initialize" false error)

      (= "2.0" (:jsonrpc body))
      (record-result! "Initialize" true "Valid JSON-RPC response")

      :else
      (record-result! "Initialize" false (str "Unexpected: " body)))))

(defn test-tools-list []
  (let [req (json-rpc-request "tools/list" {})
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "Tools List" false error)

      (and (:result body) (sequential? (get-in body [:result :tools])))
      (record-result! "Tools List" true
                      (str "Found " (count (get-in body [:result :tools])) " tools"))

      :else
      (record-result! "Tools List" false (str "Unexpected: " body)))))

(defn test-tools-call-hello []
  (let [req (json-rpc-request "tools/call" {:name "hello" :arguments {:name "Test"}})
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "Tools Call (hello)" false error)

      (get-in body [:result :content])
      (let [text (get-in body [:result :content 0 :text])]
        ;; Accept either "Hello" or "Hi" since greeting is configurable
        (if (and text (or (str/includes? text "Hello")
                         (str/includes? text "Hi")))
          (record-result! "Tools Call (hello)" true text)
          (record-result! "Tools Call (hello)" false (str "Missing greeting: " text))))

      :else
      (record-result! "Tools Call (hello)" false (str "Unexpected: " body)))))

(defn test-tools-call-add []
  (let [req (json-rpc-request "tools/call" {:name "add" :arguments {:a 5 :b 3}})
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "Tools Call (add)" false error)

      (get-in body [:result :content])
      (let [text (get-in body [:result :content 0 :text])]
        (if (and text (str/includes? text "8"))
          (record-result! "Tools Call (add)" true text)
          (record-result! "Tools Call (add)" false (str "Expected 8: " text))))

      :else
      (record-result! "Tools Call (add)" false (str "Unexpected: " body)))))

(defn test-invalid-json []
  (try
    (let [response (http/post (str base-url "/mcp")
                              {:headers {"Content-Type" "application/json"}
                               :body "not valid json"
                               :throw false})
          body (json/parse-string (:body response) true)]
      (if (= -32700 (get-in body [:error :code]))
        (record-result! "Invalid JSON" true "Correct parse error code")
        (record-result! "Invalid JSON" false (str "Expected -32700: " body))))
    (catch Exception e
      (record-result! "Invalid JSON" false (ex-message e)))))

(defn test-unknown-method []
  (let [req (json-rpc-request "unknown/method" {})
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "Unknown Method" false error)

      (= -32601 (get-in body [:error :code]))
      (record-result! "Unknown Method" true "Correct method not found error")

      :else
      (record-result! "Unknown Method" false (str "Expected -32601: " body)))))

(defn test-request-id-preservation []
  (let [test-id "test-id-12345"
        req (json-rpc-request "tools/list" {} :id test-id)
        {:keys [body error]} (send-request req)]
    (cond
      error
      (record-result! "ID Preservation" false error)

      (= test-id (:id body))
      (record-result! "ID Preservation" true "Request ID correctly preserved")

      :else
      (record-result! "ID Preservation" false (str "ID mismatch: " (:id body))))))

;; =============================================================================
;; Main Test Runner
;; =============================================================================

(defn run-tests []
  (println "=" (apply str (repeat 60 "=")))
  (println "HTTP Integration Tests")
  (println (str "Server URL: " base-url))
  (println "=" (apply str (repeat 60 "=")))
  (println)

  ;; Run all tests
  (test-health-check)
  (test-initialize)
  (test-tools-list)
  (test-tools-call-hello)
  (test-tools-call-add)
  (test-invalid-json)
  (test-unknown-method)
  (test-request-id-preservation)

  ;; Print summary
  (println)
  (println "=" (apply str (repeat 60 "=")))
  (println "Summary:")
  (println "  Passed:" (:passed @test-results))
  (println "  Failed:" (:failed @test-results))
  (println "=" (apply str (repeat 60 "=")))

  ;; Exit with appropriate code
  (when (pos? (:failed @test-results))
    (System/exit 1)))

;; Check if server is running first
(try
  (http/get (str base-url "/health") {:throw true :timeout 2000})
  (run-tests)
  (catch Exception _
    (println "❌ Server not running at" base-url)
    (println "Start server first with: bb server:http" test-port)
    (System/exit 1)))
