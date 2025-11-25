(ns ai-orchestrator.protocol
    "Provider protocol using Clojure multimethods for extensibility.

   Providers implement these multimethods to integrate with the orchestrator.
   Each provider type gets its own implementations dispatched by :provider-type.")

;; =============================================================================
;; Protocol Multimethods
;; =============================================================================

(defmulti create-instance
          "Create a new AI instance.

   Dispatches on :provider-type in opts map.

   Arguments:
     opts - Map with:
            :name           - Unique instance name (required)
            :provider-type  - Provider type keyword (required)
            :model          - Model identifier (optional)
            ... provider-specific options

   Returns:
     Instance map suitable for registry:
     {:name           \"my-instance\"
      :provider-type  :claude-subprocess
      :protocol       :jsonl-stream
      :model          \"claude-3-5-haiku-20241022\"
      :capabilities   #{:chat :tools :streaming}
      :transport      {...}  ; Provider-specific state
      :session-id     (atom nil)
      :pending-requests (atom {})
      :created-at     1700000000000}

     Or {:error \"reason\"} on failure."
          :provider-type)

(defmulti send-message
          "Send a message to an AI instance.

   Dispatches on :provider-type in instance.

   Arguments:
     instance - Instance map from registry
     message  - Message content string

   Returns:
     true on success, false on failure"
          (fn [instance _message] (:provider-type instance)))

(defmulti stop-instance
          "Stop an AI instance and cleanup resources.

   Dispatches on :provider-type in instance.

   Arguments:
     instance - Instance map from registry

   Returns:
     true on success, false if already stopped"
          :provider-type)

(defmulti get-capabilities
          "Get capabilities of this provider type.

   Dispatches on provider-type keyword.

   Arguments:
     provider-type - Provider type keyword

   Returns:
     Set of capability keywords, e.g. #{:chat :tools :streaming :vision}"
          identity)

;; =============================================================================
;; Default Implementations (errors for missing providers)
;; =============================================================================

(defmethod create-instance :default
           [{:keys [provider-type] :as _opts}]
           {:error (str "Unknown provider type: " provider-type)
            :available-providers (keys (methods create-instance))})

(defmethod send-message :default
           [instance _message]
           (throw (ex-info "send-message not implemented for provider"
                           {:provider-type (:provider-type instance)})))

(defmethod stop-instance :default
           [instance]
           (throw (ex-info "stop-instance not implemented for provider"
                           {:provider-type (:provider-type instance)})))

(defmethod get-capabilities :default
           [provider-type]
           (throw (ex-info "get-capabilities not implemented for provider"
                           {:provider-type provider-type})))
