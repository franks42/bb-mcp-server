(ns bb-mcp-server.protocol.processor
    "Unified JSON-RPC processor for MCP server.

  This namespace provides a transport-agnostic JSON-RPC processor that
  handles parsing, validation, routing, and response formatting.

  The processor accepts a context object (`ctx`) that carries transport-specific
  capabilities to handlers:

    ;; Stdio context
    {:transport :stdio
     :send-notification! (fn [msg] (println (json/generate-string msg)))}

    ;; HTTP context
    {:transport :http
     :session-id \"abc-123\"
     :send-notification! (fn [msg] (sse/send-json-rpc! channel msg))}

  Usage:
    ;; Process a JSON-RPC request with context
    (process-request ctx json-string)
    ;; => {:response response-map} or {:notification true} or {:error error-map}

    ;; Create a stdio context
    (make-stdio-ctx)

    ;; Create an HTTP context with SSE channel
    (make-http-ctx session-id send-fn)"
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.protocol.router :as router]
              [cheshire.core :as json]
              [taoensso.trove :as log]))

;; =============================================================================
;; Context Builders
;; =============================================================================

(defn make-stdio-ctx
  "Create a context for stdio transport.

  Returns:
    Context map with :transport :stdio and :send-notification! that prints
    JSON to stdout."
  []
  {:transport :stdio
   :send-notification! (fn [notification]
                         (println (json/generate-string notification))
                         (flush))})

(defn make-http-ctx
  "Create a context for HTTP transport.

  Args:
    session-id - String session identifier
    send-fn    - Function (fn [json-rpc-msg]) to send notifications via SSE

  Returns:
    Context map with :transport :http, :session-id, and :send-notification!"
  [session-id send-fn]
  {:transport :http
   :session-id session-id
   :send-notification! send-fn})

(defn make-test-ctx
  "Create a context for testing (notifications are collected).

  Returns:
    Context map with :transport :test and :send-notification! that appends
    notifications to an atom. Access collected notifications via
    @(:notifications ctx)."
  []
  (let [notifications (atom [])]
    {:transport :test
     :notifications notifications
     :send-notification! (fn [notification]
                           (swap! notifications conj notification))}))

;; =============================================================================
;; Core Processor
;; =============================================================================

(defn process-request
  "Process a JSON-RPC request with transport context.

  This is the unified entry point for both stdio and HTTP transports.

  Args:
    ctx         - Context map with :transport and :send-notification!
    json-str    - JSON-RPC request as string

  Returns:
    Map with one of:
      {:response response-map}      - Success, return this response
      {:notification true}          - Was a notification, no response needed
      {:error error-map}            - Parse or validation error

  The ctx is passed through to handlers, allowing them to send notifications
  via (:send-notification! ctx)."
  [ctx json-str]
  (log/log! {:level :debug
             :id ::process-request
             :msg "Processing JSON-RPC request"
             :data {:transport (:transport ctx)
                    :input-length (count json-str)}})

  ;; Parse request
  (let [parse-result (msg/parse-request json-str)]
    (cond
      ;; Parse error - return error response
      (:error parse-result)
      (let [error-response (msg/create-error-response
                            nil
                            (get-in parse-result [:error :code])
                            (get-in parse-result [:error :message])
                            (get-in parse-result [:error :data]))]
        (log/log! {:level :warn
                   :id ::parse-error
                   :msg "JSON-RPC parse error"
                   :data {:error (:error parse-result)}})
        {:error error-response})

      ;; Notification - process but don't respond
      ;; JSON-RPC 2.0: "The Server MUST NOT reply to a Notification"
      (:notification parse-result)
      (do
       (log/log! {:level :info
                  :id ::notification
                  :msg "Processing notification (no response)"
                  :data {:method (get-in parse-result [:notification :method])}})
        ;; Route notification to handler with context
       (router/route-request ctx (:notification parse-result))
       {:notification true})

      ;; Regular request - route with context and respond
      :else
      (let [request (:request parse-result)
            response (router/route-request ctx request)]
        (log/log! {:level :debug
                   :id ::request-complete
                   :msg "Request processed"
                   :data {:method (:method request)
                          :id (:id request)
                          :success? (not (contains? response :error))}})
        {:response response}))))

(defn process-request-str
  "Process a JSON-RPC request and return response as JSON string.

  Convenience function that wraps process-request and serializes the response.

  Args:
    ctx      - Context map with :transport and :send-notification!
    json-str - JSON-RPC request as string

  Returns:
    JSON string response, or nil for notifications."
  [ctx json-str]
  (let [result (process-request ctx json-str)]
    (cond
      (:notification result) nil
      (:error result) (json/generate-string (:error result))
      (:response result) (json/generate-string (:response result)))))
