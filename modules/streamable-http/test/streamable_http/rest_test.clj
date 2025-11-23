(ns streamable-http.rest-test
  "Tests for REST API handlers."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [streamable-http.handlers.rest :as rest]
            [streamable-http.openapi :as openapi]
            [streamable-http.util :as util]))

;; =============================================================================
;; Test Fixtures & Helpers
;; =============================================================================

(def test-tools
  "Sample tools for testing."
  {"add"       {:name "add"
                :description "Add two numbers"
                :inputSchema {:type "object"
                              :properties {:a {:type "number"}
                                           :b {:type "number"}}}}
   "echo"      {:name "echo"
                :description "Echo the input"
                :inputSchema {:type "object"
                              :properties {:message {:type "string"}}}}
   "nrepl-eval" {:name "nrepl-eval"
                 :description "Evaluate code in nREPL (stdio only)"
                 :inputSchema {:type "object"
                               :properties {:code {:type "string"}}}
                 :transports #{:mcp-stdio}}})

(def test-handlers
  "Sample handlers for testing."
  {"add"  (fn [{:keys [a b]}] (+ a b))
   "echo" (fn [{:keys [message]}] {:echoed message})
   "nrepl-eval" (fn [{:keys [code]}] {:result code})})

(defn list-tools-fn [transport]
  (->> (vals test-tools)
       (filter (fn [tool]
                 (let [transports (or (:transports tool) #{:rest :mcp-http :mcp-stdio})]
                   (contains? transports transport))))
       (map #(select-keys % [:name :description :inputSchema :transports]))))

(defn get-tool-fn [name]
  (get test-tools name))

(defn get-handler-fn [name]
  (get test-handlers name))

(defn supports-rest-fn [name transport]
  (when-let [tool (get test-tools name)]
    (let [transports (or (:transports tool) #{:rest :mcp-http :mcp-stdio})]
      (contains? transports transport))))

(def test-config
  {:list-tools-fn    list-tools-fn
   :get-tool-fn      get-tool-fn
   :get-handler-fn   get-handler-fn
   :supports-rest-fn supports-rest-fn})

;; =============================================================================
;; Response Helper Tests
;; =============================================================================

(deftest tool-to-rest-format-test
  (testing "Tool transformation includes href"
    (let [tool {:name "add"
                :description "Add numbers"
                :inputSchema {:type "object"}}
          result (#'rest/tool-to-rest-format tool)]
      (is (= "/api/tools/add" (:href result)))
      (is (= "add" (:name result))))))

;; =============================================================================
;; List Tools Tests
;; =============================================================================

(deftest handle-list-tools-test
  (testing "Lists only REST-enabled tools"
    (let [response (rest/handle-list-tools {} list-tools-fn)
          body (util/parse-json (:body response))]
      (is (= 200 (:status response)))
      (is (= 2 (:count body)) "Should exclude nrepl-eval (stdio-only)")
      (is (some #(= "add" (:name %)) (:tools body)))
      (is (some #(= "echo" (:name %)) (:tools body)))
      (is (not (some #(= "nrepl-eval" (:name %)) (:tools body)))))))

;; =============================================================================
;; Get Tool Tests
;; =============================================================================

(deftest handle-get-tool-test
  (testing "Returns tool metadata for REST-enabled tool"
    (let [response (rest/handle-get-tool {} "add" get-tool-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 200 (:status response)))
      (is (= "add" (:name body)))
      (is (= "/api/tools/add" (:href body)))))

  (testing "Returns 404 for non-REST tool"
    (let [response (rest/handle-get-tool {} "nrepl-eval" get-tool-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 404 (:status response)))
      (is (clojure.string/includes? (:message body) "not available via REST"))))

  (testing "Returns 404 for non-existent tool"
    (let [response (rest/handle-get-tool {} "nonexistent" get-tool-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 404 (:status response)))
      (is (clojure.string/includes? (:message body) "not found")))))

;; =============================================================================
;; Call Tool Tests
;; =============================================================================

(deftest handle-call-tool-test
  (testing "Calls tool and returns result"
    (let [request {:body "{\"a\": 2, \"b\": 3}"}
          response (rest/handle-call-tool request "add" get-handler-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 200 (:status response)))
      (is (= 5 (:result body)))
      (is (= "add" (:tool body)))))

  (testing "Handles empty body as empty params"
    (let [request {:body ""}
          response (rest/handle-call-tool request "echo" get-handler-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 200 (:status response)))
      (is (map? (:result body)))))

  (testing "Returns 404 for non-REST tool"
    (let [request {:body "{\"code\": \"(+ 1 2)\"}"}
          response (rest/handle-call-tool request "nrepl-eval" get-handler-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 404 (:status response)))))

  (testing "Returns 400 for invalid JSON"
    (let [request {:body "not json"}
          response (rest/handle-call-tool request "add" get-handler-fn supports-rest-fn)
          body (util/parse-json (:body response))]
      (is (= 400 (:status response)))
      (is (clojure.string/includes? (:message body) "Invalid JSON")))))

;; =============================================================================
;; Router Tests
;; =============================================================================

(deftest create-rest-router-test
  (let [router (rest/create-rest-router test-config)]

    (testing "GET /api/tools returns tool list"
      (let [response (router {:uri "/api/tools" :request-method :get})
            body (util/parse-json (:body response))]
        (is (= 200 (:status response)))
        (is (= 2 (:count body)))))

    (testing "GET /api/tools/:name returns tool metadata"
      (let [response (router {:uri "/api/tools/add" :request-method :get})
            body (util/parse-json (:body response))]
        (is (= 200 (:status response)))
        (is (= "add" (:name body)))))

    (testing "POST /api/tools/:name calls tool"
      (let [response (router {:uri "/api/tools/add"
                              :request-method :post
                              :body "{\"a\": 10, \"b\": 5}"})
            body (util/parse-json (:body response))]
        (is (= 200 (:status response)))
        (is (= 15 (:result body)))))

    (testing "OPTIONS returns CORS headers"
      (let [response (router {:uri "/api/tools" :request-method :options})]
        (is (= 204 (:status response)))
        (is (contains? (:headers response) "Access-Control-Allow-Origin"))))

    (testing "PUT returns 405"
      (let [response (router {:uri "/api/tools" :request-method :put})]
        (is (= 405 (:status response)))))

    (testing "Non-API path returns nil"
      (let [response (router {:uri "/other/path" :request-method :get})]
        (is (nil? response))))

    (testing "GET /api/openapi.json returns OpenAPI spec"
      (let [response (router {:uri "/api/openapi.json" :request-method :get})
            body (util/parse-json (:body response))]
        (is (= 200 (:status response)))
        (is (= "3.0.3" (:openapi body)))
        (is (map? (:info body)))
        (is (map? (:paths body)))))))

;; =============================================================================
;; OpenAPI Tests
;; =============================================================================

(deftest openapi-generate-spec-test
  (testing "Generates valid OpenAPI 3.0 spec"
    (let [tools [{:name "add"
                  :description "Add numbers"
                  :inputSchema {:type "object"
                                :properties {:a {:type "number"}}}}]
          spec (openapi/generate-spec tools)]
      (is (= "3.0.3" (:openapi spec)))
      (is (= "bb-mcp-server REST API" (get-in spec [:info :title])))
      (is (map? (:paths spec)))
      (is (contains? (:paths spec) "/api/tools"))
      (is (contains? (:paths spec) "/api/tools/add"))))

  (testing "Custom options override defaults"
    (let [spec (openapi/generate-spec [] {:title "My API"
                                           :version "2.0.0"
                                           :server-url "http://myserver:8080"})]
      (is (= "My API" (get-in spec [:info :title])))
      (is (= "2.0.0" (get-in spec [:info :version])))
      (is (= "http://myserver:8080" (get-in spec [:servers 0 :url])))))

  (testing "Tool paths include GET and POST operations"
    (let [tools [{:name "test-tool"
                  :description "Test"
                  :inputSchema {:type "object"}}]
          spec (openapi/generate-spec tools)
          path (get-in spec [:paths "/api/tools/test-tool"])]
      (is (map? (:get path)))
      (is (map? (:post path)))
      (is (= "call-test-tool" (get-in path [:post :operationId]))))))
