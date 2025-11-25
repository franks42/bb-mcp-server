(ns anthropic-http.http-client
    "HTTP client for Anthropic Messages API."
    (:require [babashka.http-client :as http]
              [cheshire.core :as json]
              [clojure.string]
              [taoensso.trove :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private api-base-url "https://api.anthropic.com/v1")
(def ^:private api-version "2023-06-01")
(def ^:private default-timeout-ms 30000)

;; =============================================================================
;; HTTP Request
;; =============================================================================

(defn- build-headers
  "Build HTTP headers for Anthropic API."
  [api-key]
  {"Content-Type" "application/json"
   "x-api-key" api-key
   "anthropic-version" api-version})

;; =============================================================================
;; API Calls
;; =============================================================================

(defn create-message
  "Send a message to Anthropic Messages API.

   Arguments:
     opts - Map with:
            :api-key      - Anthropic API key (required)
            :model        - Model identifier (required)
            :messages     - Message array (required)
            :max-tokens   - Max output tokens (required)
            :system       - System prompt (optional)
            :temperature  - Temperature 0.0-1.0 (optional)
            :stream       - Enable streaming (optional, default false)
            :timeout-ms   - Request timeout (optional)

   Returns:
     {:status 200 :body {...}} on success
     {:error true :status 4xx/5xx :message \"...\"} on error"
  [{:keys [api-key model messages max-tokens system temperature stream timeout-ms]
    :or {stream false
         timeout-ms default-timeout-ms}}]
  (log/log! {:level :info
             :id    ::create-message
             :msg   "Sending message to Anthropic API"
             :data  {:model model
                     :stream stream
                     :message-count (count messages)}})

  (let [start (System/currentTimeMillis)
        url (str api-base-url "/messages")
        body (cond-> {:model model
                      :messages messages
                      :max_tokens max-tokens
                      :stream stream}
                     system (assoc :system system)
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
                     :id    ::create-message-failed
                     :msg   "Anthropic API request failed"
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
                     :id    ::create-message-success
                     :msg   "Anthropic API request succeeded"
                     :data  {:status status
                             :duration-ms duration}})
          {:status status
           :body (json/parse-string (:body response) true)})))

     (catch Exception e
            (log/log! {:level :error
                       :id    ::create-message-exception
                       :msg   "Exception during Anthropic API request"
                       :error e})
            {:error true
             :message (ex-message e)
             :exception e}))))

(defn- extract-text-content
  "Extract text from message content blocks."
  [content-blocks]
  (when (vector? content-blocks)
    (->> content-blocks
         (filter #(= "text" (:type %)))
         (map :text)
         (clojure.string/join "\n"))))

(defn ask-message
  "Simplified ask API - send message and get text response.

   Arguments:
     api-key      - Anthropic API key
     model        - Model identifier
     message      - Message string
     opts         - Optional map with :system, :temperature, :max-tokens, :timeout-ms

   Returns:
     String response text on success
     {:error true ...} on error"
  [api-key model message & [opts]]
  (let [max-tokens (or (:max-tokens opts) 1024)
        result (create-message
                (merge {:api-key api-key
                        :model model
                        :messages [{:role "user" :content message}]
                        :max-tokens max-tokens}
                       opts))]

    (if (:error result)
      result
      (or (extract-text-content (get-in result [:body :content]))
          {:error true :message "No text content in response"}))))
