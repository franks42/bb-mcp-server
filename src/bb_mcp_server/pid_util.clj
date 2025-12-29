(ns bb-mcp-server.pid-util
    "Utility functions for managing PID and port files for bb-mcp-server instances."
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [cheshire.core :as json]
              [taoensso.trove :as log])
    (:import [java.time Instant]))

;; Directory for port files
(def ^:private ports-dir ".ports")

;; Counter for incremental numbering (atom for thread safety)
(def ^:private counter (atom 0))

(defn- ensure-ports-dir!
  "Ensure the .ports directory exists in the current working directory."
  []
  (let [dir (io/file ports-dir)]
    (when-not (.exists dir)
      (.mkdir dir))
    dir))

(defn- get-incremental-number
  "Get the next incremental number for uniqueness."
  []
  (swap! counter inc))

(defn- derive-default-nickname
  "Derive a default nickname from the config path or use a generic name with increment."
  [config-path]
  (let [base-name (if config-path
                    (let [file-name (.getName (io/file config-path))]
                      (str/replace file-name ".edn" ""))
                    "mcp-server")
        increment (get-incremental-number)]
    (str base-name "-" increment)))

(defn write-pid-file!
  "Write a port file for the given port, optionally using a nickname.
   If no nickname is provided, derive one from config or use a default with increment.
   File is written to .ports/ directory with JSON content including PID and timestamp."
  ([port]
   (write-pid-file! port nil nil))
  ([port nickname config-path]
   (let [pid (.pid (java.lang.ProcessHandle/current))
         actual-nickname (or nickname (derive-default-nickname config-path))
         file-name (str actual-nickname ".json")
         ports-dir (ensure-ports-dir!)
         file-path (io/file ports-dir file-name)
         content (json/generate-string
                  {:pid pid
                   :port port
                   :nickname actual-nickname
                   :config config-path
                   :timestamp (str (Instant/now))}
                  {:pretty true})]
     (log/log! {:level :info
                :id ::write-port-file
                :msg "Writing port file"
                :data {:file file-path :port port :pid pid :nickname actual-nickname}})
     (spit file-path content)
     (println (str "Wrote port file: " file-path " for port " port))
     file-path)))

(defn delete-pid-file!
  "Delete the port file for the given port, using nickname if provided."
  ([port]
   (delete-pid-file! port nil))
  ([port nickname]
   (let [ports-dir ".ports"
         file-name (if nickname
                     (str nickname ".json")
                     (str "mcp-server-" port ".json"))
         file-path (io/file ports-dir file-name)]
     (when (.exists file-path)
       (log/log! {:level :info
                  :id ::delete-port-file
                  :msg "Deleting port file"
                  :data {:file file-path :port port :nickname nickname}})
       (io/delete-file file-path)
       (println (str "Deleted port file: " file-path))
       true))))
