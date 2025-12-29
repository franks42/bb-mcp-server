# Session Context

> **AI Assistant Directive:** This file captures current working state for session handoffs.
> Keep this structure intact. Update sections as you work. Refresh "Recent Changes" from git log.
> When ending a session, update "Previous Session Summary" and "Current Focus" for the next assistant.

**Last Updated:** 2025-12-29

## Previous Session Summary

Fixed clj-kondo lint issues in CLI scripts:
- Added proper `ns` declarations to 4 scripts in `scripts/`
- Fixed "redefined var" warnings and arity mismatch errors
- Root cause: scripts using implicit `user` namespace conflicted when analyzed together
- Result: 0 errors, 0 warnings, all tests passing

---

## Current Focus

**No active work** - Project is in stable state at Phase 20 complete.

---

## Recent Changes

```
78a07d6 fix: Add namespace declarations to CLI scripts
d230592 docs: Add AI directive to context.md
105774c docs: Restructure context.md for session handoffs
6b7582c docs: Condense IMPLEMENTATION_PLAN.md and mark Phase 20 complete
cd4dacf docs: Update context.md for fresh session handoff
```

---

## Pending Work

See IMPLEMENTATION_PLAN.md for details:

1. **bb calc CLI** (low priority) - Convenience wrapper for calculate module
2. **Phase 14C** - Dynamic loading documentation
3. **Phase 15C** - AI knowledge persistence in Datalevin
4. **Phase 15D** - Message bus Datalevin migration

---

## Open Questions

None currently.

---

## Session Notes

Things learned that aren't in CLAUDE.md:

- **Port files** use `.json` extension in `.ports/` directory
- **E2E tests** require server running with `--nickname e2e-test`
- **scittle-nrepl** needs sente-lite bundle at configured path

---

## Quick Resume

```bash
# Start server for development
bb server --http 0 --nickname dev

# Verify everything works
bb test:modules
bb lint

# Explore available tools
bb mcp servers
bb mcp tools --mcp dev
```

