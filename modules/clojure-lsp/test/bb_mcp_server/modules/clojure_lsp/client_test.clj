(ns bb-mcp-server.modules.clojure-lsp.client-test
    (:require [clojure.test :refer [deftest is testing]]
              [bb-mcp-server.modules.clojure-lsp.client :as client]
              [babashka.fs :as fs]))

(deftest lifecycle-test
         (testing "Start and stop clojure-lsp client"
                  (let [clj-lsp (fs/which "clojure-lsp")]
                    (is (some? clj-lsp) "clojure-lsp not found in PATH")

                    (when clj-lsp
                      (let [executable (str clj-lsp)
                            root (System/getProperty "user.dir")]
          ;; Start and initialize
                        (let [result (client/start! {:project-root root
                                                     :executable-path executable})]
                          (is (= "initialized" (:status result)) "Should initialize successfully")
                          (is (client/running?) "Should be running after start")
                          (is (client/initialized?) "Should be initialized after start"))

          ;; Stop
                        (let [stop-result (client/stop!)]
                          (is (= "stopped" (:status stop-result)) "Should stop cleanly")
                          (is (not (client/running?)) "Should not be running after stop")
                          (is (not (client/initialized?)) "Should not be initialized after stop")))))))

(deftest invalid-project-root-test
         (testing "Start with invalid project root throws"
                  (is (thrown? clojure.lang.ExceptionInfo
                               (client/start! {:project-root "/non/existent/path"})))))

(deftest request-without-running-test
         (testing "Request without running client throws"
                  (is (thrown? clojure.lang.ExceptionInfo
                               (client/request! "test" {})))))
