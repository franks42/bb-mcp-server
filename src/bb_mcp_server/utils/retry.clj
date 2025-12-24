(ns bb-mcp-server.utils.retry
    "Retry utilities with exponential backoff."
    (:require [taoensso.trove :as log]))

(def ^:private max-delay-ms
     "Maximum delay between retries (60 seconds) to prevent overflow."
     60000)

(def ^:private max-attempts-limit
     "Upper bound for max-attempts to prevent unreasonable retry counts."
     100)

(defn retry-with-backoff
  "Retries a function call with exponential backoff.

   Arguments:
   - f: A no-argument function to retry

   Options (map):
   - :max-attempts    - Maximum total attempts including initial (default: 3, max: 100)
   - :initial-delay-ms - Initial delay in milliseconds (default: 100, max: 60000)

   The delay doubles after each failed attempt (exponential backoff),
   capped at 60 seconds to prevent overflow.

   With max-attempts of 3, the function will try up to 3 times total.
   Setting max-attempts to 1 means try once with no retries.

   Only catches Exception (not Throwable), so errors like OutOfMemoryError
   will not be retried. InterruptedException during sleep is caught and
   re-throws the original exception (interruption stops retrying).

   Returns the successful result of f, or throws the last exception
   after exhausting all attempts.

   Example:
     (retry-with-backoff #(http-request url) {:max-attempts 5 :initial-delay-ms 200})"
  ([f] (retry-with-backoff f {}))
  ([f {:keys [max-attempts initial-delay-ms]
       :or {max-attempts 3
            initial-delay-ms 100}}]
   (when-not (pos? max-attempts)
     (throw (IllegalArgumentException. "max-attempts must be positive")))
   (when (> max-attempts max-attempts-limit)
     (throw (IllegalArgumentException.
             (str "max-attempts must be <= " max-attempts-limit))))
   (when-not (pos? initial-delay-ms)
     (throw (IllegalArgumentException. "initial-delay-ms must be positive")))
   (when (> initial-delay-ms max-delay-ms)
     (throw (IllegalArgumentException.
             (str "initial-delay-ms must be <= " max-delay-ms))))
   (loop [attempt 1
          delay-ms initial-delay-ms]
         (let [result (try
                       {:success true :value (f)}
                       (catch Exception e
                              {:success false :exception e}))]
           (if (:success result)
             (:value result)
             (let [ex (:exception result)]
               (if (>= attempt max-attempts)
                 (do
                  (log/log! {:level :error
                             :id ::retries-exhausted
                             :msg "All retry attempts exhausted"
                             :data {:attempt attempt
                                    :max-attempts max-attempts
                                    :error (ex-message ex)}})
                  (throw ex))
                 (let [next-delay (min (* delay-ms 2) max-delay-ms)]
                   (log/log! {:level :warn
                              :id ::retry-attempt
                              :msg "Retry attempt failed, backing off"
                              :data {:attempt attempt
                                     :max-attempts max-attempts
                                     :delay-ms delay-ms
                                     :next-delay-ms next-delay
                                     :error (ex-message ex)}})
                   (try
                    (Thread/sleep delay-ms)
                    (catch InterruptedException _
                           (log/log! {:level :warn
                                      :id ::retry-interrupted
                                      :msg "Retry interrupted during sleep"
                                      :data {:attempt attempt
                                             :error (ex-message ex)}})
                           (.interrupt (Thread/currentThread))
                           (throw ex)))
                   (recur (inc attempt) next-delay)))))))))
