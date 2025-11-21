(ns bb-mcp-server.protocol.message
    "JSON-RPC 2.0 message parsing and formatting."
    (:require [cheshire.core :as json]
              [taoensso.trove :as log]))

;; JSON-RPC 2.0 error codes
(def error-codes
     "Standard JSON-RPC 2.0 error codes and custom error codes for MCP."
     {:parse-error -32700
      :invalid-request -32600
      :method-not-found -32601
      :invalid-params -32602
      :internal-error -32603
      :tool-not-found -32000
      :tool-execution-failed -32001
      :invalid-tool-params -32002
      :server-not-initialized -32003})

(defn parse-request
  "Parse and validate a JSON-RPC 2.0 request.

  Returns a map with either:
  - {:request <parsed-map>} on success
  - {:error {:code <code> :message <msg>}} on failure

  Validates:
  - JSON syntax
  - JSON-RPC 2.0 structure
  - Required fields: jsonrpc, method, id"
  [json-str]
  (log/log! {:level :info :msg "Parsing JSON-RPC request" :data {:input-length (count json-str)}})
  (try
    ;; Parse JSON
   (let [parsed (json/parse-string json-str true)]
     (log/log! {:level :debug :msg "JSON parsed successfully" :data {:keys (keys parsed)}})

      ;; Validate JSON-RPC 2.0 structure
     (cond
        ;; Check jsonrpc field
       (not (contains? parsed :jsonrpc))
       (do
        (log/log! {:level :warn :msg "Missing jsonrpc field"})
        {:error {:code (:invalid-request error-codes)
                 :message "Invalid Request"
                 :data "Missing 'jsonrpc' field"}})

        ;; Check version
       (not= "2.0" (:jsonrpc parsed))
       (do
        (log/log! {:level :warn :msg "Invalid JSON-RPC version" :data {:version (:jsonrpc parsed)}})
        {:error {:code (:invalid-request error-codes)
                 :message "Invalid Request"
                 :data "JSON-RPC version must be '2.0'"}})

        ;; Check method field
       (not (contains? parsed :method))
       (do
        (log/log! {:level :warn :msg "Missing method field"})
        {:error {:code (:invalid-request error-codes)
                 :message "Invalid Request"
                 :data "Missing 'method' field"}})

        ;; Check id field - missing id means it's a notification
       ;; JSON-RPC 2.0 spec: "The Server MUST NOT reply to a Notification"
       (not (contains? parsed :id))
       (do
        (log/log! {:level :info :msg "Notification received (no id field)"
                   :data {:method (:method parsed)}})
        {:notification parsed})

        ;; Valid request
       :else
       (do
        (log/log! {:level :info :msg "Valid JSON-RPC request parsed"
                   :data {:method (:method parsed)
                          :id (:id parsed)}})
        {:request parsed})))

   (catch Exception e
          (log/log! {:level :error :msg "JSON parse error" :error e})
          {:error {:code (:parse-error error-codes)
                   :message "Parse error"
                   :data (ex-message e)}})))

(defn create-response
  "Create a JSON-RPC 2.0 success response.

  Args:
  - id: Request ID (must match request)
  - result: The result value

  Returns: JSON-RPC 2.0 response map"
  [id result]
  (log/log! {:level :debug :msg "Creating success response" :data {:id id}})
  {:jsonrpc "2.0"
   :result result
   :id id})

(defn create-error-response
  "Create a JSON-RPC 2.0 error response.

  Args:
  - id: Request ID (must match request, or nil)
  - error-code: Error code (see error-codes map)
  - message: Human-readable error message
  - data: (optional) Additional error data

  Returns: JSON-RPC 2.0 error response map"
  ([id error-code message]
   (create-error-response id error-code message nil))
  ([id error-code message data]
   (log/log! {:level :warn :msg "Creating error response"
              :data {:id id :code error-code :message message}})
   {:jsonrpc "2.0"
    :error (cond-> {:code error-code
                    :message message}
                   data (assoc :data data))
    :id id}))
