(ns bb-mcp-server.bootstrap.config-test
    "Tests for bootstrap configuration loading."
    (:require [clojure.test :refer [deftest is testing]]
              [clojure.java.io :as io]
              [clojure.edn :as edn]
              [clojure.string :as str]))

(deftest test-bootstrap-config-loading
         (testing "Bootstrap configuration loads minimal module set"
                  (let [config-path "bb-bootstrap-system.edn"
                        config (when (.exists (io/file config-path))
                                 (edn/read-string (slurp config-path)))]
                    (is (some? config) "Bootstrap config should exist and load")
                    (when config
                      (is (contains? config :modules) "Config should have modules key")
                      (is (= ["local-eval"] (:modules config)) "Should only have local-eval module")))))

(deftest test-bootstrap-config-format
         (testing "Bootstrap configuration has correct format"
                  (let [config-path "bb-bootstrap-system.edn"]
                    (when (.exists (io/file config-path))
                      (let [config (edn/read-string (slurp config-path))]
                        (is (map? config) "Config should be a map")
                        (is (vector? (:modules config)) "Modules should be a vector")
                        (is (pos? (count (:modules config))) "Should have at least one module")
                        (is (every? string? (:modules config)) "All module names should be strings"))))))

(deftest test-bootstrap-file-exists
         (testing "Bootstrap configuration file exists"
                  (let [config-path "bb-bootstrap-system.edn"]
                    (is (.exists (io/file config-path)) "Bootstrap config file should exist")
                    (when (.exists (io/file config-path))
                      (let [content (slurp config-path)]
                        (is (pos? (count content)) "Config file should not be empty")
                        (is (str/includes? content "local-eval")
                            "Config should contain local-eval module"))))))
