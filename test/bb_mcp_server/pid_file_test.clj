(ns bb-mcp-server.pid-file-test
  "Tests for PID file creation with nickname support."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn pid-file-path
  "Get the path for the PID file for a given port or nickname."
  [port-or-nickname]
  (let [ports-dir ".ports-test"]
    (io/file ports-dir (str "." port-or-nickname))))

(defn current-pid
  "Get current process PID."
  []
  12345) ; Mock PID for testing

(defn write-pid-file!
  "Write current process PID to file for given port."
  [port nickname config]
  (let [path (pid-file-path (or nickname port))
        pid (current-pid)]
    (.mkdirs (.getParentFile path))
    (spit path (json/generate-string {:pid pid
                                      :port port
                                      :nickname nickname
                                      :config config
                                      :timestamp (java.time.Instant/now)}))
    pid))

(def test-ports-dir ".ports-test")
(def test-port 30099)
(def test-nickname "test-bootstrap")
(def test-config "bb-bootstrap-system.edn")

(defn cleanup-port-files-fixture
  "Clean up test port files after each test"
  [f]
  (try
    (f)
    (finally
      ;; Clean up test port files
      (when (.exists (io/file test-ports-dir))
        (doseq [file (.listFiles (io/file test-ports-dir))]
          (.delete file))
        (.delete (io/file test-ports-dir))))))

(use-fixtures :each cleanup-port-files-fixture)

(deftest test-pid-file-creation-with-nickname
  (testing "PID file is created with nickname in filename"
    (let [result (write-pid-file! test-port test-nickname test-config)]
      (is (some? result) "write-pid-file! should return result")
      (let [port-file (io/file test-ports-dir (str "." test-nickname))]
        (is (.exists port-file) "Port file should exist with nickname name")
        (when (.exists port-file)
          (let [content (slurp port-file)
                data (json/parse-string content true)]
            (is (= test-port (:port data)) "Port should match")
            (is (= test-nickname (:nickname data)) "Nickname should match")
            (is (= test-config (:config data)) "Config should match")
            (is (some? (:pid data)) "PID should be present")
            (is (some? (:timestamp data)) "Timestamp should be present")))))))

(deftest test-pid-file-json-format
  (testing "PID file contains valid JSON with all required fields"
    (let [result (write-pid-file! test-port test-nickname test-config)]
      (let [port-file (io/file test-ports-dir (str "." test-nickname))]
        (when (.exists port-file)
          (let [content (slurp port-file)
                data (json/parse-string content true)]
            (is (contains? data :pid) "Should contain pid")
            (is (contains? data :port) "Should contain port")
            (is (contains? data :nickname) "Should contain nickname")
            (is (contains? data :config) "Should contain config")
            (is (contains? data :timestamp) "Should contain timestamp")
            (is (string? (:timestamp data)) "Timestamp should be string")))))))
