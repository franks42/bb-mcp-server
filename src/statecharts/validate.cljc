(ns statecharts.validate
    "Static analyzer for clj-statecharts machine definitions.
   Works on normalized machines (output of fsm/machine).
   Pure functions, no side effects. .cljc for BB + Scittle."
    (:require [clojure.set :as set]
              [clojure.string :as str]))

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

(defn- normalize-transition
  "Normalize a transition value to a vector of maps.
   Handles clj-statecharts shorthand forms:
   - :target-kw => [{:target :target-kw}]
   - {:target :foo :actions [...]} => [{:target :foo :actions [...]}]
   - {:actions [...]} => [{:actions [...]}]  (self-transition, no target)
   - [{:target :a :guard g} {:target :b}] => as-is"
  [v]
  (cond
    (keyword? v) [{:target v}]
    (map? v)     [v]
    (vector? v)  v
    :else        []))

(defn- extract-transitions-from-on
  "Extract transition edges from a state's :on map.
   Returns seq of [source-state target-state event-keyword guard?].
   Handles bare keyword targets and self-transitions."
  [state-kw on-map]
  (mapcat
   (fn [[event-kw transition-val]]
     (keep (fn [t]
             (when-let [target (:target t)]
                       {:source state-kw
                        :target target
                        :event  event-kw
                        :guard  (some? (:guard t))}))
           (normalize-transition transition-val)))
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
;; Convention Checks
;; =============================================================================

(defn check-has-id
  "Check that machine has an :id for identification in logs and CLI."
  [machine]
  (when-not (:id machine)
    [{:type     :missing-id
      :severity :convention
      :message  "Machine has no :id — add :id for CLI validation, logging, and browser viz"}]))

(defn check-has-context
  "Check that machine has an explicit :context for inspectability."
  [machine]
  (when-not (:context machine)
    [{:type     :missing-context
      :severity :convention
      :message  "Machine has no :context — add :context for debugging and state inspection"}]))

(defn check-error-recovery
  "Check that states with 'error' in name have outgoing transitions."
  [machine]
  (let [edges      (extract-edges machine)
        sources    (into #{} (map :source) edges)
        all-states (extract-states machine)
        error-states (filter #(str/includes? (name %) "error") all-states)]
    (for [s (sort error-states)
          :when (not (contains? sources s))]
         {:type     :error-no-recovery
          :severity :convention
          :state    s
          :message  (str "Error state " s " has no outgoing transitions — add recovery path (e.g. :reset, :retry)")})))

(def ^:private terminal-state-patterns
     "Name patterns that indicate intentional terminal states in per-instance lifecycles."
     #{"disconnected" "terminal" "done" "final" "completed" "finished" "closed" "destroyed"})

(defn- terminal-state?
  "Check if a state name matches a known terminal lifecycle pattern."
  [state-kw]
  (let [n (name state-kw)]
    (some #(str/includes? n %) terminal-state-patterns)))

(defn check-initial-return-path
  "Check that there is a path back to the initial state from every reachable state.
   Excludes states matching terminal lifecycle patterns (e.g. :disconnected, :done)
   since per-instance lifecycles intentionally terminate without recycling."
  [machine]
  (let [initial  (:initial machine)
        edges    (extract-edges machine)
        ;; Build reverse adjacency: who can reach whom
        rev-adj  (reduce (fn [m {:keys [source target]}]
                           (update m target (fnil conj #{}) source))
                         {}
                         edges)
        ;; BFS backwards from initial to find who can reach it
        can-reach-initial
        (loop [visited #{initial}
               queue   [initial]]
              (if (empty? queue)
                visited
                (let [current   (first queue)
                      neighbors (get rev-adj current #{})
                      unvisited (set/difference neighbors visited)]
                  (recur (into visited unvisited)
                         (into (subvec queue 1) unvisited)))))
        reachable   (reachable-states machine)
        no-return   (set/difference reachable can-reach-initial)]
    (for [s (sort no-return)
          :when (not (terminal-state? s))]
         {:type     :no-return-to-initial
          :severity :convention
          :state    s
          :message  (str "State " s " has no path back to initial state " initial)})))

(defn check-longform-transitions
  "Check that all transitions use longform {:target :state} instead of shorthand :state.
   Shorthand is valid clj-statecharts syntax but harder to read and breaks naive tooling.
   Works on raw config maps; compiled machines are already normalized."
  [machine]
  (let [states (:states machine)]
    (vec
     (for [[state-kw state-def] states
           :when (map? state-def)
           :let [on-map (:on state-def)]
           :when (map? on-map)
           [event-kw transition-val] on-map
           :when (keyword? transition-val)]
          {:type     :shorthand-transition
           :severity :convention
           :state    state-kw
           :event    event-kw
           :target   transition-val
           :message  (str "Event " event-kw " in state " state-kw
                          " uses shorthand " transition-val
                          " — use {:target " transition-val "} for consistency")}))))

(defn check-conventions
  "Run all convention checks on a normalized machine.
   Returns a vector of convention issue maps."
  [machine]
  (vec (concat (check-has-id machine)
               (check-has-context machine)
               (check-error-recovery machine)
               (check-initial-return-path machine)
               (check-longform-transitions machine))))

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
   Returns {:errors [...] :warnings [...] :info [...] :conventions [...] :summary {...} :graph {...}}."
  [machine]
  (let [errors      (vec (find-unreachable machine))
        warnings    (vec (concat (find-dead-ends machine)
                                 (find-non-deterministic machine)
                                 (find-orphans machine)))
        info        (vec (find-self-only machine))
        conventions (check-conventions machine)
        graph       (machine->graph machine)]
    {:errors      errors
     :warnings    warnings
     :info        info
     :conventions conventions
     :graph       graph
     :summary     {:states      (count (:states graph))
                   :edges       (count (:edges graph))
                   :errors      (count errors)
                   :warnings    (count warnings)
                   :info        (count info)
                   :conventions (count conventions)}}))
