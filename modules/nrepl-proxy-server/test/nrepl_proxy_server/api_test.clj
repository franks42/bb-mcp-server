(ns nrepl-proxy-server.api-test
    (:require [clojure.test :refer [deftest is testing]]
              [nrepl-proxy-server.api :as api]))

(deftest browser-list-call?-test
         (testing "detects browser/list calls"
                  (is (api/browser-list-call? "(browser/list)"))
                  (is (api/browser-list-call? "( browser/list )"))
                  (is (api/browser-list-call? "(browser/list)")))

         (testing "rejects non-browser/list calls"
                  (is (not (api/browser-list-call? "(println \"hi\")")))
                  (is (not (api/browser-list-call? "browser/list")))
                  (is (not (api/browser-list-call? nil)))))

(deftest browser-repl-call?-test
         (testing "detects browser/repl calls and extracts browser-id"
                  (is (= :browser-1 (api/browser-repl-call? "(browser/repl :browser-1)")))
                  (is (= :browser-2 (api/browser-repl-call? "( browser/repl :browser-2 )")))
                  (is (= :my-browser (api/browser-repl-call? "(browser/repl :my-browser)"))))

         (testing "returns nil for non-browser/repl calls"
                  (is (nil? (api/browser-repl-call? "(println \"hi\")")))
                  (is (nil? (api/browser-repl-call? "(browser/list)")))
                  (is (nil? (api/browser-repl-call? nil)))))

(deftest cljs-quit?-test
         (testing "detects :cljs/quit"
                  (is (api/cljs-quit? ":cljs/quit"))
                  (is (api/cljs-quit? "  :cljs/quit  "))
                  (is (api/cljs-quit? ":cljs/quit\n")))

         (testing "rejects non :cljs/quit"
                  (is (not (api/cljs-quit? "(println :cljs/quit)")))
                  (is (not (api/cljs-quit? "cljs/quit")))
                  (is (not (api/cljs-quit? nil)))))

(deftest generate-list-code-test
         (testing "generate-list-code returns valid EDN string"
                  (let [code (api/generate-list-code)]
                    (is (string? code))
      ;; Should be parseable as EDN
                    (is (vector? (read-string code))))))

(deftest generate-quit-code-test
         (testing "generate-quit-code returns nil string"
                  (is (= "nil" (api/generate-quit-code)))))
