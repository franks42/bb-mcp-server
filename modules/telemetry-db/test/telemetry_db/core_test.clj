(ns telemetry-db.core-test
    "Tests for telemetry-db module."
    (:require [clojure.test :refer [deftest testing is use-fixtures]]
              [clojure.string :as str]
              [taoensso.trove :as trove]
              [telemetry-db.core :as tdb]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- with-module
  "Start/stop telemetry-db module around each test."
  [f]
  (let [instance ((:start tdb/module) {} {:max-entries 100})]
    (try
     (f)
     (finally
      ((:stop tdb/module) instance)))))

(use-fixtures :each with-module)

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(deftest test-module-lifecycle
         (testing "Module starts and provides a store"
                  (is (some? (tdb/get-store)))
                  (is (instance? clojure.lang.IDeref (tdb/get-store))))

         (testing "Status returns entry count"
                  (let [status ((:status tdb/module) nil)]
                    (is (= :ok (:status status)))
                    (is (number? (:entries status))))))

;; =============================================================================
;; Trove Log-Fn Wrapper
;; =============================================================================

(deftest test-trove-wrapper-captures-logs
         (testing "Trove log! calls are captured in store"
                  (trove/log! {:level :info
                               :id ::test-event
                               :msg "Test message"
                               :data {:key "value"}})
                  (Thread/sleep 10)
                  (let [results (tdb/query {:event-id "test-event"})]
                    (is (pos? (count results)))
                    (when (seq results)
                      (let [entry (first results)]
                        (is (= "info" (:log/level entry)))
                        (is (= "Test message" (:log/msg entry)))
                        (is (some? (:log/event-id entry)))
                        (is (= "server" (:log/source entry))))))))

(deftest test-wrapper-preserves-original-log-fn
         (testing "Original log-fn is replaced by wrapper"
                  (let [instance @(deref #'telemetry-db.core/!instance)
                        original-fn (:original-log-fn instance)]
                    (is (fn? trove/*log-fn*))
                    (is (not= original-fn trove/*log-fn*)))))

(deftest test-wrapper-handles-errors-gracefully
         (testing "Wrapper does not throw even with minimal lazy_"
                  (let [log-fn trove/*log-fn*]
                    (is (nil? (try
                               (log-fn "test.ns" nil :info nil (delay {}))
                               nil
                               (catch Exception e e)))))))

;; =============================================================================
;; Query API
;; =============================================================================

(deftest test-query-by-level
         (testing "Query filters by level"
                  (tdb/ingest! {:level :info :ns "test" :msg "info msg"})
                  (tdb/ingest! {:level :error :ns "test" :msg "error msg"})
                  (tdb/ingest! {:level :warn :ns "test" :msg "warn msg"})
                  (let [errors (tdb/query {:level :error})]
                    (is (= 1 (count errors)))
                    (is (= "error" (:log/level (first errors)))))))

(deftest test-query-by-namespace
         (testing "Query filters by namespace prefix"
                  (tdb/ingest! {:level :info :ns "code-browser.core" :msg "cb msg"})
                  (tdb/ingest! {:level :info :ns "code-browser.ui" :msg "ui msg"})
                  (tdb/ingest! {:level :info :ns "atom-sync.core" :msg "as msg"})
                  (let [cb-results (tdb/query {:ns "code-browser"})]
                    (is (= 2 (count cb-results))))))

(deftest test-query-by-event-id
         (testing "Query filters by event-id substring"
                  (tdb/ingest! {:level :info :ns "test" :event-id :module/loaded :msg "loaded"})
                  (tdb/ingest! {:level :info :ns "test" :event-id :module/stopped :msg "stopped"})
                  (tdb/ingest! {:level :info :ns "test" :event-id :http/request :msg "request"})
                  (let [module-results (tdb/query {:event-id "module"})]
                    (is (= 2 (count module-results))))))

(deftest test-query-by-since
         (testing "Query filters by timestamp"
                  (let [store (tdb/get-store)
                        now (System/currentTimeMillis)]
                    ;; Insert with explicit timestamps
                    (swap! store conj {:log/id "old-1"
                                       :log/level "info"
                                       :log/ns "test"
                                       :log/msg "old"
                                       :log/timestamp (- now 60000)
                                       :log/source "server"})
                    (swap! store (fn [entries]
                                   (into [{:log/id "new-1"
                                           :log/level "info"
                                           :log/ns "test"
                                           :log/msg "new"
                                           :log/timestamp now
                                           :log/source "server"}]
                                         entries)))
                    (let [recent-entries (tdb/query {:since (- now 30000)})]
                      (is (= 1 (count recent-entries)))
                      (is (= "new" (:log/msg (first recent-entries))))))))

(deftest test-query-limit
         (testing "Query respects limit"
                  (dotimes [i 10]
                           (tdb/ingest! {:level :info :ns "test" :msg (str "msg-" i)}))
                  (let [limited (tdb/query {:limit 3})]
                    (is (= 3 (count limited))))))

(deftest test-query-newest-first
         (testing "Query returns newest entries first"
                  ;; Ingest with small delay to ensure different timestamps
                  (tdb/ingest! {:level :info :ns "test" :msg "first"})
                  (Thread/sleep 5)
                  (tdb/ingest! {:level :info :ns "test" :msg "second"})
                  (let [results (tdb/query {})]
                    (is (>= (count results) 2))
                    (is (>= (:log/timestamp (first results))
                            (:log/timestamp (second results)))))))

(deftest test-recent
         (testing "recent returns last N entries"
                  (dotimes [i 5]
                           (tdb/ingest! {:level :info :ns "test" :msg (str "msg-" i)}))
                  (let [results (tdb/recent 3)]
                    (is (= 3 (count results))))))

;; =============================================================================
;; External Ingestion
;; =============================================================================

(deftest test-ingest-external-entry
         (testing "ingest! stores browser entries"
                  (tdb/ingest! {:level :warn
                                :ns "browser.ui"
                                :event-id :click/error
                                :msg "Button click failed"
                                :data {:button-id "submit"}
                                :source "browser"})
                  (let [results (tdb/query {:ns "browser"})]
                    (is (= 1 (count results)))
                    (let [entry (first results)]
                      (is (= "warn" (:log/level entry)))
                      (is (= "browser" (:log/source entry)))
                      (is (= "browser.ui" (:log/ns entry)))))))

(deftest test-ingest-defaults
         (testing "ingest! provides defaults for missing fields"
                  (tdb/ingest! {:msg "minimal entry"})
                  (let [results (tdb/query {})]
                    (is (pos? (count results)))
                    (let [entry (first results)]
                      (is (= "info" (:log/level entry)))
                      (is (= "browser" (:log/ns entry)))
                      (is (= "browser" (:log/source entry)))))))

;; =============================================================================
;; Retention / Ring Buffer
;; =============================================================================

(deftest test-retention-enforced
         (testing "Entries beyond max-entries are purged"
                  ;; Module started with max-entries=100
                  ;; Insert 110 entries to trigger retention
                  (dotimes [i 110]
                           (tdb/ingest! {:level :info :ns "test" :msg (str "entry-" i)}))
                  ;; After cleanup, should have <= 100
                  (let [store (tdb/get-store)
                        total (count @store)]
                    (is (<= total 100)))))

;; =============================================================================
;; Persistence (dump/load)
;; =============================================================================

(deftest test-dump-and-load
         (testing "dump! serializes logs, load-dump reads them back"
                  (tdb/ingest! {:level :error :ns "test.dump" :msg "dump test"
                                :event-id :test/dump})
                  (let [path "/tmp/telemetry-db-test-dump.edn"]
                    (is (= path (tdb/dump! path)))
                    ;; Load it back
                    (let [loaded (tdb/load-dump path)]
                      (is (vector? loaded))
                      (is (some #(= "dump test" (:log/msg %)) loaded)))
                    ;; Cleanup
                    (.delete (java.io.File. path)))))

;; =============================================================================
;; Capture Filters
;; =============================================================================

(deftest test-min-level-filter
         (testing "min-level filters out lower-severity entries"
                  ;; Stop current module, restart with :min-level :info
                  (let [instance @(deref #'telemetry-db.core/!instance)]
                    ((:stop tdb/module) instance))
                  (let [inst ((:start tdb/module) {} {:max-entries 100
                                                      :min-level :info})]
                    (try
                     ;; Ingest at various levels (bypasses wrapper, but uses add-entry!)
                     ;; Use Trove log! to test the wrapper filter
                     (trove/log! {:level :trace :id ::filter-trace :msg "trace msg"})
                     (trove/log! {:level :debug :id ::filter-debug :msg "debug msg"})
                     (trove/log! {:level :info :id ::filter-info :msg "info msg"})
                     (trove/log! {:level :warn :id ::filter-warn :msg "warn msg"})
                     (Thread/sleep 10)
                     (let [results (tdb/query {:event-id "filter-" :limit 100})]
                       ;; trace and debug should be filtered out
                       (is (= 2 (count results)))
                       (is (every? #(contains? #{"info" "warn"} (:log/level %)) results)))
                     (finally
                      ((:stop tdb/module) inst)
                      ;; Restart with default config for remaining tests
                      ((:start tdb/module) {} {:max-entries 100}))))))

(deftest test-exclude-ns-filter
         (testing "exclude-ns filters out matching namespace prefixes"
                  (let [instance @(deref #'telemetry-db.core/!instance)]
                    ((:stop tdb/module) instance))
                  (let [inst ((:start tdb/module) {} {:max-entries 100
                                                      :exclude-ns #{"sente-lite."
                                                                    "noisy."}})]
                    (try
                     (trove/log! {:level :info :id ::ns-sente :msg "sente msg"})
                     ;; Manually call wrapper with specific ns-str to test exclude
                     (let [log-fn trove/*log-fn*]
                       (log-fn "sente-lite.wire-format" nil :info ::ns-wire "wire msg")
                       (log-fn "noisy.heartbeat" nil :info ::ns-noisy "noisy msg")
                       (log-fn "code-browser.core" nil :info ::ns-cb "cb msg"))
                     (Thread/sleep 10)
                     (let [results (tdb/query {:limit 100})]
                       ;; sente-lite.* and noisy.* should be excluded
                       ;; Only the non-excluded entries remain
                       (is (not (some #(str/starts-with? (:log/ns %) "sente-lite.") results)))
                       (is (not (some #(str/starts-with? (:log/ns %) "noisy.") results)))
                       (is (some #(= "code-browser.core" (:log/ns %)) results)))
                     (finally
                      ((:stop tdb/module) inst)
                      ((:start tdb/module) {} {:max-entries 100}))))))

;; =============================================================================
;; Module Stop Restores Log-Fn
;; =============================================================================

(deftest test-stop-restores-log-fn
         (testing "Stopping module restores original log-fn"
                  (let [instance @(deref #'telemetry-db.core/!instance)
                        original (:original-log-fn instance)]
                    ;; Stop module
                    ((:stop tdb/module) instance)
                    ;; After stop, log-fn should be the original
                    (is (= original trove/*log-fn*))
                    ;; Restart for fixture cleanup
                    ((:start tdb/module) {} {:max-entries 100}))))
