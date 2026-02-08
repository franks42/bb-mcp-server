# The `!` Escaping Problem in AI Tool Environments

## Problem

Claude Code's Bash tool (and potentially other AI tool environments) silently escapes `!` to `\!` inside **single-quoted** strings. This breaks Clojure code containing common idioms like `swap!`, `reset!`, `mount!`, `!atom-name`, etc.

Example of silent failure:

```bash
# What you type:
bb nrepl-direct eval '(swap! my-atom inc)' -t myserver

# What actually executes (! escaped to \!):
bb nrepl-direct eval '(swap\! my-atom inc)' -t myserver

# Result: Clojure eval error — swap\! is not a valid symbol
```

The failure is **silent** — there's no warning that the escaping occurred. The eval just fails with an opaque error about an unknown symbol.

## Root Cause

In standard bash, `!` triggers history expansion inside double-quoted strings (e.g., `"!!"` expands to the last command). The conventional fix is `set +H` to disable history expansion, or using single quotes where `!` is literal.

However, Claude Code's Bash tool applies its own escaping **before** the command reaches bash. It escapes `!` to `\!` inside single-quoted strings, where bash would normally treat `!` as literal. This means:

- `set +H` in wrapper scripts does **not** help — the escaping happens upstream
- Single quotes (normally the safe choice for literal strings) are **not** safe
- The escaping is invisible in the tool's output

## Solutions

### 1. Use double quotes for inline eval (primary fix)

```bash
# CORRECT — double quotes, ! passes through fine
bb nrepl-direct eval "(swap! my-atom inc)" -t myserver
bb nrepl-direct eval "(reset! my-atom {})" -t myserver
bb nrepl-direct eval "(mount!)" -t cb-v2-test/browser-1

# WRONG — single quotes, ! gets escaped to \!
bb nrepl-direct eval '(swap! my-atom inc)' -t myserver
```

If the code itself contains double quotes, escape them:

```bash
bb nrepl-direct eval "(str \"swap!\" \" works\")" -t myserver
```

### 2. Use `load-local-file` for complex code (best for multi-line)

Write the code to a `.clj` file and load it. This bypasses all shell escaping:

```bash
# Write your code to a file first, then:
bb nrepl-direct load-local-file scripts/my-script.clj -t myserver
bb nrepl-direct load-local-file scripts/my-script.clj -t myserver/browser-1
```

This is the recommended approach for:
- Multi-line code
- Code with nested quotes
- Code you'll run more than once
- Any situation where escaping is getting complicated

### 3. Use heredocs for complex inline eval (bash-level workaround)

```bash
bb nrepl-direct eval "$(cat <<'CLOJURE'
(let [result (swap! my-atom update :count inc)]
  (println "New value:" result))
CLOJURE
)" -t myserver
```

## Quick Reference

| Scenario | Approach |
|----------|----------|
| Simple eval with `!` | Double quotes: `eval "(swap! x inc)"` |
| Simple eval without `!` | Either quotes work: `eval '(+ 1 2)'` or `eval "(+ 1 2)"` |
| Code with inner double quotes | Escape them: `eval "(str \"hello\")"` |
| Multi-line or complex code | `load-local-file scripts/foo.clj` |
| Repeated operations | Write a `.clj` script, use `load-local-file` |

## What Changed in the Project

1. **`bb.edn`**: The `nrepl-direct` task was changed from `shell "scripts/nrepl-direct.sh"` to `load-file "scripts/nrepl_direct_cli.clj"` — eliminates the bash wrapper entirely
2. **`CLAUDE.md`**: Updated all examples to use double quotes and added a CRITICAL warning section
3. **`scripts/nrepl-direct.sh`**: Still exists but no longer used by the bb task (was a `set +H` wrapper that didn't actually help)

## Discovery Timeline (2026-02-07)

1. Browser telemetry `log/log!` calls appeared to not work when triggered via nREPL eval
2. Initially misdiagnosed as a "Scittle macro limitation" (incorrect — Trove's `log!` macro uses syntax-quoted `` `*log-fn* `` which resolves at runtime)
3. Investigation revealed the real cause: `!` in `log/log!` was being escaped to `log/log\!` by the Bash tool
4. Confirmed by switching from single to double quotes — everything worked immediately
5. The `set +H` in `nrepl-direct.sh` was a red herring — it disables bash history expansion but the escaping happens in the AI tool layer before bash processes the command
