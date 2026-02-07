# Session Context

> **AI Assistant Directive:** Keep this concise. Update as you work.
> For Scittle browser work, read `docs/SCITTLE_DEV_ENVIRONMENT.md` first.
> For nrepl-direct CLI, read `docs/bb-nrepl-direct-user-guide.md`.

**Last Updated:** 2026-02-07
**Version:** v1.16.0
**Focus:** Scittle dev environment fixed and verified end-to-end

---

## Current State (2026-02-07)

### What's been done this session

1. **UUIDv7 migration (v1.16.0)** — Committed at `cef8def`. All tests pass.

2. **Module loading bugs FIXED (uncommitted)** — atom-sync and code-browser-v2 modules now load correctly:

   | File | Fix |
   |------|-----|
   | `modules/atom-sync/src/atom_sync/core.clj` | Added `:status` fn, fixed `:start` arity to `[_deps _config]` |
   | `modules/code-browser-v2/module.edn` | Fixed `:entry` and `:requires` to use strings |
   | `modules/code-browser-v2/src/code_browser/core.clj` | Added `module` var with lifecycle map |
   | `system-cb-v2-test.edn` | Added `nrepl-server`, added `db-path`/`sources` to code-browser-v2 config |

3. **Scittle dev environment FIXED (uncommitted):**

   **Root cause of init! hanging:** `system-cb-v2-test.edn` had no `db-path` or `sources` in the code-browser-v2 module config, so `init!` never ran during server startup. Users had to call it manually via nrepl-direct, which hung because Datalevin pod operations blocked the nREPL thread.

   **Fix:** Added `db-path` and `sources` to module config so `init!` runs during server startup in the main process. No manual nrepl-direct calls needed.

   **Also fixed:** The guide falsely warned "Load Code Browser button loads v1" — it actually loads v2 (scittle_cm6.cljs, uri.cljc, code_browser_v2.cljs, mount!).

4. **New files created (uncommitted):**
   - `scripts/cb_v2_dev.clj` — bb task script for v2 dev environment (`bb dev:cb-v2`)
   - `docs/SCITTLE_DEV_ENVIRONMENT.md` — Complete rewrite, v2-focused, correct info
   - `bb.edn` — Added `dev:cb-v2` task

5. **End-to-end verification PASSED (Playwright):**
   - Server starts with all 9 modules, code-browser-v2 auto-initializes (208 namespaces, 2423 symbols)
   - Browser connects, "Load Code Browser" button works
   - Projects widget shows 1 project, clicking navigates to 203 namespaces
   - Symbol list loads (14 symbols for code-browser.core)
   - Source view works with CM6 editor (init! source displayed with line numbers)
   - Screenshot saved: `cb-v2-working-e2e.png`

### What's NOT done yet

1. **Git commit** — 6 changed files not committed yet
2. **R3.4** — File watching / cache invalidation (pending)
3. **R3.5** — Git status display (pending)

---

## Uncommitted Changes

```
modules/atom-sync/src/atom_sync/core.clj            # Module lifecycle fix
modules/code-browser-v2/module.edn                   # Entry point and requires fix
modules/code-browser-v2/src/code_browser/core.clj    # Module lifecycle var
system-cb-v2-test.edn                                # Auto-init config + nrepl-server
scripts/cb_v2_dev.clj                                  # NEW: bb task script (bb dev:cb-v2)
bb.edn                                                 # Added dev:cb-v2 task
docs/SCITTLE_DEV_ENVIRONMENT.md                      # REWRITTEN: v2-focused guide
```

---

## Quick Resume

```bash
# Single command to start v2 dev environment:
bb dev:cb-v2

# Then click "Load Code Browser" button in browser.
# That's it.

# Other commands:
bb dev:cb-v2 status     # Check status
bb dev:cb-v2 stop       # Stop server
bb dev:cb-v2 restart    # Restart with fresh data

# Run tests
bb test:module code-browser-v2
bb lint && bb format
```

---

*Last Updated: 2026-02-07*
