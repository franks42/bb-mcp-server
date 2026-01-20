;; Code Browser v2 - Test join patterns
;; Usage: bb nrepl load-file scripts/cb-v2-query-join-test.clj --mcp cb-v2-test --connection server

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
    [;; Step 1: Get project's :uri/project value
     (timed-query "1-project-name" db
                  '[:find ?proj-name
                    :in $ ?uri
                    :where [?p :uri/string ?uri]
                    [?p :uri/project ?proj-name]]
                  proj-uri)

     ;; Step 2: Get namespaces with that project name (no pull)
     (timed-query "2-ns-with-project" db
                  '[:find ?e
                    :in $ ?proj-uri
                    :where [?p :uri/string ?proj-uri]
                    [?p :uri/project ?proj-name]
                    [?e :ns/name _]
                    [?e :uri/project ?proj-name]]
                  proj-uri)

     ;; Step 3: Same but with simple pull (just uri/string)
     (timed-query "3-ns-with-pull-uri" db
                  '[:find (pull ?e [:uri/string])
                    :in $ ?proj-uri
                    :where [?p :uri/string ?proj-uri]
                    [?p :uri/project ?proj-name]
                    [?e :ns/name _]
                    [?e :uri/project ?proj-name]]
                  proj-uri)]))
