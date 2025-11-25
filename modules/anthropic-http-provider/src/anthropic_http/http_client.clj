(ns anthropic-http.http-client
    "HTTP client for Anthropic Messages API."
    (:require [cheshire.core :as json]
              [clojure.java.io :as io]
              [clojure.string]
              [taoensso.trove :as log])
    (:import [java.net HttpURLConnection URL]))

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

(defn- create-connection
  "Create HTTP connection with headers."
  [url api-key timeout-ms]
  (let [conn ^HttpURLConnection (.openConnection (URL. url))]
    (doto conn
          (.setRequestMethod "POST")
          (.setDoOutput true)
          (.setConnectTimeout timeout-ms)
          (.setReadTimeout timeout-ms))
    (doseq [[k v] (build-headers api-key)]
           (.setRequestProperty conn k v))
    conn))

(defn- write-request-body
  "Write JSON request body to connection."
  [^HttpURLConnection conn body]
  (with-open [os (.getOutputStream conn)]
             (io/copy (json/generate-string body) os)))

(defn- read-response
  "Read JSON response from connection."
  [^HttpURLConnection conn]
  (let [status (.getResponseCode conn)]
    (if (>= status 400)
      (let [error-stream (.getErrorStream conn)
            error-body (when error-stream
                         (slurp error-stream))]
        {:error true
         :status status
         :message (or error-body "Unknown error")
         :body (when error-body
                 (try (json/parse-string error-body true)
                      (catch Exception _e error-body)))})
      (let [response-body (slurp (.getInputStream conn))]
        {:status status
         :body (json/parse-string response-body true)}))))

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
                     temperature (assoc :temperature temperature))
        conn (create-connection url api-key timeout-ms)]

    (try
     (write-request-body conn body)
     (let [response (read-response conn)
           duration (- (System/currentTimeMillis) start)]

       (if (:error response)
         (do
          (log/log! {:level :error
                     :id    ::create-message-failed
                     :msg   "Anthropic API request failed"
                     :data  {:status (:status response)
                             :message (:message response)
                             :duration-ms duration}})
          response)
         (do
          (log/log! {:level :info
                     :id    ::create-message-success
                     :msg   "Anthropic API request succeeded"
                     :data  {:status (:status response)
                             :duration-ms duration}})
          response)))

     (catch Exception e
            (log/log! {:level :error
                       :id    ::create-message-exception
                       :msg   "Exception during Anthropic API request"
                       :error e})
            {:error true
             :message (ex-message e)
             :exception e})

     (finally
      (.disconnect conn)))))

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
