(ns streamable-http.session
    "DEPRECATED: Use mcp-http.session directly.

   This namespace re-exports mcp-http.session for backwards compatibility.
   New code should require mcp-http.session instead."
    (:require [mcp-http.session :as core-session]))

;; Re-export all public vars from mcp-http.session
(def create-session! core-session/create-session!)
(def get-session core-session/get-session)
(def list-sessions core-session/list-sessions)
(def valid-session? core-session/valid-session?)
(def touch-session! core-session/touch-session!)
(def update-session! core-session/update-session!)
(def destroy-session! core-session/destroy-session!)
(def destroy-all-sessions! core-session/destroy-all-sessions!)
(def add-sse-channel! core-session/add-sse-channel!)
(def remove-sse-channel! core-session/remove-sse-channel!)
(def get-sse-channels core-session/get-sse-channels)
(def cleanup-expired-sessions! core-session/cleanup-expired-sessions!)
(def start-cleanup-task! core-session/start-cleanup-task!)
(def stop-cleanup-task! core-session/stop-cleanup-task!)
(def session-count core-session/session-count)
(def session-stats core-session/session-stats)
(def all-sse-channels core-session/all-sse-channels)
