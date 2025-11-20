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
  It validates the protocol version and client information, then marks the server
  as initialized.

  Args:
  - request: The parsed JSON-RPC request map with:
    - :params {:protocolVersion string
               :clientInfo {:name string :version string (optional)}}
    - :id request identifier

  Returns: JSON-RPC response map (success or error)

  Success response includes:
  - protocolVersion: The protocol version the server supports (\"1.0\")
  - serverInfo: Server name and version
  - capabilities: What features the server supports

  Error responses:
  - -32602 Invalid params: Missing or invalid required fields"
  [request]
  (let [params (:params request)
        request-id (:id request)]

    (log/info "Processing initialize request"
              {:request-id request-id
               :client-info (:clientInfo params)
               :protocol-version (:protocolVersion params)})

    (cond
      ;; Validate protocolVersion exists
      (not (contains? params :protocolVersion))
      (do
       (log/warn "Initialize failed: missing protocolVersion"
                 {:request-id request-id})
       (msg/create-error-response
        request-id
        (:invalid-params msg/error-codes)
        "Invalid params"
        "Missing required field: protocolVersion"))

      ;; Validate protocolVersion is supported
      (not= "1.0" (:protocolVersion params))
      (do
       (log/warn "Initialize failed: unsupported protocolVersion"
                 {:request-id request-id
                  :requested-version (:protocolVersion params)
                  :supported-version "1.0"})
       (msg/create-error-response
        request-id
        (:invalid-params msg/error-codes)
        "Invalid params"
        (str "Unsupported protocolVersion: " (:protocolVersion params)
             ". Server supports version 1.0")))

      ;; Validate clientInfo exists
      (not (contains? params :clientInfo))
      (do
       (log/warn "Initialize failed: missing clientInfo"
                 {:request-id request-id})
       (msg/create-error-response
        request-id
        (:invalid-params msg/error-codes)
        "Invalid params"
        "Missing required field: clientInfo"))

      ;; Validate clientInfo.name exists
      (not (contains? (:clientInfo params) :name))
      (do
       (log/warn "Initialize failed: missing clientInfo.name"
                 {:request-id request-id})
       (msg/create-error-response
        request-id
        (:invalid-params msg/error-codes)
        "Invalid params"
        "Missing required field: clientInfo.name"))

      ;; Success case
      :else
      (let [client-info (:clientInfo params)
            response-data {:protocolVersion "1.0"
                           :serverInfo {:name "bb-mcp-server"
                                        :version "0.1.0"}
                           :capabilities {:tools true
                                          :dynamicToolRegistration false}}]

        ;; Mark server as initialized
        (router/set-initialized! true)

        (log/info "Initialize successful"
                  {:request-id request-id
                   :client-name (:name client-info)
                   :client-version (:version client-info)
                   :server-initialized true})

        (msg/create-response request-id response-data)))))
