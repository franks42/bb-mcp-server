(ns bb-mcp-server.modules.clojure-lsp.watcher
    "File system watcher for clojure-lsp.

   Uses pod-babashka-fswatcher to monitor Clojure source files
   and notifies clojure-lsp of changes to keep the index fresh."
    (:require [babashka.pods :as pods]
              [bb-mcp-server.modules.clojure-lsp.client :as client]
              [clojure.string :as str]
              [taoensso.trove :as trove]))

;; Load the fswatcher pod
(pods/load-pod 'org.babashka/fswatcher "0.0.7")
(require '[pod.babashka.fswatcher :as fw])

;; -----------------------------------------------------------------------------
;; State
;; -----------------------------------------------------------------------------

(defonce ^:private watcher-state
         (atom {:watcher nil
                :running? false
                :stats {:events 0 :files-changed 0}}))

;; -----------------------------------------------------------------------------
;; File Filtering
;; -----------------------------------------------------------------------------

(def ^:private clojure-extensions
     "File extensions to watch for Clojure projects."
     #{".clj" ".cljs" ".cljc" ".edn"})

(defn- clojure-file?
  "Check if a path is a Clojure source file."
  [path]
  (some #(str/ends-with? path %) clojure-extensions))

(defn- should-ignore?
  "Check if a path should be ignored (build dirs, hidden files, etc.)."
  [path]
  (or (str/includes? path "/.git/")
      (str/includes? path "/.clj-kondo/")
      (str/includes? path "/.lsp/")
      (str/includes? path "/.cpcache/")
      (str/includes? path "/target/")
      (str/includes? path "/node_modules/")
      (str/includes? path "/.shadow-cljs/")))

;; -----------------------------------------------------------------------------
;; Event Handling
;; -----------------------------------------------------------------------------

(defn- event-type
  "Convert fswatcher event type to LSP type keyword."
  [type]
  (case type
    :create :created
    :write :changed
    :remove :deleted
    :rename :changed  ; Treat rename as changed
    :chmod :changed   ; Treat chmod as changed (metadata change)
    :changed))        ; Default

(defn- handle-event!
  "Handle a file system event."
  [{:keys [type path]}]
  (when (and (clojure-file? path)
             (not (should-ignore? path)))
    (let [lsp-type (event-type type)]
      (trove/log! {:level :trace
                   :id :clojure-lsp.watcher/event
                   :msg (str "File " (name lsp-type) ": " path)
                   :data {:path path :type lsp-type}})
      (swap! watcher-state update-in [:stats :events] inc)
      (swap! watcher-state update-in [:stats :files-changed] inc)
      ;; Notify clojure-lsp of the change
      (when (client/running?)
        (client/did-change-watched-files! [{:path path :type lsp-type}])))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn watching?
  "Check if the watcher is currently running."
  []
  (:running? @watcher-state))

(defn stats
  "Get watcher statistics."
  []
  (:stats @watcher-state))

(defn start!
  "Start watching a directory for Clojure file changes.

   Args:
     path - Directory path to watch (defaults to clojure-lsp project root)
     opts - Options map:
       :recursive - Watch recursively (default: true)
       :delay-ms  - Debounce delay in ms (default: 100)

   Returns:
     {:status \"watching\" :path path}"
  ([] (start! nil {}))
  ([path] (start! path {}))
  ([path {:keys [recursive delay-ms]
          :or {recursive true delay-ms 100}}]
   (when (watching?)
     (throw (ex-info "Watcher already running" {:path path})))

   (let [watch-path (or path
                        (client/get-project-root)
                        (throw (ex-info "No path specified and clojure-lsp not running" {})))]
     (trove/log! {:level :info
                  :id :clojure-lsp.watcher/starting
                  :msg (str "Starting file watcher on: " watch-path)
                  :data {:path watch-path :recursive recursive :delay-ms delay-ms}})

     (let [watcher (fw/watch watch-path
                             handle-event!
                             {:recursive recursive
                              :delay-ms delay-ms})]
       (swap! watcher-state assoc
              :watcher watcher
              :running? true
              :stats {:events 0 :files-changed 0})
       {:status "watching"
        :path watch-path
        :recursive recursive}))))

(defn stop!
  "Stop the file watcher."
  []
  (when-let [watcher (:watcher @watcher-state)]
            (trove/log! {:level :info
                         :id :clojure-lsp.watcher/stopping
                         :msg "Stopping file watcher"
                         :data (stats)})
            (fw/unwatch watcher)
            (swap! watcher-state assoc
                   :watcher nil
                   :running? false)
            {:status "stopped"
             :stats (stats)}))

(defn watch-blocking!
  "Start watching and block until interrupted.

   Useful for CLI usage. Press Ctrl-C to stop.

   Args:
     path - Directory to watch
     opts - Options map (see start!)"
  ([] (watch-blocking! nil {}))
  ([path] (watch-blocking! path {}))
  ([path opts]
   (let [result (start! path opts)]
     (trove/log! {:level :info
                  :id :clojure-lsp.watcher/blocking
                  :msg "Watcher running. Press Ctrl-C to stop."})
     ;; Add shutdown hook for clean exit
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread. ^Runnable stop!))
     ;; Block forever
     @(promise)
     result)))
