#!/usr/bin/env bb

;; Test: Do Babashka pod calls need explicit serialization?
;;
;; Fires concurrent threads doing Datalevin pod queries and transacts
;; simultaneously to determine whether the pod infrastructure serializes
;; calls internally or whether concurrent callers can deadlock.
;;
;; Usage: bb scripts/test_pod_concurrency.clj
;;
;; Expected outcomes:
;; A) All calls complete -> pod has internal serialization, no external lock needed
;; B) Some calls hang/fail -> pod does NOT serialize, external lock required

(require '[babashka.pods :as pods]
         '[babashka.fs :as fs])

(println "=== Babashka Pod Concurrency Test ===")
(println)

;; Load the Datalevin pod
(println "Loading Datalevin pod 0.10.5...")
(pods/load-pod 'huahaiy/datalevin "0.10.5")
(require '[pod.huahaiy.datalevin :as d])
(println "Pod loaded.")

;; Create a temp database
(def db-path (str (fs/create-temp-dir {:prefix "pod-concurrency-test-"})))
(println (str "Database path: " db-path))

(def schema {:item/name {:db/unique :db.unique/identity}
             :item/value {}})

(def conn (d/get-conn db-path schema))
(println "Connection created.")
(println)

;; Seed some data
(println "Seeding 100 entities...")
(d/transact! conn (mapv (fn [i] {:item/name (str "item-" i) :item/value i})
                        (range 100)))
(println "Seeded.")
(println)

;; ----- Test 1: Concurrent reads (queries) -----

(println "--- Test 1: Concurrent reads (10 threads x 20 queries = 200 total) ---")
(let [n-threads 10
      n-ops 20
      results (atom [])
      errors (atom [])
      start-latch (promise)
      done-latch (java.util.concurrent.CountDownLatch. n-threads)
      start-ms (atom nil)]
  ;; Launch threads
  (doseq [t (range n-threads)]
    (future
      @start-latch ;; wait for all threads to start together
      (doseq [i (range n-ops)]
        (try
          (let [r (d/q '[:find (count ?e)
                         :where [?e :item/name _]]
                       (d/db conn))]
            (swap! results conj {:thread t :op i :result r}))
          (catch Exception e
            (swap! errors conj {:thread t :op i :error (str e)}))))
      (.countDown done-latch)))

  ;; Release all threads simultaneously
  (reset! start-ms (System/currentTimeMillis))
  (deliver start-latch true)

  ;; Wait with timeout
  (let [completed? (.await done-latch 30 java.util.concurrent.TimeUnit/SECONDS)
        elapsed (- (System/currentTimeMillis) @start-ms)]
    (if completed?
      (do
        (println (str "  COMPLETED: " (count @results) " successful, "
                      (count @errors) " errors in " elapsed "ms"))
        (when (seq @errors)
          (println "  ERRORS:" (take 3 @errors))))
      (println (str "  TIMEOUT after 30s! Only " (count @results)
                    " completed out of " (* n-threads n-ops)
                    " - DEADLOCK LIKELY")))))

(println)

;; ----- Test 2: Concurrent writes (transacts) -----

(println "--- Test 2: Concurrent writes (10 threads x 10 transacts = 100 total) ---")
(let [n-threads 10
      n-ops 10
      results (atom [])
      errors (atom [])
      start-latch (promise)
      done-latch (java.util.concurrent.CountDownLatch. n-threads)
      start-ms (atom nil)]
  (doseq [t (range n-threads)]
    (future
      @start-latch
      (doseq [i (range n-ops)]
        (try
          (let [entity-name (str "concurrent-" t "-" i)
                r (d/transact! conn [{:item/name entity-name
                                      :item/value (+ (* t 1000) i)}])]
            (swap! results conj {:thread t :op i :ok true}))
          (catch Exception e
            (swap! errors conj {:thread t :op i :error (str e)}))))
      (.countDown done-latch)))

  (reset! start-ms (System/currentTimeMillis))
  (deliver start-latch true)

  (let [completed? (.await done-latch 30 java.util.concurrent.TimeUnit/SECONDS)
        elapsed (- (System/currentTimeMillis) @start-ms)]
    (if completed?
      (do
        (println (str "  COMPLETED: " (count @results) " successful, "
                      (count @errors) " errors in " elapsed "ms"))
        (when (seq @errors)
          (println "  ERRORS:" (take 3 @errors))))
      (println (str "  TIMEOUT after 30s! Only " (count @results)
                    " completed out of " (* n-threads n-ops)
                    " - DEADLOCK LIKELY")))))

(println)

;; ----- Test 3: Mixed reads + writes (the critical case) -----

(println "--- Test 3: Mixed reads + writes (5 readers + 5 writers, 20 ops each = 200 total) ---")
(let [n-readers 5
      n-writers 5
      n-ops 20
      results (atom [])
      errors (atom [])
      start-latch (promise)
      done-latch (java.util.concurrent.CountDownLatch. (+ n-readers n-writers))
      start-ms (atom nil)]
  ;; Reader threads
  (doseq [t (range n-readers)]
    (future
      @start-latch
      (doseq [i (range n-ops)]
        (try
          (let [r (d/q '[:find ?name ?val
                         :where [?e :item/name ?name]
                         [?e :item/value ?val]]
                       (d/db conn))]
            (swap! results conj {:thread (str "R" t) :op i :type :read
                                 :count (count r)}))
          (catch Exception e
            (swap! errors conj {:thread (str "R" t) :op i :type :read
                                :error (str e)}))))
      (.countDown done-latch)))

  ;; Writer threads
  (doseq [t (range n-writers)]
    (future
      @start-latch
      (doseq [i (range n-ops)]
        (try
          (d/transact! conn [{:item/name (str "mixed-" t "-" i)
                              :item/value (+ (* t 10000) i)}])
          (swap! results conj {:thread (str "W" t) :op i :type :write :ok true})
          (catch Exception e
            (swap! errors conj {:thread (str "W" t) :op i :type :write
                                :error (str e)}))))
      (.countDown done-latch)))

  (reset! start-ms (System/currentTimeMillis))
  (deliver start-latch true)

  (let [completed? (.await done-latch 30 java.util.concurrent.TimeUnit/SECONDS)
        elapsed (- (System/currentTimeMillis) @start-ms)]
    (if completed?
      (do
        (println (str "  COMPLETED: " (count @results) " successful, "
                      (count @errors) " errors in " elapsed "ms"))
        (when (seq @errors)
          (println "  ERRORS:" (take 3 @errors)))
        ;; Verify data integrity
        (let [total (d/q '[:find (count ?e) :where [?e :item/name _]]
                         (d/db conn))]
          (println (str "  Total entities in DB: " (ffirst total)))))
      (println (str "  TIMEOUT after 30s! Only " (count @results)
                    " completed out of " (* (+ n-readers n-writers) n-ops)
                    " - DEADLOCK LIKELY")))))

(println)

;; ----- Test 4: Compound operations (query then transact, like our handlers) -----

(println "--- Test 4: Compound ops (5 threads doing query+transact pairs, 10 each = 50 compounds) ---")
(let [n-threads 5
      n-ops 10
      results (atom [])
      errors (atom [])
      start-latch (promise)
      done-latch (java.util.concurrent.CountDownLatch. n-threads)
      start-ms (atom nil)]
  (doseq [t (range n-threads)]
    (future
      @start-latch
      (doseq [i (range n-ops)]
        (try
          ;; Simulates update-namespace-symbols!: query existing, then transact
          (let [existing (d/q '[:find ?e ?name
                                :where [?e :item/name ?name]
                                [?e :item/value ?v]
                                [(< ?v 50)]]
                              (d/db conn))
                _ (Thread/sleep (rand-int 2)) ;; tiny jitter to maximize interleaving
                r (d/transact! conn [{:item/name (str "compound-" t "-" i)
                                      :item/value (+ 10000 (* t 100) i)}])]
            (swap! results conj {:thread t :op i :queried (count existing) :ok true}))
          (catch Exception e
            (swap! errors conj {:thread t :op i :error (str e)}))))
      (.countDown done-latch)))

  (reset! start-ms (System/currentTimeMillis))
  (deliver start-latch true)

  (let [completed? (.await done-latch 30 java.util.concurrent.TimeUnit/SECONDS)
        elapsed (- (System/currentTimeMillis) @start-ms)]
    (if completed?
      (do
        (println (str "  COMPLETED: " (count @results) " successful, "
                      (count @errors) " errors in " elapsed "ms"))
        (when (seq @errors)
          (println "  ERRORS:" (take 3 @errors))))
      (println (str "  TIMEOUT after 30s! Only " (count @results)
                    " completed out of " (* n-threads n-ops)
                    " - DEADLOCK LIKELY")))))

(println)

;; Cleanup
(println "Cleaning up...")
(d/close conn)
(fs/delete-tree db-path)
(println)

;; Summary
(println "=== Summary ===")
(println "If all 4 tests COMPLETED: Pod serializes internally, no external lock needed.")
(println "If any test TIMED OUT:    Pod does NOT serialize, external lock required.")
(println "If tests completed but with ERRORS: Pod may have partial serialization.")
