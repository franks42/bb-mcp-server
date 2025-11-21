# Trove Refactor Guide

This document captures the concrete steps required to remove the `telemere-lite` dependency from **bb-mcp-server** and standardize all telemetry on the Trove + Timbre stack. Follow the checklist in order; each step should leave the repository in a green (tests pass) state.

## 0. Preconditions
- `bb --version` ≥ **1.12.204**
- `bb.edn` already depends on `com.taoensso/trove` and `taoensso/timbre`
- No long-running production sessions; plan for a short interruption when swapping telemetry bootstrap

## 1. Introduce a Telemetry Facade Namespace
- Create `src/bb_mcp_server/telemetry.clj`
- Require `taoensso.trove :as log`, `taoensso.trove.timbre :as backend`, `taoensso.timbre :as timbre`
- Export helpers: `init!`, `ensure-initialized!`, `shutdown!`, `event!`, `info!`, `warn!`, `error!`, `with-duration`, etc.
- `init!` responsibilities:
  - Set Timbre level/appenders (env driven)
  - Call `(log/set-log-fn! (backend/get-log-fn))`
  - Log a startup event with version info
- Provide a noop `shutdown!` that can later flush async appenders
- Implement `ensure-initialized!` (idempotent wrapper) so every Babashka task/script can simply `(telemetry/ensure-initialized! {:level :debug})` without duplicating the wiring.

## 2. Wire Telemetry Facade into Entry Points
- In `server.clj`, `bb_mcp_server/transport/stdio.clj`, and `test_harness.clj`:
  - Require the new telemetry namespace
  - Call `(telemetry/init!)` once during startup
  - Ensure tests stop logging noise by allowing `LOG_LEVEL=test` overrides
- Update **all `bb.edn` tasks and standalone `.bb` scripts** to require the telemetry namespace and call `ensure-initialized!` as the first statement. This keeps logging consistent regardless of how the server is launched and matches the bootstrap recipe documented in `docs/TROVE_TIMBRE_BABASHKA_GUIDE.md`.

## 3. Replace Direct Timbre Usage
- Search for `taoensso.timbre` across `src/` and `test/`
- For each namespace:
  - Remove direct Timbre requires
  - Require `bb-mcp-server.telemetry :as telemetry`
  - Replace `(log/info ...)` with `(telemetry/info! "message" {:context ...})` or higher-level helpers
  - Use structured payloads: include `:id` (e.g., `:bb-mcp-server.router/request`) when context matters
- Update unit tests if they depended on Timbre side effects (none currently do)

## 4. Remove telemere-lite Dependency
- Delete `src/telemere_lite/` directories **after** all namespaces compile without it
- Remove telemere-lite references from `bb.edn`, `deps.edn`, and docs
- If any consumers (e.g., sente-lite) still need telemere-lite, split them into separate projects before deletion

## 5. Update Documentation & Guides
- `docs/TROVE_TIMBRE_BABASHKA_GUIDE.md`: mark telemere-lite as legacy, point to telemetry facade
- `docs/bb-mcp-server-architecture.md`: update telemetry section to describe the Trove facade + Timbre backend
- Record the change log entry (e.g., `CHANGELOG.md` if present)

## 6. Validation Checklist
- `bb test`
- Manual stdio smoke test: start server, send `initialize`, `tools/list`, `tools/call`, confirm logs emit Trove-style JSON
- Verify no namespace still requires `telemere-lite` or `taoensso.timbre` directly (except the telemetry facade)
- Inspect generated logs for secrets/PII and ensure filters/redaction policies are documented

## 7. Future Flip to Telemere
Once official Telemere support for Babashka lands:
- Replace the backend binding in `telemetry/init!` with the Telemere backend
- Retain Trove API to avoid touching call sites
- Consider exposing OpenTelemetry exporters if required

---
Use this guide as the authoritative playbook for AI agents performing the migration. Always pause before deleting telemere-lite if other repos still depend on it.
