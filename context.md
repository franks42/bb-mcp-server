# bb-mcp-server Project Context

**Current State: December 29, 2025**

## Status: Phase 20 Complete

All major infrastructure is in place. The server is production-ready with comprehensive testing.

### What's Available

**CLI Tools:**
- `bb server` - Unified server entry (stdio/http/both)
- `bb mcp` - Generic MCP exploration & testing (servers, tools, call, init)
- `bb mcp-eval` - Code evaluation via local-eval
- `bb nrepl` - Remote nREPL operations
- `bb rebel-nrepl-client` - iTerm2 + rebel-readline

**Testing:**
- `bb test:modules` - Unit tests for all modules
- `bb test:e2e` - Real MCP protocol tests (11 tests, 42 assertions)
- `bb test:bootstrap` - Configuration tests

---

## Pending TODO

**`bb calc` convenience CLI** - Higher-level wrapper for calculate module:
```bash
# Instead of:
bb mcp call calculate.calculate '{"expr":"(percent-change 100 125)"}'

# Would be:
bb calc "(percent-change 100 125)"
bb calc --help  # Show 100+ available functions
```

Low priority - calculate works fine via `bb mcp call`, this is just ergonomics.

---

## Quick Reference

```bash
# Start server
bb server --http 0 --nickname dev

# Explore tools
bb mcp servers
bb mcp tools --mcp dev
bb mcp call echo.echo '{"message":"test"}' --mcp dev

# Evaluate code
bb mcp-eval "(+ 1 2 3)"

# Run tests
bb test:modules
bb test:e2e  # requires running server with --nickname e2e-test
```

---

## Important Reminders
- **Verification**: Run `clj-kondo --lint <files>` and `cljfmt check <files>` before commit
- **Zero warnings required**: Do NOT commit with lint warnings
- **macOS**: Do NOT use `timeout` command (doesn't exist)
- **Port files**: Now use `.json` extension in `.ports/` directory
