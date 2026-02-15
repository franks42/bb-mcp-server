# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-15
**Version:** v1.31.2
**Focus:** Datalevin pod hang ROOT CAUSE FOUND AND FIXED

---

## Current State — STABLE

All tests pass (80 tests, 631 assertions, 0 failures). Lint and format clean.
Live E2E verified: INSERT (new namespace + 3 symbols), UPDATE (retract 3 + add 4), pod stays alive, graceful shutdown.

---

## Dev Environment Setup

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
bb nrepl-direct eval "(ns my-test-ns) (defn greet [name] (str \"Hello, \" name))" --port 9876
```

---

## Fixed Issues

### Fixed: Datalevin Pod transact! Hang — ROOT CAUSE (2026-02-15)

**Problem:** `transact!` hung permanently, pod stdin/stdout pipe jammed, all subsequent pod calls blocked forever.

**Two root causes found and fixed:**

1. **Nil values in tx-data (handlers.clj):** `update-namespace-symbols!` sent entity maps with nil-valued attributes (`:symbol/doc nil`, `:symbol/private? nil`, etc.) to the pod. The pod's transit serialization failed on nil values, and the pod's own exception handler also failed to serialize the `ex-data`, causing NO response to be written back — the babashka client blocked forever on the undelivered promise. **Fix:** Added `clean-entity` to strip nil values before transacting, matching the pattern already used in `core.clj`'s `scan-and-populate!`.

2. **`pod-call-with-timeout` future wrapper (datalevin.clj, uncommitted):** Wrapped every pod call in `(future ...)`, scattering pod I/O across random threads. When calls timed out, `future-cancel` didn't interrupt blocked I/O, leaving zombie threads on the pod's stdout that consumed responses meant for other callers, desynchronizing the bencode protocol. **Fix:** Removed the wrapper entirely, reverted to direct pod calls.

**Research findings (for future reference):**
- babashka/pods #60: Pod read errors cause hangs (fixed Dec 2022 in PR #62)
- datalevin #274: Hangs on unknown attributes (nil → internal error → no response)
- datalevin #331: Query threading in write tx (fixed v0.10.1)
- Pod exception handler vulnerability: If `ex-data` contains non-transit-serializable objects, the error handler itself throws, no response written, client hangs forever
- No runtime logging switch for datalevin pod (hardcoded `debug? false` in native image). Alternatives: bencode proxy, macOS `sample <pid>`, rebuild with debug=true

### Fixed: Datalevin Version Upgrade 0.9.27 → 0.10.5 (2026-02-15)

Updated across all files: `datalevin.clj`, `datalevin_pod/core.clj`, tests, scripts. Old databases deleted and recreated.

### Fixed: Datalevin Pod Deadlock Prevention — db-lock (2026-02-14–15)

**Problem:** Babashka's Datalevin pod serializes ALL calls through a single stdin/stdout message channel. Concurrent pod calls from different threads deadlock the pod permanently.

**What changed:**
- Added `(defonce db-lock (Object.))` in `handlers.clj`
- All pod-calling functions wrap `db-proto/q` or `db-proto/transact!` calls with `(locking db-lock ...)`
- `core.clj`: `scan-and-populate!` and `rescan-file!` also hold `db-lock`
- `dispatch-event` itself is NOT locked (nREPL eval calls can take 30s+)

**Note:** `datalevin-pod` module's own functions do NOT use `db-lock` — potential concern if both modules share the same pod process.

### Fixed: Self-Introspection Deadlock (2026-02-14)

**Problem:** Pod calls hang, server needs SIGKILL, widgets stuck loading.
**Root Cause:** code-browser-v2 introspecting its own nREPL server deadlocks nREPL thread pool.
**Fix:** External nREPL target on port 9876. `bb dev:cb-v2` manages both processes.

### Fixed: nrepl-direct Silent Error Swallowing (2026-02-14)

**Problem:** All eval errors silently swallowed — no output, exit 0.
**Fix:** Check `:ex`/`:root-ex` in response. v1.31.0.

### Fixed: Datalevin Deadlock from Rescan Storm (2026-02-14)

**Problem:** Fingerprints never stored → infinite rescan loop → concurrent rescans.
**Fix:** Store initial fingerprints + `compare-and-set!` concurrency guard.

---

## Recent Commits

- `9a81401` — Two-process dev env to prevent nREPL self-introspection deadlock
- `6332a9d` — Fix nrepl-direct silent error swallowing
- `0cd899a` — Check Datalevin tx results before telling browser data changed
- `467e083` — Datalevin access statechart documentation
- `06863c4` — Incremental per-namespace Datalevin updates

---

## Next Session Priorities

1. **Full E2E "drum roll" test** — create namespace, see symbols, add function, see update in browser
2. **Monitor server stability** — confirm polling works without hangs over extended period
3. **Consider `datalevin-pod` module locking** — its functions bypass `db-lock`, potential concurrent access if sharing the pod process with code-browser-v2

---

*For detailed debugging history, see git log and claude-mem observations.*
