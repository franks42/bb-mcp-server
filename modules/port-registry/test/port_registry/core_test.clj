(ns port-registry.core-test
    (:require [clojure.test :refer [deftest is testing]]
              [port-registry.core :as registry]
              [clojure.java.io :as io]))

(def test-registry-path
     "Path for test registry file (isolated from production)."
     ".ports/test-registry.edn")

(defn cleanup-test-registry
  "Remove test registry file if it exists."
  []
  (let [file (io/file test-registry-path)]
    (when (.exists file)
      (.delete file))))

(defn with-test-registry
  "Test fixture that initializes registry and cleans up after."
  [f]
  (cleanup-test-registry)
  (registry/init! test-registry-path)
  (try
   (f)
   (finally
    (cleanup-test-registry))))

(deftest test-init
         (with-test-registry
          (fn []
            (testing "Registry initializes with default port ranges"
                     (is (= [19880 19889] (registry/get-port-range :clojure-tools)))
                     (is (= [19890 19899] (registry/get-port-range :aws-tools)))))))

(deftest test-allocate-port
         (with-test-registry
          (fn []
            (testing "Allocates first port in range"
                     (let [port (registry/allocate-port! {:domain :clojure-tools
                                                          :service-type :mcp-server
                                                          :expert-id :test-expert-1})]
                       (is (= 19880 port))
                       (is (= :test-expert-1 (:expert-id (registry/get-port-info port))))))

            (testing "Allocates next available port"
                     (let [port2 (registry/allocate-port! {:domain :clojure-tools
                                                           :service-type :mcp-server
                                                           :expert-id :test-expert-2})]
                       (is (= 19881 port2))))

            (testing "Returns nil when range exhausted"
        ;; Allocate all remaining ports in clojure-tools range
                     (doseq [_ (range 8)] ;; 10 total - 2 already allocated = 8 remaining
                            (registry/allocate-port! {:domain :clojure-tools
                                                      :service-type :mcp-server}))
                     (is (nil? (registry/allocate-port! {:domain :clojure-tools
                                                         :service-type :mcp-server}))))

            (testing "Throws when domain missing"
                     (is (thrown? Exception
                                  (registry/allocate-port! {:service-type :mcp-server})))))))

(deftest test-release-port
         (with-test-registry
          (fn []
            (testing "Releases allocated port"
                     (let [port (registry/allocate-port! {:domain :aws-tools
                                                          :service-type :rest-api})]
                       (is (some? (registry/get-port-info port)))
                       (registry/release-port! port)
                       (is (nil? (registry/get-port-info port)))))

            (testing "Released port can be reallocated"
                     (let [port1 (registry/allocate-port! {:domain :python-tools
                                                           :service-type :mcp-server})
                           _ (registry/release-port! port1)
                           port2 (registry/allocate-port! {:domain :python-tools
                                                           :service-type :mcp-server})]
                       (is (= port1 port2)))))))

(deftest test-discover-by-domain
         (with-test-registry
          (fn []
            (testing "Discovers all ports for domain"
                     (registry/allocate-port! {:domain :js-tools
                                               :service-type :mcp-server
                                               :expert-id :js-expert-1})
                     (registry/allocate-port! {:domain :js-tools
                                               :service-type :rest-api
                                               :expert-id :js-expert-2})
                     (let [ports (registry/discover-by-domain :js-tools)]
                       (is (= 2 (count ports)))
                       (is (every? #(= :js-tools (:domain %)) ports))))

            (testing "Returns empty for domain with no allocations"
                     (is (empty? (registry/discover-by-domain :ml-tools)))))))

(deftest test-discover-by-expert
         (with-test-registry
          (fn []
            (testing "Discovers port by expert ID"
                     (registry/allocate-port! {:domain :data-tools
                                               :service-type :mcp-server
                                               :expert-id :data-expert-1})
                     (let [info (registry/discover-by-expert :data-expert-1)]
                       (is (some? info))
                       (is (= :data-expert-1 (:expert-id info)))
                       (is (= 19920 (:port info)))))

            (testing "Returns nil for unknown expert"
                     (is (nil? (registry/discover-by-expert :unknown-expert)))))))

(deftest test-update-port-info
         (with-test-registry
          (fn []
            (testing "Updates port allocation info"
                     (let [port (registry/allocate-port! {:domain :web-tools
                                                          :service-type :mcp-server})]
                       (registry/update-port-info! port {:pid 12345 :status :healthy})
                       (let [info (registry/get-port-info port)]
                         (is (= 12345 (:pid info)))
                         (is (= :healthy (:status info))))))

            (testing "Throws when updating unallocated port"
                     (is (thrown? Exception
                                  (registry/update-port-info! 99999 {:pid 123})))))))

(deftest test-list-allocations
         (with-test-registry
          (fn []
            (testing "Lists all allocations"
                     (registry/allocate-port! {:domain :db-tools
                                               :service-type :mcp-server})
                     (registry/allocate-port! {:domain :test-tools
                                               :service-type :rest-api})
                     (let [allocations (registry/list-allocations)]
                       (is (= 2 (count allocations)))
                       (is (every? :port allocations))
                       (is (every? :domain allocations)))))))

(deftest test-validate-registry
         (with-test-registry
          (fn []
            (testing "Cleans up zombie ports with dead PID"
        ;; Allocate with a definitely dead PID
                     (let [port (registry/allocate-port! {:domain :misc-tools
                                                          :service-type :mcp-server
                                                          :pid 999999})] ;; PID unlikely to exist
                       (is (some? (registry/get-port-info port)))
          ;; Run validation
                       (let [cleaned (registry/validate-registry!)]
                         (is (>= cleaned 1))
                         (is (nil? (registry/get-port-info port)))))))))

(deftest test-persistence
         (with-test-registry
          (fn []
            (testing "Registry persists across init cycles"
        ;; Allocate some ports
                     (let [port1 (registry/allocate-port! {:domain :clojure-tools
                                                           :service-type :mcp-server
                                                           :expert-id :persist-test-1})
                           port2 (registry/allocate-port! {:domain :aws-tools
                                                           :service-type :rest-api
                                                           :expert-id :persist-test-2})]
          ;; Re-init (simulates restart)
                       (registry/init! test-registry-path)
          ;; Verify allocations survived
                       (is (= :persist-test-1 (:expert-id (registry/get-port-info port1))))
                       (is (= :persist-test-2 (:expert-id (registry/get-port-info port2)))))))))

(deftest test-check-port-health
         (with-test-registry
          (fn []
            (testing "Returns :unknown for unallocated port"
                     (is (= :unknown (registry/check-port-health! 99999))))

            (testing "Returns :unhealthy for allocated but not listening port"
                     (let [port (registry/allocate-port! {:domain :clojure-tools
                                                          :service-type :mcp-server})]
                       (is (= :unhealthy (registry/check-port-health! port))))))))

(deftest test-domain-isolation
         (with-test-registry
          (fn []
            (testing "Domains have isolated port ranges"
                     (let [clj-port (registry/allocate-port! {:domain :clojure-tools
                                                              :service-type :mcp-server})
                           aws-port (registry/allocate-port! {:domain :aws-tools
                                                              :service-type :mcp-server})]
                       (is (>= clj-port 19880))
                       (is (<= clj-port 19889))
                       (is (>= aws-port 19890))
                       (is (<= aws-port 19899)))))))

(deftest test-concurrent-allocations
         (with-test-registry
          (fn []
            (testing "Multiple allocations in same domain work correctly"
                     (let [ports (doall
                                  (for [i (range 5)]
                                       (registry/allocate-port! {:domain :python-tools
                                                                 :service-type :mcp-server
                                                                 :expert-id (keyword (str "expert-" i))})))]
                       (is (= 5 (count (set ports)))) ;; All unique
                       (is (every? #(and (>= % 19900) (<= % 19909)) ports)))))))
