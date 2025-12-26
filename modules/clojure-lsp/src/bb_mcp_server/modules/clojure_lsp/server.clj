(ns bb-mcp-server.modules.clojure-lsp.server
    (:require [babashka.process :as p]
              [clojure.java.io :as io]
              [taoensso.trove :as trove])
    (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]))

(defonce ^:private state
         (atom {:process nil
                :in nil
                :out nil
                :project-root nil
                :executable-path "clojure-lsp"
                :initialized? false}))

(defn- running? []
  (boolean (:process @state)))

(defn stop!
  "Shutdown clojure-lsp subprocess if running."
  []
  (when-let [proc (:process @state)]
            (trove/log! {:level :info
                         :id :clojure-lsp/stopping
                         :msg "Stopping clojure-lsp subprocess"})
            (p/destroy proc)
            (reset! state {:process nil
                           :in nil
                           :out nil
                           :project-root nil
                           :executable-path "clojure-lsp"
                           :initialized? false})
            {:status "stopped"}))

(defn start!
  "Start clojure-lsp subprocess."
  [{:keys [project-root executable-path]
    :or {executable-path "clojure-lsp"}}]
  (when (running?)
    (stop!))

  (trove/log! {:level :info
               :id :clojure-lsp/starting
               :msg "Starting clojure-lsp subprocess"
               :data {:project-root project-root :executable-path executable-path}})

  (let [proc (p/process [executable-path]
                        {:dir project-root
                         :in :pipe
                         :out :pipe
                         :err :inherit})
        in (BufferedReader. (InputStreamReader. (:out proc)))
        out (BufferedWriter. (OutputStreamWriter. (:in proc)))]

    (swap! state assoc
           :process proc
           :in in
           :out out
           :project-root project-root
           :executable-path executable-path)

    (trove/log! {:level :info
                 :id :clojure-lsp/started
                 :msg "clojure-lsp subprocess started"
                 :data {:pid (.pid (:proc (:process @state)))}})

    {:status "started"
     :pid (.pid (:proc (:process @state)))}))

(defn init!
  "Tool handler for clj-init."
  [{:keys [project-root executable-path]}]
  (trove/log! {:level :info
               :id :clojure-lsp/init
               :msg "Initializing clojure-lsp module"
               :data {:project-root project-root}})
  ;; Verify project root exists
  (when-not (and project-root (.exists (io/file project-root)))
    (let [msg (str "Project root does not exist: " project-root)]
      (trove/log! {:level :error
                   :id :clojure-lsp/init-failed
                   :msg msg})
      (throw (ex-info msg {:project-root project-root}))))

  (start! {:project-root project-root
           :executable-path executable-path}))
