(ns ai-orchestrator-tools.tools.start-instance
    "MCP tool for starting AI instances."
    (:require [ai-orchestrator.core :as orch]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful instance creation response."
  [instance]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "ai_start_instance"
                      :instance {:name (:name instance)
                                 :provider-type (name (:provider-type instance))
                                 :model (:model instance)
                                 :created-at (str (java.time.Instant/now))}}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "error"
                      :operation "ai_start_instance"
                      :error error-message
                      :details details}
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Input Validation
;; =============================================================================

(def ^:private valid-provider-types
     "Set of valid provider types."
     #{:anthropic-http :openai-http :claude-subprocess})

(defn- validate-provider-type
  "Validate provider-type parameter."
  [provider-type-str]
  (when-not provider-type-str
    (throw (ex-info "Missing required parameter: provider_type"
                    {:parameter "provider_type"})))
  (let [pt (keyword provider-type-str)]
    (when-not (contains? valid-provider-types pt)
      (throw (ex-info (str "Invalid provider_type: " provider-type-str
                           ". Valid options: " (pr-str (map name valid-provider-types)))
                      {:provider-type provider-type-str
                       :valid-options (map name valid-provider-types)})))
    pt))

(defn- validate-name
  "Validate instance name."
  [name]
  (when (or (nil? name) (empty? name))
    (throw (ex-info "Missing required parameter: name"
                    {:parameter "name"})))
  (when-not (re-matches #"^[a-zA-Z][a-zA-Z0-9_-]{0,63}$" name)
    (throw (ex-info "Invalid instance name. Must start with letter, contain only alphanumeric, underscore, hyphen, max 64 chars."
                    {:name name})))
  name)

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Start a new AI instance.

   Required parameters:
   - name: Unique instance identifier
   - provider_type: One of 'anthropic-http', 'openai-http', 'claude-subprocess'

   Optional parameters depend on provider:
   - model: Model identifier (e.g., 'claude-sonnet-4-5-20250929')
   - api_key: API key (for HTTP providers)
   - base_url: Base URL override (for openai-http)
   - max_tokens: Max tokens for response
   - cmd: Command path (for claude-subprocess, defaults to 'claude')"
  [{:keys [name provider_type model api_key base_url max_tokens cmd]}]
  (log/log! {:level :info
             :id    ::handle-start-instance
             :msg   "Handling ai_start_instance request"
             :data  {:name name :provider_type provider_type}})
  (try
    ;; Validate inputs
   (let [validated-name (validate-name name)
         validated-provider (validate-provider-type provider_type)
          ;; Build options map based on provider
         opts (cond-> {:provider-type validated-provider}
                      model (assoc :model model)
                      api_key (assoc :api-key api_key)
                      base_url (assoc :base-url base_url)
                      max_tokens (assoc :max-tokens max_tokens)
                 ;; For subprocess, set cmd with default
                      (= validated-provider :claude-subprocess)
                      (assoc :cmd (or cmd "claude")))
          ;; Start instance
         instance (orch/start-instance! validated-name opts)]
     (if (:error instance)
       (do
        (log/log! {:level :error
                   :id    ::start-instance-failed
                   :msg   "Failed to start instance"
                   :data  {:name validated-name :error (:error instance)}})
        (format-error-response "Failed to start instance" (:error instance)))
       (do
        (log/log! {:level :info
                   :id    ::start-instance-success
                   :msg   "Instance started successfully"
                   :data  {:name validated-name
                           :provider-type validated-provider}})
        (format-success-response instance))))

   (catch clojure.lang.ExceptionInfo e
          (log/log! {:level :warn
                     :id    ::start-instance-validation-error
                     :msg   "Validation error"
                     :error e
                     :data  (ex-data e)})
          (format-error-response (ex-message e) (ex-data e)))

   (catch Exception e
          (log/log! {:level :error
                     :id    ::start-instance-unexpected-error
                     :msg   "Unexpected error starting instance"
                     :error e
                     :data  {:name name :provider_type provider_type}})
          (format-error-response (str "Unexpected error: " (ex-message e)) nil))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "ai_start_instance")

(def metadata
     "MCP tool metadata for ai_start_instance."
     {:description "Start a new AI instance using the multi-provider orchestrator. Supports Claude subprocess, Anthropic HTTP API, and OpenAI HTTP API (including Anthropic compatibility mode)."
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Unique instance identifier. Must start with letter, alphanumeric/underscore/hyphen only, max 64 chars."}
                                 :provider_type {:type "string"
                                                 :description "Provider type: 'anthropic-http' (native Anthropic API), 'openai-http' (OpenAI API or Anthropic compatibility), 'claude-subprocess' (Claude CLI subprocess)"
                                                 :enum ["anthropic-http" "openai-http" "claude-subprocess"]}
                                 :model {:type "string"
                                         :description "Model identifier (e.g., 'claude-sonnet-4-5-20250929', 'gpt-4-turbo')"}
                                 :api_key {:type "string"
                                           :description "API key for HTTP providers. If not provided, uses CLAUDE_API_KEY or OPENAI_API_KEY env var."}
                                 :base_url {:type "string"
                                            :description "Base URL override for openai-http provider (e.g., 'https://api.anthropic.com/v1' for Anthropic compatibility)"}
                                 :max_tokens {:type "integer"
                                              :description "Maximum tokens for response (default: 1024)"
                                              :minimum 1
                                              :maximum 200000}
                                 :cmd {:type "string"
                                       :description "Command path for claude-subprocess (default: 'claude')"}}
                    :required ["name" "provider_type"]}})
