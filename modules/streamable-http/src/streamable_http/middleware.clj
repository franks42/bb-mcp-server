(ns streamable-http.middleware
    "Ring middleware for Streamable HTTP transport.

   All middleware follows standard Ring pattern and is bb-compatible.
   Can be composed with any Ring-compatible middleware."
    (:require [clojure.string :as str]
              [streamable-http.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; CORS Middleware
;; =============================================================================

(defn- origin-allowed?
  "Check if origin is in allowed set (empty set = allow all)."
  [origin allowed-origins]
  (or (empty? allowed-origins)
      (contains? allowed-origins origin)
      (contains? allowed-origins "*")))

(defn- cors-headers
  "Build CORS headers map."
  [origin allowed-origins allowed-methods allowed-headers expose-headers max-age]
  (let [origin-header (if (or (empty? allowed-origins)
                              (contains? allowed-origins "*"))
                        "*"
                        origin)]
    {"Access-Control-Allow-Origin" origin-header
     "Access-Control-Allow-Methods" (str/join ", " (map #(str/upper-case (name %)) allowed-methods))
     "Access-Control-Allow-Headers" (str/join ", " allowed-headers)
     "Access-Control-Expose-Headers" (str/join ", " expose-headers)
     "Access-Control-Max-Age" (str max-age)}))

(defn wrap-cors
  "Add CORS headers to responses.

   Options:
     :allowed-origins  - Set of allowed origins (empty = allow all)
     :allowed-methods  - HTTP methods (default: #{:get :post :delete :options})
     :allowed-headers  - Request headers (default: content-type, authorization, session header)
     :expose-headers   - Headers to expose to client (default: session header)
     :max-age          - Preflight cache time in seconds (default: 86400)
     :session-header   - Session header name (default: \"Mcp-Session-Id\")

   Example:
     (wrap-cors handler {:allowed-origins #{\"https://example.com\"}})"
  [handler & [{:keys [allowed-origins allowed-methods allowed-headers expose-headers max-age session-header]
               :or {allowed-origins #{}
                    allowed-methods #{:get :post :delete :options}
                    allowed-headers #{"content-type" "authorization" "accept"}
                    expose-headers #{}
                    max-age 86400
                    session-header "Mcp-Session-Id"}}]]
  (let [all-allowed-headers (conj allowed-headers (str/lower-case session-header))
        all-expose-headers (conj expose-headers session-header)]
    (fn [request]
      (let [origin (get-in request [:headers "origin"])]
        (if (= (:request-method request) :options)
          ;; Preflight response
          {:status 204
           :headers (cors-headers origin allowed-origins allowed-methods
                                  all-allowed-headers all-expose-headers max-age)
           :body ""}
          ;; Regular response with CORS headers
          (when-let [response (handler request)]
                    (if (origin-allowed? origin allowed-origins)
                      (update response :headers merge
                              (cors-headers origin allowed-origins allowed-methods
                                            all-allowed-headers all-expose-headers max-age))
                      response)))))))

;; =============================================================================
;; Rate Limiting Middleware
;; =============================================================================

;; Token bucket state: {key -> {:tokens N :last-update timestamp}}
(defonce ^:private rate-limit-buckets (atom {}))

(defn- get-bucket
  "Get or create bucket for key."
  [buckets key max-tokens]
  (get buckets key {:tokens max-tokens
                    :last-update (System/currentTimeMillis)}))

(defn- refill-tokens
  "Refill tokens based on elapsed time."
  [{:keys [tokens last-update]} max-tokens refill-rate]
  (let [now (System/currentTimeMillis)
        elapsed-ms (- now last-update)
        elapsed-sec (/ elapsed-ms 1000.0)
        new-tokens (+ tokens (* elapsed-sec refill-rate))]
    {:tokens (min max-tokens new-tokens)
     :last-update now}))

(defn- try-consume-token
  "Try to consume a token, returns [success? updated-bucket]."
  [bucket]
  (if (>= (:tokens bucket) 1)
    [true (update bucket :tokens dec)]
    [false bucket]))

(defn wrap-rate-limit
  "Limit requests per client using token bucket algorithm.

   Options:
     :requests-per-minute - Max requests per minute (default: 60)
     :burst               - Allow burst up to N requests (default: same as rpm)
     :key-fn              - Function to extract rate limit key (default: :remote-addr)

   Example:
     (wrap-rate-limit handler {:requests-per-minute 60})"
  [handler {:keys [requests-per-minute burst key-fn]
            :or {requests-per-minute 60
                 key-fn :remote-addr}}]
  (let [max-tokens (or burst requests-per-minute)
        refill-rate (/ requests-per-minute 60.0)] ; tokens per second
    (fn [request]
      (let [key (or (key-fn request) "anonymous")
            ;; Atomically check and consume token
            allowed? (atom false)
            _ (swap! rate-limit-buckets
                     (fn [buckets]
                       (let [bucket (get-bucket buckets key max-tokens)
                             refilled (refill-tokens bucket max-tokens refill-rate)
                             [can-consume? updated] (try-consume-token refilled)]
                         (reset! allowed? can-consume?)
                         (if can-consume?
                           (assoc buckets key updated)
                           (assoc buckets key refilled)))))]
        (if @allowed?
          (handler request)
          (do
           (log/log! {:level :warn
                      :id    ::rate-limited
                      :msg   "Rate limit exceeded"
                      :data  {:key key}})
           {:status 429
            :headers {"Content-Type" "application/json"
                      "Retry-After" "60"}
            :body (util/generate-json {:error "Rate limit exceeded"})}))))))

(defn reset-rate-limits!
  "Reset all rate limit buckets. Useful for testing."
  []
  (reset! rate-limit-buckets {}))

;; =============================================================================
;; Basic Auth Middleware
;; =============================================================================

(defn- parse-basic-auth
  "Parse Basic auth header, returns [username password] or nil."
  [auth-header]
  (when (and auth-header (str/starts-with? auth-header "Basic "))
    (try
     (let [encoded (subs auth-header 6)
           decoded (String. (.decode (java.util.Base64/getDecoder) encoded) "UTF-8")
           [user pass] (str/split decoded #":" 2)]
       (when (and user pass)
         [user pass]))
     (catch Exception _ nil))))

(defn- valid-credentials?
  "Check if credentials are valid."
  [auth-header credentials]
  (when-let [[user pass] (parse-basic-auth auth-header)]
            (= pass (get credentials user))))

(defn wrap-basic-auth
  "HTTP Basic Authentication.

   Options:
     :credentials - Map of username -> password
     :realm       - Auth realm (default: \"Streamable HTTP\")
     :exclude     - Set of paths to exclude from auth (default: #{})

   Example:
     (wrap-basic-auth handler {:credentials {\"admin\" \"secret\"}})"
  [handler {:keys [credentials realm exclude]
            :or {realm "Streamable HTTP"
                 exclude #{}}}]
  (fn [request]
    (let [path (:uri request)]
      (if (contains? exclude path)
        (handler request)
        (let [auth-header (get-in request [:headers "authorization"])]
          (if (valid-credentials? auth-header credentials)
            (handler request)
            (do
             (log/log! {:level :warn
                        :id    ::auth-failed
                        :msg   "Authentication failed"
                        :data  {:path path}})
             {:status 401
              :headers {"WWW-Authenticate" (str "Basic realm=\"" realm "\"")
                        "Content-Type" "application/json"}
              :body (util/generate-json
                     {:error (if auth-header
                               "Invalid credentials"
                               "Authentication required")})})))))))

;; =============================================================================
;; Request Logging Middleware
;; =============================================================================

(defn wrap-request-logging
  "Log incoming requests and outgoing responses.

   Options:
     :log-fn      - Logging function (default: taoensso.trove/log!)
     :level       - Log level (default: :info)
     :include-body - Include request/response body (default: false)

   Example:
     (wrap-request-logging handler {:level :debug})"
  [handler & [{:keys [level include-body]
               :or {level :info
                    include-body false}}]]
  (fn [request]
    (let [start (System/currentTimeMillis)
          method (:request-method request)
          uri (:uri request)]
      (log/log! {:level level
                 :id    ::request-start
                 :msg   "Request started"
                 :data  (cond-> {:method method :uri uri}
                                include-body (assoc :body (some-> request :body slurp)))})
      (let [response (handler request)
            elapsed (- (System/currentTimeMillis) start)]
        (log/log! {:level level
                   :id    ::request-complete
                   :msg   "Request complete"
                   :data  (cond-> {:method method
                                   :uri uri
                                   :status (:status response)
                                   :elapsed-ms elapsed}
                                  include-body (assoc :body (:body response)))})
        response))))

;; =============================================================================
;; Origin Validation Middleware (DNS Rebinding Protection)
;; =============================================================================

(defn wrap-origin-validation
  "Validate Origin/Host headers to prevent DNS rebinding attacks.

   Options:
     :allowed-hosts - Set of allowed Host header values (empty = allow all)

   Example:
     (wrap-origin-validation handler {:allowed-hosts #{\"localhost:3000\"}})"
  [handler {:keys [allowed-hosts]
            :or {allowed-hosts #{}}}]
  (fn [request]
    (let [host (get-in request [:headers "host"])]
      (if (or (empty? allowed-hosts) (contains? allowed-hosts host))
        (handler request)
        (do
         (log/log! {:level :warn
                    :id    ::invalid-host
                    :msg   "Request from invalid host"
                    :data  {:host host}})
         {:status 403
          :headers {"Content-Type" "application/json"}
          :body (util/generate-json {:error "Invalid host"})})))))

;; =============================================================================
;; Middleware Composition Helper
;; =============================================================================

(defn apply-middleware
  "Apply a sequence of middleware to a handler.
   Middleware is applied in order (first in list = outermost wrapper).

   Example:
     (apply-middleware handler [wrap-logging wrap-cors wrap-auth])"
  [handler middleware-fns]
  (reduce (fn [h mw] (mw h)) handler (reverse middleware-fns)))
