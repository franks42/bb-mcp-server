;; Code Browser v2 - Initialize with database and project
;; Usage: bb nrepl load-file scripts/cb-v2-init.clj --mcp cb-v2-test --connection server

(require '[code-browser.core :as cb-v2])

(cb-v2/init! {:db-path "/tmp/cb-v2-test"
              :sources [{:type :dir :path "."}]})
