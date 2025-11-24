(ns mcp-http.core
    "MCP HTTP Transport Module - Entry Point.

   Provides MCP JSON-RPC over HTTP with SSE notifications.
   Implements MCP Streamable HTTP spec (2025-03-26).

   Main components:
   - mcp-http.session    - Session management
   - mcp-http.server     - Server lifecycle
   - mcp-http.router     - Request routing
   - mcp-http.handlers.* - POST/GET/DELETE handlers"
    (:require [mcp-http.server :as server]
              [mcp-http.session :as session]
              [mcp-http.router :as router]))

;; =============================================================================
;; Module Entry Point
;; =============================================================================

(def module
     "Module manifest for bb-mcp-server module system."
     {:name "mcp-http"
      :version "0.1.0"
      :description "MCP JSON-RPC over HTTP with SSE notifications"})

;; =============================================================================
;; Public API - Re-exports for convenience
;; =============================================================================

;; Server lifecycle
(def start-server! "See mcp-http.server/start-server!" server/start-server!)
(def stop-server! "See mcp-http.server/stop-server!" server/stop-server!)
(def server-status "See mcp-http.server/server-status" server/server-status)
(def draining? "See mcp-http.server/draining?" server/draining?)

;; Session management
(def create-session! "See mcp-http.session/create-session!" session/create-session!)
(def get-session "See mcp-http.session/get-session" session/get-session)
(def valid-session? "See mcp-http.session/valid-session?" session/valid-session?)
(def destroy-session! "See mcp-http.session/destroy-session!" session/destroy-session!)
(def list-sessions "See mcp-http.session/list-sessions" session/list-sessions)
(def session-count "See mcp-http.session/session-count" session/session-count)
(def session-stats "See mcp-http.session/session-stats" session/session-stats)
(def all-sse-channels "See mcp-http.session/all-sse-channels" session/all-sse-channels)

;; Router
(def create-router "See mcp-http.router/create-router" router/create-router)
(def wrap-cors "See mcp-http.router/wrap-cors" router/wrap-cors)
