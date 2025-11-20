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

  CRITICAL: MCP Protocol Version Format
  ======================================
  The protocolVersion is NOT a semantic version (like \"1.0\").
  It is the RELEASE DATE of the MCP specification in YYYY-MM-DD format:

  - \"2024-11-05\" = First stable MCP spec (Nov 5, 2024)
  - \"2025-03-26\" = Major update with OAuth 2.1, chunked HTTP (Mar 26, 2025)
  - \"2025-06-18\" = Removed JSON-RPC batching (Jun 18, 2025)

  We return \"2025-03-26\" to signal support for:
  - Tool annotations (readOnly, destructive flags)
  - Future HTTP transport with OAuth 2.1 and chunked streaming

  IMPORTANT: Capabilities Negotiation
  ====================================
  We declare what we support via the capabilities object:
  - {:tools {}} = We support tools (required)
  - No authorization capability = OAuth not implemented (stdio uses environment auth)
  - When we add HTTP transport, we'll add authorization capability

  Do NOT validate the client's version - just return this fixed response.

  For details, see: docs/bb-mcp-server-architecture.md -> Critical Implementation Lessons

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
    ;; IMPORTANT: "2025-03-26" is a DATE (Mar 26, 2025), NOT a version number!
    ;; We don't declare authorization capability, so OAuth is not required
    (msg/create-response request-id
                         {:protocolVersion "2025-03-26"
                          :serverInfo {:name "bb-mcp-server"
                                       :version "0.1.0"}
                          :capabilities {:tools {}}})))
