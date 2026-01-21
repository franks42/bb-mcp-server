# bb nrepl-direct User Guide

Direct nREPL client that bypasses MCP - connects directly to nREPL servers via bencode protocol.

## Quick Reference

```bash
bb nrepl-direct eval "<code>" --port 7888              # Eval code
bb nrepl-direct eval "<code>" --port 7888 --output edn # Clean EDN output
bb nrepl-direct load-local-file <path> --port 7888     # Load file (recommended)
bb nrepl-direct -h                                      # Help
```

## Output Modes

| Mode | Use Case | stdout | exit code |
|------|----------|--------|-----------|
| `result` | Interactive (default) | Parsed value | 1 on error |
| `full` | Debugging | Full JSON response | 0 |
| `edn` | Scripting/piping | EDN value only | 0 success, 1 error |
| `pipe` | Shell piping | stdout+stderr+value | 1 on error |

### EDN Mode for Scripting

Best for AI assistants and shell scripts - clean EDN output, proper exit codes:

```bash
# Simple eval - returns just the value
bb nrepl-direct eval "(+ 1 2 3)" --port 7888 --output edn
# => 6

# Complex data structures
bb nrepl-direct eval "{:a 1 :b [1 2 3]}" --port 7888 --output edn
# => {:a 1, :b [1 2 3]}

# Pipe to another command
bb nrepl-direct eval "(range 5)" --port 7888 --output edn | bb -e "(reduce + (read))"
# => 10

# Errors return full result + exit 1
bb nrepl-direct eval "(throw (ex-info \"oops\" {}))" --port 7888 --output edn
# => {:err "...", :ex "class ", :status "success"}
# exit code: 1
```

### Debug Output with `--stdout2stderr`

See println debug output on stderr while keeping stdout clean for piping:

```bash
bb nrepl-direct eval '(do (println "debug") 42)' --port 7888 --output edn --stdout2stderr
# stderr: debug
# stdout: 42
```

## Port Discovery

Auto-discover ports from `.ports/<nickname>.json` files:

```bash
# Using nickname instead of explicit port
bb nrepl-direct eval "(+ 1 2)" --nickname myserver

# Specify service (default: nrepl-server)
bb nrepl-direct eval "(+ 1 2)" --nickname myserver --service nrepl-proxy
```

## Loading Files

### CRITICAL: `load-file` vs `load-local-file`

| Command | What happens | Use for |
|---------|--------------|---------|
| `load-file` | Sends PATH to server, server reads file | Server has file access |
| `load-local-file` | Reads file HERE, sends CONTENT | **Browser/Scittle, remote servers** |

**Rule: Always use `load-local-file` unless you're certain the server can read local files.**

```bash
# RECOMMENDED - works everywhere
bb nrepl-direct load-local-file src/app.cljs --port 7888

# Only if server has filesystem access
bb nrepl-direct load-file /path/on/server.clj --port 7888
```

## Browser/Scittle Development

For browser-based Clojure (Scittle), use the nREPL proxy:

```bash
# Eval in browser context via proxy
bb nrepl-direct eval "(js/alert \"Hello\")" --nickname scittle-dev --service nrepl-proxy

# Load file to browser (MUST use load-local-file!)
bb nrepl-direct load-local-file src/browser/app.cljs \
  --nickname scittle-dev --service nrepl-proxy

# WRONG - browser cannot read local files!
bb nrepl-direct load-file src/browser/app.cljs --service nrepl-proxy  # FAILS
```

## Shell Escaping: The `!` Problem

**Problem:** Bash interprets `!` as history expansion, breaking Clojure function names like `swap!`, `reset!`, `println`.

```bash
# BROKEN - bash expands ! before nrepl-direct sees it
bb nrepl-direct eval "(swap! state inc)" --port 7888
# bash: !: event not found

# WORKS - use single quotes (prevents all shell expansion)
bb nrepl-direct eval '(swap! state inc)' --port 7888

# WORKS - escape the !
bb nrepl-direct eval "(swap\! state inc)" --port 7888
```

**Best Practice:** Always use single quotes `'...'` for Clojure code containing `!`:

```bash
# Good - single quotes
bb nrepl-direct eval '(reset! atom-val 42)' --port 7888
bb nrepl-direct eval '(println "hello")' --port 7888

# Also good - use script files for complex code
bb nrepl-direct load-local-file scripts/init.clj --port 7888
```

**Other escaping issues:**
- `$` - Variable expansion: use single quotes or `\$`
- `"` inside code - Use `\"` or single-quote the whole thing
- Newlines - Use script files instead of inline code

**Recommendation:** For anything beyond simple expressions, use `.clj` script files with `load-local-file`.

## Common Options

```
--port PORT          nREPL port (required unless --nickname)
--host HOST          Host (default: localhost)
--nickname NAME      Discover port from .ports/<NAME>.json
--service SERVICE    Service in port file: nrepl-server (default), nrepl-proxy
--ns NAMESPACE       Namespace to eval in
--timeout MS         Timeout in ms (default: 30000)
--output MODE        result, full, edn, pipe
--pprint             Pretty-print output
--stdout2stderr      With --output edn: show eval's stdout on stderr
```

## Examples for AI Assistants

```bash
# Get data structure from running server
result=$(bb nrepl-direct eval "(get-system-state)" --port 7888 --output edn)

# Check if operation succeeded
if bb nrepl-direct eval "(validate-config)" --port 7888 --output edn > /dev/null 2>&1; then
  echo "Config valid"
fi

# Load and execute a script file
bb nrepl-direct load-local-file scripts/init.clj --port 7888 --output edn

# Chain evaluations
bb nrepl-direct eval "(range 10)" --port 7888 --output edn | \
  bb -e "(filter odd? (read))"
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Connection refused | Check port, ensure server is running |
| File not found (browser) | Use `load-local-file` instead of `load-file` |
| Garbled output | Use `--output edn` or `--output full` |
| Timeout | Increase with `--timeout 60000` |
| Wrong server | Check `--service` (nrepl-server vs nrepl-proxy) |
