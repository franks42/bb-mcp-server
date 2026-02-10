(ns statecharts.types
    "Typed statechart record for discovery and introspection.
   .cljc for BB + Scittle compatibility.

   Single Statechart defrecord type covers both config and compiled forms.
   fsm/machine normalizes transitions (map -> vector), which we detect
   to distinguish the two flavors without separate types.

   Usage:
     (def my-machine-config
       (map->Statechart
         {:id :my-machine :initial :idle :states {...}}))

     (statechart? my-machine-config)  ;; => true
     (compiled? my-machine-config)    ;; => false
     (compiled? (fsm/machine my-machine-config))  ;; => true")

;; =============================================================================
;; Statechart Record
;; =============================================================================
;; Fields:
;;   id      - keyword identifying the machine (for logs, CLI, viz)
;;   initial - keyword for the initial state
;;   states  - map of state-keyword -> {:on {...}} transition definitions
;;
;; Additional keys (:context, :regions, etc.) preserved as map overflow.
;; fsm/machine consumes this as a regular map — no code changes needed.
;; assoc on a defrecord preserves the type, so compiled machines are
;; still Statechart instances.

#_{:clj-kondo/ignore [:missing-docstring]}
(defrecord Statechart [id initial states])

(defn statechart?
  "Check if x is a Statechart record (config or compiled)."
  [x]
  (instance? Statechart x))

(defn compiled?
  "Check if a Statechart has been compiled by fsm/machine.
   Compiled machines have vectorized transitions (map -> vector)."
  [x]
  (and (statechart? x)
       (let [states (:states x)]
         (some (fn [[_state-kw state-def]]
                 (some (fn [[_event-kw transition]]
                         (vector? transition))
                       (:on state-def)))
               states))))

(defn find-statecharts
  "Find all Statechart vars in the given namespace.
   Returns a seq of {:var v :statechart @v :compiled? bool} maps."
  [ns-sym]
  (for [[_sym v] (ns-publics (find-ns ns-sym))
        :let [val (deref v)]
        :when (statechart? val)]
       {:var v :statechart val :compiled? (boolean (compiled? val))}))
