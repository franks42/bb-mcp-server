(ns streamable-http.middleware
    "DEPRECATED: Use http-core.middleware directly.

   This namespace re-exports http-core.middleware for backwards compatibility.
   New code should require http-core.middleware instead."
    (:require [http-core.middleware :as core-mw]))

;; Re-export all public vars from http-core.middleware
(def wrap-cors core-mw/wrap-cors)
(def wrap-rate-limit core-mw/wrap-rate-limit)
(def reset-rate-limits! core-mw/reset-rate-limits!)
(def wrap-basic-auth core-mw/wrap-basic-auth)
(def wrap-api-key core-mw/wrap-api-key)
(def wrap-request-logging core-mw/wrap-request-logging)
(def wrap-origin-validation core-mw/wrap-origin-validation)
(def apply-middleware core-mw/apply-middleware)
