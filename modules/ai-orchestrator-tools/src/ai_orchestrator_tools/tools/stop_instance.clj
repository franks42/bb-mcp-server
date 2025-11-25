(ns ai-orchestrator-tools.tools.stop-instance
    "MCP tool for stopping AI instances."
    (:require [ai-orchestrator.core :as orch]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful stop response."
  [instance-name]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "ai_stop_instance"
                      :instance instance-name
                      :message "Instance stopped successfully"}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "error"
                      :operation "ai_stop_instance"
                      :error error-message
                      :details details}
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Stop a running AI instance.

   Required parameters:
   - name: Instance identifier to stop"
  [{:keys [name]}]
  (log/log! {:level :info
             :id    ::handle-stop-instance
             :msg   "Handling ai_stop_instance request"
             :data  {:name name}})
  (try
    ;; Validate inputs
   (when (or (nil? name) (empty? name))
     (throw (ex-info "Missing required parameter: name"
                     {:parameter "name"})))

    ;; Stop instance
   (let [stopped (orch/stop-instance! name)]
     (if stopped
       (do
        (log/log! {:level :info
                   :id    ::stop-instance-success
                   :msg   "Instance stopped successfully"
                   :data  {:name name}})
        (format-success-response name))
       (do
        (log/log! {:level :warn
                   :id    ::stop-instance-not-found
                   :msg   "Instance not found for stop"
                   :data  {:name name}})
        (format-error-response
         (str "Instance not found: " name)
         {:name name
          :available-instances (map :name (orch/list-instances))}))))

   (catch clojure.lang.ExceptionInfo e
          (log/log! {:level :warn
                     :id    ::stop-instance-validation-error
                     :msg   "Validation error"
                     :error e
                     :data  (ex-data e)})
          (format-error-response (ex-message e) (ex-data e)))

   (catch Exception e
          (log/log! {:level :error
                     :id    ::stop-instance-unexpected-error
                     :msg   "Unexpected error stopping instance"
                     :error e
                     :data  {:name name}})
          (format-error-response (str "Unexpected error: " (ex-message e)) nil))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "ai_stop_instance")

(def metadata
     "MCP tool metadata for ai_stop_instance."
     {:description "Stop a running AI instance. Releases resources and removes from registry."
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Instance identifier to stop"}}
                    :required ["name"]}})
