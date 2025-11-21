(ns bb-mcp-server.telemetry
    "Trove + Timbre bootstrap helpers.

  This namespace owns the one-time wiring between Trove (facade) and Timbre (backend)
  so the rest of the codebase can require Trove directly without thinking about
  initialization order, REPL reloads, or stderr routing."
    (:require [clojure.string :as str]
              [taoensso.trove :as log]
              [taoensso.trove.timbre :as backend]
              [taoensso.timbre :as timbre]))

(defonce ^:private initialized? (atom false))

(defn- parse-level
  "Normalize level inputs coming from opts or LOG_LEVEL env var."
  [value]
  (cond
    (keyword? value) value
    (string? value) (-> value str/trim str/lower-case keyword)
    :else nil))

(defn- effective-level [level]
  (or (parse-level level)
      (parse-level (System/getenv "LOG_LEVEL"))
      :info))

(defn- stderr-appender
  "Return a Timbre println appender that forces output to stderr.
  Keeping stdout clean is critical for MCP stdio transports that expect JSON only."
  []
  {:enabled? true
   :fn (fn [data]
         (let [{:keys [output_]} data]
           (binding [*out* *err*]
                    (println (force output_))
                    (flush))))})

(defn- runtime-info []
  {:bb-version (System/getProperty "babashka.version")
   :java-version (System/getProperty "java.version")
   :os-name (System/getProperty "os.name")
   :user-dir (System/getProperty "user.dir")})

(defn- configure-timbre!
  [{:keys [level stderr? merge-config configure!]
    :as opts
    :or {stderr? true}}]
  (let [resolved-level (effective-level level)
        appender (or (:stderr-appender opts) (stderr-appender))]
    (timbre/merge-config!
     (cond-> {:min-level resolved-level}
             stderr? (assoc-in [:appenders :println] appender)))
    (when merge-config
      (timbre/merge-config! merge-config))
    (when configure!
      (configure!))
    resolved-level))

(defn- start! [opts]
  (let [resolved-level (configure-timbre! opts)]
    (log/set-log-fn! (backend/get-log-fn))
    (log/log! {:level :info
               :id :bb-mcp-server.telemetry/initialized
               :msg "Telemetry initialized"
               :data (merge (runtime-info)
                            {:level resolved-level
                             :stderr? (get opts :stderr? true)
                             :log-level-env (System/getenv "LOG_LEVEL")})})
    resolved-level))

(defn init!
  "Forcefully (re)initialize telemetry with the provided opts map.
  Useful for tests that want to tweak appenders or levels mid-run."
  ([] (init! {}))
  ([opts]
   (start! opts)
   (reset! initialized? true)
   :reinitialized))

(defn ensure-initialized!
  "Idempotent initializer for entry points and bb tasks.
  Subsequent calls are cheap no-ops so callers can invoke this unconditionally."
  ([] (ensure-initialized! {}))
  ([opts]
   (when (compare-and-set! initialized? false true)
     (start! opts)
     :initialized)))

(defn shutdown!
  "Reset bookkeeping so future ensure-initialized! calls re-run configuration.
  This is mainly helpful in tests or long-lived REPL sessions."
  []
  (when (compare-and-set! initialized? true false)
    (timbre/merge-config! {:appenders {:println {:enabled? false}}})
    :shutdown))


