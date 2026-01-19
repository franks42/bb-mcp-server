;; Code Browser v2 - List available projects
;; Usage: bb nrepl load-file scripts/cb-v2-list-projects.clj --mcp cb-v2-test --connection server

(require '[code-browser.sync :as sync])

(mapv :uri/string (:projects @sync/!state))
