(ns ai-orchestrator-tools.tools.ask
    "MCP tool for sending messages to AI instances."
    (:require [ai-orchestrator.core :as orch]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Formatting
;; =============================================================================

(defn- format-success-response
  "Format successful response."
  [instance-name response]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "success"
                      :operation "ai_ask"
                      :instance instance-name
                      :response {:content (:content response)
                                 :duration_ms (:duration_ms response)
                                 :cost_usd (:cost_usd response)}}
                     {:pretty true})}]})

(defn- format-error-response
  "Format error response."
  [error-message details]
  {:content [{:type "text"
              :text (json/generate-string
                     {:status "error"
                      :operation "ai_ask"
                      :error error-message
                      :details details}
                     {:pretty true})}]
   :isError true})

;; =============================================================================
;; Main Handler
;; =============================================================================

(defn handle
  "Send a message to an AI instance and get a response.

   Required parameters:
   - name: Instance identifier (must be running)
   - message: Message to send to the AI"
  [{:keys [name message]}]
  (log/log! {:level :info
             :id    ::handle-ask
             :msg   "Handling ai_ask request"
             :data  {:name name :message-length (count (str message))}})
  (try
    ;; Validate inputs
   (when (or (nil? name) (empty? name))
     (throw (ex-info "Missing required parameter: name"
                     {:parameter "name"})))
   (when (or (nil? message) (empty? message))
     (throw (ex-info "Missing required parameter: message"
                     {:parameter "message"})))

    ;; Check instance exists
   (when-not (orch/get-instance-info name)
     (throw (ex-info (str "Instance not found: " name)
                     {:name name
                      :available-instances (map :name (orch/list-instances))})))

    ;; Send message and await response
   (let [start-time (System/currentTimeMillis)
         response (orch/ask name message)
         duration (- (System/currentTimeMillis) start-time)]

     (if (:error response)
       (do
        (log/log! {:level :error
                   :id    ::ask-failed
                   :msg   "Ask request failed"
                   :data  {:name name :error (:message response)}})
        (format-error-response (:message response) response))
       (do
        (log/log! {:level :info
                   :id    ::ask-success
                   :msg   "Ask request completed"
                   :data  {:name name
                           :duration-ms duration
                           :response-length (count (str (:content response)))}})
        (format-success-response name response))))

   (catch clojure.lang.ExceptionInfo e
          (log/log! {:level :warn
                     :id    ::ask-validation-error
                     :msg   "Validation or lookup error"
                     :error e
                     :data  (ex-data e)})
          (format-error-response (ex-message e) (ex-data e)))

   (catch Exception e
          (log/log! {:level :error
                     :id    ::ask-unexpected-error
                     :msg   "Unexpected error in ask"
                     :error e
                     :data  {:name name}})
          (format-error-response (str "Unexpected error: " (ex-message e)) nil))))

;; =============================================================================
;; Tool Metadata
;; =============================================================================

(def tool-name "Tool name identifier." "ai_ask")

(def metadata
     "MCP tool metadata for ai_ask."
     {:description "Send a message to a running AI instance and get a response. The instance must have been started with ai_start_instance."
      :inputSchema {:type "object"
                    :properties {:name {:type "string"
                                        :description "Instance identifier (must be running)"}
                                 :message {:type "string"
                                           :description "Message to send to the AI instance"}}
                    :required ["name" "message"]}})
