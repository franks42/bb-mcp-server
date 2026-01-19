;; Code Browser v2 - Select project
;; Usage: bb nrepl load-file scripts/cb-v2-select-project.clj --mcp cb-v2-test --connection server
;; Note: Edit project-uri below before running

(require '[code-browser.handlers :as handlers])

;; Change this to the project URI you want to select
(def project-uri "dir://.@ceb04b4")

(handlers/handle-select-project! project-uri)
