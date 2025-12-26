(ns bb-mcp-server.modules.clojure-lsp.jsonrpc-test
    (:require [clojure.test :refer [deftest is testing]]
              [clojure.string :as str]
              [bb-mcp-server.modules.clojure-lsp.jsonrpc :as jsonrpc])
    (:import [java.io BufferedReader BufferedWriter StringReader StringWriter]))

(deftest write-message-test
         (testing "write-message! creates correct Content-Length header"
                  (let [sw (StringWriter.)
                        bw (BufferedWriter. sw)
                        msg {:jsonrpc "2.0" :id 1 :method "test"}]
                    (jsonrpc/write-message! bw msg)
                    (let [output (str sw)
                          body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test\"}"
                          expected-len (count (.getBytes body "UTF-8"))]
                      (is (str/starts-with? output "Content-Length: "))
                      (is (str/includes? output (str "Content-Length: " expected-len)))
                      (is (str/includes? output "\r\n\r\n"))
                      (is (str/ends-with? output body))))))

(deftest read-message-test
         (testing "read-message! parses Content-Length and body"
                  (let [body "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"hello\"}"
                        len (count (.getBytes body "UTF-8"))
                        input (str "Content-Length: " len "\r\n\r\n" body)
                        sr (StringReader. input)
                        br (BufferedReader. sr)
                        result (jsonrpc/read-message! br)]
                    (is (= "2.0" (:jsonrpc result)))
                    (is (= 1 (:id result)))
                    (is (= "hello" (:result result))))))

(deftest roundtrip-test
         (testing "write then read produces same message"
                  (let [sw (StringWriter.)
                        bw (BufferedWriter. sw)
                        original {:jsonrpc "2.0" :id 42 :method "testMethod" :params {:foo "bar"}}]
                    (jsonrpc/write-message! bw original)
                    (let [output (str sw)
                          sr (StringReader. output)
                          br (BufferedReader. sr)
                          result (jsonrpc/read-message! br)]
                      (is (= (:jsonrpc original) (:jsonrpc result)))
                      (is (= (:id original) (:id result)))
                      (is (= (:method original) (:method result)))
                      (is (= (:foo (:params original)) (:foo (:params result))))))))

(deftest eof-test
         (testing "read-message! returns nil on EOF"
                  (let [sr (StringReader. "")
                        br (BufferedReader. sr)]
                    (is (nil? (jsonrpc/read-message! br))))))
