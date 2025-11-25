(ns openai-http.http-client
    "HTTP client for OpenAI Chat Completions API (and Anthropic compatibility)."
    (:require [babashka.http-client :as http]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-base-url "https://api.openai.com/v1")
(def ^:private default-timeout-ms 30000)

;; =============================================================================
;; HTTP Request
;; =============================================================================

(defn- build-headers
  "Build HTTP headers for OpenAI-compatible API."
  [api-key]
  {"Content-Type" "application/json"
   "Authorization" (str "Bearer " api-key)})

;; =============================================================================
;; API Calls
;; =============================================================================

(defn create-chat-completion
  "Send a message to OpenAI-compatible Chat Completions API.

   Arguments:
     opts - Map with:
            :api-key      - API key (required)
            :base-url     - Base URL (optional, defaults to OpenAI)
            :model        - Model identifier (required)
            :messages     - Message array (required)
            :max-tokens   - Max output tokens (optional)
            :temperature  - Temperature 0.0-2.0 (optional)
            :stream       - Enable streaming (optional, default false)
            :timeout-ms   - Request timeout (optional)

   Returns:
     {:status 200 :body {...}} on success
     {:error true :status 4xx/5xx :message \"...\"} on error"
  [{:keys [api-key base-url model messages max-tokens temperature stream timeout-ms]
    :or {base-url default-base-url
         stream false
         timeout-ms default-timeout-ms}}]
  (log/log! {:level :info
             :id    ::create-chat-completion
             :msg   "Sending message to OpenAI-compatible API"
             :data  {:base-url base-url
                     :model model
                     :stream stream
                     :message-count (count messages)}})

  (let [start (System/currentTimeMillis)
        url (str base-url "/chat/completions")
        body (cond-> {:model model
                      :messages messages
                      :stream stream}
                     max-tokens (assoc :max_tokens max-tokens)
                     temperature (assoc :temperature temperature))]

    (try
     (let [response (http/post url
                               {:headers (build-headers api-key)
                                :body (json/generate-string body)
                                :throw false
                                :connect-timeout timeout-ms
                                :read-timeout timeout-ms})
           duration (- (System/currentTimeMillis) start)
           status (:status response)]

       (if (>= status 400)
         (do
          (log/log! {:level :error
                     :id    ::create-chat-completion-failed
                     :msg   "OpenAI-compatible API request failed"
                     :data  {:status status
                             :message (:body response)
                             :duration-ms duration}})
          {:error true
           :status status
           :message (:body response)
           :body (try (json/parse-string (:body response) true)
                      (catch Exception _e (:body response)))})
         (do
          (log/log! {:level :info
                     :id    ::create-chat-completion-success
                     :msg   "OpenAI-compatible API request succeeded"
                     :data  {:status status
                             :duration-ms duration}})
          {:status status
           :body (json/parse-string (:body response) true)})))

     (catch Exception e
            (log/log! {:level :error
                       :id    ::create-chat-completion-exception
                       :msg   "Exception during OpenAI-compatible API request"
                       :error e})
            {:error true
             :message (ex-message e)
             :exception e}))))

(defn- extract-message-content
  "Extract text from OpenAI response."
  [response-body]
  (when-let [choices (:choices response-body)]
            (when-let [first-choice (first choices)]
                      (get-in first-choice [:message :content]))))

(defn ask-message
  "Simplified ask API - send message and get text response.

   Arguments:
     api-key      - API key
     model        - Model identifier
     message      - Message string
     opts         - Optional map with :base-url, :system, :temperature, :max-tokens, :timeout-ms

   Returns:
     String response text on success
     {:error true ...} on error"
  [api-key model message & [opts]]
  (let [messages (if-let [system (:system opts)]
                         [{:role "system" :content system}
                          {:role "user" :content message}]
                         [{:role "user" :content message}])
        result (create-chat-completion
                (merge {:api-key api-key
                        :model model
                        :messages messages}
                       opts))]

    (if (:error result)
      result
      (or (extract-message-content (:body result))
          {:error true :message "No content in response"}))))
