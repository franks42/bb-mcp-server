(ns bb-mcp-server.handlers.initialize
    "Initialize handler for MCP server.

  Implements the 'initialize' method which is the first method clients must call
  to establish a session with the server."
    (:require [bb-mcp-server.protocol.message :as msg]
              [bb-mcp-server.protocol.router :as router]
              [taoensso.timbre :as log]))

(defn handle-initialize
  "Process an initialize request.

  This is the handshake method that clients must call before any other methods.
  Following the working nrepl-mcp-server pattern, we accept any initialize request
  and return a fixed response.

  Args:
  - request: The parsed JSON-RPC request map

  Returns: JSON-RPC response map (success)"
  [request]
  (let [request-id (:id request)
        params (:params request)]

    (log/info "Processing initialize request"
              {:request-id request-id
               :params params})

    ;; Mark server as initialized
    (router/set-initialized! true)

    (log/info "Initialize successful"
              {:request-id request-id
               :server-initialized true})

    ;; Return fixed response matching MCP spec
    (msg/create-response request-id
                         {:protocolVersion "2024-11-05"
                          :serverInfo {:name "bb-mcp-server"
                                       :version "0.1.0"}
                          :capabilities {:tools {}}})))
