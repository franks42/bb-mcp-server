;; Code Browser v2 - Check current state
;; Usage: bb nrepl load-file scripts/cb-v2-state.clj --mcp cb-v2-test --connection server

(require '[code-browser.sync :as sync])
(require '[atom-sync.core :as atom-sync])

(let [s @sync/!state]
  {:projects (count (:projects s))
   :namespaces (count (:namespaces s))
   :symbols (count (:symbols s))
   :selected-project (:selected-project s)
   :selected-ns (:selected-ns s)
   :loading? (:loading? s)
   :error (:error s)
   :atom-sync-seq (get (atom-sync/get-sync-status) :code-browser-v2)})
