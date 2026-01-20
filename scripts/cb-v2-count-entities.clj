;; Code Browser v2 - Count entities
;; Usage: bb nrepl load-file scripts/cb-v2-count-entities.clj --mcp cb-v2-test --connection server

(require '[code-browser.handlers :as handlers])
(require '[code-browser.db.protocol :as db-proto])

(let [db (handlers/get-db)]
  (if db
    {:db-present true
     :entity-count (count (db-proto/q db '[:find ?e :where [?e :uri/string _]]))}
    {:db-present false}))
