(ns http-core.core
    "HTTP Core module entry point.

   This is an infrastructure module that provides shared HTTP utilities
   for other modules. It does not register any tools - it only provides
   namespaces for:
   - http-core.util      - JSON, UUID, time, header utilities
   - http-core.sse       - Server-Sent Events formatting and channel ops
   - http-core.middleware - Ring middleware (CORS, rate-limit, auth, etc.)

   Usage:
     Other modules depend on http-core and require its namespaces directly."
    (:require [taoensso.trove :as log]))

;; =============================================================================
;; Module Lifecycle
;; =============================================================================

(defn start
  "Start the http-core module.
   This is an infrastructure module - no tools to register."
  [_deps config]
  (log/log! {:level :info
             :id    ::http-core-starting
             :msg   "Starting http-core module"
             :data  {:config config}})
  (log/log! {:level :info
             :id    ::http-core-started
             :msg   "http-core module started (infrastructure only)"})
  {:status :running})

(defn stop
  "Stop the http-core module."
  [_instance]
  (log/log! {:level :info
             :id    ::http-core-stopping
             :msg   "Stopping http-core module"})
  nil)

(defn status
  "Get http-core module status."
  [_instance]
  {:status :ok
   :type :infrastructure
   :provides ["http-core.util" "http-core.sse" "http-core.middleware"]})

;; =============================================================================
;; Module Export
;; =============================================================================

(def module
     "http-core module lifecycle implementation."
     {:start start
      :stop stop
      :status status})
