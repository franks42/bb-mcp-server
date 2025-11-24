(ns rest-api.handlers-test
    "Tests for REST API handlers."
    (:require [clojure.test :refer [deftest testing is]]
              [clojure.string :as str]
              [rest-api.handlers :as rest]
              [rest-api.openapi :as openapi]
              [http-core.util :as util]))

;; =============================================================================
;; Test Fixtures & Helpers
;; =============================================================================

(def test-tools
     "Sample tools for testing."
     {"add"       {:name "add"
                   :module "math"
                   :description "Add two numbers"
                   :inputSchema {:type "object"
                                 :properties {:a {:type "number"}
                                              :b {:type "number"}}}
                   :handler (fn [{:keys [a b]}] (+ a b))}
      "echo"      {:name "echo"
                   :module "echo"
                   :description "Echo the input"
                   :inputSchema {:type "object"
                                 :properties {:message {:type "string"}}}
                   :handler (fn [{:keys [message]}] {:echoed message})}
      "nrepl-eval" {:name "nrepl-eval"
                    :module "nrepl"
                    :description "Evaluate code in nREPL (stdio only)"
                    :inputSchema {:type "object"
                                  :properties {:code {:type "string"}}}
                    :transports #{:mcp-stdio}
                    :handler (fn [{:keys [code]}] {:result code})}})

(defn list-tools-fn [transport]
  (->> (vals test-tools)
       (filter (fn [tool]
                 (let [transports (or (:transports tool) #{:rest :mcp-http :mcp-stdio})]
                   (contains? transports transport))))
       (map #(select-keys % [:name :description :inputSchema :transports :module]))))

(defn list-modules-fn []
  (->> (vals test-tools)
       (map :module)
       distinct
       sort))

(defn list-tools-for-module-fn [module-name]
  (->> (vals test-tools)
       (filter #(= module-name (:module %)))
       (map #(select-keys % [:name :description :inputSchema :module]))))

(defn get-tool-in-module-fn [module-name tool-name]
  (when-let [tool (get test-tools tool-name)]
            (when (= module-name (:module tool))
              tool)))

(def test-config
     {:list-tools-fn            list-tools-fn
      :list-modules-fn          list-modules-fn
      :list-tools-for-module-fn list-tools-for-module-fn
      :get-tool-in-module-fn    get-tool-in-module-fn})

;; =============================================================================
;; Response Helper Tests
;; =============================================================================

(deftest tool-to-rest-format-test
         (testing "Tool with module gets module-based href"
                  (let [tool {:name "add"
                              :module "math"
                              :description "Add numbers"
                              :inputSchema {:type "object"}}
                        result (#'rest/tool-to-rest-format tool)]
                    (is (= "/api/modules/math/tools/add" (:href result)))
                    (is (= "add" (:name result)))
                    (is (= "math" (:module result)))))

         (testing "Tool without module gets flat href"
                  (let [tool {:name "legacy"
                              :description "Legacy tool"
                              :inputSchema {:type "object"}}
                        result (#'rest/tool-to-rest-format tool)]
                    (is (= "/api/tools/legacy" (:href result)))
                    (is (nil? (:module result))))))

;; =============================================================================
;; Module-based Handler Tests
;; =============================================================================

(deftest handle-list-modules-test
         (testing "Lists all modules"
                  (let [response (rest/handle-list-modules {} list-modules-fn)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (= 3 (:count body)))
                    (is (some #(= "math" (:name %)) (:modules body)))
                    (is (some #(= "echo" (:name %)) (:modules body))))))

(deftest handle-list-module-tools-test
         (testing "Lists tools for a module"
                  (let [response (rest/handle-list-module-tools {} "math" list-tools-for-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (= "math" (:module body)))
                    (is (= 1 (:count body)))
                    (is (= "add" (get-in body [:tools 0 :name])))))

         (testing "Returns 404 for non-existent module"
                  (let [response (rest/handle-list-module-tools {} "nonexistent" list-tools-for-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 404 (:status response)))
                    (is (str/includes? (:message body) "not found")))))

(deftest handle-get-module-tool-test
         (testing "Returns tool metadata for tool in module"
                  (let [response (rest/handle-get-module-tool {} "math" "add" get-tool-in-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (= "add" (:name body)))
                    (is (= "math" (:module body)))))

         (testing "Returns 404 for tool not in module"
                  (let [response (rest/handle-get-module-tool {} "math" "echo" get-tool-in-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 404 (:status response))))))

(deftest handle-call-module-tool-test
         (testing "Calls tool and returns result"
                  (let [request {:body "{\"a\": 2, \"b\": 3}"}
                        response (rest/handle-call-module-tool request "math" "add" get-tool-in-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (= 5 (:result body)))
                    (is (= "add" (:tool body)))
                    (is (= "math" (:module body)))))

         (testing "Returns 404 for tool not in module"
                  (let [request {:body "{}"}
                        response (rest/handle-call-module-tool request "math" "echo" get-tool-in-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 404 (:status response)))))

         (testing "Returns 400 for invalid JSON"
                  (let [request {:body "not json"}
                        response (rest/handle-call-module-tool request "math" "add" get-tool-in-module-fn)
                        body (util/parse-json (:body response))]
                    (is (= 400 (:status response)))
                    (is (str/includes? (:message body) "Invalid JSON")))))

;; =============================================================================
;; Router Tests
;; =============================================================================

(deftest create-rest-router-test
         (let [router (rest/create-rest-router test-config)]

           (testing "GET /api/modules returns module list"
                    (let [response (router {:uri "/api/modules" :request-method :get})
                          body (util/parse-json (:body response))]
                      (is (= 200 (:status response)))
                      (is (= 3 (:count body)))))

           (testing "GET /api/modules/:module/tools returns tools"
                    (let [response (router {:uri "/api/modules/math/tools" :request-method :get})
                          body (util/parse-json (:body response))]
                      (is (= 200 (:status response)))
                      (is (= 1 (:count body)))))

           (testing "GET /api/modules/:module/tools/:name returns tool metadata"
                    (let [response (router {:uri "/api/modules/math/tools/add" :request-method :get})
                          body (util/parse-json (:body response))]
                      (is (= 200 (:status response)))
                      (is (= "add" (:name body)))))

           (testing "POST /api/modules/:module/tools/:name calls tool"
                    (let [response (router {:uri "/api/modules/math/tools/add"
                                            :request-method :post
                                            :body "{\"a\": 10, \"b\": 5}"})
                          body (util/parse-json (:body response))]
                      (is (= 200 (:status response)))
                      (is (= 15 (:result body)))))

           (testing "OPTIONS returns CORS headers"
                    (let [response (router {:uri "/api/modules" :request-method :options})]
                      (is (= 204 (:status response)))
                      (is (contains? (:headers response) "Access-Control-Allow-Origin"))))

           (testing "PUT returns 405"
                    (let [response (router {:uri "/api/modules" :request-method :put})]
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
;; Server Info Tests
;; =============================================================================

(deftest handle-server-info-test
         (testing "Returns server info when configured"
                  (let [server-info-fn (fn [] {:name "test-server"
                                               :version "1.0.0"
                                               :moduleToolSeparator "."
                                               :mcpProtocolVersion "2025-03-26"})
                        response (rest/handle-server-info {} server-info-fn)
                        body (util/parse-json (:body response))]
                    (is (= 200 (:status response)))
                    (is (= "test-server" (:name body)))
                    (is (= "1.0.0" (:version body)))
                    (is (= "." (:moduleToolSeparator body)))
                    (is (= "2025-03-26" (:mcpProtocolVersion body))))))

(deftest create-rest-router-server-info-test
         (let [server-info-fn (fn [] {:name "bb-mcp-server"
                                      :version "0.1.0"
                                      :moduleToolSeparator "."})
               config (assoc test-config :server-info-fn server-info-fn)
               router (rest/create-rest-router config)]

           (testing "GET /api/server returns server info"
                    (let [response (router {:uri "/api/server" :request-method :get})
                          body (util/parse-json (:body response))]
                      (is (= 200 (:status response)))
                      (is (= "bb-mcp-server" (:name body)))
                      (is (= "." (:moduleToolSeparator body)))))))

;; =============================================================================
;; OpenAPI Tests
;; =============================================================================

(deftest openapi-generate-spec-test
         (testing "Generates valid OpenAPI 3.0 spec"
                  (let [tools [{:name "add"
                                :module "math"
                                :description "Add numbers"
                                :inputSchema {:type "object"
                                              :properties {:a {:type "number"}}}}]
                        spec (openapi/generate-spec tools)]
                    (is (= "3.0.3" (:openapi spec)))
                    (is (= "bb-mcp-server REST API" (get-in spec [:info :title])))
                    (is (map? (:paths spec)))))

         (testing "Custom options override defaults"
                  (let [spec (openapi/generate-spec [] {:title "My API"
                                                        :version "2.0.0"
                                                        :server-url "http://myserver:8080"})]
                    (is (= "My API" (get-in spec [:info :title])))
                    (is (= "2.0.0" (get-in spec [:info :version])))
                    (is (= "http://myserver:8080" (get-in spec [:servers 0 :url]))))))
