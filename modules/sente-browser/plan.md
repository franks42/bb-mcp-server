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
| Phase 2 | Full nREPL support + Bootstrap HTML | ⏳ Pending |
| Phase 3 | Developer experience improvements | ⏳ Pending |

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
- [ ] Test: Claude can list browser connections via `op=list` (deferred to Phase 2)

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
- Connection sync (detecting new browsers) deferred to Phase 2

---

## Phase 2: Full nREPL Support

**Objective:** Complete nREPL eval support with bootstrap HTML

### Tasks

- [ ] Implement connection sync task (poll sente-server/get-connections)
- [ ] Implement response routing from browser
- [ ] Add bootstrap HTTP server (`bootstrap.clj`)
- [ ] Create bootstrap HTML with Scittle + nREPL client
- [ ] Wire message watcher to route based on connection type
- [ ] Test: Claude can eval code in browser
- [ ] Test: stdout/stderr streaming works

---

## Phase 3: Developer Experience

**Objective:** Polish and improve DX

### Tasks

- [ ] Connection health monitoring with heartbeat
- [ ] Broadcast to all browsers support
- [ ] Documentation and examples
- [ ] Integration tests

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

---

*Last Updated: 2025-12-24*
