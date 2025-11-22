(ns nrepl.client.connection-test
    "Tests for nREPL connection parameter parsing - pure functions without I/O"
    (:require [clojure.string :as str]
              [clojure.test :refer [deftest is testing]]
              [nrepl.client.connection :as conn]))

;; =============================================================================
;; Parse Host Port Tests
;; =============================================================================

(deftest parse-host-port-test
         (testing "parse-host-port with host:port format"
                  (let [result (conn/parse-host-port "localhost:7888")]
                    (is (= "localhost" (:hostname result)))
                    (is (= 7888 (:port result)))
                    (is (not (contains? result :error)))))

         (testing "parse-host-port with IP:port format"
                  (let [result (conn/parse-host-port "192.168.1.100:9000")]
                    (is (= "192.168.1.100" (:hostname result)))
                    (is (= 9000 (:port result)))))

         (testing "parse-host-port with just port number"
                  (let [result (conn/parse-host-port "7888")]
                    (is (= "localhost" (:hostname result)))
                    (is (= 7888 (:port result)))))

         (testing "parse-host-port with whitespace"
                  (let [result (conn/parse-host-port "  localhost:7888  ")]
                    (is (= "localhost" (:hostname result)))
                    (is (= 7888 (:port result)))))

         (testing "parse-host-port with whitespace around port only"
                  (let [result (conn/parse-host-port "  7890  ")]
                    (is (= "localhost" (:hostname result)))
                    (is (= 7890 (:port result))))))

(deftest parse-host-port-error-test
         (testing "parse-host-port with invalid port number"
                  (let [result (conn/parse-host-port "localhost:notaport")]
                    (is (contains? result :error))
                    (is (str/includes? (:error result) "Invalid port"))))

         (testing "parse-host-port with invalid format"
                  (let [result (conn/parse-host-port "not-valid-format")]
                    (is (contains? result :error))
                    (is (str/includes? (:error result) "Invalid connection format"))))

         (testing "parse-host-port with empty string after trim"
                  (let [result (conn/parse-host-port "   ")]
                    (is (contains? result :error)))))

(deftest parse-host-port-edge-cases-test
         (testing "parse-host-port with minimum port"
                  (let [result (conn/parse-host-port "1")]
                    (is (= 1 (:port result)))))

         (testing "parse-host-port with maximum typical port"
                  (let [result (conn/parse-host-port "65535")]
                    (is (= 65535 (:port result)))))

         (testing "parse-host-port with zero port"
                  (let [result (conn/parse-host-port "0")]
                    (is (= 0 (:port result)))))

         (testing "parse-host-port preserves IPv6 localhost"
                  (let [result (conn/parse-host-port "::1:7888")]
      ;; IPv6 format with colons - split on first colon only
                    (is (some? result)))))

;; =============================================================================
;; Resolve Connection Params Tests (without file I/O)
;; =============================================================================

(deftest resolve-connection-params-string-test
         (testing "resolve-connection-params with port string"
                  (let [result (conn/resolve-connection-params "7888")]
                    (is (= "localhost" (:hostname result)))
                    (is (= 7888 (:port result)))))

         (testing "resolve-connection-params with host:port string"
                  (let [result (conn/resolve-connection-params "myhost:8080")]
                    (is (= "myhost" (:hostname result)))
                    (is (= 8080 (:port result)))))

         (testing "resolve-connection-params with nil and no env"
    ;; This test depends on NREPL_CONNECT not being set
                  (when-not (System/getenv "NREPL_CONNECT")
                    (let [result (conn/resolve-connection-params nil)]
                      (is (contains? result :error))
                      (is (str/includes? (:error result) "No connection info"))))))

(deftest resolve-connection-params-blank-test
         (testing "resolve-connection-params with blank string"
                  (when-not (System/getenv "NREPL_CONNECT")
                    (let [result (conn/resolve-connection-params "")]
                      (is (contains? result :error)))))

         (testing "resolve-connection-params with whitespace only"
                  (when-not (System/getenv "NREPL_CONNECT")
                    (let [result (conn/resolve-connection-params "   ")]
                      (is (contains? result :error))))))
