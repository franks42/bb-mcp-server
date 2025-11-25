(ns port-registry.core
    "Port allocation and discovery for multi-server orchestration."
    (:require [port-registry.storage :as storage]
              [taoensso.trove :as log]))

;; Domain-specific port ranges
(def default-port-ranges
     "Default port ranges for different tool domains."
     {:clojure-tools [19880 19889]
      :aws-tools     [19890 19899]
      :python-tools  [19900 19909]
      :js-tools      [19910 19919]
      :data-tools    [19920 19929]
      :ml-tools      [19930 19939]
      :web-tools     [19940 19949]
      :db-tools      [19950 19959]
      :test-tools    [19960 19969]
      :misc-tools    [19970 19979]})

(def registry-atom
     "Atom holding the current registry state."
     (atom nil))

(def registry-path
     "Atom holding the path to the registry file."
     (atom storage/default-registry-path))

(defn process-alive?
  "Check if a process with given PID is alive."
  [pid]
  (try
   (let [result (.waitFor (.exec (Runtime/getRuntime)
                                 (into-array String ["kill" "-0" (str pid)])))]
     (zero? result))
   (catch Exception _
          false)))

(defn init!
  "Initialize port registry. Loads from file and validates on startup."
  ([] (init! storage/default-registry-path))
  ([path]
   (log/log! {:level :info
              :id ::init
              :msg "Initializing port registry"
              :data {:path path}})
   (reset! registry-path path)
   (let [registry (storage/load-registry path)
         ;; Add default port ranges if not present
         registry (update registry :port-ranges #(merge default-port-ranges %))]
     (reset! registry-atom registry)
     (log/log! {:level :info
                :id ::init-complete
                :msg "Port registry initialized"
                :data {:allocation-count (count (:allocations registry))}}))))

(defn validate-registry!
  "Clean up zombie ports from crashed processes. Should be called on startup."
  []
  (log/log! {:level :info
             :id ::validate-registry
             :msg "Validating registry for zombie ports"})
  (let [registry @registry-atom
        allocations (:allocations registry)
        zombies (filter (fn [[_port info]]
                          (when-let [pid (:pid info)]
                                    (not (process-alive? pid))))
                        allocations)]
    (when (seq zombies)
      (log/log! {:level :warn
                 :id ::zombie-ports-found
                 :msg "Found zombie ports from dead processes"
                 :data {:zombie-count (count zombies)
                        :ports (mapv first zombies)}})
      (doseq [[port info] zombies]
             (log/log! {:level :warn
                        :id ::zombie-port-cleanup
                        :msg "Releasing zombie port"
                        :data {:port port :pid (:pid info) :domain (:domain info)}})
             (swap! registry-atom update :allocations dissoc port)
             (when-let [domain (:domain info)]
                       (swap! registry-atom update-in [:domain-index domain] disj port)))
      (storage/save-registry! @registry-path @registry-atom)
      (log/log! {:level :info
                 :id ::zombie-cleanup-complete
                 :msg "Zombie port cleanup complete"
                 :data {:released-count (count zombies)}}))
    (when-not (seq zombies)
      (log/log! {:level :debug
                 :id ::no-zombies
                 :msg "No zombie ports found"}))
    (count zombies)))

(defn get-port-range
  "Get [start end] range for a domain."
  [domain]
  (get-in @registry-atom [:port-ranges domain]))

(defn find-available-port
  "Find first available port in domain's range."
  [domain]
  (when-let [[start end] (get-port-range domain)]
            (let [allocations (:allocations @registry-atom)
                  allocated-ports (set (keys allocations))]
              (some #(when-not (allocated-ports %) %)
                    (range start (inc end))))))

(defn allocate-port!
  "Allocate a port for the given options.

   Options:
   - :domain       - Domain keyword (required)
   - :service-type - Type of service (e.g., :mcp-server, :rest-api)
   - :expert-id    - Expert instance ID
   - :pid          - Process ID (optional, can be set later)
   - :url          - Service URL (optional)

   Returns port number on success, nil if no ports available."
  [{:keys [domain service-type expert-id pid url] :as opts}]
  (log/log! {:level :info
             :id ::allocate-port
             :msg "Allocating port"
             :data {:domain domain :service-type service-type}})
  (when-not domain
    (throw (ex-info "Domain is required" {:opts opts})))

  (when-let [port (find-available-port domain)]
            (let [allocation {:service-type service-type
                              :domain domain
                              :expert-id expert-id
                              :pid pid
                              :url (or url (str "http://127.0.0.1:" port))
                              :allocated-at (java.util.Date.)
                              :status :allocated}]
              (swap! registry-atom
                     (fn [registry]
                       (-> registry
                           (assoc-in [:allocations port] allocation)
                           (update-in [:domain-index domain] (fnil conj #{}) port))))
              (storage/save-registry! @registry-path @registry-atom)
              (log/log! {:level :info
                         :id ::port-allocated
                         :msg "Port allocated successfully"
                         :data {:port port :domain domain}})
              port)))

(defn update-port-info!
  "Update allocation info for a port (e.g., set PID after process starts)."
  [port updates]
  (when-not (get-in @registry-atom [:allocations port])
    (throw (ex-info "Port not allocated" {:port port})))

  (swap! registry-atom update-in [:allocations port] merge updates)
  (storage/save-registry! @registry-path @registry-atom)
  (log/log! {:level :debug
             :id ::port-info-updated
             :msg "Port info updated"
             :data {:port port :updates (keys updates)}}))

(defn release-port!
  "Release a port, making it available for reuse."
  [port]
  (log/log! {:level :info
             :id ::release-port
             :msg "Releasing port"
             :data {:port port}})
  (when-let [allocation (get-in @registry-atom [:allocations port])]
            (let [domain (:domain allocation)]
              (swap! registry-atom
                     (fn [registry]
                       (-> registry
                           (update :allocations dissoc port)
                           (update-in [:domain-index domain] disj port))))
              (storage/save-registry! @registry-path @registry-atom)
              (log/log! {:level :info
                         :id ::port-released
                         :msg "Port released successfully"
                         :data {:port port :domain domain}})
              true)))

(defn discover-by-domain
  "Find all allocated ports for a domain."
  [domain]
  (log/log! {:level :debug
             :id ::discover-by-domain
             :msg "Discovering ports by domain"
             :data {:domain domain}})
  (let [ports (get-in @registry-atom [:domain-index domain] #{})]
    (mapv (fn [port]
            (assoc (get-in @registry-atom [:allocations port])
                   :port port))
          ports)))

(defn discover-by-expert
  "Find port allocated for a specific expert instance."
  [expert-id]
  (log/log! {:level :debug
             :id ::discover-by-expert
             :msg "Discovering port by expert"
             :data {:expert-id expert-id}})
  (some (fn [[port info]]
          (when (= expert-id (:expert-id info))
            (assoc info :port port)))
        (:allocations @registry-atom)))

(defn get-port-info
  "Get allocation info for a specific port."
  [port]
  (get-in @registry-atom [:allocations port]))

(defn list-allocations
  "List all current port allocations."
  []
  (let [allocations (:allocations @registry-atom)]
    (mapv (fn [[port info]]
            (assoc info :port port))
          allocations)))

(defn check-port-health!
  "Check if service at port is responding.

   Returns:
   - :healthy if responding
   - :unhealthy if not responding
   - :unknown if can't determine"
  [port]
  (log/log! {:level :debug
             :id ::check-port-health
             :msg "Checking port health"
             :data {:port port}})
  (let [allocation (get-port-info port)]
    (if-not allocation
      (do
       (log/log! {:level :warn
                  :id ::port-not-allocated
                  :msg "Cannot check health of unallocated port"
                  :data {:port port}})
       :unknown)
      (try
        ;; Try to connect to the port
       (with-open [socket (java.net.Socket.)]
                  (.connect socket (java.net.InetSocketAddress. "127.0.0.1" port) 1000)
                  (update-port-info! port {:status :healthy})
                  (log/log! {:level :debug
                             :id ::port-healthy
                             :msg "Port is healthy"
                             :data {:port port}})
                  :healthy)
       (catch Exception e
              (update-port-info! port {:status :unhealthy})
              (log/log! {:level :warn
                         :id ::port-unhealthy
                         :msg "Port is unhealthy"
                         :error e
                         :data {:port port}})
              :unhealthy)))))

(defn cleanup-stale-allocations!
  "Remove allocations where process is dead or service is unhealthy.

   Returns number of cleaned up allocations."
  []
  (log/log! {:level :info
             :id ::cleanup-stale
             :msg "Cleaning up stale allocations"})
  (let [allocations (:allocations @registry-atom)
        stale (filter (fn [[port info]]
                        (or
                         ;; Process is dead
                         (and (:pid info) (not (process-alive? (:pid info))))
                         ;; Service is unhealthy (optional check)
                         (= :unhealthy (check-port-health! port))))
                      allocations)
        stale-count (count stale)]
    (when (seq stale)
      (log/log! {:level :warn
                 :id ::stale-allocations-found
                 :msg "Found stale allocations"
                 :data {:stale-count stale-count
                        :ports (mapv first stale)}})
      (doseq [[port _] stale]
             (release-port! port))
      (log/log! {:level :info
                 :id ::cleanup-complete
                 :msg "Stale allocation cleanup complete"
                 :data {:cleaned-count stale-count}}))
    stale-count))

;; Module entry point
(defn module
  "Module entry point for port-registry."
  []
  {:init (fn [_]
           (init!)
           (validate-registry!))
   :start (fn [_] nil)
   :stop (fn [_] nil)})
