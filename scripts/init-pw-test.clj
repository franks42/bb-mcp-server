(require '[code-browser.core :as core])
(require '[code-browser.handlers :as h])

(println "Initializing code-browser-v2...")
(core/init! {:db-path "/tmp/cb-pw-test-7"
             :sources [{:type :dir :path "/Users/franksiebenlist/Development/bb-mcp-server"}]
             :auto-scan? true
             :auto-enable? false})

(println "Testing fetch project-list...")
(println (h/handle-fetch {:uri nil :property :project-list :widget-id :test}))
(println "Done!")
