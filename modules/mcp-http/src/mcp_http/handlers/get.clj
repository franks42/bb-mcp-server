(ns mcp-http.handlers.get
    "GET /mcp handler for SSE notification streams.

   Opens a long-lived SSE connection for server-to-client notifications.
   Requires valid session ID in Mcp-Session-Id header."
    (:require [org.httpkit.server :as http]
              [mcp-http.session :as session]
              [http-core.sse :as sse]
              [http-core.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; Response Helpers
;; =============================================================================

(defn- error-response
  "Create error response."
  [status message]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (util/generate-json {:error message})})

;; =============================================================================
;; Handler Implementation
;; =============================================================================

(defn handle-get
  "Handle GET /mcp request - open SSE notification stream.

   Arguments:
     request - Ring request map (must be http-kit async request)

   Returns:
     Ring response or nil for async handling."
  [request]
  (let [session-id (util/get-header request "mcp-session-id")
        _accept (util/get-header request "accept")]

    (cond
      ;; Must accept SSE
      (not (util/accepts? request "text/event-stream"))
      (do
       (log/log! {:level :warn
                  :id    ::invalid-accept
                  :msg   "GET request must accept text/event-stream"})
       (error-response 406 "Must accept text/event-stream"))

      ;; Must have valid session
      (not (session/valid-session? session-id))
      (do
       (log/log! {:level :warn
                  :id    ::invalid-session
                  :msg   "GET request with invalid session"
                  :data  {:session-id session-id}})
       (error-response 400 "Invalid or missing session"))

      ;; Open SSE stream
      :else
      ;; http-kit's with-channel is deprecated but still functional
      ;; as-channel requires different setup; keeping with-channel for simplicity
      #_{:clj-kondo/ignore [:deprecated-var]}
      (http/with-channel request channel
                         (log/log! {:level :info
                                    :id    ::sse-stream-opened
                                    :msg   "Opening SSE stream"
                                    :data  {:session-id session-id}})

        ;; Set up the SSE stream
                         (sse/open-stream! channel
                                           (fn [status]
                                             (log/log! {:level :info
                                                        :id    ::sse-stream-closed
                                                        :msg   "SSE stream closed"
                                                        :data  {:session-id session-id
                                                                :status status}})
                                             (session/remove-sse-channel! session-id channel)))

        ;; Register channel with session
                         (session/add-sse-channel! session-id channel)
                         (session/touch-session! session-id)))))
