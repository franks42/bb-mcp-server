(ns port-registry.storage
    "EDN-based persistence for port registry."
    (:require [clojure.edn :as edn]
              [clojure.java.io :as io]
              [taoensso.trove :as log]))

(def default-registry-path
     "Default path for registry persistence file."
     ".ports/registry.edn")

(defn ensure-directory!
  "Ensure registry directory exists.

   Creates parent directory if it doesn't exist."
  [path]
  (let [dir (.getParentFile (io/file path))]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn load-registry
  "Load registry from EDN file. Returns empty registry if file doesn't exist."
  [path]
  (log/log! {:level :debug
             :id ::load-registry
             :msg "Loading registry from file"
             :data {:path path}})
  (let [file (io/file path)]
    (if (.exists file)
      (try
       (let [registry (edn/read-string (slurp path))]
         (log/log! {:level :info
                    :id ::registry-loaded
                    :msg "Registry loaded successfully"
                    :data {:path path
                           :allocation-count (count (:allocations registry))}})
         registry)
       (catch Exception e
              (log/log! {:level :error
                         :id ::load-failed
                         :msg "Failed to load registry"
                         :error e
                         :data {:path path}})
              {:allocations {}
               :domain-index {}
               :port-ranges {}}))
      (do
       (log/log! {:level :debug
                  :id ::registry-not-found
                  :msg "Registry file not found, returning empty registry"
                  :data {:path path}})
       {:allocations {}
        :domain-index {}
        :port-ranges {}}))))

(defn save-registry!
  "Save registry to EDN file."
  [path registry]
  (log/log! {:level :debug
             :id ::save-registry
             :msg "Saving registry to file"
             :data {:path path
                    :allocation-count (count (:allocations registry))}})
  (try
   (ensure-directory! path)
   (spit path (pr-str registry))
   (log/log! {:level :info
              :id ::registry-saved
              :msg "Registry saved successfully"
              :data {:path path}})
   true
   (catch Exception e
          (log/log! {:level :error
                     :id ::save-failed
                     :msg "Failed to save registry"
                     :error e
                     :data {:path path}})
          false)))
