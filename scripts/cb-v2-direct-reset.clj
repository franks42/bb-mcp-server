;; Code Browser v2 - Direct reset without require
;; Usage: bb nrepl load-file scripts/cb-v2-direct-reset.clj --mcp cb-v2-test --connection server

(when (find-ns 'code-browser.sync)
  (swap! @(ns-resolve 'code-browser.sync '!state) assoc :loading? false))
:done
