(ns mcp-http.handlers.delete
    "DELETE /mcp handler for session termination.

   Terminates an active session and closes all associated SSE channels."
    (:require [mcp-http.session :as session]
              [http-core.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; Handler Implementation
;; =============================================================================

(defn handle-delete
  "Handle DELETE /mcp request - terminate session.

   Arguments:
     request - Ring request map

   Returns:
     Ring response map."
  [request]
  (let [session-id (util/get-header request "mcp-session-id")]

    (cond
      ;; No session ID provided
      (nil? session-id)
      (do
       (log/log! {:level :warn
                  :id    ::delete-no-session
                  :msg   "DELETE request without session ID"})
       {:status 400
        :headers {"Content-Type" "application/json"}
        :body (util/generate-json {:error "Missing Mcp-Session-Id header"})})

      ;; Session doesn't exist (already terminated or never existed)
      (not (session/valid-session? session-id))
      (do
       (log/log! {:level :debug
                  :id    ::delete-invalid-session
                  :msg   "DELETE request for non-existent session"
                  :data  {:session-id session-id}})
       {:status 404
        :headers {"Content-Type" "application/json"}
        :body (util/generate-json {:error "Session not found"})})

      ;; Valid session - destroy it
      :else
      (do
       (log/log! {:level :info
                  :id    ::session-terminated
                  :msg   "Terminating session via DELETE"
                  :data  {:session-id session-id}})
       (session/destroy-session! session-id)
       {:status 204
        :headers {}
        :body ""}))))
