(ns streamable-http.session
    "DEPRECATED: Use mcp-http.session directly.

   This namespace re-exports mcp-http.session for backwards compatibility.
   New code should require mcp-http.session instead."
    (:require [mcp-http.session :as core-session]))

;; Re-export all public vars from mcp-http.session
(def create-session! "See mcp-http.session/create-session!" core-session/create-session!)
(def get-session "See mcp-http.session/get-session" core-session/get-session)
(def list-sessions "See mcp-http.session/list-sessions" core-session/list-sessions)
(def valid-session? "See mcp-http.session/valid-session?" core-session/valid-session?)
(def touch-session! "See mcp-http.session/touch-session!" core-session/touch-session!)
(def update-session! "See mcp-http.session/update-session!" core-session/update-session!)
(def destroy-session! "See mcp-http.session/destroy-session!" core-session/destroy-session!)
(def destroy-all-sessions! "See mcp-http.session/destroy-all-sessions!" core-session/destroy-all-sessions!)
(def add-sse-channel! "See mcp-http.session/add-sse-channel!" core-session/add-sse-channel!)
(def remove-sse-channel! "See mcp-http.session/remove-sse-channel!" core-session/remove-sse-channel!)
(def get-sse-channels "See mcp-http.session/get-sse-channels" core-session/get-sse-channels)
(def cleanup-expired-sessions! "See mcp-http.session/cleanup-expired-sessions!" core-session/cleanup-expired-sessions!)
(def start-cleanup-task! "See mcp-http.session/start-cleanup-task!" core-session/start-cleanup-task!)
(def stop-cleanup-task! "See mcp-http.session/stop-cleanup-task!" core-session/stop-cleanup-task!)
(def session-count "See mcp-http.session/session-count" core-session/session-count)
(def session-stats "See mcp-http.session/session-stats" core-session/session-stats)
(def all-sse-channels "See mcp-http.session/all-sse-channels" core-session/all-sse-channels)
