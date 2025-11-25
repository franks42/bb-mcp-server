(ns ai-orchestrator-tools.tools.list-instances
    "MCP tool for listing AI instances."
    (:require [ai-orchestrator.core :as orch]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-instance-info
  "Format instance info for output."
  [info]
  {:name (:name info)
   :provider_type (when (:provider-type info) (name (:provider-type info)))
   :model (:model info)
   :created_at (str (:created-at info))
   :status "running"})

(defn- format-success-response
  "Format successful list response."
  [instances]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "ai_list_instances"
                      :count (count instances)
                      :instances (map format-instance-info instances)}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "error"
                      :operation "ai_list_instances"
                      :error error-message
                      :details details}
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "List all running AI instances.

   Optional parameters:
   - provider_type: Filter by provider type (e.g., 'anthropic-http')"
  [{:keys [provider_type]}]
  (log/log! {:level :debug
             :id    ::handle-list-instances
             :msg   "Handling ai_list_instances request"
             :data  {:provider_type provider_type}})
  (try
   (let [instances (if provider_type
                      ;; Filter by provider
                     (orch/find-instances-by-provider (keyword provider_type))
                      ;; All instances
                     (orch/list-instances))]
     (log/log! {:level :info
                :id    ::list-instances-success
                :msg   "Listed instances"
                :data  {:count (count instances)
                        :filter provider_type}})
     (format-success-response instances))

   (catch Exception e
          (log/log! {:level :error
                     :id    ::list-instances-unexpected-error
                     :msg   "Unexpected error listing instances"
                     :error e})
          (format-error-response (str "Unexpected error: " (ex-message e)) nil))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "ai_list_instances")

(def metadata
     "MCP tool metadata for ai_list_instances."
     {:description "List all running AI instances. Optionally filter by provider type."
      :inputSchema {:type "object"
                    :properties {:provider_type {:type "string"
                                                 :description "Filter by provider type: 'anthropic-http', 'openai-http', 'claude-subprocess'"
                                                 :enum ["anthropic-http" "openai-http" "claude-subprocess"]}}}})
