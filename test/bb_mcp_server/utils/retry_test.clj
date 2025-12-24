(ns bb-mcp-server.utils.retry-test
  "Comprehensive tests for retry-with-backoff utility."
  (:require [clojure.test :refer [deftest testing is are]]
            [bb-mcp-server.utils.retry :refer [retry-with-backoff]]))

;; =============================================================================
;; Happy Path Tests
;; =============================================================================

(deftest test-success-on-first-try
  (testing "Returns result immediately when function succeeds on first call"
    (let [call-count (atom 0)]
      (is (= :success
             (retry-with-backoff
              #(do (swap! call-count inc) :success))))
      (is (= 1 @call-count) "Function should be called exactly once")))

  (testing "Returns complex result on first try"
    (is (= {:status "ok" :data [1 2 3]}
           (retry-with-backoff
            #(identity {:status "ok" :data [1 2 3]})))))

  (testing "Returns nil successfully"
    (is (nil? (retry-with-backoff #(identity nil)))))

  (testing "Returns false successfully (falsy but valid)"
    (is (false? (retry-with-backoff #(identity false))))))

;; =============================================================================
;; Retry Success Tests
;; =============================================================================

(deftest test-retry-then-succeed
  (testing "Succeeds after one failure with default options"
    (let [call-count (atom 0)]
      (is (= :eventual-success
             (retry-with-backoff
              #(if (< (swap! call-count inc) 2)
                 (throw (ex-info "Transient failure" {:attempt @call-count}))
                 :eventual-success))))
      (is (= 2 @call-count) "Function should be called twice")))

  (testing "Succeeds on the last allowed attempt (attempt 3 of 3)"
    (let [call-count (atom 0)]
      (is (= :last-chance-success
             (retry-with-backoff
              #(if (< (swap! call-count inc) 3)
                 (throw (ex-info "Almost there" {}))
                 :last-chance-success)
              {:max-attempts 3 :initial-delay-ms 1})))
      (is (= 3 @call-count) "Function should be called three times")))

  (testing "Succeeds after multiple failures with custom max-attempts"
    (let [call-count (atom 0)]
      (is (= :finally
             (retry-with-backoff
              #(if (< (swap! call-count inc) 5)
                 (throw (Exception. "Keep trying"))
                 :finally)
              {:max-attempts 5 :initial-delay-ms 1})))
      (is (= 5 @call-count) "Function should be called five times"))))

;; =============================================================================
;; Exhaustion Tests
;; =============================================================================

(deftest test-all-retries-exhausted
  (testing "Throws after exhausting all default retries (3 attempts)"
    (let [call-count (atom 0)
          thrown-exception (atom nil)]
      (try
        (retry-with-backoff
         #(do (swap! call-count inc)
              (throw (ex-info "Always fails" {:count @call-count})))
         {:initial-delay-ms 1})
        (catch Exception e
          (reset! thrown-exception e)))
      (is (= 3 @call-count) "Function should be called exactly 3 times")
      (is (some? @thrown-exception) "Should throw exception")
      (is (= "Always fails" (ex-message @thrown-exception)))))

  (testing "Throws the last exception, not an earlier one"
    (let [call-count (atom 0)
          thrown-exception (atom nil)]
      (try
        (retry-with-backoff
         #(let [n (swap! call-count inc)]
            (throw (ex-info (str "Failure #" n) {:attempt n})))
         {:max-attempts 4 :initial-delay-ms 1})
        (catch Exception e
          (reset! thrown-exception e)))
      (is (= 4 @call-count))
      (is (= "Failure #4" (ex-message @thrown-exception)))
      (is (= 4 (:attempt (ex-data @thrown-exception))))))

  (testing "Custom max-attempts exhaustion"
    (let [call-count (atom 0)]
      (is (thrown-with-msg?
           Exception
           #"Persistent error"
           (retry-with-backoff
            #(do (swap! call-count inc)
                 (throw (Exception. "Persistent error")))
            {:max-attempts 7 :initial-delay-ms 1})))
      (is (= 7 @call-count) "Function should be called exactly 7 times"))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-single-attempt-no-retry
  (testing "max-attempts 1 means try once, no retries"
    (let [call-count (atom 0)]
      (is (thrown? Exception
                   (retry-with-backoff
                    #(do (swap! call-count inc)
                         (throw (Exception. "Single attempt")))
                    {:max-attempts 1 :initial-delay-ms 1})))
      (is (= 1 @call-count) "Function should be called exactly once")))

  (testing "max-attempts 1 with success"
    (let [call-count (atom 0)]
      (is (= :one-shot
             (retry-with-backoff
              #(do (swap! call-count inc) :one-shot)
              {:max-attempts 1})))
      (is (= 1 @call-count)))))

(deftest test-invalid-options
  (testing "Zero max-attempts throws IllegalArgumentException"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"max-attempts must be positive"
         (retry-with-backoff #(identity :never-called) {:max-attempts 0}))))

  (testing "Negative max-attempts throws IllegalArgumentException"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"max-attempts must be positive"
         (retry-with-backoff #(identity :never-called) {:max-attempts -1}))))

  (testing "max-attempts exceeding limit throws"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"max-attempts must be <= 100"
         (retry-with-backoff #(identity :never-called) {:max-attempts 101}))))

  (testing "Zero initial-delay-ms throws"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"initial-delay-ms must be positive"
         (retry-with-backoff #(identity :never-called) {:initial-delay-ms 0}))))

  (testing "Negative initial-delay-ms throws"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"initial-delay-ms must be positive"
         (retry-with-backoff #(identity :never-called) {:initial-delay-ms -100}))))

  (testing "initial-delay-ms exceeding max throws"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"initial-delay-ms must be <= 60000"
         (retry-with-backoff #(identity :never-called) {:initial-delay-ms 60001})))))

(deftest test-boundary-values
  (testing "max-attempts at limit (100) is accepted"
    (let [call-count (atom 0)]
      (is (= :at-limit
             (retry-with-backoff
              #(do (swap! call-count inc) :at-limit)
              {:max-attempts 100})))
      (is (= 1 @call-count))))

  (testing "initial-delay-ms at limit (60000) is accepted"
    (is (= :max-delay
           (retry-with-backoff
            #(identity :max-delay)
            {:initial-delay-ms 60000}))))

  (testing "max-attempts 1 is minimum valid value"
    (is (= :min-attempts
           (retry-with-backoff #(identity :min-attempts) {:max-attempts 1}))))

  (testing "initial-delay-ms 1 is minimum valid value"
    (is (= :min-delay
           (retry-with-backoff #(identity :min-delay) {:initial-delay-ms 1})))))

;; =============================================================================
;; Backoff Timing Verification
;; =============================================================================

(deftest test-exponential-backoff-timing
  (testing "Delays double with each retry"
    (let [sleep-times (atom [])
          original-sleep Thread/sleep
          call-count (atom 0)]
      ;; We can't easily mock Thread/sleep in bb, so we measure elapsed time
      (let [start (System/currentTimeMillis)]
        (try
          (retry-with-backoff
           #(do (swap! call-count inc)
                (throw (Exception. "Timing test")))
           {:max-attempts 4 :initial-delay-ms 50})
          (catch Exception _))
        (let [elapsed (- (System/currentTimeMillis) start)]
          ;; Expected delays: 50 + 100 + 200 = 350ms minimum
          ;; Allow some tolerance for execution time
          (is (>= elapsed 300) (str "Total elapsed should be >= 300ms, was " elapsed "ms"))
          (is (<= elapsed 600) (str "Total elapsed should be <= 600ms, was " elapsed "ms"))))))

  (testing "Initial delay is respected"
    (let [call-count (atom 0)
          start (System/currentTimeMillis)]
      (try
        (retry-with-backoff
         #(do (swap! call-count inc)
              (throw (Exception. "Delay test")))
         {:max-attempts 2 :initial-delay-ms 100})
        (catch Exception _))
      (let [elapsed (- (System/currentTimeMillis) start)]
        ;; Should have waited ~100ms (one retry)
        (is (>= elapsed 80) "Should wait at least initial delay")
        (is (<= elapsed 300) "Should not wait too long")))))

(deftest test-delay-cap-at-maximum
  (testing "Delay is capped at 60 seconds to prevent overflow"
    ;; We can't easily test the 60s cap without waiting that long,
    ;; but we can verify the function handles large initial delays
    (let [call-count (atom 0)]
      (is (= :capped
             (retry-with-backoff
              #(do (swap! call-count inc) :capped)
              {:max-attempts 2 :initial-delay-ms 50000})))
      (is (= 1 @call-count)))))

;; =============================================================================
;; Exception Type Tests
;; =============================================================================

(deftest test-exception-types
  (testing "Catches and retries on RuntimeException"
    (let [call-count (atom 0)]
      (is (= :runtime-recovery
             (retry-with-backoff
              #(if (< (swap! call-count inc) 2)
                 (throw (RuntimeException. "Runtime error"))
                 :runtime-recovery)
              {:initial-delay-ms 1})))))

  (testing "Catches and retries on ex-info"
    (let [call-count (atom 0)]
      (is (= :exinfo-recovery
             (retry-with-backoff
              #(if (< (swap! call-count inc) 2)
                 (throw (ex-info "Ex-info error" {:type :transient}))
                 :exinfo-recovery)
              {:initial-delay-ms 1})))))

  (testing "Catches and retries on IOException"
    (let [call-count (atom 0)]
      (is (= :io-recovery
             (retry-with-backoff
              #(if (< (swap! call-count inc) 2)
                 (throw (java.io.IOException. "IO error"))
                 :io-recovery)
              {:initial-delay-ms 1})))))

  (testing "Preserves exception data on final throw"
    (let [thrown-exception (atom nil)]
      (try
        (retry-with-backoff
         #(throw (ex-info "With data" {:key "value" :code 42}))
         {:max-attempts 1})
        (catch Exception e
          (reset! thrown-exception e)))
      (is (= {:key "value" :code 42} (ex-data @thrown-exception))))))

;; =============================================================================
;; Default Options Tests
;; =============================================================================

(deftest test-default-options
  (testing "Default max-attempts is 3"
    (let [call-count (atom 0)]
      (try
        (retry-with-backoff
         #(do (swap! call-count inc)
              (throw (Exception. "Default attempts test")))
         {:initial-delay-ms 1})
        (catch Exception _))
      (is (= 3 @call-count))))

  (testing "Function can be called with no options map"
    (let [call-count (atom 0)]
      (is (= :no-options
             (retry-with-backoff #(do (swap! call-count inc) :no-options))))
      (is (= 1 @call-count))))

  (testing "Empty options map uses defaults"
    (let [call-count (atom 0)]
      (is (= :empty-options
             (retry-with-backoff #(do (swap! call-count inc) :empty-options) {})))
      (is (= 1 @call-count)))))

;; =============================================================================
;; Side Effect Tests
;; =============================================================================

(deftest test-side-effects-on-retry
  (testing "Side effects occur on each attempt"
    (let [side-effects (atom [])]
      (try
        (retry-with-backoff
         #(do (swap! side-effects conj (System/currentTimeMillis))
              (throw (Exception. "Side effect test")))
         {:max-attempts 3 :initial-delay-ms 10})
        (catch Exception _))
      (is (= 3 (count @side-effects)) "Side effect should occur 3 times")
      ;; Verify timestamps are increasing (retries happened in sequence)
      (is (apply < @side-effects) "Timestamps should be strictly increasing")))

  (testing "Successful result stops further attempts"
    (let [attempts (atom [])]
      (retry-with-backoff
       #(let [n (count (swap! attempts conj :attempt))]
          (if (< n 2)
            (throw (Exception. "Not yet"))
            :done))
       {:max-attempts 5 :initial-delay-ms 1})
      (is (= 2 (count @attempts)) "Should stop after success on attempt 2"))))

;; =============================================================================
;; Return Value Tests
;; =============================================================================

(deftest test-return-values
  (testing "Returns various types correctly"
    (are [expected] (= expected (retry-with-backoff #(identity expected)))
      42
      "string"
      :keyword
      'symbol
      [1 2 3]
      #{:a :b}
      {:nested {:data true}}
      (list 1 2 3)))

  (testing "Returns lazy sequences correctly"
    (let [result (retry-with-backoff #(map inc [1 2 3]))]
      (is (= [2 3 4] (vec result)))))

  (testing "Returns function correctly"
    (let [result (retry-with-backoff #(fn [x] (* x 2)))]
      (is (fn? result))
      (is (= 10 (result 5))))))

;; =============================================================================
;; Stress Tests
;; =============================================================================

(deftest test-many-retries
  (testing "Handles many retries before success"
    (let [call-count (atom 0)]
      (is (= :after-many
             (retry-with-backoff
              #(if (< (swap! call-count inc) 50)
                 (throw (Exception. "Keep going"))
                 :after-many)
              {:max-attempts 50 :initial-delay-ms 1})))
      (is (= 50 @call-count)))))
