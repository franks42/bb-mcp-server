# Code Review Checklist

**For:** All code changes to bb-mcp-server

---

## Critical: Stdio Transport Safety

- [ ] **No println/prn/print usage** - All logging must use `taoensso.trove/log!`
  - **Why:** stdio transport uses stdin/stdout for JSON-RPC. Any println corrupts the stream.
  - **Check:** `grep -rn "println\|prn\|print " <changed-files> | grep -v ";.*println"`
  - **Exception:** Test files (`test/`) can use println (don't run during server operation)
  - **Lint rule:** `.clj-kondo/config.edn` has `:discouraged-var` to catch this automatically

**Valid stdio output locations:**
- `mcp-stdio/core.clj` - Sending JSON-RPC responses (this IS the transport)
- `processor.clj` - Sending MCP notifications via stdio
- `main.clj` - **Only before stdio transport starts** (startup messages, errors)

**Invalid:**
- Any module code that might execute during server operation
- Any handler or tool implementation
- Any utility functions called by handlers

---

## Verification Workflow (MANDATORY)

Before committing, run ALL three:

```bash
# 1. Lint (MUST be 0 errors, 0 warnings)
clj-kondo --lint <files>

# 2. Format (MUST have no issues)
cljfmt check <files>

# 3. Tests (MUST pass all)
bb test:modules
```

**Zero tolerance:** Do NOT commit code with lint warnings or test failures.

---

## Telemetry Requirements

From `docs/AI_TELEMETRY_GUIDE.md`:

- [ ] **Every I/O or business logic function has telemetry**
  - Use `(require '[taoensso.trove :as log])`
  - Log: entry, success, failure (with `:error` key), duration for slow ops
  - Event ID pattern: `:bb-mcp-server.{component}/{action}`
  - Default to `:info` level unless you have a reason for another

**Template:**
```clojure
(defn process-request [request]
  (log/log! {:level :info :id ::process-request :msg "Processing" :data {:id (:id request)}})
  (try
    (let [result (do-work request)]
      (log/log! {:level :info :id ::process-complete :msg "Complete" :data {:id (:id request)}})
      result)
    (catch Exception e
      (log/log! {:level :error :id ::process-failed :msg "Failed" :error e :data {:id (:id request)}})
      (throw e))))
```

---

## Code Quality

- [ ] **Babashka compatible** - No JVM-only features
- [ ] **Thread-safe** - Atoms for shared state, no unsynchronized mutation
- [ ] **Error handling** - Use `ex-info` with structured data
- [ ] **Docstrings** - Public functions must have docstrings
- [ ] **No hardcoded paths** - Use configuration or environment variables
- [ ] **Secrets in env vars** - Never hardcode API keys, tokens, passwords

---

## Module System

- [ ] **module.edn present** - Every module must have `module.edn`
- [ ] **Tool registration** - MCP tools listed in `:tools` section
- [ ] **Dependencies declared** - Required modules in `:requires`
- [ ] **Tests exist** - Every module should have tests in `test/` directory

---

## MCP Protocol Compliance

- [ ] **Tool schemas valid** - All `:inputSchema` follow JSON Schema spec
- [ ] **Error responses** - Use proper JSON-RPC error codes
- [ ] **listChanged notifications** - Emit when tools added/removed dynamically
- [ ] **Transport agnostic** - Code works with stdio, HTTP, and REST transports

---

## Performance

- [ ] **No blocking operations in handlers** - Use async/promises for long operations
- [ ] **Resource cleanup** - Close streams, stop threads, release locks
- [ ] **Memory leaks** - Check for growing atoms, unclosed resources

---

## Security

- [ ] **Input validation** - Validate all external inputs
- [ ] **Path traversal** - Don't trust user-provided file paths
- [ ] **Command injection** - Don't shell out with user input
- [ ] **Sensitive data in logs** - Redact credentials, tokens, passwords

---

## Documentation

- [ ] **IMPLEMENTATION_PLAN.md updated** - If this is a planned phase/feature
- [ ] **Architecture docs current** - Update `docs/design/*.md` if architecture changed
- [ ] **README accurate** - Update if user-facing changes
- [ ] **Commit message clear** - Explain WHAT and WHY, not just HOW

---

## Git Hygiene

- [ ] **Single concern** - Each commit does one thing
- [ ] **No commented-out code** - Delete it (git preserves history)
- [ ] **No debug artifacts** - Remove temporary files, debug prints
- [ ] **Attribution** - Include co-authorship if applicable

---

*See also: CLAUDE.md, CLOJURE_EXPERT_CONTEXT.md, AI_TELEMETRY_GUIDE.md*
