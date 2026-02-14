# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-14 (evening)
**Version:** v1.31.1
**Focus:** Two-process dev environment, self-introspection fix

---

## Current Work (2026-02-14)

### Self-Introspection Deadlock — RESOLVED

**Problem:** When code-browser-v2 introspected its own nREPL server (port 7888), all Datalevin pod calls hung after initialization. Server required SIGKILL to stop.

**Root Cause:** Self-introspection consumed nREPL threads needed for other operations. Introspection evals waited for threads that were waiting for pod responses that needed more nREPL threads — a thread-pool deadlock.

**Fix:** Two-process architecture:
- Process 1: nREPL target (`bb --nrepl-server 9876`) — separate process to introspect
- Process 2: Main server — code-browser-v2 introspects port 9876 (not self)

**Changes:**
- `system-cb-v2-test.edn`: nREPL source port 7888 → 9876
- `scripts/cb_v2_dev.clj`: Manages nREPL target lifecycle (start/stop/status)
- `docs/SCITTLE_DEV_ENVIRONMENT.md`: Documented two-process architecture

### nrepl-direct Error Reporting — FIXED (v1.31.0)

**Problem:** `nrepl-direct eval` silently swallowed all eval errors. Divide-by-zero, undefined symbols, bad requires — all returned success with no output.

**Root Cause:** `send-message` in `client.clj` conflated nREPL protocol "done" (exchange complete) with eval success. Set status to `"success"` even when `:ex`/`:root-ex` fields were present.

**Fix:** Check merged response for `:ex`/`:root-ex` before setting status. Errors now print to stderr and exit with code 1.

### Dev Environment Setup

```bash
# Standard dev environment (ALWAYS use this):
bb dev:cb-v2                    # Full start: target + server + browser
bb dev:cb-v2 start --no-open    # Same but skip browser
bb dev:cb-v2 stop               # Stop server + nREPL target
bb dev:cb-v2 status             # Check both processes

# Two-process architecture:
# Process 1: nREPL target on port 9876 (auto-managed by dev:cb-v2)
# Process 2: Main server on port 7888 (introspects port 9876)

# Testing:
bb nrepl-direct eval "(+ 1 2)" -t cb-v2-test
bb nrepl-direct eval "(code-browser.handlers/dispatch-event :code-browser-v2/fetch {:type :projects})" -t cb-v2-test
```

---

## Fixed Issues

### Fixed: Self-Introspection Deadlock (2026-02-14)

**Problem:** Pod calls hang, server needs SIGKILL, widgets stuck loading.
**Root Cause:** code-browser-v2 introspecting its own nREPL server deadlocks nREPL thread pool.
**Fix:** External nREPL target on port 9876. `bb dev:cb-v2` manages both processes.

### Fixed: nrepl-direct Silent Error Swallowing (2026-02-14)

**Problem:** All eval errors silently swallowed — no output, exit 0.
**Root Cause:** `send-message` set status "success" for all completed exchanges.
**Fix:** Check `:ex`/`:root-ex` in response. v1.31.0.

### Fixed: Datalevin Deadlock from Rescan Storm (2026-02-14)

**Problem:** Fingerprints never stored → infinite rescan loop → concurrent rescans.
**Fix:** Store initial fingerprints + `compare-and-set!` concurrency guard.

### Fixed: dispatch-event Unbound Bug (2026-02-13)

**Problem:** Two parenthesis errors in handlers.clj.
**Fix:** Corrected paren errors.

### Fixed: nREPL Source Not Registered (2026-02-13)

**Problem:** nREPL source missing from `:sources` map.
**Fix:** Added to `system-cb-v2-test.edn`.

---

## Next Session Priorities

1. **Continue browser testing** — verify all widget types work end-to-end with two-process setup
2. **Implement incremental rescans** — single-namespace rescan instead of full `all-ns` batch-introspect
3. **Integrate runtime-data-sync statechart** — wire documentation into code
4. **Monitor server stability** — confirm polling works without rescan storms

---

## Recent Commits

- `v1.31.0` (`6332a9d`) — Fix nrepl-direct silent error swallowing
- `0cd899a` — Datalevin telemetry + tx result checking
- `467e083` — Datalevin access statechart (documentation)
- `06863c4` — Incremental per-namespace Datalevin updates
- `15aaad5` — Rescan storm fix (fingerprint storage + concurrency guard)

---

*For detailed debugging history, see git log and claude-mem observations.*
