(ns bb-mcp-server.module.deps
    "Dependency resolution for bb-mcp-server modules.

  Provides topological sorting and cycle detection for module dependencies.
  Modules declare dependencies in their module.edn manifest via :deps key.

  Example module.edn:
    {:name \"api-handler\"
     :version \"1.0.0\"
     :entry-ns api-handler.core
     :deps [\"database\" \"cache\"]}  ; depends on database and cache modules"
    (:require [clojure.set :as set]
              [taoensso.trove :as log]))

;; -----------------------------------------------------------------------------
;; Telemetry
;; -----------------------------------------------------------------------------

(def ^:private resolver-metrics
     "Atom holding resolver telemetry metrics."
     (atom {:resolutions 0
            :cycles-detected 0
            :last-resolution-at nil
            :last-resolution-ms 0}))

(defn get-resolver-metrics
  "Get current resolver metrics.

  Returns:
    Map with keys:
      :resolutions        - Number of successful resolutions
      :cycles-detected    - Number of cycles found
      :last-resolution-at - Timestamp of last resolution
      :last-resolution-ms - Duration of last resolution"
  []
  @resolver-metrics)

(defn reset-resolver-metrics!
  "Reset resolver metrics to initial state."
  []
  (reset! resolver-metrics
          {:resolutions 0
           :cycles-detected 0
           :last-resolution-at nil
           :last-resolution-ms 0}))

(defn- record-resolution!
  "Record successful dependency resolution."
  [duration-ms]
  (swap! resolver-metrics
         (fn [m]
           (-> m
               (update :resolutions inc)
               (assoc :last-resolution-at (System/currentTimeMillis))
               (assoc :last-resolution-ms duration-ms)))))

(defn- record-cycle!
  "Record cycle detection."
  []
  (swap! resolver-metrics update :cycles-detected inc))

;; -----------------------------------------------------------------------------
;; Dependency Graph Construction
;; -----------------------------------------------------------------------------

(defn build-dependency-graph
  "Build a dependency graph from loaded modules.

  Args:
    loaded-modules - Map of module-name -> {:manifest {...} :module {...}}

  Returns:
    Map of module-name -> set of dependency names

  Example:
    {\"api\" #{\"db\" \"cache\"}
     \"db\" #{}
     \"cache\" #{\"db\"}}"
  [loaded-modules]
  (->> loaded-modules
       (filter (fn [[_name data]] (not (:error data))))
       (map (fn [[name data]]
              (let [deps (get-in data [:manifest :deps] [])]
                [name (set deps)])))
       (into {})))

(defn get-all-dependencies
  "Get all transitive dependencies for a module.

  Args:
    graph      - Dependency graph from build-dependency-graph
    module-name - Module to get dependencies for

  Returns:
    Set of all dependency names (direct and transitive)"
  [graph module-name]
  (loop [to-visit #{module-name}
         visited #{}]
        (if (empty? to-visit)
          (disj visited module-name)
          (let [current (first to-visit)
                deps (get graph current #{})
                new-deps (set/difference deps visited)]
            (recur (into (disj to-visit current) new-deps)
                   (conj visited current))))))

;; -----------------------------------------------------------------------------
;; Cycle Detection
;; -----------------------------------------------------------------------------

(defn- find-cycle-from
  "Find a cycle starting from a given node using DFS.

  Args:
    graph   - Dependency graph
    start   - Starting node
    path    - Current path (vector for ordered cycle reporting)
    visited - Set of visited nodes in this path

  Returns:
    Vector representing the cycle path, or nil if no cycle"
  [graph start path visited]
  (if (contains? visited start)
    (conj path start) ; Found cycle
    (let [deps (get graph start #{})]
      (some (fn [dep]
              (find-cycle-from graph dep (conj path start) (conj visited start)))
            deps))))

(defn detect-cycles
  "Detect circular dependencies in the dependency graph.

  Args:
    graph - Dependency graph from build-dependency-graph

  Returns:
    Vector of cycles found, where each cycle is a vector of module names.
    Empty vector if no cycles exist."
  [graph]
  (let [nodes (keys graph)
        cycles (atom [])]
    (doseq [node nodes]
           (when-let [cycle (find-cycle-from graph node [] #{})]
                     (record-cycle!)
                     (swap! cycles conj cycle)
                     (log/log! {:level :error
                                :id ::cycle-detected
                                :msg "Circular dependency detected"
                                :data {:cycle cycle}})))
    @cycles))

;; -----------------------------------------------------------------------------
;; Topological Sort
;; -----------------------------------------------------------------------------

(defn- kahn-sort
  "Implement Kahn's algorithm for topological sorting.

  Args:
    graph - Dependency graph (module -> set of dependencies)

  Returns:
    {:success sorted-list} or {:error {:type :cycle :nodes [...]}}

  Note: Returns modules in dependency order (dependencies first)."
  [graph]
  (let [;; Build reverse graph (what depends on what)
        dependents (reduce (fn [acc [node deps]]
                             (reduce (fn [a d]
                                       (update a d (fnil conj #{}) node))
                                     acc deps))
                           {} graph)
        ;; Count incoming edges (number of dependencies)
        in-degree (reduce (fn [acc [node deps]]
                            (-> acc
                                (update node (fnil identity 0))
                                (as-> m (reduce (fn [a d]
                                                  (update a d (fnil inc 0)))
                                                m deps))))
                          {} graph)
        ;; Find nodes with no dependencies
        no-deps (filter (fn [[_node deg]] (zero? deg)) in-degree)
        queue (into clojure.lang.PersistentQueue/EMPTY (map first no-deps))]

    (loop [q queue
           result []
           remaining-in-degree in-degree]
          (if (empty? q)
        ;; Check if all nodes are sorted
            (if (= (count result) (count graph))
              {:success result}
              {:error {:type :cycle
                       :nodes (vec (set/difference
                                    (set (keys graph))
                                    (set result)))}})
            (let [node (peek q)
                  node-dependents (get dependents node #{})
              ;; Decrement in-degree for dependents
                  new-in-degree (reduce (fn [acc dep]
                                          (update acc dep dec))
                                        remaining-in-degree
                                        node-dependents)
              ;; Find newly free nodes
                  newly-free (filter (fn [d]
                                       (zero? (get new-in-degree d)))
                                     node-dependents)]
              (recur (into (pop q) newly-free)
                     (conj result node)
                     new-in-degree))))))

(defn resolve-order
  "Resolve module load order based on dependencies.

  Args:
    loaded-modules - Map of module-name -> {:manifest {...} :module {...}}

  Returns:
    {:success [module-names...]} in load order (dependencies first)
    or {:error {:type :cycle :cycles [...]}}

  Telemetry:
    Records resolution time and success/failure"
  [loaded-modules]
  (let [start-time (System/currentTimeMillis)]
    (log/log! {:level :info
               :id ::resolve-order-start
               :msg "Starting dependency resolution"
               :data {:module-count (count loaded-modules)}})
    (let [graph (build-dependency-graph loaded-modules)
          cycles (detect-cycles graph)]
      (if (seq cycles)
        (do
         (log/log! {:level :error
                    :id ::resolution-failed
                    :msg "Dependency resolution failed due to cycles"
                    :data {:cycles cycles}})
         {:error {:type :cycle :cycles cycles}})
        (let [result (kahn-sort graph)
              duration (- (System/currentTimeMillis) start-time)]
          (if (:success result)
            (do
             (record-resolution! duration)
             (log/log! {:level :info
                        :id ::resolution-complete
                        :msg "Dependency resolution complete"
                        :data {:order (:success result)
                               :duration-ms duration}})
             result)
            (do
             (log/log! {:level :error
                        :id ::resolution-failed
                        :msg "Dependency resolution failed"
                        :data {:error (:error result)}})
             result)))))))

;; -----------------------------------------------------------------------------
;; Dependency Validation
;; -----------------------------------------------------------------------------

(defn validate-dependencies
  "Validate that all declared dependencies exist.

  Args:
    loaded-modules - Map of module-name -> {:manifest {...} :module {...}}

  Returns:
    {:valid true} or {:valid false :missing {...}}
    where :missing is a map of module -> [missing-deps]"
  [loaded-modules]
  (let [available (set (keys loaded-modules))
        graph (build-dependency-graph loaded-modules)
        missing (reduce (fn [acc [module deps]]
                          (let [missing-deps (set/difference deps available)]
                            (if (seq missing-deps)
                              (assoc acc module (vec missing-deps))
                              acc)))
                        {} graph)]
    (if (empty? missing)
      (do
       (log/log! {:level :info
                  :id ::deps-valid
                  :msg "All dependencies satisfied"})
       {:valid true})
      (do
       (log/log! {:level :error
                  :id ::deps-invalid
                  :msg "Missing dependencies detected"
                  :data {:missing missing}})
       {:valid false :missing missing}))))

(defn get-dependents
  "Get modules that depend on a given module.

  Args:
    loaded-modules - Map of module-name -> {:manifest {...} :module {...}}
    module-name    - Module to find dependents for

  Returns:
    Set of module names that depend on the given module"
  [loaded-modules module-name]
  (let [graph (build-dependency-graph loaded-modules)]
    (->> graph
         (filter (fn [[_name deps]] (contains? deps module-name)))
         (map first)
         (set))))
