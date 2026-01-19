;; Code Browser v2 - Reset loading state
;; Usage: bb nrepl load-file scripts/cb-v2-reset-loading.clj --mcp cb-v2-test --connection server

(require '[code-browser.sync :as sync])

(sync/set-loading! false)
:loading-reset
