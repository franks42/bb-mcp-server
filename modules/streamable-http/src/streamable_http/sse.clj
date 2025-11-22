(ns streamable-http.sse
    "Server-Sent Events utilities for http-kit.

   Provides SSE event formatting and channel management functions
   compatible with the MCP Streamable HTTP transport spec."
    (:require [org.httpkit.server :as http]
              [streamable-http.util :as util]
              [taoensso.trove :as log]))

;; =============================================================================
;; SSE Event Formatting
;; =============================================================================

(defn format-sse-event
  "Format data as SSE event string.

   Arguments:
     opts - Map with optional keys:
       :id    - Event ID for resumability
       :event - Event type (default: 'message')
       :data  - Event payload (string or map, will be JSON-encoded if map)

   Returns:
     SSE-formatted string with trailing blank line.

   Example:
     (format-sse-event {:id \"1\" :event \"message\" :data {:foo \"bar\"}})
     => \"id: 1\\nevent: message\\ndata: {\\\"foo\\\":\\\"bar\\\"}\\n\\n\""
  [{:keys [id event data]}]
  (str (when id (str "id: " id "\n"))
       (when event (str "event: " event "\n"))
       "data: " (if (string? data) data (util/generate-json data))
       "\n\n"))

(defn format-json-rpc-event
  "Format a JSON-RPC message as SSE event.

   Arguments:
     json-rpc-msg - JSON-RPC message map
     event-id     - Optional event ID for resumability

   Returns:
     SSE-formatted string."
  [json-rpc-msg & [event-id]]
  (format-sse-event {:id event-id
                     :event "message"
                     :data json-rpc-msg}))

;; =============================================================================
;; SSE Response Headers
;; =============================================================================

(def sse-headers
     "Standard SSE response headers."
     {"Content-Type" "text/event-stream"
      "Cache-Control" "no-cache, no-store, must-revalidate"
      "Connection" "keep-alive"
      "X-Accel-Buffering" "no"})  ; Disable nginx buffering

;; =============================================================================
;; Channel Operations
;; =============================================================================

(defn send-event!
  "Send SSE event to http-kit channel.

   Arguments:
     channel    - http-kit async channel
     event-opts - Map with :id, :event, :data keys

   Returns:
     true if sent successfully, false otherwise."
  [channel event-opts]
  (try
   (let [event-str (format-sse-event event-opts)]
     (http/send! channel event-str false))
   (catch Exception e
          (log/log! {:level :warn
                     :id    ::send-event-failed
                     :msg   "Failed to send SSE event"
                     :error e})
          false)))

(defn send-json-rpc!
  "Send JSON-RPC message as SSE event.

   Arguments:
     channel      - http-kit async channel
     json-rpc-msg - JSON-RPC message map
     event-id     - Optional event ID

   Returns:
     true if sent successfully, false otherwise."
  [channel json-rpc-msg & [event-id]]
  (send-event! channel {:id event-id
                        :event "message"
                        :data json-rpc-msg}))

(defn open-stream!
  "Open SSE stream on http-kit channel.

   Arguments:
     channel     - http-kit async channel
     on-close-fn - Callback when channel closes (receives status keyword)

   Returns:
     The channel."
  [channel on-close-fn]
  (log/log! {:level :debug
             :id    ::open-stream
             :msg   "Opening SSE stream"})
  (http/send! channel
              {:status 200
               :headers sse-headers}
              false)
  (when on-close-fn
    (http/on-close channel on-close-fn))
  channel)

(defn close-stream!
  "Close SSE stream.

   Arguments:
     channel - http-kit async channel

   Returns:
     nil."
  [channel]
  (log/log! {:level :debug
             :id    ::close-stream
             :msg   "Closing SSE stream"})
  (http/close channel)
  nil)

;; =============================================================================
;; Notification Helpers
;; =============================================================================

(defn send-notification!
  "Send a JSON-RPC notification via SSE.

   Arguments:
     channel - http-kit async channel
     method  - Notification method name
     params  - Notification parameters map

   Returns:
     true if sent successfully, false otherwise."
  [channel method params]
  (send-json-rpc! channel {:jsonrpc "2.0"
                           :method method
                           :params params}))

(defn broadcast!
  "Broadcast SSE event to multiple channels.

   Arguments:
     channels   - Collection of http-kit channels
     event-opts - Map with :id, :event, :data keys

   Returns:
     Number of channels that received the event successfully."
  [channels event-opts]
  (let [results (map #(send-event! % event-opts) channels)]
    (count (filter true? results))))
