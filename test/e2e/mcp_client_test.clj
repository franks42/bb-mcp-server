(ns e2e.mcp-client-test
    "End-to-end tests for MCP tool invocations via mcp_client.clj.

   These tests verify real tool execution through the full MCP protocol stack.

   Prerequisites:
   - Start a server with echo, calculate, and local-eval modules:
     bb server --http 0 --nickname e2e-test

   Run with:
     bb test:e2e"
    (:require [clojure.test :refer [deftest is testing]]
              [clojure.edn :as edn]
              [bb-mcp-server.mcp-client :as client]))

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def ^:dynamic *test-nickname*
     "Server nickname for E2E tests. Can be overridden for different test scenarios."
     "e2e-test")

(defn get-test-port
  "Get port for the E2E test server."
  []
  (or (client/find-port-by-nickname *test-nickname*)
      (throw (ex-info (str "E2E test server not running. Start with: "
                           "bb server --http 0 --nickname " *test-nickname*)
                      {:nickname *test-nickname*}))))

;; =============================================================================
;; Server Info Tests
;; =============================================================================

(deftest test-get-server-info
         (testing "get-server-info! returns protocol version and capabilities"
                  (let [port (get-test-port)
                        info (client/get-server-info! port)]
                    (is (map? info) "Response should be a map")
                    (is (string? (:protocolVersion info)) "Should have protocol version")
                    (is (map? (:serverInfo info)) "Should have server info")
                    (is (= "bb-mcp-server" (get-in info [:serverInfo :name]))
                        "Server name should be bb-mcp-server")
                    (is (map? (:capabilities info)) "Should have capabilities")
                    (is (get-in info [:capabilities :tools :listChanged])
                        "Should support listChanged"))))

;; =============================================================================
;; Tools List Tests
;; =============================================================================

(deftest test-list-tools
         (testing "list-tools! returns vector of tools"
                  (let [port (get-test-port)
                        tools (client/list-tools! port)]
                    (is (vector? tools) "Response should be a vector")
                    (is (pos? (count tools)) "Should have at least one tool")))

         (testing "Each tool has required fields"
                  (let [port (get-test-port)
                        tools (client/list-tools! port)]
                    (doseq [tool tools]
                           (is (string? (:name tool)) "Tool should have name")
                           (is (map? (:inputSchema tool)) "Tool should have inputSchema")))))

(deftest test-get-tool
         (testing "get-tool returns specific tool by name"
                  (let [port (get-test-port)
                        tool (client/get-tool port "local-eval.local-eval")]
                    (is (some? tool) "Should find local-eval.local-eval tool")
                    (is (= "local-eval.local-eval" (:name tool)))))

         (testing "get-tool returns nil for non-existent tool"
                  (let [port (get-test-port)
                        tool (client/get-tool port "nonexistent.tool")]
                    (is (nil? tool) "Should return nil for non-existent tool"))))

;; =============================================================================
;; Local-Eval Tool Tests
;; =============================================================================

(deftest test-local-eval-basic
         (testing "local-eval evaluates simple expression"
                  (let [port (get-test-port)
                        response (client/call-tool! port "local-eval.local-eval"
                                                    {:code "(+ 1 2 3)"})
                        result (client/extract-json-result response)]
                    (is (= "success" (:status result)))
                    (is (= 6 (:result result)))))

         (testing "local-eval handles strings"
                  (let [port (get-test-port)
                        response (client/call-tool! port "local-eval.local-eval"
                                                    {:code "(str \"hello\" \" \" \"world\")"})
                        result (client/extract-json-result response)]
                    (is (= "success" (:status result)))
                    (is (= "hello world" (:result result)))))

         (testing "local-eval handles data structures"
                  (let [port (get-test-port)
                        response (client/call-tool! port "local-eval.local-eval"
                                                    {:code "(vec (range 5))"})
                        result (client/extract-json-result response)]
                    (is (= "success" (:status result)))
                    ;; local-eval serializes collections as strings in JSON response
                    (is (= "[0 1 2 3 4]" (:result result))))))

(deftest test-local-eval-stdout
         (testing "local-eval captures stdout"
                  (let [port (get-test-port)
                        response (client/call-tool! port "local-eval.local-eval"
                                                    {:code "(do (println \"hello\") 42)"})
                        result (client/extract-json-result response)]
                    (is (= "success" (:status result)))
                    (is (= 42 (:result result)))
                    (is (= "hello\n" (:stdout result))))))

(deftest test-local-eval-error
         (testing "local-eval handles errors"
                  (let [port (get-test-port)
                        response (client/call-tool! port "local-eval.local-eval"
                                                    {:code "(/ 1 0)"})
                        result (client/extract-json-result response)]
                    (is (= "error" (:status result)))
                    (is (string? (:error result))))))

;; =============================================================================
;; Calculate Tool Tests (if available)
;; =============================================================================

(deftest test-calculate-tool
         (testing "calculate tool evaluates math expressions"
                  (let [port (get-test-port)
                        tool (client/get-tool port "calculate.calculate")]
                    (when tool
                      (let [response (client/call-tool! port "calculate.calculate"
                                                        {:expr "(+ 10 20 30)"})
                            ;; Calculate returns EDN, not JSON
                            text (client/extract-text-content response)
                            result (edn/read-string text)]
                        (is (= 60 (:result result))))))))

;; =============================================================================
;; Echo Tool Tests (if available)
;; =============================================================================

(deftest test-echo-tool
         (testing "echo tool returns message"
                  (let [port (get-test-port)
                        tool (client/get-tool port "echo.echo")]
                    (when tool
                      (let [response (client/call-tool! port "echo.echo"
                                                        {:message "hello world"})
                            result (client/extract-text-content response)]
                        (is (= "hello world" result)))))))

;; =============================================================================
;; Hello Tool Tests (if available)
;; =============================================================================

(deftest test-hello-tool
         (testing "hello tool greets by name"
                  (let [port (get-test-port)
                        tool (client/get-tool port "hello.hello")]
                    (when tool
                      (let [response (client/call-tool! port "hello.hello"
                                                        {:name "World"})
                            result (client/extract-text-content response)]
                        (is (string? result))
                        (is (re-find #"World" result)))))))

;; =============================================================================
;; Math Tool Tests (if available)
;; =============================================================================

(deftest test-math-add-tool
         (testing "math add tool sums numbers"
                  (let [port (get-test-port)
                        tool (client/get-tool port "math.add")]
                    (when tool
                      (let [response (client/call-tool! port "math.add"
                                                        {:a 10 :b 20})
                            result (client/extract-tool-result response)]
                        ;; math.add returns the number directly
                        (is (= 30 result)))))))

;; =============================================================================
;; Strings Tool Tests (if available)
;; =============================================================================

(deftest test-strings-concat-tool
         (testing "strings concat tool joins strings"
                  (let [port (get-test-port)
                        tool (client/get-tool port "strings.concat")]
                    (when tool
                      (let [response (client/call-tool! port "strings.concat"
                                                        {:strings ["hello" " " "world"]})
                            result (client/extract-text-content response)]
                        ;; strings.concat returns the joined string directly
                        (is (= "hello world" result)))))))
