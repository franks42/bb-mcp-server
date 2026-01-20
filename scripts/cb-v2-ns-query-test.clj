;; Code Browser v2 - Test the exact namespace query from handlers.clj
;; Usage: bb nrepl load-file scripts/cb-v2-ns-query-test.clj --mcp cb-v2-test --connection server

(require '[code-browser.handlers :as handlers])
(require '[code-browser.db.protocol :as db-proto])

(def project-uri "dir://.@ceb04b4")

(let [db (handlers/get-db)
      start (System/currentTimeMillis)]
  (when db
    ;; This is the exact query from handlers.clj:query-namespaces
    ;; NOTE: args must be wrapped in a vector for apply in db-proto/q
    (let [results (db-proto/q db
                              '[:find (pull ?e [*])
                                :in $ ?proj-uri
                                :where [?p :uri/string ?proj-uri]
                                [?e :ns/name _]
                                [?e :uri/project ?proj-name]
                                [?p :uri/project ?proj-name]]
                              [project-uri])
          elapsed (- (System/currentTimeMillis) start)]
      {:query-time-ms elapsed
       :result-count (count results)
       :first-result (first (first results))})))
