;; Manual initialization of code-browser-v2
(require '[code-browser.db.datalevin :as dl])
(require '[code-browser.handlers :as handlers])
(require '[code-browser.sources.directory :as dir-source])
(require '[code-browser.sources.protocol :as source-proto])
(require '[code-browser.db.protocol :as db-proto])
(require '[code-browser.sync :as sync])

;; Create database
(def db (dl/create-db {:path "/tmp/cb-v2-test4"}))

;; Set in handlers
(handlers/set-db! db)

;; Create source
(def source (dir-source/create-directory-source "."))

;; Scan project
(def scan-result (source-proto/scan-project source))

;; Clean and transact entities
(let [{:keys [project namespaces symbols aliases refers]} scan-result
      clean-entity (fn [e]
                     (->> e
                          (remove (fn [[_k v]] (nil? v)))
                          (remove (fn [[k _v]] (= k :uri/parent)))
                          (into {})))
      project-uri (:uri/string project)]
  ;; Register source
  (handlers/register-source! project-uri source)
  ;; Transact project
  (db-proto/transact! db [(clean-entity project)])
  ;; Transact namespaces
  (doseq [ns-batch (partition-all 50 namespaces)]
    (db-proto/transact! db (mapv clean-entity ns-batch)))
  ;; Transact symbols
  (doseq [sym-batch (partition-all 100 symbols)]
    (db-proto/transact! db (mapv clean-entity sym-batch)))
  ;; Transact aliases
  (when (seq aliases)
    (doseq [alias-batch (partition-all 100 aliases)]
      (db-proto/transact! db (mapv clean-entity alias-batch))))
  ;; Transact refers
  (when (seq refers)
    (doseq [refer-batch (partition-all 100 refers)]
      (db-proto/transact! db (mapv clean-entity refer-batch))))
  ;; Enable sync and load projects
  (sync/register-sync!)
  (handlers/handle-load-projects!)
  {:project project-uri
   :namespaces (count namespaces)
   :symbols (count symbols)
   :aliases (count aliases)
   :refers (count refers)})
