(ns bb-mcp-server.modules.clojure-lsp.server-test
    (:require [clojure.test :refer [deftest is testing]]
              [bb-mcp-server.modules.clojure-lsp.server :as server]
              [babashka.fs :as fs]))

(deftest lifecycle-test
         (testing "Start and Stop clojure-lsp"
                  (let [clj-lsp (fs/which "clojure-lsp")]
                    (is (some? clj-lsp) "clojure-lsp not found in PATH")

                    (when clj-lsp
                      (let [executable (str clj-lsp)
                            root (System/getProperty "user.dir")
                            start-res (server/start! {:project-root root
                                                      :executable-path executable})]

          ;; New client returns "initialized" after full LSP handshake
                        (is (= "initialized" (:status start-res)))
                        (is (map? (:capabilities start-res)))
                        (is (server/running?))
                        (is (server/initialized?))

                        (let [stop-res (server/stop!)]
                          (is (= "stopped" (:status stop-res))))

          ;; Stopping again should be safe
                        (is (nil? (server/stop!))))))))

(deftest init-test
         (testing "Init validation"
                  (is (thrown? clojure.lang.ExceptionInfo
                               (server/init! {:project-root "/non/existent/path"})))

                  (let [clj-lsp (fs/which "clojure-lsp")
                        root (System/getProperty "user.dir")]
                    (when clj-lsp
                      (let [res (server/init! {:project-root root
                                               :executable-path (str clj-lsp)})]
                        (is (= "initialized" (:status res)))
                        (server/stop!))))))
