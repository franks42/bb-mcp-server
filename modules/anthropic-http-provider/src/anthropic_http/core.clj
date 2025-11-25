(ns anthropic-http.core
    "Anthropic HTTP provider implementation for ai-orchestrator."
    (:require [ai-orchestrator.protocol :as proto]
              [anthropic-http.http-client :as http]
              [taoensso.trove :as log]))

;; =============================================================================
;; Protocol Implementation
;; =============================================================================

(defmethod proto/create-instance :anthropic-http
           [{:keys [name api-key model max-tokens temperature timeout-ms] :as _opts}]
           (log/log! {:level :info
                      :id    ::creating-instance
                      :msg   "Creating Anthropic HTTP instance"
                      :data  {:name name :model model}})

           (cond
             (nil? api-key)
             {:error "api-key is required for Anthropic HTTP provider"}

             (nil? model)
             {:error "model is required for Anthropic HTTP provider"}

             :else
             (try
              (let [instance {:name name
                              :provider-type :anthropic-http
                              :protocol :http-rest
                              :model model
                              :capabilities #{:chat :streaming}
                              :transport {:api-key api-key
                                          :max-tokens (or max-tokens 1024)
                                          :temperature temperature
                                          :timeout-ms (or timeout-ms 30000)}
                              :session-id (atom nil)
                              :pending-requests (atom {})
                              :created-at (System/currentTimeMillis)}]
                (log/log! {:level :info
                           :id    ::instance-created
                           :msg   "Anthropic HTTP instance created"
                           :data  {:name name :model model}})
                instance)
              (catch Exception e
                     (log/log! {:level :error
                                :id    ::instance-creation-failed
                                :msg   "Failed to create Anthropic HTTP instance"
                                :error e
                                :data  {:name name}})
                     {:error (str "Failed to create instance: " (ex-message e))}))))

(defmethod proto/send-message :anthropic-http
           [instance message]
           (log/log! {:level :info
                      :id    ::sending-message
                      :msg   "Sending message via Anthropic HTTP"
                      :data  {:instance-name (:name instance)
                              :message-length (count message)}})

           (let [{:keys [api-key max-tokens temperature timeout-ms]} (:transport instance)
                 model (:model instance)
                 start (System/currentTimeMillis)]

             (try
              (let [result (http/ask-message
                            api-key
                            model
                            message
                            {:max-tokens max-tokens
                             :temperature temperature
                             :timeout-ms timeout-ms})
                    duration (- (System/currentTimeMillis) start)]

                (if (:error result)
                  (do
                   (log/log! {:level :error
                              :id    ::send-message-failed
                              :msg   "Message send failed"
                              :data  {:instance-name (:name instance)
                                      :error-message (:message result)
                                      :duration-ms duration}})
                   false)
                  (do
                   (log/log! {:level :info
                              :id    ::send-message-success
                              :msg   "Message sent successfully"
                              :data  {:instance-name (:name instance)
                                      :duration-ms duration
                                      :response-length (count result)}})
            ;; Return the response text for now
            ;; In real implementation, would go through router/promise system
                   result)))

              (catch Exception e
                     (log/log! {:level :error
                                :id    ::send-message-exception
                                :msg   "Exception during message send"
                                :error e
                                :data  {:instance-name (:name instance)}})
                     false))))

(defmethod proto/stop-instance :anthropic-http
           [instance]
           (log/log! {:level :info
                      :id    ::stopping-instance
                      :msg   "Stopping Anthropic HTTP instance"
                      :data  {:instance-name (:name instance)}})
  ;; HTTP provider has no persistent connection to close
           true)

(defmethod proto/get-capabilities :anthropic-http
           [_provider-type]
           #{:chat :streaming})

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn module
  "Module lifecycle map for bb-mcp-server."
  []
  {:init (fn []
           (log/log! {:level :info
                      :id    ::module-init
                      :msg   "Anthropic HTTP provider module initialized"}))
   :start (fn []
            (log/log! {:level :info
                       :id    ::module-start
                       :msg   "Anthropic HTTP provider module started"}))
   :stop (fn []
           (log/log! {:level :info
                      :id    ::module-stop
                      :msg   "Anthropic HTTP provider module stopped"}))})
