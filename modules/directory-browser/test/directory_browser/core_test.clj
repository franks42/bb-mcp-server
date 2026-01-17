(ns directory-browser.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [directory-browser.core :as dir]
            [babashka.fs :as fs]))

(deftest expand-path-test
  (testing "expands ~ to home directory"
    (is (= (System/getProperty "user.home")
           (dir/expand-path "~"))))

  (testing "expands ~/subdir"
    (is (= (str (System/getProperty "user.home") "/Documents")
           (dir/expand-path "~/Documents"))))

  (testing "handles nil"
    (is (nil? (dir/expand-path nil)))))

(deftest can-read-test
  (testing "home directory is readable"
    (is (dir/can-read? (dir/home-directory))))

  (testing "non-existent path is not readable"
    (is (not (dir/can-read? "/nonexistent/path/12345")))))

(deftest list-directory-test
  (testing "lists home directory"
    (let [result (dir/list-home)]
      (is (nil? (:error result)))
      (is (string? (:path result)))
      (is (vector? (:entries result)))
      (is (set? (:properties result)))))

  (testing "returns error for non-existent path"
    (let [result (dir/list-directory "/nonexistent/path/12345")]
      (is (= :not-found (get-in result [:error :type])))))

  (testing "returns error for file path"
    (let [test-file (str (dir/home-directory) "/.bashrc")]
      (when (fs/exists? test-file)
        (let [result (dir/list-directory test-file)]
          (is (= :not-directory (get-in result [:error :type])))))))

  (testing "filters hidden files by default"
    (let [result (dir/list-home {:show-hidden false})]
      (is (not-any? :hidden? (:entries result)))))

  (testing "includes hidden files when requested"
    (let [result (dir/list-home {:show-hidden true})]
      (is (some :hidden? (:entries result))))))

(deftest breadcrumbs-test
  (testing "generates breadcrumbs for path"
    (let [home (dir/home-directory)
          crumbs (dir/breadcrumbs (str home "/Development/project"))]
      (is (vector? crumbs))
      (is (every? #(contains? % :name) crumbs))
      (is (every? #(contains? % :path) crumbs))
      ;; Should have ~ for home
      (is (some #(= "~" (:name %)) crumbs)))))

(deftest project-detection-test
  (testing "detects bb-mcp-server as clojure project"
    (let [project-root (-> (fs/cwd) str)
          props (dir/get-directory-properties project-root)]
      (is (contains? props :clojure-project))
      (is (contains? props :git)))))

(deftest is-project-root-test
  (testing "bb-mcp-server is a project root"
    (is (dir/is-project-root? (str (fs/cwd)))))

  (testing "/tmp is not a project root"
    (is (not (dir/is-project-root? "/tmp")))))

(deftest find-projects-test
  (testing "finds projects in Development directory"
    (let [dev-dir (str (dir/home-directory) "/Development")]
      (when (fs/directory? dev-dir)
        (let [projects (dir/find-projects-in dev-dir)]
          (is (vector? projects))
          ;; Should find bb-mcp-server
          (is (some #(= "bb-mcp-server" (:name %)) projects)))))))
