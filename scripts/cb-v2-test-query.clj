;; Code Browser v2 - Test simple query
;; Usage: bb nrepl load-file scripts/cb-v2-test-query.clj --mcp cb-v2-test --connection server

(require '[code-browser.handlers :as handlers])
(require '[code-browser.db.protocol :as db-proto])

(let [db (handlers/get-db)]
  (if db
    (let [start (System/currentTimeMillis)
          result (db-proto/q db '[:find ?e :where [?e :ns/name _]])
          elapsed (- (System/currentTimeMillis) start)]
      {:db-present true
       :query-time-ms elapsed
       :namespace-count (count result)})
    {:db-present false}))
