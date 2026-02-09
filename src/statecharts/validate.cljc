(ns statecharts.validate
    "Static analyzer for clj-statecharts machine definitions.
   Works on normalized machines (output of fsm/machine).
   Pure functions, no side effects. .cljc for BB + Scittle."
    (:require [clojure.set :as set]))

;; =============================================================================
;; State Extraction
;; =============================================================================

(defn- collect-states
  "Recursively collect all state keywords from a machine node.
   Returns a set of keywords for flat machines, or paths for hierarchical."
  [node]
  (let [states (:states node)
        regions (:regions node)]
    (cond
      states (into #{}
                   (mapcat (fn [[k child]]
                             (let [children (collect-states child)]
                               (if (seq children)
                                 (conj children k)
                                 #{k}))))
                   states)
      regions (into #{}
                    (mapcat (fn [[k child]]
                              (let [children (collect-states child)]
                                (if (seq children)
                                  (conj children k)
                                  #{k}))))
                    regions)
      :else #{})))

(defn extract-states
  "Extract the set of all state keywords from a normalized machine."
  [machine]
  (collect-states machine))

;; =============================================================================
;; Edge Extraction
;; =============================================================================

(defn- extract-transitions-from-on
  "Extract transition edges from a state's :on map.
   Returns seq of [source-state target-state event-keyword guard?]."
  [state-kw on-map]
  (mapcat
   (fn [[event-kw transitions]]
     (keep (fn [t]
             (when-let [target (:target t)]
                       {:source state-kw
                        :target target
                        :event  event-kw
                        :guard  (some? (:guard t))}))
           transitions))
   on-map))

(defn- collect-edges
  "Recursively collect all transition edges from a machine."
  [node]
  (let [states (:states node)
        regions (:regions node)]
    (cond
      states (into []
                   (mapcat (fn [[state-kw child]]
                             (concat
                              (extract-transitions-from-on state-kw (:on child))
                              (collect-edges child))))
                   states)
      regions (into []
                    (mapcat (fn [[_region-kw child]]
                              (collect-edges child)))
                    regions)
      :else [])))

(defn extract-edges
  "Extract all transition edges from a normalized machine.
   Returns a vector of {:source :target :event :guard} maps."
  [machine]
  (collect-edges machine))

;; =============================================================================
;; Reachability Analysis
;; =============================================================================

(defn reachable-states
  "Compute the set of states reachable from the initial state via BFS."
  [machine]
  (let [initial (:initial machine)
        edges   (extract-edges machine)
        adj     (reduce (fn [m {:keys [source target]}]
                          (update m source (fnil conj #{}) target))
                        {}
                        edges)]
    (loop [visited #{initial}
           queue   [initial]]
          (if (empty? queue)
            visited
            (let [current    (first queue)
                  neighbors  (get adj current #{})
                  unvisited  (set/difference neighbors visited)]
              (recur (into visited unvisited)
                     (into (subvec queue 1) unvisited)))))))

;; =============================================================================
;; Validation Checks
;; =============================================================================

(defn find-unreachable
  "Find states that cannot be reached from the initial state.
   Returns a seq of issue maps with :severity :error."
  [machine]
  (let [all-states (extract-states machine)
        reachable  (reachable-states machine)]
    (for [s (sort (set/difference all-states reachable))]
         {:type     :unreachable-state
          :severity :error
          :state    s
          :message  (str "State " s " cannot be reached from initial state " (:initial machine))})))

(defn find-dead-ends
  "Find states with no outgoing transitions.
   Returns a seq of issue maps with :severity :warning."
  [machine]
  (let [edges     (extract-edges machine)
        sources   (into #{} (map :source) edges)
        all-states (extract-states machine)]
    (for [s (sort (set/difference all-states sources))]
         {:type     :dead-end
          :severity :warning
          :state    s
          :message  (str "State " s " has no outgoing transitions")})))

(defn find-non-deterministic
  "Find events that have multiple transitions without guards.
   Returns a seq of issue maps with :severity :warning."
  [machine]
  (let [edges (extract-edges machine)]
    (->> edges
         (group-by (juxt :source :event))
         (filter (fn [[_k v]] (> (count v) 1)))
         (filter (fn [[_k v]] (not-every? :guard v)))
         (map (fn [[[source event] transitions]]
                {:type     :non-deterministic
                 :severity :warning
                 :state    source
                 :event    event
                 :count    (count transitions)
                 :message  (str "Event " event " in state " source
                                " has " (count transitions)
                                " transitions without full guard coverage")})))))

(defn find-orphans
  "Find states with no incoming transitions (except the initial state).
   Returns a seq of issue maps with :severity :warning."
  [machine]
  (let [initial    (:initial machine)
        edges      (extract-edges machine)
        targets    (into #{} (map :target) edges)
        all-states (extract-states machine)]
    (for [s (sort (set/difference all-states targets #{initial}))]
         {:type     :orphan
          :severity :warning
          :state    s
          :message  (str "State " s " has no incoming transitions (except if initial)")})))

(defn find-self-only
  "Find states whose only transitions point back to themselves.
   Returns a seq of issue maps with :severity :info."
  [machine]
  (let [edges (extract-edges machine)]
    (->> edges
         (group-by :source)
         (filter (fn [[source transitions]]
                   (every? #(= source (:target %)) transitions)))
         (map (fn [[source _]]
                {:type     :self-only
                 :severity :info
                 :state    source
                 :message  (str "State " source " only has self-transitions")})))))

;; =============================================================================
;; Graph Extraction (for visualization)
;; =============================================================================

(defn machine->graph
  "Extract a pure-data graph from a normalized machine.
   Returns {:states #{kw} :edges [{:source :target :event :guard}] :initial kw}."
  [machine]
  {:states  (extract-states machine)
   :edges   (extract-edges machine)
   :initial (:initial machine)
   :id      (:id machine)})

;; =============================================================================
;; Main Validation Entry Point
;; =============================================================================

(defn validate
  "Run all static analysis checks on a normalized machine.
   Returns {:errors [...] :warnings [...] :info [...] :summary {...} :graph {...}}."
  [machine]
  (let [errors   (vec (find-unreachable machine))
        warnings (vec (concat (find-dead-ends machine)
                              (find-non-deterministic machine)
                              (find-orphans machine)))
        info     (vec (find-self-only machine))
        graph    (machine->graph machine)]
    {:errors   errors
     :warnings warnings
     :info     info
     :graph    graph
     :summary  {:states    (count (:states graph))
                :edges     (count (:edges graph))
                :errors    (count errors)
                :warnings  (count warnings)
                :info      (count info)}}))
