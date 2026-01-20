;; Code Browser v2 - Query isolation test
;; Tests each part of the query separately to find the deadlock source
;; Usage: bb nrepl load-file scripts/cb-v2-query-isolate.clj --mcp cb-v2-test --connection server

(require '[code-browser.handlers :as handlers])
(require '[code-browser.db.protocol :as db-proto])

(defn timed-query [label db query & args]
  (let [start (System/currentTimeMillis)
        result (if args
                 (db-proto/q db query args)
                 (db-proto/q db query))
        elapsed (- (System/currentTimeMillis) start)]
    {:label label
     :time-ms elapsed
     :count (count result)}))

(let [db (handlers/get-db)
      proj-uri "dir://.@ceb04b4"]
  (when db
    [(timed-query "1-projects" db
                  '[:find ?e :where [?e :project/root-path _]])

     (timed-query "2-namespaces-only" db
                  '[:find ?e :where [?e :ns/name _]])

     (timed-query "3-find-project" db
                  '[:find ?e :in $ ?uri :where [?e :uri/string ?uri]]
                  proj-uri)]))
