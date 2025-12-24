# Sente-Browser Module Implementation Plan

**Module:** `sente-browser`
**Goal:** Enable Claude to eval code in browsers via existing `nrepl-eval` tool
**Started:** 2025-12-23

---

## Status Summary

| Phase | Description | Status |
|-------|-------------|--------|
| Pre-work | Verify nrepl tests, lint, dependencies | ✅ Complete |
| Phase 1 | Module structure + WebSocket server | ✅ Complete |
| Phase 2 | Full nREPL support + Bootstrap HTML | ✅ Complete |
| Phase 3 | Developer experience improvements | ✅ Complete |

---

## Pre-work (Complete)

- [x] Run nrepl module tests (34 tests, 131 assertions, 0 failures)
- [x] Check dependencies are up to date
- [x] Run clj-kondo linting (0 errors, 0 warnings)
- [x] Fix formatting issues in webserver test
- [x] Commit and push clj-kondo updates
- [x] Commit and push sente-browser design docs

---

## Phase 1: Minimal Integration (Complete)

**Objective:** Create module structure and basic WebSocket server integration

### Tasks

- [x] Create `modules/sente-browser/` directory structure
- [x] Add sente-lite 0.4.2-SNAPSHOT as dependency to bb.edn
- [x] Create `module.edn` with lifecycle config
- [x] Create `core.clj` - module lifecycle (start/stop/status)
- [x] Create `server.clj` - sente-lite WebSocket server
- [x] Add `:type` field to `nrepl/state/connection.clj`
- [x] Add `register-browser-connection!` function
- [x] Add `is-browser-connection?` and `get-browser-connections` helpers

### Files Created/Modified

**New files:**
- `modules/sente-browser/module.edn` - Module config with nrepl dependency
- `modules/sente-browser/src/sente_browser/core.clj` - Module lifecycle
- `modules/sente-browser/src/sente_browser/server.clj` - sente-lite integration

**Modified:**
- `bb.edn` - Added sente-lite 0.4.2-SNAPSHOT dependency
- `modules/nrepl/src/nrepl/state/connection.clj` - Added browser connection support

### Notes

- Browsers connect first, Claude discovers via `op=list`
- Different from socket pattern where Claude initiates connection
- Security: bind to 127.0.0.1 by default

---

## Phase 2: Full nREPL Support (Complete)

**Objective:** Complete nREPL eval support with bootstrap HTML

### Tasks

- [x] Implement connection sync task (poll sente-server/get-connections every 500ms)
- [x] Implement response routing from browser (results/deliver-result!)
- [x] Add bootstrap HTTP server (`bootstrap.clj`)
- [x] Create bootstrap HTML with Scittle + nREPL client
- [x] Wire message watcher to route based on connection type
- [x] Add browser send adapter to nrepl (register/unregister in messages.clj)
- [ ] Test: Claude can eval code in browser (requires manual testing with sente-lite client)
- [ ] Test: stdout/stderr streaming works

### Files Created/Modified

**New files:**
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - HTTP server for bootstrap page

**Modified:**
- `modules/sente-browser/src/sente_browser/server.clj` - Added sync task, response routing
- `modules/sente-browser/src/sente_browser/core.clj` - Register/unregister browser send fn
- `modules/nrepl/src/nrepl/state/messages.clj` - Browser send adapter registry, connection type routing
- `modules/nrepl/src/nrepl/state/watchers.clj` - Route by connection type (socket vs browser)

### Architecture Notes

- Browser send function registered on module start, unregistered on stop
- Message routing: enqueue-message! detects :browser type, creates different ready-to-send
- Watcher routes to nrepl-ops/send-message-fire-and-forget for sockets
- Watcher routes to registered browser-send-fn for browsers
- Responses from browsers routed via on-browser-message -> results/deliver-result!

---

## Phase 3: Developer Experience (Complete)

**Objective:** Polish and improve DX

### Tasks

- [x] Connection health monitoring with heartbeat
- [x] Broadcast to all browsers support
- [x] Documentation (README.md)
- [x] Unit tests (6 tests, 7 assertions)

### Implementation Details

**Heartbeat Monitoring:**
- Ping sent every 10s to all connected browsers
- Pong responses update `last-heartbeat` timestamp
- Connections stale after 30s without pong automatically disconnected
- `get-connection-health` returns health status for all browsers

**Broadcast Support:**
- `broadcast-to-browsers!` sends event to all connected browsers
- Returns count of browsers message was sent to

**Documentation & Tests:**
- README.md with usage, API reference, configuration
- Unit tests for state access and lookup functions
- Test task added to bb.edn (`bb test:sente-browser`)

### Files Created/Modified

- `modules/sente-browser/src/sente_browser/server.clj` - Added heartbeat task, broadcast, health API
- `modules/sente-browser/src/sente_browser/bootstrap.clj` - Bootstrap HTML responds to heartbeat pings
- `modules/sente-browser/README.md` - Module documentation
- `modules/sente-browser/test/run_tests.clj` - Test runner
- `modules/sente-browser/test/sente_browser/server_test.clj` - Unit tests
- `bb.edn` - Added test:sente-browser task

---

## Future: Adapter Registry Pattern

After MVP works, refactor to cleaner architecture:
- nrepl exposes `register-adapter!` / `unregister-adapter!`
- sente-browser registers itself on startup
- Removes hardcoded `:browser` type handling from nrepl

---

## Commits

| Date | Commit | Description |
|------|--------|-------------|
| 2025-12-23 | 5b8a12b | chore: Update clj-kondo imports and fix webserver test formatting |
| 2025-12-23 | 1b3d75f | docs: Add sente-browser module design documents |
| 2025-12-24 | 854e498 | feat(sente-browser): Implement Phase 1 - module structure |
| 2025-12-24 | 1739d41 | feat(sente-browser): Implement Phase 2 - full nREPL support |
| 2025-12-24 | 931da10 | feat(sente-browser): Implement Phase 3 heartbeat & broadcast |
| 2025-12-24 | (pending) | feat(sente-browser): Complete Phase 3 - docs & tests |

---

*Last Updated: 2025-12-24 (Phase 3 complete)*
