(ns openai-http.core
    "OpenAI HTTP provider implementation for ai-orchestrator (OpenAI + Anthropic compat)."
    (:require [ai-orchestrator.protocol :as proto]
              [openai-http.http-client :as http]
              [taoensso.trove :as log]))

;; =============================================================================
;; Protocol Implementation
;; =============================================================================

(defmethod proto/create-instance :openai-http
           [{:keys [name api-key model base-url max-tokens temperature timeout-ms] :as _opts}]
           (log/log! {:level :info
                      :id    ::creating-instance
                      :msg   "Creating OpenAI HTTP instance"
                      :data  {:name name :model model :base-url base-url}})

           (cond
             (nil? api-key)
             {:error "api-key is required for OpenAI HTTP provider"}

             (nil? model)
             {:error "model is required for OpenAI HTTP provider"}

             :else
             (try
              (let [instance {:name name
                              :provider-type :openai-http
                              :protocol :http-rest
                              :model model
                              :capabilities #{:chat :streaming}
                              :transport {:api-key api-key
                                          :base-url (or base-url "https://api.openai.com/v1")
                                          :max-tokens max-tokens
                                          :temperature temperature
                                          :timeout-ms (or timeout-ms 30000)}
                              :session-id (atom nil)
                              :pending-requests (atom {})
                              :current-request-id (atom nil)
                              :created-at (System/currentTimeMillis)}]
                (log/log! {:level :info
                           :id    ::instance-created
                           :msg   "OpenAI HTTP instance created"
                           :data  {:name name :model model :base-url (:base-url (:transport instance))}})
                instance)
              (catch Exception e
                     (log/log! {:level :error
                                :id    ::instance-creation-failed
                                :msg   "Failed to create OpenAI HTTP instance"
                                :error e
                                :data  {:name name}})
                     {:error (str "Failed to create instance: " (ex-message e))}))))

(defmethod proto/send-message :openai-http
           [instance message]
           (log/log! {:level :info
                      :id    ::sending-message
                      :msg   "Sending message via OpenAI HTTP"
                      :data  {:instance-name (:name instance)
                              :message-length (count message)}})

           (let [{:keys [api-key base-url max-tokens temperature timeout-ms]} (:transport instance)
                 model (:model instance)
                 request-id @(:current-request-id instance)
                 pending-requests (:pending-requests instance)]

             ;; Make HTTP call async in a future
             (future
              (let [start (System/currentTimeMillis)]
                (try
                 (let [result (http/ask-message
                               api-key
                               model
                               message
                               {:base-url base-url
                                :max-tokens max-tokens
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
                                         :duration-ms duration
                                         :request-id request-id}})
                      ;; Deliver error to promise
                      (when-let [p (get @pending-requests request-id)]
                                (deliver p {:error true :message (:message result)})
                                (swap! pending-requests dissoc request-id)))
                     (do
                      (log/log! {:level :info
                                 :id    ::send-message-success
                                 :msg   "Message sent successfully"
                                 :data  {:instance-name (:name instance)
                                         :duration-ms duration
                                         :response-length (count result)
                                         :request-id request-id}})
                      ;; Deliver response to promise
                      (when-let [p (get @pending-requests request-id)]
                                (deliver p {:content result
                                            :duration_ms duration})
                                (swap! pending-requests dissoc request-id)))))

                 (catch Exception e
                        (log/log! {:level :error
                                   :id    ::send-message-exception
                                   :msg   "Exception during message send"
                                   :error e
                                   :data  {:instance-name (:name instance)
                                           :request-id request-id}})
                        ;; Deliver error to promise
                        (when-let [p (get @pending-requests request-id)]
                                  (deliver p {:error true :message (ex-message e)})
                                  (swap! pending-requests dissoc request-id))))))

             ;; Return true immediately (don't wait for HTTP response)
             true))

(defmethod proto/stop-instance :openai-http
           [instance]
           (log/log! {:level :info
                      :id    ::stopping-instance
                      :msg   "Stopping OpenAI HTTP instance"
                      :data  {:instance-name (:name instance)}})
  ;; HTTP provider has no persistent connection to close
           true)

(defmethod proto/get-capabilities :openai-http
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
                      :msg   "OpenAI HTTP provider module initialized"}))
   :start (fn []
            (log/log! {:level :info
                       :id    ::module-start
                       :msg   "OpenAI HTTP provider module started"}))
   :stop (fn []
           (log/log! {:level :info
                      :id    ::module-stop
                      :msg   "OpenAI HTTP provider module stopped"}))})
