;; Code Browser v2 - Raw query test (no sync machinery)
;; Usage: bb nrepl load-file scripts/cb-v2-raw-query.clj --mcp cb-v2-test --connection server

;; Step 1: Check db exists
(require '[code-browser.handlers :as handlers])

(def db (handlers/get-db))
(println "DB exists:" (some? db))

;; Step 2: Run the simplest possible query - just count
(require '[code-browser.db.protocol :as db-proto])

(when db
  (println "Running count query...")
  (flush)
  (let [start (System/currentTimeMillis)
        result (db-proto/q db '[:find (count ?e) :where [?e :uri/string _]])
        elapsed (- (System/currentTimeMillis) start)]
    (println "Result:" result "in" elapsed "ms")))
