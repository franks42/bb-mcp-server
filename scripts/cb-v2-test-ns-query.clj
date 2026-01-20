;; Code Browser v2 - Test namespace query (same as handlers.clj:query-namespaces)
;; Usage: bb nrepl load-file scripts/cb-v2-test-ns-query.clj --mcp cb-v2-test --connection server

(require '[code-browser.handlers :as handlers])
(require '[code-browser.db.protocol :as db-proto])

;; Get a project URI first
(let [db (handlers/get-db)]
  (when db
    ;; First get project URIs
    (let [projects (db-proto/q db '[:find ?uri :where [?e :project/root-path _] [?e :uri/string ?uri]])]
      {:project-uris (vec (map first (take 3 projects)))})))
