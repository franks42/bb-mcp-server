(ns mcp-nrepl.tools.introspection-test
    "Unit tests for Phase 0 introspection tools.

   Tests response formatting logic using mocked delegation.
   Integration tests (requiring live nREPL) are in a separate file."
    (:require [clojure.test :refer [deftest is testing]]
              [cheshire.core :as json]
              [mcp-nrepl.tools.nrepl-loaded-namespaces :as loaded-ns]
              [mcp-nrepl.tools.nrepl-introspect-ns :as introspect-ns]
              [mcp-nrepl.tools.nrepl-var-meta :as var-meta]
              [mcp-nrepl.tools.nrepl-get-value :as get-value]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- parse-response
  "Parse MCP tool response text to map."
  [response]
  (-> response :content first :text (json/parse-string true)))

;; =============================================================================
;; Tool Metadata Tests
;; =============================================================================

(deftest loaded-namespaces-metadata-test
         (testing "nrepl-loaded-namespaces has correct metadata"
                  (is (= "nrepl-loaded-namespaces" loaded-ns/tool-name))
                  (is (string? (:description loaded-ns/metadata)))
                  (is (contains? (:inputSchema loaded-ns/metadata) :properties))
                  (is (contains? (get-in loaded-ns/metadata [:inputSchema :properties]) :connection))
                  (is (contains? (get-in loaded-ns/metadata [:inputSchema :properties]) :prefix))
                  (is (contains? (get-in loaded-ns/metadata [:inputSchema :properties]) :timeout))))

(deftest introspect-ns-metadata-test
         (testing "nrepl-introspect-ns has correct metadata"
                  (is (= "nrepl-introspect-ns" introspect-ns/tool-name))
                  (is (string? (:description introspect-ns/metadata)))
                  (is (contains? (get-in introspect-ns/metadata [:inputSchema :properties]) :ns))
                  (is (contains? (get-in introspect-ns/metadata [:inputSchema :properties]) :include-meta))
                  (is (= ["ns"] (get-in introspect-ns/metadata [:inputSchema :required])))))

(deftest var-meta-metadata-test
         (testing "nrepl-var-meta has correct metadata"
                  (is (= "nrepl-var-meta" var-meta/tool-name))
                  (is (string? (:description var-meta/metadata)))
                  (is (contains? (get-in var-meta/metadata [:inputSchema :properties]) :symbol))
                  (is (= ["symbol"] (get-in var-meta/metadata [:inputSchema :required])))))

(deftest get-value-metadata-test
         (testing "nrepl-get-value has correct metadata"
                  (is (= "nrepl-get-value" get-value/tool-name))
                  (is (string? (:description get-value/metadata)))
                  (is (contains? (get-in get-value/metadata [:inputSchema :properties]) :symbol))
                  (is (contains? (get-in get-value/metadata [:inputSchema :properties]) :max-length))
                  (is (= ["symbol"] (get-in get-value/metadata [:inputSchema :required])))))

;; =============================================================================
;; Validation Tests (no mocking needed)
;; =============================================================================

(deftest introspect-ns-validation-test
         (testing "nrepl-introspect-ns requires ns parameter"
                  (let [response (introspect-ns/handle {})]
                    (is (:isError response))
                    (let [parsed (parse-response response)]
                      (is (= "error" (:status parsed)))
                      (is (= "Namespace required" (:error parsed)))))))

(deftest var-meta-validation-test
         (testing "nrepl-var-meta requires symbol parameter"
                  (let [response (var-meta/handle {})]
                    (is (:isError response))
                    (let [parsed (parse-response response)]
                      (is (= "error" (:status parsed)))
                      (is (= "Symbol required" (:error parsed)))))))

(deftest get-value-validation-test
         (testing "nrepl-get-value requires symbol parameter"
                  (let [response (get-value/handle {})]
                    (is (:isError response))
                    (let [parsed (parse-response response)]
                      (is (= "error" (:status parsed)))
                      (is (= "Symbol required" (:error parsed)))))))

;; =============================================================================
;; Tool Count Verification
;; =============================================================================

(deftest all-tools-exported-test
         (testing "All four Phase 0 introspection tools are defined"
                  (is (fn? loaded-ns/handle) "loaded-namespaces handler exists")
                  (is (fn? introspect-ns/handle) "introspect-ns handler exists")
                  (is (fn? var-meta/handle) "var-meta handler exists")
                  (is (fn? get-value/handle) "get-value handler exists")))
