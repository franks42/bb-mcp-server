(ns expert-registry.core-test
    (:require [clojure.test :refer [deftest is testing use-fixtures]]
              [expert-registry.core :as er]
              [expert-registry.curriculum :as curriculum]
              [clojure.java.io :as io]))

(def test-experts-dir
     "Test experts directory."
     ".experts-test")

(defn create-test-expert
  "Create a test expert definition."
  [expert-id]
  (let [expert-dir (io/file test-experts-dir (name expert-id))
        essential-dir (io/file expert-dir "essential")
        reference-dir (io/file expert-dir "reference")]

    ;; Create directories
    (.mkdirs essential-dir)
    (.mkdirs reference-dir)

    ;; Create manifest
    (spit (io/file expert-dir "manifest.edn")
          (pr-str {:id expert-id
                   :name (str "Test Expert " (name expert-id))
                   :description "A test expert"
                   :capabilities #{:testing}
                   :domains #{:test}}))

    ;; Create essential curriculum
    (spit (io/file essential-dir "guide.md")
          "# Test Guide\n\nThis is a test curriculum file.")

    ;; Create reference curriculum
    (spit (io/file reference-dir "reference.md")
          "# Test Reference\n\nThis is a reference document.")))

(defn cleanup-test-experts
  "Remove test experts directory."
  []
  (let [dir (io/file test-experts-dir)]
    (when (.exists dir)
      (doseq [file (file-seq dir)]
             (when (.isFile file)
               (.delete file)))
      (doseq [file (reverse (file-seq dir))]
             (.delete file)))))

(defn with-test-experts
  "Test fixture that creates test experts and cleans up after."
  [f]
  (cleanup-test-experts)
  (create-test-expert :test-expert-1)
  (create-test-expert :test-expert-2)
  (er/init! test-experts-dir)
  (try
   (f)
   (finally
    (cleanup-test-experts))))

(use-fixtures :each with-test-experts)

;; =============================================================================
;; Initialization Tests
;; =============================================================================

(deftest test-init
         (testing "Initializes and loads expert definitions"
                  (is (= 2 (count (er/list-experts))))))

(deftest test-load-expert-definitions
         (testing "Expert definitions have required fields"
                  (let [experts (er/list-experts)]
                    (is (every? :id experts))
                    (is (every? :name experts))
                    (is (every? :description experts))
                    (is (every? :capabilities experts))
                    (is (every? :curriculum-paths experts)))))

;; =============================================================================
;; Expert Query Tests
;; =============================================================================

(deftest test-get-expert
         (testing "Gets expert by ID"
                  (let [expert (er/get-expert :test-expert-1)]
                    (is (some? expert))
                    (is (= :test-expert-1 (:id expert)))
                    (is (= "Test Expert test-expert-1" (:name expert)))))

         (testing "Returns nil for unknown expert"
                  (is (nil? (er/get-expert :unknown-expert)))))

(deftest test-list-experts
         (testing "Lists all experts"
                  (let [experts (er/list-experts)]
                    (is (= 2 (count experts)))
                    (is (= #{:test-expert-1 :test-expert-2} (set (map :id experts)))))))

(deftest test-find-experts-by-capability
         (testing "Finds experts by capability"
                  (let [experts (er/find-experts-by-capability :testing)]
                    (is (= 2 (count experts)))
                    (is (every? #(contains? (:capabilities %) :testing) experts))))

         (testing "Returns empty for non-existent capability"
                  (is (empty? (er/find-experts-by-capability :non-existent)))))

;; =============================================================================
;; Curriculum Tests
;; =============================================================================

(deftest test-discover-curriculum-files
         (testing "Discovers curriculum files"
                  (let [expert (er/get-expert :test-expert-1)
                        paths (:curriculum-paths expert)]
                    (is (= 1 (count (:essential paths))))
                    (is (= 1 (count (:reference paths))))
                    (is (some #(re-find #"guide\.md$" %) (:essential paths)))
                    (is (some #(re-find #"reference\.md$" %) (:reference paths))))))

(deftest test-load-curriculum
         (testing "Loads curriculum content"
                  (let [expert (er/get-expert :test-expert-1)
                        curriculum (curriculum/load-curriculum expert)]
                    (is (some? curriculum))
                    (is (string? curriculum))
                    (is (re-find #"Test Expert test-expert-1" curriculum))
                    (is (re-find #"Test Guide" curriculum))
                    (is (re-find #"test curriculum file" curriculum)))))

(deftest test-curriculum-stats
         (testing "Gets curriculum statistics"
                  (let [expert (er/get-expert :test-expert-1)
                        stats (curriculum/get-curriculum-stats expert)]
                    (is (= 1 (:essential-count stats)))
                    (is (= 1 (:reference-count stats)))
                    (is (> (:total-size stats) 0)))))

;; =============================================================================
;; Instance Management Tests (Mock-based)
;; =============================================================================

(deftest test-instance-management
         (testing "List instances starts empty"
                  (is (empty? (er/list-instances))))

         (testing "Get non-existent instance returns nil"
                  (is (nil? (er/get-instance "non-existent")))))

;; Note: Actual spawn-expert! and kill-expert! tests are integration tests
;; that require claude-manager and port-registry to be running.
;; These would go in a separate integration test suite.
