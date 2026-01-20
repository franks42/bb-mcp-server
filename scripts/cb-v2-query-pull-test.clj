;; Code Browser v2 - Test pull patterns
;; Usage: bb nrepl load-file scripts/cb-v2-query-pull-test.clj --mcp cb-v2-test --connection server

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
    [;; Test 1: Pull specific attributes
     (timed-query "1-pull-2-attrs" db
                  '[:find (pull ?e [:uri/string :ns/name])
                    :in $ ?proj-uri
                    :where [?p :uri/string ?proj-uri]
                    [?p :uri/project ?proj-name]
                    [?e :ns/name _]
                    [?e :uri/project ?proj-name]]
                  proj-uri)

     ;; Test 2: Pull more attributes
     (timed-query "2-pull-5-attrs" db
                  '[:find (pull ?e [:uri/string :ns/name :ns/file :uri/project :uri/version])
                    :in $ ?proj-uri
                    :where [?p :uri/string ?proj-uri]
                    [?p :uri/project ?proj-name]
                    [?e :ns/name _]
                    [?e :uri/project ?proj-name]]
                  proj-uri)

     ;; Test 3: Pull ALL attributes - this might cause the issue
     (timed-query "3-pull-ALL" db
                  '[:find (pull ?e [*])
                    :in $ ?proj-uri
                    :where [?p :uri/string ?proj-uri]
                    [?p :uri/project ?proj-name]
                    [?e :ns/name _]
                    [?e :uri/project ?proj-name]]
                  proj-uri)]))
