(ns ai-orchestrator.registry
    "Provider-agnostic instance registry and state management.

   Tracks all AI instances regardless of provider type.
   Stores provider-agnostic metadata and provider-specific transport state."
    (:require [com.github.franks42.uuidv7.core :as uuidv7]
              [taoensso.trove :as log]))

;; =============================================================================
;; Registry State
;; =============================================================================

(defonce ^:private instances (atom {}))

(defonce ^:private request-counter (atom 0))

;; =============================================================================
;; Registry Operations
;; =============================================================================

(defn register-instance!
  "Add an instance to the registry.

   Arguments:
     instance - Instance map with :name, :provider-type, :transport, etc.

   Returns:
     The registered instance."
  [instance]
  (log/log! {:level :info
             :id    ::registering-instance
             :msg   "Registering AI instance"
             :data  {:name (:name instance)
                     :provider-type (:provider-type instance)}})
  (swap! instances assoc (:name instance) instance)
  instance)

(defn get-instance
  "Get an instance by name.

   Arguments:
     name - Instance name string

   Returns:
     Instance map or nil if not found."
  [name]
  (get @instances name))

(defn list-instances
  "List all registered instances.

   Returns:
     Sequence of instance maps."
  []
  (vals @instances))

(defn instance-names
  "Get names of all registered instances.

   Returns:
     Sequence of instance name strings."
  []
  (keys @instances))

(defn unregister-instance!
  "Remove an instance from the registry.

   Arguments:
     name - Instance name string

   Returns:
     The removed instance or nil if not found."
  [name]
  (log/log! {:level :info
             :id    ::unregistering-instance
             :msg   "Unregistering AI instance"
             :data  {:name name}})
  (let [instance (get-instance name)]
    (swap! instances dissoc name)
    instance))

(defn clear-registry!
  "Remove all instances from the registry.

   Warning: Does not stop instances - use orchestrator API instead."
  []
  (log/log! {:level :warn
             :id    ::clearing-registry
             :msg   "Clearing all instances from registry"})
  (reset! instances {}))

;; =============================================================================
;; Request ID Generation
;; =============================================================================

(defn next-request-id
  "Generate a unique request ID.

   Format: {instance-name}-{counter}-{uuid-prefix}

   Arguments:
     instance-name - Name of the instance making the request

   Returns:
     Unique request ID string."
  [instance-name]
  (let [n (swap! request-counter inc)
        uuid-prefix (subs (str (uuidv7/uuidv7)) 0 8)]
    (format "%s-%06d-%s" instance-name n uuid-prefix)))

;; =============================================================================
;; Instance Queries
;; =============================================================================

(defn get-instance-info
  "Get summary info about an instance.

   Arguments:
     name - Instance name

   Returns:
     Map with :name, :provider-type, :model, :capabilities, :created-at
     Or nil if not found."
  [name]
  (when-let [instance (get-instance name)]
            {:name (:name instance)
             :provider-type (:provider-type instance)
             :protocol (:protocol instance)
             :model (:model instance)
             :capabilities (:capabilities instance #{})
             :created-at (:created-at instance)
             :session-id (when-let [sid (:session-id instance)]
                                   @sid)}))

(defn find-instances-by-provider
  "Find all instances of a specific provider type.

   Arguments:
     provider-type - Provider type keyword (e.g., :claude-subprocess)

   Returns:
     Sequence of instance maps."
  [provider-type]
  (filter #(= provider-type (:provider-type %))
          (vals @instances)))

(defn find-instances-by-capability
  "Find instances with a specific capability.

   Arguments:
     capability - Capability keyword (e.g., :vision, :tools)

   Returns:
     Sequence of instance maps."
  [capability]
  (filter #(contains? (:capabilities % #{}) capability)
          (vals @instances)))
